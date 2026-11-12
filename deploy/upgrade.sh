#!/usr/bin/env bash
# Upgrades HomeGAP on the homelab without taking access down. Run it next to your compose file
# instead of `docker compose pull && docker compose up -d`:
#
#   ./upgrade.sh                      # uses ./compose.yml (or whatever `docker compose` finds)
#   ./upgrade.sh -f homelab-compose.yml
#
# Arguments are passed to every `docker compose` call. In order, it:
#   1. pulls the new images;
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
# with --tunnels. --no-pull uses the images already on the host. Until then it keeps running the previous image, so Docker keeps that image too.
#
# Service names can be overridden for merged compose projects: HG_ORCHESTRATOR, HG_MESH_AGENT,
# HG_TUNNELS and HG_CLUSTER (space-separated service:role pairs, in restart order).
set -euo pipefail

FORCE_TUNNELS=no
PULL=yes
ARGS=()
for a in "$@"; do
  case "$a" in
    --tunnels) FORCE_TUNNELS=yes ;;
    --no-pull) PULL=no ;;
    -h|--help) sed -n '2,31p' "$0" | sed 's/^# \{0,1\}//'; exit 0 ;;
    *) ARGS+=("$a") ;;
  esac
done

ORCHESTRATOR=${HG_ORCHESTRATOR:-orchestrator}
MESH_AGENT=${HG_MESH_AGENT:-homegap-mesh-agent}
TUNNELS=${HG_TUNNELS:-tunnels}
CLUSTER=${HG_CLUSTER:-homegap-etcd:etcd homegap-sentinel:sentinel homegap-valkey:valkey homegap-db:patroni}
TUNNELS_VERSION_FILE=/usr/share/homegap/tunnels-version

dc() { docker compose ${ARGS[@]+"${ARGS[@]}"} "$@"; }
# `exec` never reads this script's stdin (which may be the script itself, when piped into bash).
dx() { dc exec -T "$@" </dev/null; }
say() { printf '\033[1m==> %s\033[0m\n' "$*"; }
warn() { printf '\033[33mwarning:\033[0m %s\n' "$*" >&2; }
die() { printf '\033[31merror:\033[0m %s\n' "$*" >&2; exit 1; }

services=$(dc config --services)
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

has "$ORCHESTRATOR" || die "no '$ORCHESTRATOR' service in this compose project (set HG_ORCHESTRATOR)"
cluster_services=()
for pair in $CLUSTER; do has "${pair%%:*}" && cluster_services+=("$pair"); done

if [ "$PULL" = yes ]; then
  say "Pulling images"
  pull=("$ORCHESTRATOR")
  has "$MESH_AGENT" && pull+=("$MESH_AGENT")
  has "$TUNNELS" && pull+=("$TUNNELS")
  for pair in ${cluster_services[@]+"${cluster_services[@]}"}; do pull+=("${pair%%:*}"); done
  dc pull "${pull[@]}"
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
