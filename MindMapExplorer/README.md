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

## Troubleshooting

### Black screen in the app, but the URL works in Chrome

The page and network are fine (Chrome proves it), so it's almost always one of two
things. Diagnose first, then fix:

**1. See what's actually happening (2 minutes).**
The app enables WebView remote debugging in debug builds.
- Run the debug app, then on your computer open **`chrome://inspect`** in desktop
  Chrome → find the device's WebView → **inspect**. You'll see the live DOM and the
  JS console.
- Or watch **Logcat** filtered by tag `MindMapperWeb` — console messages, JS errors,
  and load failures are logged there.

If the DOM is present but the screen is black → it's a **render/GPU** issue (case A).
If you see a **JS error** or the DOM is empty → case B.

**A. Emulator GPU compositing (most common).** The Android System WebView composites
its hardware layer as solid black on some emulator graphics settings, even though the
Chrome app renders fine. Fixes, in order:
- **Cold boot** the emulator (Device Manager → ▾ → *Cold Boot Now*).
- Emulator **Settings → Advanced → OpenGL ES renderer → "Desktop native OpenGL"**
  (or switch *Graphics* to **Hardware**), then restart the emulator.
- Use a **Google APIs** system image (has an up-to-date WebView).
- Update **Android System WebView** + Chrome in the emulator via the Play Store.
- Last resort (guaranteed paint, lower perf): uncomment the
  `setLayerType(... LAYER_TYPE_SOFTWARE ...)` line in `MainActivity.configureWebView()`.

**B. Reaching the wrong host.** `10.0.2.2` is the host loopback **from the Android
emulator only**. On a physical device it won't resolve — point `APP_URL` at the host's
LAN IP (and add it to `network_security_config.xml`). If Chrome worked because you
typed a different address there, that's the tell.

## Scope

This is the v1 shell: **generate · view · chat**, which the embedded web UI
already provides. Native screens (offline maps, share, study mode) are future
work — see `MindMapAIServer/docs/WORKPLAN.md` (WS11).
