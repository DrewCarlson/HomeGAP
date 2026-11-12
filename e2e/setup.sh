#!/usr/bin/env bash
# Generates throwaway SSH keys and a test CA + wildcard certificate for the e2e harness.
set -euo pipefail
cd "$(dirname "$0")"
mkdir -p work/keys work/certs work/data work/cluster-config work/tunnels work/homelab-certs
[ -f work/keys/id_ed25519 ] || ssh-keygen -q -t ed25519 -N '' -f work/keys/id_ed25519 -C homegap-e2e
cp work/keys/id_ed25519.pub work/keys/authorized_keys
D=test.example.net
if [ ! -f work/certs/ca.pem ]; then
  openssl req -x509 -newkey rsa:2048 -nodes -days 30 -subj "/CN=HomeGAP E2E CA" -keyout work/certs/ca.key -out work/certs/ca.pem 2>/dev/null
  printf "subjectAltName=DNS:%s,DNS:*.%s\n" "$D" "$D" > work/certs/ext.cnf
  # homelab Traefik's own cert (distinct CN proves passthrough: viewers see the origin's cert)
  openssl req -newkey rsa:2048 -nodes -subj "/CN=homelab-origin" -keyout work/homelab-certs/origin.key -out work/homelab-certs/origin.csr 2>/dev/null
  openssl x509 -req -in work/homelab-certs/origin.csr -CA work/certs/ca.pem -CAkey work/certs/ca.key -CAcreateserial -days 30 -extfile work/certs/ext.cnf -out work/homelab-certs/origin.pem 2>/dev/null
fi
chmod 600 work/keys/id_ed25519
echo "e2e material ready in $(pwd)/work"
