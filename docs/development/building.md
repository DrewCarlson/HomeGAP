# Build from source

This page tells you how to build and test HomeGAP, how CI builds the image, and how to contribute.

## Requirements

You need JDK 21. The build downloads Gradle, Kotlin/Native and Node (for the dashboard) by itself.

## Commands

| Command | What it does |
| --- | --- |
| `./gradlew macosArm64Test` | Runs the unit tests on an Apple Silicon Mac. The other targets are `linuxX64` and `linuxArm64`. |
| `./gradlew :presentation:jsNodeTest` | Runs the presenter tests of the dashboard, on Node. |
| `./gradlew linkReleaseExecutableLinuxX64 linkReleaseExecutableLinuxArm64` | Builds the two Linux binaries. |
| `./gradlew -Phomegap.skipWeb=true …` | Skips the dashboard bundle, for fast iteration. |
| `./gradlew :web:jsBrowserDevelopmentServe --continuous` | Runs the development server of the dashboard. It sends `/api` to port 8088. |
| `tools/build-map.sh tiles` | Builds `map/world.pmtiles` for local runs. The image builds its own tiles. |

## The image

The image build needs the two Linux binaries first:

1. Build the binaries:
    ```bash
    ./gradlew linkReleaseExecutableLinuxX64 linkReleaseExecutableLinuxArm64
    ```
2. Build the image:
    ```bash
    docker buildx build --platform linux/amd64,linux/arm64 -t homegap .
    ```

The Dockerfile builds the map tiles and these docs (`mkdocs.embedded.yml`) itself.

## CI

`.github/workflows/build.yml` runs on pushes to `main`, on version tags, on pull requests and on a manual start. It runs the unit tests and the presenter tests. Then it builds two multi-arch images: the orchestrator image and the cluster image from `cluster/Dockerfile`. On each run that is not a pull request, it publishes the two images to GHCR and signs them with cosign. The name of the cluster image has the suffix `-cluster`. The image tags are:

| Event | Tags |
| --- | --- |
| A push to `main` | `main`, `sha-<short>` |
| A tag `vX.Y.Z` | `X.Y.Z`, `X.Y`, `latest` |

## Repository layout

```text
src/nativeMain/kotlin/homegap/
  discovery/    Docker socket client + label parsing
  model/        pure planner: labels + config -> desired state
  reconcile/    controller loop and the node, tunnel, resource and DNS reconcilers
  clients/      Pangolin (integration + bootstrap), Bunny, SSH (OpenSSH ControlMaster)
  provision/    node, cluster and exit stack templates, firewall rules, maintenance page, YAML emitter
  acme/         the certificate of the nodes: orders, DNS challenge, probes
  setup/        the add-node wizard: first login, keys, setup scripts
  mesh/         the mesh agent of the homelab
  state/        local state cache
  dashboard/    HTTP server, token check, embedded SPA, and the map and docs files
  routes/       the REST API routes
  access/       users and devices of the cluster
  diagnostics/  node logs and the diagnostics bundle
  terminal/     shell sessions on the nodes
  speedtest/    speed tests and the public listener
  status/       the public status page
  locations/    the locations catalog (shipped list + local edits)
  tunnels/      Newt connector supervisor
  config/       config file, secrets, shared directories
  platform/     files, processes, crypto and other native helpers
  di/           the dependency graphs
api/            wire types shared by the API and the dashboard
presentation/   presenters and UI models of the dashboard, tested on Node
web/            Compose for HTML dashboard, bundled with Vite (kotlin-vite)
cluster/        the cluster image (PostgreSQL with Patroni, etcd, Valkey, HAProxy)
deploy/         homelab compose (homelab-compose.yml) and the upgrade script (upgrade.sh)
blueprints/     example Pangolin base blueprint
config/         example configuration
locations/      the shipped data-center catalog (datacenters.yml)
tools/          build-map.sh: the self-hosted map's tiles; localize-docs-assets.py: local copies of the docs theme files
docs/           these docs
e2e/            end-to-end harness
```

## Contributing

Issues and pull requests are welcome. Before you open a pull request, make sure that the unit tests and the presenter tests pass (`./gradlew macosArm64Test :presentation:jsNodeTest`). For changes to provisioning, routing or DNS, the end-to-end harness must also pass. See [End-to-end tests](e2e.md).

For changes to these docs, see [Documentation](documentation.md).
