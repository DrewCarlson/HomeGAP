import { defineConfig } from "vite";

export default defineConfig({
  base: "/",
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
