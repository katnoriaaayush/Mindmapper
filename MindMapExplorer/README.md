# MindMapExplorer — Android app

A thin native **WebView shell** for MindMapper, targeting a **1280×720 dp, 75″
landscape touch display** (interactive board / large panel). It loads the
single-page UI served by `MindMapAIServer` with the `?display=board` flag, which
switches the web UI into a large-format, touch-optimised skin.

The app holds **no credentials** — all AI work happens server-side via Gemini.

## What it does

- Loads `http://10.0.2.2:3000/?display=board` (emulator → host loopback)
- Landscape-locked, immersive fullscreen, keep-screen-on
- Pull-to-refresh and an error/Retry screen when the server is unreachable
- Back button navigates WebView history, then exits

The board skin (in `MindMapAIServer/static/index.html`) scales up type and
controls, adds overscan-safe margins, enlarges canvas nodes, and enables
**touch drag-to-pan** and **two-finger pinch-to-zoom**.

## Prerequisites

- Android Studio (Koala / 2024.1+), Android SDK 34
- The backend running on your host machine:
  ```bash
  cd ../MindMapAIServer
  pip install -r requirements.txt
  cp .env.example .env      # add GCP credentials to use AI features
  python main.py            # serves on :3000
  ```

## Run (emulator)

1. Open the `MindMapExplorer/` folder in Android Studio and let it sync Gradle.
   (Android Studio provisions the Gradle wrapper automatically; from a terminal
   you can instead run `gradle wrapper` once if you have Gradle installed.)
2. Start the `MindMapAIServer` on your host (see above).
3. Launch an emulator (API 26+) and **Run** the `app` configuration.
4. The board UI loads. Without GCP credentials the generate/chat actions will
   error, but **"Load sample map"** renders the canvas so you can verify touch
   pan/zoom and the large-format layout.

## Pointing at a different backend

`app/build.gradle.kts` defines the URL once:

```kotlin
buildConfigField("String", "APP_URL", "\"http://10.0.2.2:3000/?display=board\"")
```

- **Physical device on the same LAN:** replace `10.0.2.2` with the host's LAN IP
  (and add that IP to `res/xml/network_security_config.xml`).
- **Deployed backend:** point at the `https://` URL and remove the cleartext
  exemption in `network_security_config.xml`.

## Scope

This is the v1 shell: **generate · view · chat**, which the embedded web UI
already provides. Native screens (offline maps, share, study mode) are future
work — see `MindMapAIServer/docs/WORKPLAN.md` (WS11).
