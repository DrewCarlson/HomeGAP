#!/usr/bin/env bash
# Asserts the data plane end to end on every node: viewer -> node :443 (SNI passthrough or edge
# termination) -> Newt tunnel -> homelab Traefik -> the service. With the cluster on, the shared
# client address (login pages and client API) is checked on every node too.
set -euo pipefail
cd "$(dirname "$0")"
v() { docker compose exec -T viewer "$@"; }
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
done
