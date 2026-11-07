#!/usr/bin/env bash
# Asserts the data plane end to end: viewer -> node :443 (SNI passthrough) -> Newt tunnel -> homelab Traefik -> whoami.
set -euo pipefail
cd "$(dirname "$0")"
v() { docker compose exec -T viewer "$@"; }
echo "--- passthrough root (expect origin certificate CN=homelab-origin)"
v sh -c 'curl -sS -v --cacert /certs/ca.pem --resolve test.example.net:443:172.30.0.10 https://test.example.net/ 2>&1 | grep -E "subject:|Hostname:|HTTP/" '
echo "--- passthrough wildcard"
v sh -c 'curl -sS --cacert /certs/ca.pem --resolve media.test.example.net:443:172.30.0.10 https://media.test.example.net/ | grep -E "Host:|Hostname"'
echo "--- management API is served by the node itself (CN=test.example.net wildcard)"
v sh -c 'curl -sS -v --cacert /certs/ca.pem --resolve pangolin-na1.test.example.net:443:172.30.0.10 https://pangolin-na1.test.example.net/api/v1/ 2>&1 | grep -E "subject:|HTTP/"'
echo "--- port-80 health endpoint (what Bunny probes)"
v sh -c 'curl -sS -o /dev/null -w "%{http_code}\n" http://172.30.0.10/'
echo "--- http -> https redirect"
v sh -c 'curl -sS -o /dev/null -w "%{http_code} %{redirect_url}\n" -H "Host: media.test.example.net" http://172.30.0.10/'
echo "--- dedicated passthrough (expect origin cert + 'Name: git')"
v sh -c 'curl -sS -v --cacert /certs/ca.pem --resolve git.test.example.net:443:172.30.0.10 https://git.test.example.net/ 2>&1 | grep -E "subject:|Name:"'
echo "--- dedicated terminate (expect node wildcard cert CN=test.example.net + 'Name: wiki')"
v sh -c 'curl -sS -v --cacert /certs/ca.pem --resolve wiki.test.example.net:443:172.30.0.10 https://wiki.test.example.net/ 2>&1 | grep -E "subject:|Name:"'
echo "--- raw tcp on public port 19000 (expect echo)"
v sh -c '(printf "ping-through-tunnel\n"; sleep 2) | nc 172.30.0.10 19000'
