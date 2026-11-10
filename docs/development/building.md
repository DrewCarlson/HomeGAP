# Build from source

This page tells you how to build and test HomeGAP, how CI builds the image, and how to contribute.

## Requirements

You need JDK 21. The build downloads Gradle, Kotlin/Native and Node (for the dashboard) by itself.

## Commands

| Command | What it does |
| --- | --- |
| `./gradlew macosArm64Test` | Runs the unit tests on the host target. |
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

`.github/workflows/build.yml` runs on pushes to `main`, on version tags and on pull requests. It runs the unit tests and the presenter tests, then builds the multi-arch image. On pushes to `main` and on version tags, it publishes the image to GHCR. The image tags are:

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
  provision/    node stack templates, certificates, YAML emitter
  state/        local state cache
  dashboard/    REST API + embedded SPA, and the map's files
  locations/    the locations catalog (shipped list + local edits)
  tunnels/      Newt connector supervisor
api/            wire types shared by the API and the dashboard
web/            Compose for HTML dashboard, bundled with Vite (kotlin-vite)
deploy/         homelab compose; reference node stack
blueprints/     example Pangolin base blueprint
config/         example configuration
locations/      the shipped data-center catalog (datacenters.yml)
tools/          build-map.sh: the self-hosted map's tiles
e2e/            end-to-end harness
```

## Contributing

Issues and pull requests are welcome. Before you open a pull request, make sure that the unit tests pass (`./gradlew macosArm64Test`). For changes to provisioning, routing or DNS, the end-to-end harness must also pass. See [End-to-end tests](e2e.md).

For changes to these docs, see [Documentation](documentation.md).
