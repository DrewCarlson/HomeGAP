# Pangolin sign-in for services

Pangolin can ask for a sign-in or a PIN before a viewer reaches a service. This page tells you how to enable this with labels.

## The two modes

`homegap.auth: sso` puts the Pangolin sign-in in front of the service. `homegap.roles` lists the roles that can open it. The `Admin` role can always open it.

`homegap.auth: pin` asks for a 6-digit PIN. The PIN comes from the environment variable that `homegap.pin_env` names, on the orchestrator. Docker secrets also work, through `/run/secrets/<NAME>`.

## Requirements

Both modes need `homegap.tls: terminate`, because the node cannot authenticate encrypted traffic. On the front door, `terminate` with a wildcard needs Pangolin EE. See [Traffic paths](traffic-paths.md#front-door-terminate).

The sign-in pages must be public:

=== "Standalone"

    Set `dashboard_public: true` on each node. Then the node routes its Pangolin web UI on its management host, `pangolin-<id>.<domain>`.

=== "Cluster"

    The links and redirects of Pangolin use the client address `pangolin.<domain>`. Every node serves the login pages and the API on this address. You do not set anything more.

## Users

In standalone mode, you manage the users in the Pangolin UI of each node. In cluster mode, you manage them on the **Access** page. See [Users and devices](../cluster/access.md).

The resources of the speed test and the status page never have sign-in. HomeGAP creates them without it.

## Example

A service on its own hostname, with Pangolin sign-in for the `Member` role:

```yaml
grafana:
  labels:
    homegap.expose: "grafana.example.net"
    homegap.protocol: "https"
    homegap.tls: "terminate"
    homegap.auth: "sso"
    homegap.roles: "Member"
```
