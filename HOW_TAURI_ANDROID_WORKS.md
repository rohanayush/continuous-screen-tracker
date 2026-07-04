# How Tauri Works on Android

A practical explanation of Tauri v2's Android model, written against this project
(`com.eyerest.app`, a Vue + Rust eye-rest reminder).

---

## 1. The big picture

A Tauri **desktop** app = a native window hosting a system WebView, with a Rust
backend. On **Android**, the same idea is mapped onto Android's building blocks:

| Tauri concept | Desktop | Android |
|---|---|---|
| Window | OS window | An **Activity** (`MainActivity`) |
| WebView | WebView2 / WKWebView / WebKitGTK | Android **System WebView** (Chromium) |
| Backend | Rust compiled to a native exe | Rust compiled to a **`.so` shared library**, loaded via JNI |
| Frontend | HTML/CSS/JS (Vue) bundled by Vite | Same bundle, served to the WebView |
| IPC bridge | Tauri IPC | Tauri IPC over the JS ↔ JNI boundary |

So on Android your app is **a normal Android app** (an APK with an Activity)
whose main screen happens to be a WebView showing your Vue UI, plus a Rust
native library doing backend work.

```
┌──────────────────────────── APK ─────────────────────────────┐
│  MainActivity (Kotlin, extends TauriActivity)                 │
│  ┌─────────────────────────────────────────────────────────┐ │
│  │  Android System WebView                                  │ │
│  │     └─ Vue app (dist/ bundled by Vite)  ── IPC ──┐       │ │
│  └──────────────────────────────────────────────────┼──────┘ │
│  libapp.so  (your Rust crate, src-tauri/src) ◄───────┘       │
└───────────────────────────────────────────────────────────────┘
```

---

## 2. What `tauri android init` generated

`src-tauri/gen/android/` is a **full Gradle Android Studio project** that Tauri
generates for you. Key parts in this repo:

```
src-tauri/
├─ src/                      # your Rust backend (lib.rs, main.rs)
├─ tauri.conf.json           # app config (identifier, window, bundle)
└─ gen/android/              # generated native Android project
   ├─ app/
   │  ├─ build.gradle.kts    # compileSdk=36, minSdk=24, deps
   │  └─ src/main/
   │     ├─ AndroidManifest.xml
   │     ├─ java/com/eyerest/app/
   │     │   └─ MainActivity.kt   # extends TauriActivity
   │     └─ res/                  # icons, themes, layouts
   └─ buildSrc/               # Gradle plugin that compiles the Rust → .so
```

- **`MainActivity : TauriActivity`** — `TauriActivity` (from the Tauri Android
  library) creates the WebView, loads your frontend, and wires up IPC. You can
  override `onCreate` and add your own native behavior (we do).
- **`buildSrc` "rust" Gradle plugin** — during a Gradle build it invokes Cargo to
  cross-compile `src-tauri` for each Android ABI and drops the resulting
  `libapp.so` into the APK's `jniLibs`.

---

## 3. How a build actually runs

`npm run tauri android build` (or `dev`) does, in order:

1. **`beforeBuildCommand`** (from `tauri.conf.json`) → `npm run build` →
   `vue-tsc` + `vite build` produces the static frontend in `dist/`.
2. **Cargo cross-compiles the Rust** crate for the Android target(s) using the
   **NDK's clang/linker** → `libapp.so` per ABI
   (`arm64-v8a`, `armeabi-v7a`, `x86`, `x86_64`).
   - ⚠️ Even for Android, Cargo still compiles **build scripts and proc-macros
     for the host** (Windows MSVC), which is why the MSVC `link.exe` must be
     available. See `PREPARATION.md`.
3. **Gradle assembles the APK/AAB** — packages the Vue `dist/`, the `.so` files,
   resources, and compiles the Kotlin (`MainActivity`, and in our case the
   service/receivers) into Dalvik bytecode.
4. Output lands under
   `src-tauri/gen/android/app/build/outputs/`.

Targets matter: building all four ABIs is slow; `--target aarch64` builds only
arm64 (most real phones) for faster iteration.

---

## 4. Frontend ↔ Rust IPC

Your Vue code calls Rust commands with `invoke()`:

```ts
import { invoke } from "@tauri-apps/api/core";
const result = await invoke("greet", { name: "world" });
```

On Android this crosses WebView JS → JNI → your Rust `#[tauri::command]`. The API
surface is identical to desktop, so the same Vue code runs on desktop and mobile.

`tauri.conf.json` → `app.security.capabilities` / `src-tauri/capabilities/`
control which commands and plugins the frontend is allowed to call.

---

## 5. The crucial limitation (why this app needed Kotlin)

The WebView + Vue + Rust runs **only while `MainActivity` is alive and in the
foreground**. When the user leaves the app or the screen locks, that WebView is
paused or destroyed — a JS `setTimeout` in Vue is **not** a reliable background
timer.

This app must:
- count screen-on time even when the user is in *other* apps,
- react to `SCREEN_ON` / `SCREEN_OFF` and `BOOT_COMPLETED`,
- draw a reminder **on top of other apps**.

None of that is expressible in the WebView layer. So the real logic lives in
**native Android components** added alongside the Tauri files:

| File | Role |
|---|---|
| `ScreenTimerService.kt` | Foreground **Service** — runs independently of the WebView; registers `SCREEN_ON/OFF` receivers; counts; shows the overlay |
| `BootReceiver.kt` | **BroadcastReceiver** for `BOOT_COMPLETED` — starts the service after reboot |
| `MainActivity.kt` | Starts the service + requests overlay/notification permissions on launch |
| `AndroidManifest.xml` | Declares permissions, the service (`specialUse` FGS), and the receiver |

**Rule of thumb:** UI and app logic → Vue/Rust. Anything that must run in the
background, react to system events, or draw over other apps → native Kotlin in
`gen/android` (or a proper Tauri mobile plugin).

---

## 6. ⚠️ `gen/android` and regeneration

`src-tauri/gen/android` is **generated**, but it is meant to be **committed and
edited** — Tauri treats it as yours once created.

- `tauri android build` / `dev` **preserve** your edits.
- `tauri android init` **regenerates** the project and can overwrite custom
  files (manifest, `MainActivity`). Re-run it only when necessary, and back up
  the custom Kotlin (`ScreenTimerService.kt`, `BootReceiver.kt`) and the manifest
  edits first.

For changes that must survive regeneration cleanly, the "proper" path is a
**Tauri mobile plugin** (a reusable module with its own Kotlin + Rust binding),
but inline editing of `gen/android` is the pragmatic approach for a single app.

---

## 7. Running it

```bash
# Live dev on a connected device/emulator (USB debugging on):
npm run tauri android dev

# Build a debug APK (arm64 only, fastest):
npm run tauri android build -- --debug --apk --target aarch64
# → src-tauri/gen/android/app/build/outputs/apk/...

# Install on a device:
adb install -r <path-to-apk>
```

All of these need the toolchain env from `PREPARATION.md`
(`JAVA_HOME`, `ANDROID_HOME`, `NDK_HOME`, and the MSVC environment for host
proc-macro compilation).

---

## 8. Summary

- A Tauri Android app **is** a real Android app: an Activity hosting a WebView +
  a Rust `.so`.
- Tauri generates and maintains the Gradle project under `src-tauri/gen/android`.
- The same Vue + Rust + `invoke()` code runs on desktop and mobile.
- The WebView only lives in the foreground, so true background/system behavior
  (services, receivers, overlays) must be **native Kotlin** — which is exactly
  what powers this eye-rest reminder.
