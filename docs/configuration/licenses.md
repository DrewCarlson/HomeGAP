# Pangolin EE licenses

Some features of Pangolin need an EE license key. This page tells you when you need a key and how to give it to HomeGAP in each mode.

## When you need a license

You need a license for wildcard HTTP resources in `terminate` mode. Other EE features also need it, for example shared policies in a base blueprint. Passthrough works without a license.

Pangolin EE has its own license terms. It is free for personal use and for organizations below its commercial revenue threshold.

## Keys bind to one instance

A key binds to one Pangolin instance when you activate it the first time. If a second instance tries the same key, the license server refuses it with "already been activated with a different instance name".

!!! danger "Warning"
    The license server records each activation. Do not activate production keys on test instances or on instances that you discard later.

## The key in each mode

=== "Cluster"

    One key covers every node, because all nodes share one database. Set `pangolin.license_key_env`, or `pangolin.license_key_file`.

    HomeGAP activates the key through the first node that can activate it. Then it tells the other nodes to read it again. HomeGAP ignores the keys of single nodes. The **Cluster database** panel shows the state of the license.

=== "Standalone"

    Each node needs its own key. Set `nodes[].license_key_env` for each node. `pangolin.license_key_env` is only a default, for a setup with one node.

    If a node refuses a key, HomeGAP reports it one time for that node, in the dashboard check `ee-license`. HomeGAP does not try the key again until the key changes.

## Give the keys to the orchestrator

Put the keys in `.env`, and give the variables to the orchestrator in the compose file. For example, in standalone mode with two nodes:

```yaml
services:
  orchestrator:
    environment:
      PANGOLIN_LICENSE_KEY_NA1: ${PANGOLIN_LICENSE_KEY_NA1}
      PANGOLIN_LICENSE_KEY_AS1: ${PANGOLIN_LICENSE_KEY_AS1}
```

```yaml
nodes:
  - id: na1
    license_key_env: PANGOLIN_LICENSE_KEY_NA1
  - id: as1
    license_key_env: PANGOLIN_LICENSE_KEY_AS1
```

The node entries above show only the license key. A real node entry also needs `host` and `ssh_key_file`. See [Secrets](secrets.md).
