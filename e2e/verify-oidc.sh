#!/usr/bin/env bash
# Signs a device in through Authelia, as a browser does, and prints what the sign-in answers.
#
#   ./verify-oidc.sh LOGIN        LOGIN is a user of Authelia: ada, eve or mallory
#
# It runs curl in the viewer container: the start of a device sign-in, the page of the sign-in,
# the first factor of Authelia, and the way back to HomeGAP. The last line is `approved` or
# `refused`.
set -euo pipefail
cd "$(dirname "$0")"

LOGIN=${1:?give a user of Authelia: ada, eve or mallory}
NODE=${NODE_IP:-172.30.0.10}

docker compose exec -T -e LOGIN="$LOGIN" -e NODE="$NODE" viewer sh -s <<'EOF'
set -eu
S=https://signin.test.example.net
A=https://auth.test.example.net:9091
via="--resolve signin.test.example.net:443:$NODE"
jar=$(mktemp)

start=$(curl -sk $via -X POST "$S/api/v1/auth/device-web-auth/start" \
  -H 'Content-Type: application/json' -H 'X-CSRF-Token: x-csrf-protection' \
  -d '{"deviceName":"verify-oidc","applicationName":"harness"}')
code=$(printf '%s' "$start" | sed -n 's/.*"code":"\([A-Z0-9-]*\)".*/\1/p')
[ -n "$code" ] || { echo "no code: $start"; exit 1; }

# The page has the link to the provider, with the secret of the form.
link=$(curl -sk $via "$S/auth/login/device?code=$code" | sed -n 's/.*href="\(\/auth\/oidc\/start?[^"]*\)".*/\1/p' | sed 's/&amp;/\&/g')
[ -n "$link" ] || { echo "the page has no link to the provider"; exit 1; }
authorize=$(curl -sk $via -o /dev/null -w '%{redirect_url}' "$S$link")

curl -sk -c "$jar" -o /dev/null -X POST "$A/api/firstfactor" -H 'Content-Type: application/json' \
  -d "{\"username\":\"$LOGIN\",\"password\":\"e2e-authelia-password\",\"keepMeLoggedIn\":false}"

# Authelia sends the browser back to HomeGAP with the code.
page=$(curl -sk $via -b "$jar" -L --max-redirs 6 "$authorize")
rm -f "$jar"
if printf '%s' "$page" | grep -q 'Device approved'; then
  printf '%s\n' "$page" | sed -n 's/.*You are signed in as[^a-z0-9]*\([a-z0-9.@-]*\).*/signed in as \1/p' | head -n 1
  echo approved
else
  printf '%s\n' "$page" | sed -n 's/.*class="problem"[^>]*>\([^<]*\)<.*/\1/p' | head -n 1
  echo refused
fi
EOF
