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
  v sh -c "curl -sS -v --cacert /certs/trust.pem --resolve test.example.net:443:$ip https://test.example.net/ 2>&1 | grep -E 'subject:|Hostname:|HTTP/' "
  echo "--- the homelab sees the viewer's own address (PROXY protocol; expect X-Real-Ip: $(v hostname -i | tr -d '\r'))"
  v sh -c "curl -sS --cacert /certs/trust.pem --resolve test.example.net:443:$ip https://test.example.net/ | grep -E 'X-Real-Ip'"
  echo "--- a private-only name is refused to the public (expect 403)"
  v sh -c "curl -sS --cacert /certs/trust.pem --resolve private.test.example.net:443:$ip -o /dev/null -w '%{http_code}\n' https://private.test.example.net/"
  echo "--- a private-only subfolder of the root is refused to the public, the rest of the root is not (expect 403 200)"
  v sh -c "curl -sS --cacert /certs/trust.pem --resolve test.example.net:443:$ip -o /dev/null -w '%{http_code} ' https://test.example.net/grafana; curl -sS --cacert /certs/trust.pem --resolve test.example.net:443:$ip -o /dev/null -w '%{http_code}\n' https://test.example.net/"
  echo "--- passthrough wildcard"
  v sh -c "curl -sS --cacert /certs/trust.pem --resolve media.test.example.net:443:$ip https://media.test.example.net/ | grep -E 'Host:|Hostname'"
  echo "--- management API is served by the node itself, with the certificate that HomeGAP ordered (expect issuer Pebble)"
  v sh -c "curl -sS -v --cacert /certs/trust.pem --resolve pangolin-$id.test.example.net:443:$ip https://pangolin-$id.test.example.net/api/v1/ 2>&1 | grep -E 'issuer:|HTTP/'"
  if grep -q '^cluster:' config.e2e.yml; then
    echo "--- cluster client address: login page and API on this node (expect issuer Pebble)"
    v sh -c "curl -sS -v --cacert /certs/trust.pem --resolve pangolin.test.example.net:443:$ip https://pangolin.test.example.net/auth/login 2>&1 | grep -E 'issuer:|^< HTTP/'"
    v sh -c "curl -sS --cacert /certs/trust.pem --resolve pangolin.test.example.net:443:$ip -o /dev/null -w 'api %{http_code}\n' https://pangolin.test.example.net/api/v1/"
  fi
  echo "--- the shared status and speed test names name this node, with no redirect (expect 200 \"via\":\"$id\" 200 \"node\":\"$id\")"
  v sh -c "curl -sS --cacert /certs/trust.pem --resolve status.test.example.net:443:$ip -o /dev/null -w '%{http_code} ' https://status.test.example.net/; curl -sS --cacert /certs/trust.pem --resolve status.test.example.net:443:$ip https://status.test.example.net/status/nodes | grep -o '\"via\":\"[^\"]*\"'"
  v sh -c "curl -sS --cacert /certs/trust.pem --resolve speedtest.test.example.net:443:$ip -o /dev/null -w '%{http_code} ' https://speedtest.test.example.net/; curl -sS --cacert /certs/trust.pem --resolve speedtest.test.example.net:443:$ip -H 'X-HomeGAP-Token: $TOKEN' https://speedtest.test.example.net/speedtest/info | grep -o '\"node\":\"[^\"]*\"'"
  echo "--- port-80 health endpoint (what Bunny probes)"
  v sh -c "curl -sS -o /dev/null -w '%{http_code}\n' http://$ip/"
  echo "--- http -> https redirect"
  v sh -c "curl -sS -o /dev/null -w '%{http_code} %{redirect_url}\n' -H 'Host: media.test.example.net' http://$ip/"
  echo "--- dedicated passthrough (expect origin cert + 'Name: git')"
  v sh -c "curl -sS -v --cacert /certs/trust.pem --resolve git.test.example.net:443:$ip https://git.test.example.net/ 2>&1 | grep -E 'subject:|Name:'"
  echo "--- dedicated terminate (expect issuer Pebble + 'Name: wiki')"
  v sh -c "curl -sS -v --cacert /certs/trust.pem --resolve wiki.test.example.net:443:$ip https://wiki.test.example.net/ 2>&1 | grep -E 'issuer:|Name:'"
  echo "--- the certificate on the node lists only names that the node terminates"
  names=$(docker compose exec -T "node-$id" cat /opt/homegap/config/traefik/certs/homegap.crt | openssl x509 -noout -ext subjectAltName | tr ',' '\n' | sed -n 's/.*DNS://p' | sort | tr '\n' ' ')
  echo "$names"
  case " $names" in
    *'*.test.example.net'*|*" git.test.example.net "*|*" test.example.net "*|*" media.test.example.net "*) echo "FAIL: the node holds a certificate for a wildcard or a passthrough name"; exit 1 ;;
  esac
  # The front door of the extra domain is in terminate mode, so its root and its wildcard are on the certificate.
  case " $names" in *" *.other.example.org other.example.org "*) ;; *) echo "FAIL: the names of the extra domain are not on the certificate"; exit 1 ;; esac
  case " $names" in *" wiki.test.example.net "*) ;; *) echo "FAIL: the terminate name is not on the certificate"; exit 1 ;; esac
  echo "--- the node has no ACME account and no resolver (expect {} and 0)"
  docker compose exec -T "node-$id" sh -c 'cat /opt/homegap/config/letsencrypt/acme.json; cat /opt/homegap/config/traefik/traefik_config.yml /opt/homegap/config/traefik/dynamic/homegap-routes.yml | grep -c -i "letsencrypt\|certificatesResolvers" || true'
  echo "--- a node cannot answer a passthrough name with its own certificate (expect the origin certificate)"
  v sh -c "curl -sS -v --cacert /certs/trust.pem --resolve git.test.example.net:443:$ip https://git.test.example.net/ 2>&1 | grep -E 'issuer:'"
  echo "--- raw tcp on public port 19000 (expect echo)"
  v sh -c "(printf 'ping-through-tunnel\n'; sleep 2) | nc $ip 19000"
  echo "--- port list: 7881/tcp and 3478/tcp (expect two echoes)"
  v sh -c "(printf 'rtc-tcp\n'; sleep 2) | nc $ip 7881; (printf 'turn-tcp\n'; sleep 2) | nc $ip 3478"
  echo "--- port list: 3478/udp, and the named service's 17000/udp to the container's 7000 (expect two replies)"
  v sh -c "printf 'turn-udp\n' | nc -u -w3 $ip 3478; printf 'alt-udp\n' | nc -u -w3 $ip 17000"
  echo "--- two UDP callers at once on 7882 (expect each its own reply, from different source ports)"
  v sh -c "printf 'caller-a\n' | nc -u -w3 $ip 7882 & printf 'caller-b\n' | nc -u -w3 $ip 7882 & wait"
  echo "--- extra domain: its dedicated service, its front door and a name under the front door (expect shop, other-door, other-door, issuer Pebble)"
  for name in shop.other.example.org:shop other.example.org:other-door anything.other.example.org:other-door; do
    host=${name%%:*}; origin=${name#*:}
    v sh -c "curl -sS --cacert /certs/trust.pem --resolve $host:443:$ip https://$host/ | grep -E '^Name: $origin\$'"
  done
  v sh -c "curl -sS -v --cacert /certs/trust.pem --resolve shop.other.example.org:443:$ip -o /dev/null https://shop.other.example.org/ 2>&1 | grep -E 'issuer:'"
done
echo "================ DNS"
echo "--- the zone of the extra domain (expect the root, the wildcard and shop on each node, and the CAA records)"
v sh -c "curl -s -H 'AccessKey: e2e-bunny-key' http://bunny-mock:8080/dnszone/8" | python3 -c '
import json, sys
records = json.load(sys.stdin)["Records"]
addresses = sorted((r["Name"], r["Value"]) for r in records if r["Type"] == 0)
for name, value in addresses: print(repr(name), value)
nodes = ["172.30.0.10", "172.30.0.11"]
assert addresses == sorted((name, ip) for name in ["", "*", "shop"] for ip in nodes), addresses
assert sorted(r.get("Tag") for r in records if r["Type"] == 9 and r["Name"] == "") == ["issue", "issuewild"]
assert not [r for r in records if r["Name"].startswith("_acme-challenge")], "a challenge record is left"
'
echo "--- no record of the extra domain is in the main zone (expect 0)"
v sh -c "curl -s -H 'AccessKey: e2e-bunny-key' http://bunny-mock:8080/dnszone/7" | python3 -c '
import json, sys
print(sum(1 for r in json.load(sys.stdin)["Records"] if "other" in r["Name"]))
'
echo "--- CAA records for the domain (expect issue and issuewild, dns-01 only)"
v sh -c "curl -s -H 'AccessKey: e2e-bunny-key' http://bunny-mock:8080/dnszone/7" | python3 -c '
import json, sys
caa = [r for r in json.load(sys.stdin)["Records"] if r["Type"] == 9]
for r in caa: print(r["Name"], r.get("Tag"), r["Value"])
tags = sorted(r.get("Tag") for r in caa)
assert tags == ["issue", "issuewild"], tags
assert all(r["Value"] == "letsencrypt.org; validationmethods=dns-01" for r in caa)
'
echo "--- no challenge record is left in the zone (expect 0)"
v sh -c "curl -s -H 'AccessKey: e2e-bunny-key' http://bunny-mock:8080/dnszone/7" | python3 -c '
import json, sys
print(sum(1 for r in json.load(sys.stdin)["Records"] if r["Name"].startswith("_acme-challenge")))
'
