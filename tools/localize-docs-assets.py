#!/usr/bin/env python3
"""
Makes a built docs site load nothing from a CDN: the docs the orchestrator serves (see
mkdocs.embedded.yml) must never contact another site.

The theme's script bundle loads some features' files from unpkg and jsDelivr when a page uses them
(Mermaid diagrams, image lightboxes, a ResizeObserver polyfill for old browsers). This points
every such URL at a copy next to the bundle and downloads the copies, pinned and hash-checked.
Runnable code blocks (Ace, Pyodide) are not copied (Pyodide alone is hundreds of MB): their URLs
point at the site too, so they can only fail, never reach the CDN.

    tools/localize-docs-assets.py site-embedded
"""
import hashlib
import pathlib
import re
import sys
import urllib.request

# The theme's URL, when it names no exact version, and the pinned file it is served from.
PINNED = {
    "https://unpkg.com/mermaid@12/dist/mermaid.min.js":
        "https://unpkg.com/mermaid@12.2.0/dist/mermaid.min.js",
    "https://unpkg.com/glightbox@3/dist/js/glightbox.min.js":
        "https://unpkg.com/glightbox@3.3.1/dist/js/glightbox.min.js",
    "https://unpkg.com/glightbox@3/dist/css/glightbox.min.css":
        "https://unpkg.com/glightbox@3.3.1/dist/css/glightbox.min.css",
    "https://unpkg.com/resize-observer-polyfill":
        "https://unpkg.com/resize-observer-polyfill@1.5.1/dist/ResizeObserver.js",
}
SHA256 = {
    "https://unpkg.com/mermaid@12.2.0/dist/mermaid.min.js":
        "a8a6a6f805b074a8261a2825c86f99c8054acb3b35f50bb7e24a4f9f2c3c3b9f",
    "https://unpkg.com/glightbox@3.3.1/dist/js/glightbox.min.js":
        "c67c59cdf980793b0216c9de9c6ebf688418838822af1e50b09439e3aa289ff3",
    "https://unpkg.com/glightbox@3.3.1/dist/css/glightbox.min.css":
        "6d3f62d4d17969f9c70e9438cf671004725019e868123f2ebc295a006f8d5d2d",
    "https://unpkg.com/resize-observer-polyfill@1.5.1/dist/ResizeObserver.js":
        "2290b5c60e0cdc62851fb687800237273ac53797595b1b133860c4f1386de378",
}
CDN = re.compile(r'"(https://(?:unpkg\.com|cdn\.jsdelivr\.net)/[^"]*)"')
# Relative to the bundle's own URL, so it works wherever the site is mounted.
BUNDLE = "document.querySelector('script[src*=\"javascripts/bundle.\"]').src"


def local(url: str) -> str:
    return "vendor/" + url.removeprefix("https://")


def main(site: str) -> None:
    scripts = pathlib.Path(site, "assets", "javascripts")
    bundles = list(scripts.glob("bundle.*.js"))
    if len(bundles) != 1:
        sys.exit(f"expected one theme bundle in {scripts}, found {len(bundles)}")
    bundle = bundles[0]
    text = bundle.read_text()

    def replace(m: re.Match) -> str:
        url = PINNED.get(m.group(1), m.group(1))
        return f'new URL("{local(url)}",{BUNDLE}).href'

    text, n = CDN.subn(replace, text)
    if n == 0:
        sys.exit(f"no CDN URLs in {bundle}: the theme changed, check this script")
    bundle.write_text(text)

    for url, digest in SHA256.items():
        with urllib.request.urlopen(url, timeout=60) as r:
            data = r.read()
        if hashlib.sha256(data).hexdigest() != digest:
            sys.exit(f"{url}: unexpected SHA-256")
        out = scripts / local(url)
        out.parent.mkdir(parents=True, exist_ok=True)
        out.write_bytes(data)
    print(f"{bundle.name}: {n} CDN URLs now load from the site, {len(SHA256)} files copied")


if __name__ == "__main__":
    main(sys.argv[1] if len(sys.argv) > 1 else "site-embedded")
