import { defineConfig } from "vite";

// kotlin-vite runs Vite from the Kotlin/JS package output directory, where index.html and the
// compiled module are synced from src/jsMain/resources and the Kotlin compilation.
export default defineConfig({
  base: "/",
  build: {
    target: "es2020",
    sourcemap: false,
    assetsInlineLimit: 0,
    chunkSizeWarningLimit: 4096,
  },
  server: {
    // `./gradlew :web:jsBrowserDevelopmentServe --continuous` proxies the API to a running controller.
    proxy: { "/api": process.env.HOMEGAP_API ?? "http://127.0.0.1:8088" },
  },
});
