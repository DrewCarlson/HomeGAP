#!/usr/bin/env bash
# Upgrades HomeGAP on the homelab without taking access down. Run it in the folder of the stack,
# next to compose.yml, instead of `docker compose pull && docker compose up -d`:
#
#   ./upgrade.sh
#
# In order, it:
#   1. pulls the new images, and brings compose.yml, this script and the example files to the
#      version of the new image (see below);
#   2. recreates the orchestrator (the control plane only: traffic never goes through it) and
#      the mesh agent (the mesh interface stays up without it);
#   3. moves the database and Valkey primaries to a node and holds them there
#      (`homegap maintenance start`);
#   4. recreates the homelab's cluster containers one at a time, waiting for each to serve again;
#   5. recreates the tunnel connectors only if their code or Newt changed (see below);
#   6. ends the hold (`homegap maintenance end`); the primaries return to the homelab once it has
#      been caught up for the cluster's switch-back delay.
#
# If the orchestrator or a cluster container does not serve with its new image, the script puts
# that service back on the image it had, and stops. It gives the old image the tag of the compose
# file, so a later `docker compose pull` gets the new image again.
#
# The mesh container (homegap-mesh) is never recreated: the mesh and everything in its network
# namespace would restart with it.
#
# The tunnels container restarts every Newt connector at once, so every node loses the homelab
# for a few seconds. It is only recreated when the image's tunnels version differs from the one
# it runs (the tunnel supervisor's code, its platform layer, dependency versions and Newt), or
# with --tunnels. Until then it keeps running the previous image, so Docker keeps that image too.
# --no-pull uses the images already on the host.
#
# The files of the stack come from the new image (`homegap init --refresh`). A file that you did
# not change gets its new version. For a file that you changed, the script asks. Without a
# terminal, or after a "no", your file stays and the new version goes next to it as <name>.new.
# Your own files (.env, compose.override.yml, config/) never change. --no-refresh skips this
# step. Other arguments go to every `docker compose` call, and they also skip this step.
#
# Over SSH, run the script in screen or tmux. A connection that drops stops the script before it
# ends, and the upgrade can restart the tunnel that carries the connection. Without screen or
# tmux, the script stops and tells you. HG_ALLOW_SSH=1 runs it anyway.
set -euo pipefail

FORCE_TUNNELS=no
PULL=yes
REFRESH=yes
ORIGINAL=("$@")
ARGS=()
for a in "$@"; do
  case "$a" in
    --tunnels) FORCE_TUNNELS=yes ;;
    --no-pull) PULL=no ;;
    --no-refresh) REFRESH=no ;;
    -h|--help) sed -n '2,40p' "$0" | sed 's/^# \{0,1\}//'; exit 0 ;;
    *) ARGS+=("$a") ;;
  esac
done

ORCHESTRATOR=orchestrator
MESH_AGENT=homegap-mesh-agent
TUNNELS=tunnels
# The cluster services with their roles, in the order of the restarts.
CLUSTER="homegap-etcd:etcd homegap-sentinel:sentinel homegap-valkey:valkey homegap-db:patroni"
# This script started itself again after it got its new version: the pull and the refresh are done.
REFRESHED=${HG_REFRESHED:-}
TUNNELS_VERSION_FILE=/usr/share/homegap/tunnels-version

dc() { docker compose ${ARGS[@]+"${ARGS[@]}"} "$@"; }
# `exec` never reads this script's stdin (which may be the script itself, when piped into bash).
dx() { dc exec -T "$@" </dev/null; }
say() { printf '\033[1m==> %s\033[0m\n' "$*"; }
warn() { printf '\033[33mwarning:\033[0m %s\n' "$*" >&2; }
die() { printf '\033[31merror:\033[0m %s\n' "$*" >&2; exit 1; }

