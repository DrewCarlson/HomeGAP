# Secrets

The config file never holds a secret. This page tells you how to give secrets to HomeGAP, and where HomeGAP keeps the secrets that it creates.

## References to secrets

Each secret in the config is a reference:

- A key that ends in `_env` names an environment variable.
- A key that ends in `_file` names a file that you mount in the container.

The config has these references:

| Key | Default | The secret | The container that reads it |
| --- | --- | --- | --- |
| `bunny.api_key_env` | `BUNNY_API_KEY` | The API key of your Bunny account. | `orchestrator` |
| `bunny.api_key_file` | None. | The same key, as a file. | `orchestrator` |
| `dashboard.auth_token_env` | None. | The bearer token of the dashboard. | `orchestrator` |
| `dashboard.auth_token_file` | None. | The same token, as a file. | `orchestrator` |
| `sign_in.oidc.client_secret_env` | `HOMEGAP_OIDC_CLIENT_SECRET` | The client secret of the OpenID Connect provider. | `homegap-sign-in` |
| `nodes[].ssh_key_file` | None. Necessary. | The private SSH key of the node. | `orchestrator` |

For the Bunny key and the dashboard token, HomeGAP tries these sources in this order:

1. The environment variable, if it is set and not blank.
2. The file, if it is set and not blank.
3. The file `/run/secrets/<ENV_NAME>`, with the name from the `*_env` key. This is the convention of Docker secrets.

HomeGAP removes spaces and line breaks at the start and the end of each value.

The **Config** page on the dashboard lists the Bunny key, the dashboard token and the SSH key of each node. It shows if each one is present or missing. The page never shows the values.

## Environment variables

Put the values in `.env` next to the compose file. `homegap init` gives the file mode 600. Never commit the file.

```text
BUNNY_API_KEY=...
HOMEGAP_DASHBOARD_TOKEN=...
HOMEGAP_OIDC_CLIENT_SECRET=...
```

The config refers to the variables by their names:

```yaml
bunny:
  api_key_env: BUNNY_API_KEY
dashboard:
  auth_token_env: HOMEGAP_DASHBOARD_TOKEN
sign_in:
  oidc:
    client_secret_env: HOMEGAP_OIDC_CLIENT_SECRET
```

The shipped compose file gives each variable of `.env` to the orchestrator. Thus a secret of the orchestrator needs only a line in `.env`.

## The dashboard token

Every `/api` route of the dashboard needs the bearer token, or a sign-in through `dashboard.sso`. The orchestrator reads the token one time, at the start.

If the config names no token, or the token that it names is not available, HomeGAP creates one. It keeps the token in `dashboard-token` in its data directory, with mode 0600. With the shipped stack, this is the file `data/orchestrator/dashboard-token`. The log of the orchestrator names the file at the start.

## The client secret of the sign-in provider

The sign-in runs in its own container, `homegap-sign-in`. That container does not get the variables of `.env`. The shipped compose file gives it one variable, `HOMEGAP_OIDC_CLIENT_SECRET`.

With `sign_in.oidc` in the config, the sign-in container must have the variable that `client_secret_env` names. If the variable is missing or blank, the container stops with the error `sign_in.oidc needs the variable <name> in the sign-in container`. The sign-in reads this secret only from the environment variable. It does not read a file or `/run/secrets`.

To use a different variable name, add the variable to the `homegap-sign-in` service in `compose.override.yml`:

```yaml
services:
  homegap-sign-in:
    environment:
      MY_OIDC_SECRET: ${MY_OIDC_SECRET:-}
```

After a change of the secret, start the container again:

```bash
docker compose up -d homegap-sign-in
```

See [Sign-in](../vpn/sign-in.md).

## The SSH keys of the nodes

`ssh_key_file` of a node is a path in the orchestrator container. With the shipped stack, the path `/data/ssh/na1` is the file `data/orchestrator/ssh/na1` in the folder of the stack.

The **Add node** wizard of the dashboard creates an ed25519 key for a new node, or imports a key that you give it. It keeps the key in `ssh/` in the data directory, as `<id>_ed25519`, with mode 0600. The folder has mode 0700. The private key never leaves the orchestrator.

If you add a node by hand, put its private key in `data/orchestrator/ssh/`. Give the file mode 600 and the owner of the stack folder.

## Docker secrets

Docker mounts each secret at `/run/secrets/<name>`. If the name of the secret is the same as the name in the `*_env` key, the orchestrator finds it there. Put this in `compose.override.yml`:

```yaml
services:
  orchestrator:
    secrets: [HOMEGAP_DASHBOARD_TOKEN]

secrets:
  HOMEGAP_DASHBOARD_TOKEN:
    file: ./secrets/dashboard_token
```

With `dashboard.auth_token_env: HOMEGAP_DASHBOARD_TOKEN` and an empty `HOMEGAP_DASHBOARD_TOKEN` variable, HomeGAP reads `/run/secrets/HOMEGAP_DASHBOARD_TOKEN`.

!!! note
    The shipped compose file needs `BUNNY_API_KEY` in `.env`. Docker Compose refuses to start the stack without it.

## Secrets that HomeGAP creates

HomeGAP creates these secrets itself. The folders are the folders of the shipped stack.

| Secret | Where it is |
| --- | --- |
| The dashboard token, if the config gives none. | `data/orchestrator/dashboard-token`, with mode 0600. |
| The SSH keys from the **Add node** wizard. | `data/orchestrator/ssh/`, with mode 0600. |
| The key that signs the directory of users. | `data/orchestrator/authority/authority.key`, with mode 0600. No other container mounts this folder. |
| The key of the sign-in, which signs the sessions of the devices. | `data/sign-in/issuer.key`, with mode 0600. Only the sign-in container writes this folder. |
| The WireGuard keys of the homelab, for the mesh and for the VPN. | `data/mesh/hg-mesh.key` and `data/mesh/hg-vpn.key`, with mode 0600. |
| The ACME account key, and the certificate of the nodes with its key. | `data/orchestrator/acme/`. |
| The key of the CrowdSec bouncer of each node, with `node_stack.crowdsec`. | The state file `data/orchestrator/state.json` (mode 0600). On the node, `.env` and `config/traefik/dynamic/homegap-crowdsec.yml`, with mode 0600. |
| The mesh key of each node. | `/etc/wireguard/hg-mesh.key` on the node. The node creates it, and the private key never leaves the node. |
| The key of the exit of each node. | `agent-state/hg-exit.key` in the install folder of the node. |

HomeGAP does not keep the password of a user or the secret of a machine. It shows each one time, when you create it, and keeps only a hash.

!!! danger "Warning"
    Keep a backup of `data/orchestrator/authority/`. The folder holds the users and the key that signs them. Without it, HomeGAP starts with no users and a new key.

For each file, see [Files and state](../reference/files.md). For the full security model, see [Security model](../reference/security.md).
