#!/usr/bin/env bash
# Cluster checks on a running e2e deployment with `cluster:` on (after run.sh and verify.sh):
#   1. the database: every member streaming, the primary on the homelab;
#   2. a user created through the dashboard signs in at pangolin.<domain> through every node;
#   3. a Pangolin client reaches a private service through whichever node DNS sends it to;
#   4. cutting the homelab off: a node becomes primary and takes writes, the homelab stops; after
#      healing, HomeGAP switches the primary back (cluster.primary.switch_back_after).
# Test credentials come from the e2e data directory and are never printed.
set -uo pipefail
cd "$(dirname "$0")"
DASH=http://127.0.0.1:18088/api
FAILS=0
pass() { echo "PASS  $*"; }
fail() { echo "FAIL  $*"; FAILS=$((FAILS + 1)); }
state() { python3 -c "import json,sys; s=json.load(open('work/data/state.json')); print(eval(sys.argv[1]))" "$1"; }
cluster() { curl -s "$DASH/status" | python3 -c "import json,sys; c=json.load(sys.stdin)['cluster']; print(eval(sys.argv[1]))" "$1"; }
# Polls a python expression over the cluster status until it is True (or the timeout, in seconds).
until_cluster() { local expr=$1 t=$2; for _ in $(seq 1 $((t / 5))); do [ "$(cluster "$expr" 2>/dev/null)" = True ] && return 0; sleep 5; done; return 1; }

NODES="na1:172.30.0.10 as1:172.30.0.11"
ADMIN_EMAIL=$(state "s['clusterPangolin']['adminEmail']")
ADMIN_PW=$(state "s['clusterPangolin']['adminPassword']")
PGPW=$(python3 -c "import json; print(json.load(open('work/data/cluster/secrets.json'))['postgres'])")

echo "--- 1. database"
if until_cluster "c['primary'] == c['preferred'] and c.get('quorum') and all(m.get('role') == 'leader' or m.get('state') == 'streaming' for m in c['members'])" 120; then
  pass "primary on $(cluster "c['primary']"), every member streaming, the vote has a majority"
else
  fail "database not healthy: $(cluster "[(m['id'], m.get('role'), m.get('state')) for m in c['members']]")"
fi

echo "--- 2. a user from the dashboard signs in through every node"
EMAIL="verify-$(date +%s)@test.example.net"
UPW="Hg$(python3 -c 'import secrets; print(secrets.token_urlsafe(12))')9!"
ROLE=$(curl -s "$DASH/access" | python3 -c "import json,sys; print(next(r['id'] for r in json.load(sys.stdin)['roles'] if not r['admin']))")
created=$(python3 -c "import json,sys; print(json.dumps({'email': sys.argv[1], 'roleIds': [int(sys.argv[2])], 'password': sys.argv[3]}))" "$EMAIL" "$ROLE" "$UPW" \
  | curl -s -X POST -H 'Content-Type: application/json' --data @- "$DASH/access/users")
