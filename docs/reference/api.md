# HTTP API

The dashboard uses an HTTP API on the orchestrator. You can use the same API from scripts. This page lists every route.

## Basics

The base URL is `http://<dashboard.bind>`. Every `/api/*` route needs the header `Authorization: Bearer <token>`, with two exceptions. `/api/terminal/ws` uses a ticket. The Traefik of the homelab can read `GET /api/traefik/homelab` from the front door container without a token. The token comes from `dashboard.auth_token_env` or `dashboard.auth_token_file`. Without them, HomeGAP creates a token. See [Dashboard](../operations/dashboard.md#access).

An action returns `{"ok": true, "message": "..."}`. If you can undo the action, the answer also has an `undo` id for `POST /api/undo/{id}`. An error returns `{"ok": false, "message": "..."}`, with one of these HTTP status codes:

| Code | When |
| --- | --- |
| 400 | The input is not valid. |
| 409 | The request conflicts with the current state. |
| 429 | The address sent more than 20 wrong tokens in one minute. |
| 500 | Any other error. |
| 503 | `/api/events` already has 32 open streams. |

Some answers have no JSON body:

| Code | When |
| --- | --- |
| 401 | The token is missing or wrong. |
| 404 | `GET /api/setup/jobs/{id}` does not know the job. |
| 204 | `GET /api/speedtest/live`, when no test runs. |

!!! danger "Warning"
    The API has control of every node. Never expose it publicly. See [Security model](security.md).

## Routes outside /api

These routes need no token:

| Route | What it does |
| --- | --- |
| `GET /healthz` | Returns `ok`. |
| `GET /map/...` | The tiles of the map (with range requests), its scripts, its fonts and its icons. The public listener also serves them when the status page is enabled. |
| `GET /docs/...` | These docs. |
| `GET /...` | Any other path returns the dashboard app. |

## Status and log

| Route | What it does |
| --- | --- |
| `GET /api/status` | Returns the full status snapshot. |
| `GET /api/events?after=SEQ` | Server-sent events. The first event is `status`, with the full snapshot. After that, a `patch` event sends the top-level fields of the snapshot that changed. The `log` event sends the new log entries after `SEQ`. The stream also sends `metrics`, `job`, `speedtest` and `access` events. |
| `GET /api/metrics?range=&edge=` | Returns the series of the graphs. `range` is in seconds. The default is one hour, and the maximum is one day. `edge` selects one node. |
| `GET /api/log?domain=&level=&q=&before=&limit=` | Returns the recent reconcile log. `before=SEQ` gives older entries. The default of `limit` is 300, and the maximum is 1000. |
| `GET /api/search?q=` | Searches the nodes, the services and the other items of the dashboard. |

## Config

| Route | What it does |
| --- | --- |
| `GET /api/config` | Returns the config file and the nodes. |
| `GET /api/diff` | Returns the items of the diff between desired and actual state, and the effective desired state. |
| `GET /api/secrets` | Returns the referenced secrets: names, sources and presence. It never returns the values. |

## Control

| Route | What it does |
| --- | --- |
| `POST /api/resync` | Queues a pass. With `?apply=once`, that pass applies the changes that wait, and the global pause stays on. |
| `POST /api/undo/{id}` | Undoes an action, with the `undo` id from its answer. |
| `POST /api/tunnels/{id}/restart` | Restarts the Newt connector of a node. |
| `POST /api/pause` | Pauses or resumes. Body: `{"scope": "global", "id": null, "paused": true}`. The `scope` is `global`, `node` or `service`. |
| `POST /api/cluster/switchover` | Makes a caught-up replica the database primary now. Body: `{"to": "<member>"}`. Cluster only. |
| `POST /api/cluster/maintenance/start?minutes=N` | Moves the cluster primaries off the homelab and holds them for up to N minutes (default 30). HomeGAP limits N to a value from 5 to 240. |
| `POST /api/cluster/maintenance/end` | Ends the hold. |

## Certificates

The status snapshot has a `certificates` part. It holds the facts of the certificate: the names, the dates, the issuer, the serial number and the fingerprint. It also holds the state and the history of the orders, the delivery to each node, the TLS probes and the state of the CAA records. It never holds a key, a certificate file, the ACME account key or a challenge value.

| Route | What it does |
| --- | --- |
| `POST /api/certificates/order` | Starts the order of the certificate in the next pass. |
| `POST /api/certificates/replace-key` | Orders a new certificate with a new key, and revokes the old certificate. |
| `POST /api/certificates/check` | Runs the TLS probes of each public name through each node again. |
| `POST /api/certificates/nodes/{id}/resend` | Sends the certificate to the node again in the next pass. |

## Nodes

| Route | What it does |
| --- | --- |
| `POST /api/nodes` | Adds a node. The body is a JSON object with `id`, `host` and `sshKeyFile`. Optional fields are `region`, `provider`, `os`, `sshUser`, `sshPort`, `latitude`, `longitude`, `locationName`, `managementHost` and `sshAllowFrom`. HomeGAP writes the config file again, with a backup. |
| `DELETE /api/nodes/{id}` | Removes a node from the config. DNS drains first. The stack stays up, and the node stays in the cluster, until the decommission. The answer has an `undo` id. |
| `PUT /api/nodes/{id}/location` | Sets the location of a node. Body: `{"location": {...}}`, as for the homelab. HomeGAP writes the config file again. |
| `POST /api/nodes/{id}/provision` | Queues a full provisioning of the node. |
| `POST /api/nodes/{id}/update/retry` | Applies a held update to the node again. |
| `POST /api/nodes/{id}/update/skip` | Leaves the node on its current stack, and continues the rollout. |
| `POST /api/nodes/{id}/decommission?confirm={id}` | Removes the stack, the cluster member, the mesh interface and the contents of the install directory of a removed node. Then HomeGAP forgets the node. `confirm` must repeat the node id. If the teardown fails, the call returns an error and the node stays. With `force=true`, HomeGAP forgets the node without a teardown. |
| `GET /api/nodes/{id}/logs?service=&since=&tail=` | Returns the logs of a container. See [Diagnostics and troubleshooting](../operations/troubleshooting.md#log-services). The default of `since` is `30m`. The default of `tail` is 300 lines, and the maximum is 5000. |
| `GET /api/nodes/{id}/diagnostics` | Returns the read-only diagnostics bundle of the node: `{node, title, text, at}`. |

## Terminal

When `dashboard.terminal` is off, the routes that start a shell or give a ticket answer with an error. `GET /api/terminal` then returns `enabled: false`.

| Route | What it does |
| --- | --- |
| `GET /api/terminal` | Returns `enabled` and the open sessions. |
| `POST /api/nodes/{id}/terminal?rows=N&columns=N` | Starts a shell on the node and returns its session. |
| `POST /api/terminal/{id}/ticket` | Returns a ticket for one WebSocket connection to the session. The ticket works one time, for 30 seconds. |
| `DELETE /api/terminal/{id}` | Ends the session. |
| `GET /api/terminal/ws?ticket=TICKET` | The WebSocket of the session. It needs no bearer token, because a browser cannot send one. The ticket is the check, and the `Origin` header must be the host of the dashboard. |

On the WebSocket, a binary frame has the bytes of the terminal: input from the browser, and output from the shell. A text frame has a JSON message with a `type`:

| Type | From | Fields | What it means |
| --- | --- | --- | --- |
| `hello` | Orchestrator | `offset` | The first message. `offset` is the position of the next output byte in the output of the session. |
| `exit` | Orchestrator | `code` | The shell ended. |
| `resize` | Browser | `rows`, `columns` | The new size of the terminal. |
| `ack` | Browser | `offset` | The browser has shown the output up to this position. The orchestrator stops the output while the browser is more than 128 KiB behind. |

A new connection first gets the last output of the session, at most 256 KiB.

## Node setup (the add-node wizard)

| Route | What it does |
| --- | --- |
| `POST /api/setup/test` | Tests an initial SSH login. Body: `host`, `port`, `user`, `password` or `keyFile`, `resetHostKey` and `hostKeyFingerprint`. A password login to a server with no recorded host key first returns the fingerprint. Send the test again with the fingerprint that you confirmed in `hostKeyFingerprint`. |
| `POST /api/setup/keys` | Generates, imports or describes the SSH key of a node. Body: `nodeId`, `mode`, and `privateKey` or `path`. |
| `POST /api/setup/jobs` | Starts a setup job. |
| `GET /api/setup/jobs/{id}` | Returns the progress of a setup job. |

## Locations

| Route | What it does |
| --- | --- |
| `GET /api/locations` | Returns the known locations (shipped and local) and the location of the homelab. |
| `PUT /api/locations/{id}` | Adds or edits a location. |
| `DELETE /api/locations/{id}?reset=` | Removes a location. A shipped location becomes hidden. With `reset=true`, an edited shipped location goes back to the original. |
| `PUT /api/homelab/location` | Sets the location of the homelab. Body: `{"location": {"latitude": ..., "longitude": ..., "name": "..."}}`, or `{"location": null}` to clear it. |

## Access (cluster only)

| Route | What it does |
| --- | --- |
| `GET /api/access` | Returns the users, roles, open invitations and devices of the cluster. |
| `POST /api/access/users` | Adds a user. Body: `{"email": "...", "roleIds": [...], "password": "..."}`. Without `password`, the response has the invite link. |
| `DELETE /api/access/users/{id}` | Removes a user after 30 seconds. The answer has an `undo` id. |
| `PUT /api/access/users/{id}/roles` | Sets the roles of a user. Body: `{"roleIds": [...]}`. |
| `POST /api/access/invitations/{id}/reissue` | Makes a new invite link for an open invitation. The old link stops. |
| `DELETE /api/access/invitations/{id}` | Withdraws an open invitation. |
| `POST /api/access/devices/{id}/{action}` | Does an action on a device: `block`, `unblock`, `archive`, `unarchive` or `delete`. |

## Speed test

| Route | What it does |
| --- | --- |
| `GET /api/speedtest` | Returns the settings and the recent results. |
| `POST /api/speedtest/nodes/{id}?streams=N` | Runs a test between the homelab and a node. The call returns when the test ends. `streams` is optional. |
| `GET /api/speedtest/live` | Returns the test between the homelab and a node that runs now. |
| `POST /api/speedtest/runs?streams=N` | Starts a test of every node, one node at a time. The status snapshot has the progress. |
| `DELETE /api/speedtest/runs` | Stops that run. |
| `POST /api/speedtest/link` | Creates a test link for a device. |

## Examples

To read the status, set `TOKEN` to your dashboard token. Then run this command:

```bash
curl -H "Authorization: Bearer $TOKEN" http://127.0.0.1:8088/api/status
```

To queue a pass:

```bash
curl -X POST -H "Authorization: Bearer $TOKEN" http://127.0.0.1:8088/api/resync
```

To follow the events (`after=SEQ` continues the log from an entry):

```bash
curl -N -H "Authorization: Bearer $TOKEN" http://127.0.0.1:8088/api/events
```
