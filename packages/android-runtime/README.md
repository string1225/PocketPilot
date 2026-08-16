# PocketPilot Android runtime

This package bundles the TypeScript Agent loop for Android System WebView. The
production IIFE is minified and must stay at or below 4.5 MiB; the sync script
enforces that limit before copying it into the APK assets.

`execute_ts` uses the official TypeScript `transpileModule` compiler on the
WebView main thread, which accounts for most of the runtime bundle size. User
JavaScript never runs on that thread: `execute_js`, `execute_ts`, and installed
pure-computation plugin tools each create a fresh Blob Web Worker. The Worker
accepts JSON input only, has bounded output, console, and execution time, and is
terminated after completion, cancellation, or timeout. DOM, network, imports,
storage, child workers, and the Android native bridge are unavailable there.
