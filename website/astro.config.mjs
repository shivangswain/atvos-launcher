// The website of atvOS Launcher. A static site: Astro writes HTML and CSS to dist/, with no client JavaScript.
import { defineConfig } from "astro/config";

export default defineConfig({
  site: "https://atvos.tv",
  trailingSlash: "never",
  // A line break between words and a link stays a space, as in plain HTML. (The compressed HTML drops it.)
  compressHTML: false,
  build: {
    format: "file", // privacy.html, served at /privacy
    inlineStylesheets: "never", // one CSS file, so the Content-Security-Policy needs no inline styles
  },
});
