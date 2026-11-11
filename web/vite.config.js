import { defineConfig } from "vite";
import { readdirSync, readFileSync, statSync, writeFileSync } from "node:fs";
import { join, resolve } from "node:path";
import { brotliCompressSync, constants } from "node:zlib";

/** Text files the orchestrator also serves brotli-compressed. */
const COMPRESSIBLE = /\.(js|mjs|css|html|svg|json|map|wasm)$/;

/**
 * Writes a `.br` copy next to each text file in the build, at the highest quality. The
 * orchestrator embeds it and sends it to browsers that accept brotli. Node's own zlib does the
 * work, so the build needs no extra package.
 */
function brotliCopies() {
  let outDir = "dist";
  return {
    name: "homegap-brotli-copies",
    apply: "build",
    configResolved(config) {
      outDir = resolve(config.root, config.build.outDir);
    },
    closeBundle() {
      const walk = (dir) => {
        for (const name of readdirSync(dir)) {
          const path = join(dir, name);
          if (statSync(path).isDirectory()) {
            walk(path);
          } else if (COMPRESSIBLE.test(name)) {
            const compressed = brotliCompressSync(readFileSync(path), {
              params: { [constants.BROTLI_PARAM_QUALITY]: constants.BROTLI_MAX_QUALITY },
            });
            writeFileSync(`${path}.br`, compressed);
          }
        }
      };
      walk(outDir);
    },
  };
}

export default defineConfig({
  base: "/",
  plugins: [brotliCopies()],
  build: {
    target: "es2020",
    sourcemap: false,
    assetsInlineLimit: 0,
    chunkSizeWarningLimit: 4096,
  },
  server: {
    proxy: { "/api": process.env.HOMEGAP_API ?? "http://127.0.0.1:8088" },
  },
});
