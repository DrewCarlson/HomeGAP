# Secrets

The config file never holds a secret. This page tells you how to give secrets to the orchestrator, and where HomeGAP keeps the secrets that it creates.

## References to secrets

Each secret in the config is a reference:

- A key that ends in `_env` names an environment variable.
- A key that ends in `_file` names a file that you mount in the container.

For each secret, HomeGAP tries these sources in this order:

1. The environment variable, if it is set and not blank.
2. The file, if it is set and not blank.
3. The file `/run/secrets/<ENV_NAME>`, with the name from the `*_env` key. This is the convention of Docker secrets.

HomeGAP removes spaces and line breaks at the start and the end of each value.

## The secrets that the config references

The **Config** page on the dashboard lists each secret that the config references, and shows if it is present or missing. The page never shows the values. The list contains:

- The Bunny DNS API key.
- The Pangolin EE license for all nodes, if you set one.
- The bearer token of the dashboard, if you set one.
- For each node, the SSH key (`ssh_key_file`), and the license of the node if you set one.

The PINs of `homegap.pin_env` are also secrets. HomeGAP reads them for each service. See [Pangolin sign-in for services](../services/authentication.md).

## Environment variables

Put the values in `.env` next to the compose file. Use mode 600, and never commit the file.

```text
BUNNY_API_KEY=...
PANGOLIN_LICENSE_KEY=...
HOMEGAP_DASHBOARD_TOKEN=...
```

Then give the variables to the orchestrator in the compose file:

```yaml
services:
  orchestrator:
    environment:
      BUNNY_API_KEY: ${BUNNY_API_KEY:?set BUNNY_API_KEY}
      PANGOLIN_LICENSE_KEY: ${PANGOLIN_LICENSE_KEY:-}
      HOMEGAP_DASHBOARD_TOKEN: ${HOMEGAP_DASHBOARD_TOKEN}
```

The config refers to the variables by their names:

```yaml
bunny:
  api_key_env: BUNNY_API_KEY
pangolin:
  license_key_env: PANGOLIN_LICENSE_KEY
dashboard:
  auth_token_env: HOMEGAP_DASHBOARD_TOKEN
```

## Docker secrets

Docker mounts each secret at `/run/secrets/<name>`. If the name of the secret is the same as the name in the `*_env` key, HomeGAP finds it there:

```yaml
services:
  orchestrator:
    secrets: [BUNNY_API_KEY]

secrets:
  BUNNY_API_KEY:
    file: ./secrets/bunny_api_key
```

With `bunny.api_key_env: BUNNY_API_KEY` and no `BUNNY_API_KEY` variable, HomeGAP reads `/run/secrets/BUNNY_API_KEY`.

## Secrets that HomeGAP creates

HomeGAP creates some secrets itself:

- The secrets of each node: the Pangolin server secret, the admin credentials, the setup token and the CrowdSec bouncer key. They come from the CSPRNG of the kernel. HomeGAP sends them over SSH to `<install_dir>/secrets/node.json` on the node, with mode 0600.
- The cluster passwords, in `cluster/secrets.json` in the data directory. HomeGAP creates them one time and never replaces them. Keep a backup of this file.
- The dashboard token, in `dashboard-token` in the data directory, if the config gives none.
- The SSH keys that the add-node wizard generates, in `<data dir>/ssh/`, with mode 0600.

The file `node.json` on the node is the source of truth. The local state file `/data/state.json` (mode 0600) is only a cache. If you lose it, HomeGAP reads the credentials from the nodes again.

For the full security model, see [Security model](../reference/security.md).