echo "$created" | grep -q '"ok":true' && pass "created $EMAIL" || fail "create user: $created"
for n in $NODES; do
  id=${n%%:*}; ip=${n#*:}
  code=$(python3 -c "import json,sys; print(json.dumps({'email': sys.argv[1], 'password': sys.argv[2]}))" "$EMAIL" "$UPW" \
    | docker compose exec -T viewer sh -c "curl -s --cacert /certs/ca.pem --resolve pangolin.test.example.net:443:$ip -H 'X-CSRF-Token: x-csrf-protection' -H 'Content-Type: application/json' --data @- -o /dev/null -w '%{http_code}' https://pangolin.test.example.net/api/v1/auth/login")
  [ "$code" = 200 ] && pass "$EMAIL signs in at pangolin.test.example.net via $id" || fail "sign-in via $id: HTTP $code"
done
uid=$(curl -s "$DASH/access" | python3 -c "import json,sys; print(next(u['id'] for u in json.load(sys.stdin)['users'] if u['email'] == sys.argv[1]))" "$EMAIL")
curl -s -X DELETE "$DASH/access/users/$uid" >/dev/null

echo "--- 3. private access through the nearest node"
# A machine client with the Admin role (which reaches every private resource), created as the
# cluster's admin: HomeGAP's own API key is deliberately not allowed to create clients.
read -r OLM_ID OLM_SECRET CID < <(docker compose exec -T -e EMAIL="$ADMIN_EMAIL" -e PW="$ADMIN_PW" node-na1 sh -c '
  B=http://127.0.0.1:3000/api/v1; H="X-CSRF-Token: x-csrf-protection"; J="Content-Type: application/json"; C=/tmp/verify-cookies
  printf "{\"email\":\"%s\",\"password\":\"%s\"}" "$EMAIL" "$PW" | curl -s -c $C -H "$H" -H "$J" --data @- $B/auth/login >/dev/null
  D=$(curl -s -b $C -H "$H" $B/org/homegap/pick-client-defaults)
  OID=$(echo "$D" | sed -E "s/.*\"olmId\":\"([^\"]+)\".*/\1/"); OS=$(echo "$D" | sed -E "s/.*\"olmSecret\":\"([^\"]+)\".*/\1/"); SUB=$(echo "$D" | sed -E "s/.*\"subnet\":\"([^\"]+)\".*/\1/")
  R=$(curl -s -b $C -X PUT -H "$H" -H "$J" -d "{\"name\":\"verify-client\",\"olmId\":\"$OID\",\"secret\":\"$OS\",\"subnet\":\"$SUB\",\"type\":\"olm\"}" $B/org/homegap/client)
  CID=$(echo "$R" | sed -nE "s/.*\"clientId\":([0-9]+).*/\1/p")
  curl -s -b $C -X POST -H "$H" $B/client/$CID/add-role/1 >/dev/null
  rm -f $C
  echo "$OID $OS $CID"')
if [ -z "${CID:-}" ]; then fail "could not create a machine client"; else
  for n in $NODES; do
    id=${n%%:*}; ip=${n#*:}
    OLM_ID=$OLM_ID OLM_SECRET=$OLM_SECRET CLIENT_NODE_IP=$ip docker compose --profile client up -d --force-recreate client >/dev/null 2>&1
    via=""
    for _ in $(seq 1 24); do
      via=$(curl -s "$DASH/access" | python3 -c "import json,sys; print(next((d.get('viaNode') or '') for d in json.load(sys.stdin)['devices'] if d['id'] == int(sys.argv[1])))" "$CID" 2>/dev/null)
      [ "$via" = "$id" ] && break; sleep 5
    done
    [ "$via" = "$id" ] && pass "the client relays through $id" || fail "client sent to $id relays through '${via}'"
    banner=$(docker compose exec -T client sh -c '(sleep 2) | nc -w 5 ssh.test.example.net 22' 2>/dev/null | tr -d '\r')
    [ "$banner" = "SSH-2.0-fake" ] && pass "private ssh.test.example.net reached via $id" || fail "private service via $id: '$banner'"
  done
  docker compose --profile client rm -sf client >/dev/null 2>&1
  curl -s -X POST "$DASH/access/devices/$CID/delete" >/dev/null
fi

echo "--- 4. the homelab cut off, then back"
docker compose exec -T orchestrator sh -c 'iptables -I INPUT -i hg-mesh -j DROP; iptables -I OUTPUT -o hg-mesh -j DROP'
write_via() {
  docker compose exec -T -e PW="$PGPW" "node-$1" sh -c 'docker run --rm --network homegap-cluster -e PGPASSWORD="$PW" -e PGCONNECT_TIMEOUT=5 --entrypoint psql homegap-cluster:e2e -h cluster-proxy -p 5433 -U postgres -tAc "set statement_timeout = 5000; create table if not exists hg_verify(at timestamptz); insert into hg_verify values (now()) returning inet_server_addr()"' 2>/dev/null | tr -d '\r' | grep -E '^10\.' | head -1
}
promoted=""
for _ in $(seq 1 24); do
  addr=$(write_via na1)
  if [ -n "$addr" ] && [ "$addr" != 10.77.0.1 ]; then promoted=$addr; break; fi
  sleep 5
done
[ -n "$promoted" ] && pass "a node took over: writes through na1 land on $promoted" || fail "no node took writes while the homelab was cut off"
ro=$(docker compose exec -T cluster-db sh -c 'gosu postgres psql -h /var/run/postgresql -tAc "select pg_is_in_recovery()"' 2>/dev/null | tr -d '\r')
[ "$ro" = t ] && pass "the cut-off homelab no longer takes writes" || fail "homelab in recovery: '$ro'"
docker compose exec -T orchestrator sh -c 'iptables -D INPUT -i hg-mesh -j DROP; iptables -D OUTPUT -o hg-mesh -j DROP'
if until_cluster "c['primary'] == c['preferred'] and c['history'] and c['history'][0]['cause'] == 'switch-back'" 420; then
  pass "after healing, HomeGAP switched the primary back to $(cluster "c['primary']")"
else
  fail "no switch-back: primary $(cluster "c['primary']"), $(cluster "c.get('switchBack')")"
fi
until_cluster "c['valkeyPrimary'] == c['preferred']" 180 && pass "Valkey followed" || fail "Valkey primary is $(cluster "c['valkeyPrimary']")"

echo
[ "$FAILS" = 0 ] && echo "all cluster checks passed" || echo "$FAILS check(s) failed"
exit "$FAILS"
