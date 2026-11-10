# Documentation

These docs are built with Zensical, from the Material for MkDocs team. Zensical reads `mkdocs.yml`. This page tells you how to preview and build them, how the versions work and where the dashboard copy comes from.

## Sources

- The config is `mkdocs.yml`.
- The pages are in `docs/`.
- `docs/requirements.txt` pins the dependencies: `zensical` 0.1.0, `pymdown-extensions` 13.0, and the Zensical fork of mike (tag `2.2.0+zensical-0.1.0`). The fork is not on PyPI. pip installs it from GitHub, so `git` must be installed.

The theme is dark (`slate`), and it uses system fonts. The site loads no fonts from a third party.

## Preview the docs

1. Install the dependencies:
    ```bash
    python3 -m pip install -r docs/requirements.txt
    ```
2. Start the preview server:
    ```bash
    zensical serve
    ```
3. Open `http://localhost:8000`.

To build the site as CI does, run a strict build. A broken link or a missing page fails it.

```bash
zensical build --strict
```

The output goes to `site/`.

## Variables and Jinja

Every page goes through Jinja, through Zensical's own version of the `markdownextradata` plugin. The variable `hg_version` gives the version that the pages describe. It comes from the `VERSION` environment variable, with the default `dev`.

!!! warning "Caution"
    Literal Jinja delimiters in a page, for example in a Go template for `docker inspect`, break the build. Put such text in a Jinja `raw` block.

## Versions

`.github/workflows/docs.yml` builds both configs on every push and pull request that changes the docs. It publishes to GitHub Pages (the `gh-pages` branch) with the Zensical fork of mike, but only when the repository is public:

| Event | Version |
| --- | --- |
| A push to `main` | `dev` |
| A tag `vX.Y.Z` | `X.Y`, with the alias `latest`. `latest` becomes the default. |

## The copy in the image

The Dockerfile builds `mkdocs.embedded.yml` into `/usr/share/homegap/docs`. This config inherits `mkdocs.yml`, but it has no version selector and no mike. The image build gives it `VERSION`, and `build.yml` passes the image version. The image build does not install mike.

The dashboard serves this copy at `/docs/`, and links it as **Documentation** at the bottom of the sidebar. To use a different folder, set `HOMEGAP_DOCS_DIR`. For example, build a local copy and point the orchestrator at it:

```bash
zensical build -f mkdocs.embedded.yml
```

The output goes to `site-embedded/`.

## Writing rules

Follow the [Writing style guide](style-guide.md) for every page.

A screenshot that the maintainer must add is an HTML comment on its own line, in this form:

```text
<!-- SCREENSHOT: docs/img/<page>/<nn>-<what>.png | what the image shows -->
```

The images go in `docs/img/`.

Do not use Mermaid diagrams. Mermaid loads from a CDN, and the copy on the dashboard must work without internet access.
