#!/usr/bin/env bash
# Asserts the data plane end to end on every node: viewer -> node :443 (SNI passthrough or edge
# termination) -> Newt tunnel -> homelab Traefik -> the service. With the cluster on, the shared
# client address (login pages and client API) is checked on every node too.
set -euo pipefail
cd "$(dirname "$0")"
v() { docker compose exec -T viewer "$@"; }
# A device test link's token, to read which node the speed test's shared name came through.
TOKEN=$(curl -s -X POST -H "Authorization: Bearer $(cat work/data/dashboard-token)" http://127.0.0.1:18088/api/speedtest/link | sed -n 's/.*[#&]t=\([A-Za-z0-9_-]*\).*/\1/p')
for node in na1:172.30.0.10 as1:172.30.0.11; do
  id=${node%%:*}; ip=${node#*:}
  echo "================ $id ($ip)"
  echo "--- passthrough root (expect origin certificate CN=homelab-origin)"
  v sh -c "curl -sS -v --cacert /certs/ca.pem --resolve test.example.net:443:$ip https://test.example.net/ 2>&1 | grep -E 'subject:|Hostname:|HTTP/' "
  echo "--- the homelab sees the viewer's own address (PROXY protocol; expect X-Real-Ip: $(v hostname -i | tr -d '\r'))"
  v sh -c "curl -sS --cacert /certs/ca.pem --resolve test.example.net:443:$ip https://test.example.net/ | grep -E 'X-Real-Ip'"
  echo "--- a private-only name is refused to the public (expect 403)"
  v sh -c "curl -sS --cacert /certs/ca.pem --resolve private.test.example.net:443:$ip -o /dev/null -w '%{http_code}\n' https://private.test.example.net/"
  echo "--- a private-only subfolder of the root is refused to the public, the rest of the root is not (expect 403 200)"
  v sh -c "curl -sS --cacert /certs/ca.pem --resolve test.example.net:443:$ip -o /dev/null -w '%{http_code} ' https://test.example.net/grafana; curl -sS --cacert /certs/ca.pem --resolve test.example.net:443:$ip -o /dev/null -w '%{http_code}\n' https://test.example.net/"
  echo "--- passthrough wildcard"
  v sh -c "curl -sS --cacert /certs/ca.pem --resolve media.test.example.net:443:$ip https://media.test.example.net/ | grep -E 'Host:|Hostname'"
  echo "--- management API is served by the node itself (CN=test.example.net wildcard)"
  v sh -c "curl -sS -v --cacert /certs/ca.pem --resolve pangolin-$id.test.example.net:443:$ip https://pangolin-$id.test.example.net/api/v1/ 2>&1 | grep -E 'subject:|HTTP/'"
  if grep -q '^cluster:' config.e2e.yml; then
    echo "--- cluster client address: login page and API on this node (node wildcard cert)"
    v sh -c "curl -sS -v --cacert /certs/ca.pem --resolve pangolin.test.example.net:443:$ip https://pangolin.test.example.net/auth/login 2>&1 | grep -E 'subject:|^< HTTP/'"
    v sh -c "curl -sS --cacert /certs/ca.pem --resolve pangolin.test.example.net:443:$ip -o /dev/null -w 'api %{http_code}\n' https://pangolin.test.example.net/api/v1/"
  fi
  echo "--- the shared status and speed test names name this node, with no redirect (expect 200 \"via\":\"$id\" 200 \"node\":\"$id\")"
  v sh -c "curl -sS --cacert /certs/ca.pem --resolve status.test.example.net:443:$ip -o /dev/null -w '%{http_code} ' https://status.test.example.net/; curl -sS --cacert /certs/ca.pem --resolve status.test.example.net:443:$ip https://status.test.example.net/status/nodes | grep -o '\"via\":\"[^\"]*\"'"
  v sh -c "curl -sS --cacert /certs/ca.pem --resolve speedtest.test.example.net:443:$ip -o /dev/null -w '%{http_code} ' https://speedtest.test.example.net/; curl -sS --cacert /certs/ca.pem --resolve speedtest.test.example.net:443:$ip -H 'X-HomeGAP-Token: $TOKEN' https://speedtest.test.example.net/speedtest/info | grep -o '\"node\":\"[^\"]*\"'"
  echo "--- port-80 health endpoint (what Bunny probes)"
  v sh -c "curl -sS -o /dev/null -w '%{http_code}\n' http://$ip/"
  echo "--- http -> https redirect"
  v sh -c "curl -sS -o /dev/null -w '%{http_code} %{redirect_url}\n' -H 'Host: media.test.example.net' http://$ip/"
  echo "--- dedicated passthrough (expect origin cert + 'Name: git')"
  v sh -c "curl -sS -v --cacert /certs/ca.pem --resolve git.test.example.net:443:$ip https://git.test.example.net/ 2>&1 | grep -E 'subject:|Name:'"
  echo "--- dedicated terminate (expect node wildcard cert CN=test.example.net + 'Name: wiki')"
  v sh -c "curl -sS -v --cacert /certs/ca.pem --resolve wiki.test.example.net:443:$ip https://wiki.test.example.net/ 2>&1 | grep -E 'subject:|Name:'"
  echo "--- raw tcp on public port 19000 (expect echo)"
  v sh -c "(printf 'ping-through-tunnel\n'; sleep 2) | nc $ip 19000"
  echo "--- port list: 7881/tcp and 3478/tcp (expect two echoes)"
  v sh -c "(printf 'rtc-tcp\n'; sleep 2) | nc $ip 7881; (printf 'turn-tcp\n'; sleep 2) | nc $ip 3478"
  echo "--- port list: 3478/udp, and the named service's 17000/udp to the container's 7000 (expect two replies)"
  v sh -c "printf 'turn-udp\n' | nc -u -w3 $ip 3478; printf 'alt-udp\n' | nc -u -w3 $ip 17000"
  echo "--- two UDP callers at once on 7882 (expect each its own reply, from different source ports)"
  v sh -c "printf 'caller-a\n' | nc -u -w3 $ip 7882 & printf 'caller-b\n' | nc -u -w3 $ip 7882 & wait"
done
