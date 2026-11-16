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
#   3. recreates the sign-in. A device that is connected keeps its tunnel and connects to the
#      sign-in again;
#   4. brings the rest of the stack to the compose file, and removes each container of a service
#      that the compose file no longer has.
#
# If the orchestrator does not start with its new image, the script puts it back on the image it
# had, and stops. It gives the old image the tag of the compose file, so a later
# `docker compose pull` gets the new image again.
#
# The mesh container (homegap-mesh) has a fixed image, so step 4 recreates it only when its part
# of the compose files changes. The mesh agent restarts with it, and the mesh is down for a few
# seconds. --no-pull uses the images already on the host.
#
# The files of the stack come from the new image (`homegap init --refresh`). A file that you did
# not change gets its new version. For a file that you changed, the script asks. Without a
# terminal, or after a "no", your file stays and the new version goes next to it as <name>.new.
# Your own files (.env, compose.override.yml, config/) never change. --no-refresh skips this
# step. Other arguments go to every `docker compose` call, and they also skip this step.
#
# Over SSH, run the script in screen or tmux. A connection that drops stops the script before it
# ends. Without screen or tmux, the script stops and tells you. HG_ALLOW_SSH=1 runs it anyway.
set -euo pipefail

PULL=yes
REFRESH=yes
ORIGINAL=("$@")
ARGS=()
for a in "$@"; do
  case "$a" in
    --no-pull) PULL=no ;;
    --no-refresh) REFRESH=no ;;
    -h|--help) sed -n '2,30p' "$0" | sed 's/^# \{0,1\}//'; exit 0 ;;
    *) ARGS+=("$a") ;;
  esac
done

ORCHESTRATOR=orchestrator
MESH_AGENT=homegap-mesh-agent
SIGN_IN=homegap-sign-in
# The image of the mesh container, which the host already has.
FOLDER_IMAGE=docker.io/library/busybox:1.37
# This script started itself again after it got its new version: the pull and the refresh are done.
REFRESHED=${HG_REFRESHED:-}

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
If the connection drops, the upgrade stops before it ends.
Run the upgrade in screen or tmux:

  screen -S homegap-upgrade ${again% }
  tmux new -s homegap-upgrade '${again% }'

To run it in this session anyway, set HG_ALLOW_SSH=1.
EOF
    exit 1
  fi
fi

services=""
# Reads the services of the compose project. Stops if the project has no orchestrator.
read_services() {
  services=$(dc config --services) \
    || die "the compose files are not valid (see the message above). Nothing restarted. Correct the cause and run this again."
  has "$ORCHESTRATOR" || die "no '$ORCHESTRATOR' service in this compose project"
}
has() { printf '%s\n' "$services" | grep -qx "$1"; }

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

# Makes each data folder that is missing. Docker makes a missing folder as root, and then the
# orchestrator and the sign-in cannot write to it. Where this user cannot make the folder, a
# container makes it as root and gives it to the owner of this folder.
make_folders() {
  local data owner name missing=()
  data=${HOMEGAP_DATA_DIR:-}
  [ -n "$data" ] || [ ! -f .env ] || data=$(sed -n 's/^HOMEGAP_DATA_DIR=//p' .env | tail -n 1)
  data=${data:-./data}
  for name in orchestrator mesh identity sign-in vpn; do
    [ -d "$data/$name" ] || missing+=("$name")
  done
  [ ${#missing[@]} -gt 0 ] || return 0
  say "Making the data folders: ${missing[*]}"
  if (cd "$data" 2>/dev/null && mkdir -p "${missing[@]}") 2>/dev/null; then
    return 0
  fi
  owner=$(stat -c '%u:%g' . 2>/dev/null || echo "$(id -u):$(id -g)")
  mkdir -p "$data" 2>/dev/null || true
  [ -d "$data" ] || die "cannot make the data folder $data"
  docker run --rm -e "OWNER=$owner" -v "$(cd "$data" && pwd):/data" -w /data "$FOLDER_IMAGE" \
    sh -c 'mkdir -p "$@" && chown "$OWNER" "$@"' sh "${missing[@]}" \
    || die "cannot make the folders ${missing[*]} in $data. Make them, give them to user $owner, and run this again."
}

read_services

if [ "$PULL" = yes ] && [ -z "$REFRESHED" ]; then
  say "Pulling images"
  pull=("$ORCHESTRATOR")
  has "$MESH_AGENT" && pull+=("$MESH_AGENT")
  has "$SIGN_IN" && pull+=("$SIGN_IN")
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

make_folders

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

if has "$SIGN_IN"; then
  say "Updating the sign-in"
  dc up -d --no-deps "$SIGN_IN"
fi

say "Bringing the rest of the stack to the compose file"
dc up -d --remove-orphans

say "Done"
dc ps
