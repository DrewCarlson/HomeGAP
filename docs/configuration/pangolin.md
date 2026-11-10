# Pangolin options

The `pangolin` section controls the stack that HomeGAP installs on each node. This page tells you what the options do. For the defaults, see the [Configuration reference](reference.md#pangolin).

## Images

These keys set the images of the node stack:

- `pangolin_image`
- `gerbil_image`
- `traefik_image`
- `maintenance_image`
- `crowdsec_image`

A change of an image changes the node stack. Thus HomeGAP updates the nodes one at a time, with rollback. See [Node updates and rollback](../operations/node-updates.md).

In cluster mode, HomeGAP changes the Pangolin tag to the PostgreSQL build: `ee-<version>` becomes `ee-postgresql-<version>`.

`badger_version` sets the version of Badger, the Traefik plugin of Pangolin.

## Names

HomeGAP gives these names to the objects that it creates:

| Key | Object |
| --- | --- |
| `org_id` | The id of the Pangolin org. |
| `org_name` | The name of the Pangolin org. |
| `admin_email` | The email of the admin account. |
| `site_name` | The name of the homelab site. In cluster mode, each node gets `<site_name>-<node id>`. |

HomeGAP creates the admin account without a person at the keyboard. It does this on each node, or one time for the cluster.

## The install directory

`install_dir` (default `/opt/homegap`) is the directory of the stack on each node. It holds:

- The compose file and `config/`.
- `secrets/node.json`, the secrets of the node.
- `.homegap-rollback`, the saved stack for a rollback.
- `cluster/`, the cluster member, in cluster mode.
- `exit/`, the exit connector, if exits through nodes are enabled.

## The base blueprint

`base_blueprint` names a Pangolin blueprint, in YAML or JSON. HomeGAP applies it to every node before the managed resources. In cluster mode, it applies it one time for the cluster. When the content of the file changes, HomeGAP applies it again.

Use the base blueprint for static configuration that does not come from labels, for example shared resource policies. Shared policies are an EE feature.

!!! warning "Caution"
    Keys in the base blueprint must not start with `hg-`. HomeGAP reserves this prefix for the resources that it manages, and it can delete them.

This example comes from `blueprints/base.example.yml`. Mount the file and set `pangolin.base_blueprint: /config/blueprints/base.yml`:

```yaml
public-policies:
  allow-everyone:
    name: allow-everyone
    rules:
      - action: allow
        match: cidr
        value: 0.0.0.0/0
        priority: 1000
```

For the blueprint format, see the [Pangolin blueprint reference](https://docs.pangolin.net/manage/blueprints).

## CrowdSec

`crowdsec: true` enables the CrowdSec bouncer on the Traefik of each node. It uses `crowdsec_image` and `crowdsec_bouncer_version`. CrowdSec protects only terminated traffic. It cannot examine passthrough streams.

## Ports and tuning

`passthrough_port_base` (default 40000) is the start of the internal proxy ports for passthrough resources. HomeGAP never publishes these ports.

`tcp_tuning` (default `true`) applies TCP tuning on each node. See [Performance](../operations/performance.md).

## Managed and unmanaged objects

HomeGAP deletes only stale `hg-*` resources. The resources that you create by hand in the Pangolin of a node stay.
