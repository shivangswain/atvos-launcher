// The website of atvOS Launcher. A static site: Astro writes HTML and CSS to dist/, with no client JavaScript.
import { defineConfig } from "astro/config";

export default defineConfig({
  site: "https://atvos.tv",
  trailingSlash: "never",
  build: {
    format: "file", // privacy.html, served at /privacy
    inlineStylesheets: "never", // one CSS file, so the Content-Security-Policy needs no inline styles
  },
});
