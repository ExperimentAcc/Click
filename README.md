# Click

A fast, ultra-responsive, iPhone-inspired Android camera app written in **Kotlin** with
**traditional XML layouts** (no Jetpack Compose) and **Jetpack CameraX**
(`camera-core`, `camera-camera2`, `camera-lifecycle`, `camera-video`, `camera-view`).

- **App name:** Click
- **Package:** `zx.offical.click`
- **No Material icon packs** — every icon is a crisp, hand-made `<vector>` drawable.

## Features

| Area | Implementation |
|---|---|
| **Minimalist dark viewfinder** | Pitch-black `#000000` bars, `#FFD60A` accents, haptic feedback (`HapticFeedbackConstants`) on shutter taps and mode switches, instant black-out shutter flash with zero capture lag (`CAPTURE_MODE_MINIMIZE_LATENCY`). |
| **Bottom gesture & mode scroller** | Horizontal swipe/tap PHOTO–VIDEO selector with a sliding indicator, classic iOS circular shutter (white ring for photo, red circle that morphs into a rounded square while recording), circular last-media thumbnail, smooth-rotating camera-flip button. |
| **Top quick-control pill bar** | Flash toggle, Aspect Ratio toggle (4:3 / 16:9 / 1:1 via ViewPort cropping), Pro/ISO drawer toggle, dynamic Resolution/FPS badge in video mode. |
| **Photo mode** | Rear/front switching, flash OFF/ON/AUTO (bright white screen-flash overlay for front selfies), aspect ratios 4:3 / 16:9 / 1:1. |
| **Tap-to-focus + exposure slider** | Yellow iOS-style focus square with a vertical sun-icon exposure compensation slider (`CameraControl.setExposureCompensationIndex()`), fades out smoothly. |
| **Video mode** | Hardware-probed resolution/FPS badge — resolutions via `QualitySelector.getSupportedQualities()`, frame rates via `CameraInfo.getSupportedFrameRateRanges()` — e.g. `FHD • 30`, one-tap switching via `Recorder` (`QualitySelector` + `VideoCapture.Builder.setTargetFrameRate(Range)`). |
| **Live zoom pill** | 1x / 2x / 4x pills plus smooth pinch-to-zoom (`CameraControl.setZoomRatio()`) working in both photo and active video recording. |
| **Manual ISO** | Pro drawer with a slider using Camera2Interop (`Camera2CameraControl` + `CaptureRequest.SENSOR_SENSITIVITY`, AE off), ISO 100–3200 or reset to AUTO. |
| **Instant album sync** | Photos/videos saved directly to `MediaStore` (`Images` / `Video`, DCIM/Camera) — appear instantly in the Album and load into the thumbnail. |

## Performance & architecture constraints

- **APK size:** `isMinifyEnabled = true` + `isShrinkResources = true` in the release
  build type; only lightweight AndroidX + CameraX dependencies, no heavy third-party
  libraries → release APK measures **~1.1 MB**, comfortably inside (under) the 3–5 MB
  target.
- **Zero background processes:** `AndroidManifest.xml` contains **no `<service>` and no
  `<receiver>`** — and the merged manifest stays clean too: library-injected
  background components (CameraX's disabled `MetadataHolderService`, the
  `androidx.startup` provider and `profileinstaller` receiver pulled in transitively
  by `androidx.core`) are stripped at merge time with `tools:node="remove"`. The camera
  is bound strictly to the Activity lifecycle (`bindToLifecycle`), so the sensor and
  buffers release immediately on minimize/close.
- **Default camera:** `IMAGE_CAPTURE` and `VIDEO_CAPTURE` intent filters make Click a
  standard, user-installable default camera app (no root). Set it via
  *Settings → Apps → Default apps → Camera app → Click*.

## Build

Requirements: **JDK 17**, Android Studio Koala+ (or Gradle 8.7).

```bash
# from the project root (this zip's top folder)
./gradlew assembleDebug        # debug APK
./gradlew assembleRelease      # minified + resource-shrunk release APK
```

Or simply **File → Open** the project root in Android Studio and Run / Build.

> **Signing:** the project ships a demo release keystore
> (`app/keystore/click-release.jks`, alias `click`, password `click123`), wired in
> via a `signingConfig` in `app/build.gradle.kts` — so `assembleRelease` produces a
> **signed, directly installable** `app-release.apk` (R8-minified, ~1.1 MB).
>
> For a real release, generate your own keystore and replace the credentials in
> `signingConfigs.create("release")` — never ship production keys or reuse these
> demo credentials:
> `keytool -genkeypair -keystore my-release.jks -alias myapp -keyalg RSA -keysize 2048 -validity 10000`

## CI — GitHub Actions

`.github/workflows/build-apk.yml` builds the release APK on every push/PR to
`main` (plus manual `workflow_dispatch` runs) and uploads it as a workflow
artifact named **`click-release-apk`** (14-day retention).

- JDK 17 (Temurin) with Gradle cache
- Android SDK via `android-actions/setup-android`
- `./gradlew :app:assembleRelease` (R8 minify + resource shrink on)
- Artifact: `app/build/outputs/apk/release/*.apk`

Find the artifact under the run's **Artifacts** tab after the workflow completes.

## Project structure

```
app/src/main/java/zx/offical/click/
├── MainActivity.kt          # CameraX setup, capture, flash, zoom, ISO, media saving
├── ui/
│   ├── FocusOverlay.kt      # Tap-to-focus square + vertical exposure slider + pinch
│   ├── ModeScroller.kt      # PHOTO/VIDEO swipe/tap segmented control
│   └── ShutterButton.kt     # Morphing iOS shutter button
└── util/
    ├── Ui.kt                # dp/haptic/timestamp helpers
    └── ThumbnailLoader.kt   # InSampleSize-based MediaStore thumbnail decode
app/src/main/res/
├── layout/                  # activity_main.xml + custom view layouts
├── drawable/                # All vector icons + shape drawables (no icon packs)
├── values/                  # colors, strings, themes, dimens
└── mipmap-anydpi-v26/       # Adaptive vector launcher icon
```

## Technical notes

- **CameraX version:** 1.3.4. Manual ISO is implemented through the
  `Camera2Interop` API (`Camera2CameraControl.from(cameraControl)` +
  `CaptureRequest.SENSOR_SENSITIVITY`), which ships inside `camera-camera2` —
  no extra dependencies are needed.
- **Front-camera flash:** CameraX 1.3.x `ImageCapture` has no `FLASH_MODE_SCREEN`,
  so the bright white screen-flash for selfies is produced by an animated white
  overlay fired at capture time; rear-camera ON uses the hardware flash
  (`FLASH_MODE_ON`), and video ON uses the torch.
- **1:1 aspect ratio** uses true ViewPort cropping: the viewfinder becomes a
  square and `PreviewView`'s computed `ViewPort` (driven by the view's aspect +
  `FILL_CENTER`) is applied by CameraX as the crop for every bound use case —
  the photo output itself is square, not just the preview.
- **Recording** uses the 1.3.x event-based flow: `Recorder.prepareRecording()` →
  `PendingRecording.start(executor, Consumer<VideoRecordEvent>)` →
  `Recording.stop()`, saving to MediaStore via `MediaStoreOutputOptions`.
- **Front preview mirroring** is applied on the `PreviewView` (`scaleX = -1`)
  because `Preview.Builder.setMirrorMode()` is unsupported on 1.3.x; recorded
  front video is mirrored through `VideoCapture.Builder.setMirrorMode()`.
- If a device exposes no frame-rate ranges, VideoCapture gracefully falls back
  to FHD/30.
