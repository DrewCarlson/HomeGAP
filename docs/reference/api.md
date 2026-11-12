# HTTP API

The dashboard uses an HTTP API on the orchestrator. You can use the same API from scripts. This page lists every route.

## Basics

The base URL is `http://<dashboard.bind>`. Every `/api/*` route needs the header `Authorization: Bearer <token>`. The token comes from `dashboard.auth_token_env` or `dashboard.auth_token_file`. Without them, HomeGAP creates a token. See [Dashboard](../operations/dashboard.md#access).

An action returns `{"ok": true, "message": "..."}`. An error returns `{"ok": false, "message": "..."}`, with one of these HTTP status codes:

| Code | When |
| --- | --- |
| 400 | The input is not valid. |
| 409 | The request conflicts with the current state. |
| 500 | Any other error. |

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
| `GET /api/events?after=SEQ` | Server-sent events. The `status` event sends the snapshot when it changes. The `log` event sends the new log entries after `SEQ`. |
| `GET /api/log?domain=&limit=` | Returns the recent reconcile log. The default of `limit` is 300. |

## Config

| Route | What it does |
| --- | --- |
| `GET /api/config` | Returns the config file and the nodes. |
| `GET /api/diff` | Returns the items of the diff between desired and actual state, and the effective desired state. |
| `GET /api/secrets` | Returns the referenced secrets: names, sources and presence. It never returns the values. |

## Control

| Route | What it does |
| --- | --- |
| `POST /api/resync` | Queues a pass. |
| `POST /api/pause` | Pauses or resumes. Body: `{"scope": "global", "id": null, "paused": true}`. The `scope` is `global`, `node` or `service`. |
| `POST /api/cluster/switchover` | Makes a caught-up replica the database primary now. Body: `{"to": "<member>"}`. Cluster only. |
| `POST /api/cluster/maintenance/start?minutes=N` | Moves the cluster primaries off the homelab and holds them for up to N minutes (default 30). |
| `POST /api/cluster/maintenance/end` | Ends the hold. |

## Nodes

| Route | What it does |
| --- | --- |
| `POST /api/nodes` | Adds a node. The body is a node entry. HomeGAP writes the config file again, with a backup. |
| `DELETE /api/nodes/{id}` | Removes a node from the config. DNS drains first. The stack stays up, and the node stays in the cluster, until the decommission. |
| `POST /api/nodes/{id}/provision` | Queues a full provisioning of the node. |
| `POST /api/nodes/{id}/update/retry` | Applies a held update to the node again. |
| `POST /api/nodes/{id}/update/skip` | Leaves the node on its current stack, and continues the rollout. |
| `POST /api/nodes/{id}/decommission?confirm={id}` | Stops the stack of a removed node and forgets the node. `confirm` must repeat the node id. If the teardown fails, the call returns an error and the node stays. With `force=true`, HomeGAP forgets the node without a teardown. |
| `GET /api/nodes/{id}/logs?service=&since=&tail=` | Returns the logs of a container. See [Diagnostics and troubleshooting](../operations/troubleshooting.md#log-services). The default of `since` is `30m`. The default of `tail` is 300 lines, and the maximum is 5000. |
| `GET /api/nodes/{id}/diagnostics` | Returns the read-only diagnostics bundle of the node: `{node, title, text, at}`. |

## Node setup (the add-node wizard)

| Route | What it does |
| --- | --- |
| `POST /api/setup/test` | Tests an initial SSH login. Body: `host`, `port`, `user`, `password` or `keyFile`, and `resetHostKey`. |
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
| `DELETE /api/access/users/{id}` | Removes a user. |
| `PUT /api/access/users/{id}/roles` | Sets the roles of a user. Body: `{"roleIds": [...]}`. |
| `DELETE /api/access/invitations/{id}` | Withdraws an open invitation. |
| `POST /api/access/devices/{id}/{action}` | Does an action on a device: `block`, `unblock`, `archive`, `unarchive` or `delete`. |

## Speed test

| Route | What it does |
| --- | --- |
| `GET /api/speedtest` | Returns the settings and the recent results. |
| `POST /api/speedtest/nodes/{id}?streams=N` | Runs a test between the homelab and a node. The call returns when the test ends. `streams` is optional. |
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
