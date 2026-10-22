# Forge — idea → Android APK

Describe an app in one sentence; Forge writes it as a web app, drops it into a
fixed, known-good Android WebView project, and builds an installable APK with
the **same Gradle/AGP toolchain Myarch itself uses**. One GUI runs on your
Linux desktop and on your Android phone.

```
"next bus from my location"
        │
        ▼
  ┌───────────┐   Claude (or offline     ┌──────────────┐   Gradle +      ┌─────────┐
  │  Forge UI │──▶ templates) writes  ───▶│ WebView app   │── Android SDK ─▶│  .apk   │
  │ (browser) │    HTML/CSS/JS           │ project       │   on Linux      │ install │
  └───────────┘                          └──────────────┘                 └─────────┘
```

## Honest scope — read this first

- **Not instant.** A real Android build (Gradle + the SDK) takes minutes,
  especially the first time. Forge shows a live build log; it does not pretend
  to be instant.
- **Best-effort, not magic.** Turning arbitrary English into a working app is
  generation, not a guarantee. Simple, well-scoped ideas ("next bus near me",
  "a pomodoro timer", "a tip calculator") work well. Some ideas need a second
  try or a more specific sentence.
- **WebView apps.** Generated apps are a WebView around generated HTML/JS. That
  is the reliable path — it compiles every time and is great for
  location/web-API ideas. It is not native Kotlin/Compose.
- **Builds on Linux.** The APK is compiled on the machine running Forge (where
  the Android SDK lives). Your phone is a front-end: it sends the idea and
  installs the finished APK.

## Requirements

Same as building Myarch:

- **JDK 17+**
- **Android SDK** with `platforms;android-35` and `build-tools;35.0.0`
  (set `ANDROID_HOME`)
- **Python 3.9+** (standard library only for the app itself)
- Optional: the **Anthropic SDK** + an API key for full generation
  — `pip install anthropic` and `export ANTHROPIC_API_KEY=...`.
  Without them, Forge uses built-in offline templates.

Check your machine:

```sh
./forge doctor
```

## Use it

### On Linux

```sh
cd forge
./forge              # starts the server and opens the GUI in your browser
```

Type an idea, press **Build APK**, watch the log, then **Download & install**.
Install the result with `adb install <file>.apk`, or just open it.

### On Android (your phone as the GUI)

Run the server so your phone can reach it over the same Wi-Fi:

```sh
./forge --lan        # binds 0.0.0.0 and prints a http://<your-ip>:8787 URL
```

Open that URL in your phone's browser (add it to the home screen for an
app-like shortcut). Type the idea, build, and tap the download — Android will
offer to install the APK (allow "install from this source" once). Because the
server serves the APK as `application/vnd.android.package-archive`, the phone
treats it as an installable package.

> **Note:** `--lan` exposes a build endpoint on your local network. Use it on a
> network you trust. The default (no `--lan`) binds to localhost only.

### Headless / scripting

```sh
./forge build "a tip calculator that splits the bill"
# prints the path to the built APK
```

## Configuration

| Variable | Default | Meaning |
|---|---|---|
| `ANTHROPIC_API_KEY` | — | Claude key; absent → offline templates |
| `FORGE_MODEL` | `claude-opus-5-5` | model used for generation |
| `FORGE_OUT` | `/tmp/forge-apks` | where finished APKs are copied |
| `FORGE_WORK` | `/tmp/forge-builds` | scratch build directory |
| `ANDROID_HOME` | — | Android SDK location |

Flags: `./forge serve --host --port --lan --no-open --offline`.

## How it works

| Piece | File | Job |
|---|---|---|
| Generator | `forgeapp/generator.py` | idea → `AppSpec` (name, permissions, web files) via Claude, with an offline keyword fallback |
| Builder | `forgeapp/builder.py` | inject the web files + label + permissions into the template, run Gradle, find the APK |
| Template | `apk-template/` | a fixed, minimal WebView Android project (Java, no AndroidX), pinned to AGP 8.7.3 / compileSdk 35 |
| Server + GUI | `forgeapp/server.py`, `forgeapp/web/index.html` | the responsive web UI and JSON API; one page for desktop and phone |
| CLI | `forgeapp/cli.py` | `serve` / `build` / `doctor` |

Only the web assets, the app label, the `applicationId` and the declared
permissions vary per app — the Gradle scaffolding is constant, which is what
makes the build reliable. The template reuses Myarch's own Gradle wrapper
(8.11.1) at build time, so versions can't drift and the wrapper jar isn't
duplicated.

Permissions follow the idea: a location app gets `ACCESS_FINE_LOCATION` and the
runtime prompt; a timer or note app asks for nothing beyond network.

## Run the tests

```sh
cd forge
python3 -m unittest discover -s tests
```

The tests cover generation and project assembly end-to-end without needing an
Android SDK (they stop short of invoking Gradle).

## Where this could go next

- A real transit/weather data source wired into the location template.
- Native templates alongside WebView for ideas that need device APIs.
- Building inside the Myarch Arch container so a phone can build on-device.
- A "builder menu" entry in Myarch that calls Forge directly.