# True if a process above this shell has a name that matches the extended regular expression $1.
# `sudo` removes SSH_CONNECTION, STY and TMUX from the environment, so the names of the parent
# processes are the second source.
has_ancestor() {
  local pid=$$ name
  while [ "${pid:-0}" -gt 1 ]; do
    name=$(ps -o comm= -p "$pid" 2>/dev/null) || return 1
    # The tmux server has the name "tmux: server", and a login shell can start with a dash.
    name=${name##*/}
    [[ ${name// /} =~ $1 ]] && return 0
    pid=$(ps -o ppid= -p "$pid" 2>/dev/null | tr -d ' ')
  done
  return 1
}
over_ssh() { [ -n "${SSH_CONNECTION:-}${SSH_CLIENT:-}${SSH_TTY:-}" ] || has_ancestor '^sshd'; }
in_screen_or_tmux() { [ -n "${STY:-}${TMUX:-}" ] || has_ancestor '^(screen|tmux)'; }

# On the homelab itself the script just runs. Over SSH it needs screen or tmux: the session then
# continues when the connection drops.
if over_ssh; then
  if in_screen_or_tmux; then
    echo "SSH session in screen or tmux: the upgrade continues if the connection drops."
  elif [ "${HG_ALLOW_SSH:-}" = 1 ]; then
    warn "SSH session without screen or tmux (HG_ALLOW_SSH=1): if the connection drops, the upgrade stops before it ends"
  else
    again=$(printf '%q ' "$0" "$@")
    printf '\033[31merror:\033[0m this is an SSH session without screen or tmux.\n' >&2
    cat >&2 <<EOF
If the connection drops, the upgrade stops before it ends. The upgrade can also restart
the tunnel connectors, which ends an SSH connection that goes through HomeGAP.
Run the upgrade in screen or tmux:

  screen -S homegap-upgrade ${again% }
  tmux new -s homegap-upgrade '${again% }'

To run it in this session anyway, set HG_ALLOW_SSH=1.
EOF
    exit 1
  fi
fi

services=""
cluster_services=()
# Reads the services of the compose project. Stops if the project has no orchestrator.
read_services() {
  local pair
  services=$(dc config --services)
  has "$ORCHESTRATOR" || die "no '$ORCHESTRATOR' service in this compose project"
  cluster_services=()
  for pair in $CLUSTER; do
    if has "${pair%%:*}"; then cluster_services+=("$pair"); fi
  done
}
has() { printf '%s\n' "$services" | grep -qx "$1"; }
running() { [ -n "$(dc ps -q --status running "$1" 2>/dev/null)" ]; }

# The image id that the container of service $1 runs now, or nothing.
image_of() {
  local id
  id=$(dc ps -q "$1" 2>/dev/null | head -n 1)
  [ -n "$id" ] && docker inspect -f '{{.Image}}' "$id" 2>/dev/null || true
}

# Puts service $1 back on image id $2: the tag of the compose file goes to that image again.
# The image name comes from the container that `up` made for the service. `config --images` is
# no source for it: it also lists the images of the services that the service depends on.
rollback() {
  local svc=$1 old=$2 ref id
  [ -n "$old" ] || { warn "$svc had no container before the upgrade, so there is no image to go back to"; return 1; }
  id=$(dc ps -aq "$svc" 2>/dev/null | head -n 1)
  ref=$([ -n "$id" ] && docker inspect -f '{{.Config.Image}}' "$id" 2>/dev/null || true)
  case "$ref" in ""|sha256:*) warn "cannot read the image name of $svc"; return 1 ;; esac
  [ "$(docker image inspect -f '{{.Id}}' "$ref" 2>/dev/null)" != "$old" ] || { warn "$svc already has the image from before the upgrade"; return 1; }
  say "Putting $svc back on its previous image"
  docker tag "$old" "$ref" && dc up -d --no-deps "$svc"
}

# How long a service gets to serve after its restart, in seconds.
READY_TIMEOUT=${HG_READY_TIMEOUT:-300}

# Waits until `cmd...` succeeds, for at most $1 seconds.
wait_until() {
  local limit=$1 start=$SECONDS; shift
  until "$@" >/dev/null 2>&1; do
    (( SECONDS - start < limit )) || return 1
    sleep 2
  done
}

# Brings compose.yml, this script and the example files to the version of the new image. The
# one-off container runs as the owner of this folder, so the files keep their owner.
refresh_files() {
  local owner before
  owner=$(stat -c '%u:%g' . 2>/dev/null || echo "$(id -u):$(id -g)")
  before=$(cksum < "$0")
  say "Updating the files of the stack"
  if [ -t 0 ]; then
    dc run --rm --no-deps --user "$owner" -v "$PWD:/work" "$ORCHESTRATOR" init --refresh
  else
    dc run --rm --no-deps -T --user "$owner" -v "$PWD:/work" "$ORCHESTRATOR" init --refresh </dev/null
  fi || { warn "could not update the files of the stack; the upgrade continues with the files that are here"; return 0; }
  if [ "$(cksum < "$0")" != "$before" ]; then
    say "upgrade.sh has a new version: starting it"
    HG_REFRESHED=1 exec bash "$0" ${ORIGINAL[@]+"${ORIGINAL[@]}"}
  fi
  dc config -q || die "the compose files are not valid (see the message above). Nothing restarted. Correct the cause and run this again."
}

read_services

if [ "$PULL" = yes ] && [ -z "$REFRESHED" ]; then
  say "Pulling images"
  pull=("$ORCHESTRATOR")
  has "$MESH_AGENT" && pull+=("$MESH_AGENT")
  has "$TUNNELS" && pull+=("$TUNNELS")
  for pair in ${cluster_services[@]+"${cluster_services[@]}"}; do pull+=("${pair%%:*}"); done
  dc pull "${pull[@]}"
fi

if [ "$REFRESH" = yes ] && [ -z "$REFRESHED" ]; then
  if [ ${#ARGS[@]} -eq 0 ] && [ -f compose.yml ]; then
    refresh_files
    read_services
  else
    echo "The files of the stack stay as they are: the refresh needs ./compose.yml and no compose arguments."
  fi
fi

say "Updating the orchestrator"
previous=$(image_of "$ORCHESTRATOR")
dc up -d --no-deps "$ORCHESTRATOR"
if ! wait_until 120 dx "$ORCHESTRATOR" homegap version; then
  dc logs --tail 20 "$ORCHESTRATOR" >&2 || true
  if rollback "$ORCHESTRATOR" "$previous" && wait_until 120 dx "$ORCHESTRATOR" homegap version; then
    die "the orchestrator did not start with the new image. It runs the previous image again, and nothing else changed."
  fi
  die "the orchestrator did not start; see: docker compose logs $ORCHESTRATOR"
fi
if has "$MESH_AGENT"; then
  say "Updating the mesh agent"
  dc up -d --no-deps "$MESH_AGENT"
fi

held=no
# Runs at every exit, so an upgrade that stops early does not leave the hold in place.
end_maintenance() {
  [ "$held" = yes ] || return 0
  held=no
  say "Ending maintenance"
  dx "$ORCHESTRATOR" homegap maintenance end || warn "could not end maintenance; the hold expires on its own within 30 minutes"
}
trap end_maintenance EXIT
if [ ${#cluster_services[@]} -gt 0 ]; then
  say "Moving the cluster primaries off the homelab"
  echo "(a planned switchover of the database and a Sentinel failover of Valkey; usually under a minute)"
  out=""
  for attempt in 1 2 3 4 5 6; do
    if out=$(dx "$ORCHESTRATOR" homegap maintenance start --minutes 30 2>&1); then held=yes; break; fi
    case "$out" in
      *"cluster is not enabled"*) break ;;
      # The dashboard API is not up yet right after the restart, or its first pass has not read
      # the cluster yet.
      *"Connection refused"*|*"ConnectException"*|*"not been read yet"*)
        echo "  not ready yet (attempt $attempt of 6): $(printf '%s' "$out" | tail -n 1)"
        echo "  the orchestrator's log shows what it is doing: docker compose logs -f $ORCHESTRATOR"
        sleep 10 ;;
      *) break ;;
    esac
  done
  echo "$out"
  if [ "$held" != yes ]; then
    case "$out" in
      *"cluster is not enabled"*) cluster_services=() ;;
      *) die "could not move the primaries off the homelab, so its cluster containers were left alone. Fix the cause and run this again." ;;
    esac
  fi
fi

for pair in ${cluster_services[@]+"${cluster_services[@]}"}; do
  svc=${pair%%:*} role=${pair#*:}
  say "Updating $svc"
  previous=$(image_of "$svc")
  dc up -d --no-deps "$svc"
  if ! wait_until "$READY_TIMEOUT" dx "$svc" hg-member ready "$role"; then
    dc logs --tail 20 "$svc" >&2 || true
    if rollback "$svc" "$previous" && wait_until "$READY_TIMEOUT" dx "$svc" hg-member ready "$role"; then
      die "$svc did not serve with the new image. It runs the previous image again. The cluster containers after it keep their previous image too."
    fi
    die "$svc is not serving after $READY_TIMEOUT seconds; the primaries stay on the nodes until the homelab member is healthy. See: docker compose logs $svc"
  fi
done

if has "$TUNNELS"; then
  # A one-off container of the service reads the version of the image in the compose file.
  new=$(dc run --rm --no-deps -T --entrypoint cat "$TUNNELS" "$TUNNELS_VERSION_FILE" </dev/null 2>/dev/null || echo unknown)
  current=$(dx "$TUNNELS" cat "$TUNNELS_VERSION_FILE" 2>/dev/null || echo none)
  if [ "$FORCE_TUNNELS" = yes ] || ! running "$TUNNELS" || [ "$new" != "$current" ] || [ "$new" = unknown ]; then
    say "Updating the tunnel connectors (every node reconnects to the homelab: a few seconds without access)"
    dc up -d --no-deps "$TUNNELS"
  else
    say "Tunnel connectors unchanged (version $current): left running"
  fi
fi

end_maintenance

say "Done"
dc ps
