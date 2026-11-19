# P-Meeting

Local-first meeting recorder for Android.

Record meetings as audio or video with the device microphone/camera, import
existing audio/video files, and keep everything in one library. Recordings and
transcripts stay on the device. The only optional outbound network request is
the Whisper model download in *Settings*; there is no analytics, advertising,
or telemetry code anywhere in the project.

## Features

### Recording library (Phase 1)

- **Home** — Material 3 screen with search, the recording library (title,
  date, duration, media type, provenance), a clear empty state, and actions
  for *Record audio*, *Record video* and *Import recording*.
- **Audio recording** — AAC `.m4a` via `MediaRecorder`; start, pause, resume
  and stop; live duration display; runtime microphone permission flow;
  audio-focus handling so phone calls and other audio pause the take; files
  are saved in the app's private external storage and a title is requested
  before the recording is added to the library. A recording keeps running
  when the screen is locked or the app is in the background: a microphone
  foreground service and a partial wake lock keep the session valid, so long
  meetings are not cut off by the display timeout.
- **Video recording** — CameraX with front/rear switching, audio included
  when the microphone permission is granted, recording pause/resume, rotation
  handled without interrupting the take, and the screen kept awake for the
  whole take so the camera is not released by the lifecycle. Stopping and
  leaving keeps the take with a default title (rename later).
- **Import** — Storage Access Framework picker for audio/video files (MP3,
  WAV, M4A, AAC, MP4, 3GP, WebM where a decoder exists). The original file is
  preserved: by default only a read-only link is stored (with a persisted
  permission); optional copy into app storage. Broken links surface a
  playback error instead of crashing.
- **Library** — open playback (audio and video), rename, and delete with an
  explicit confirmation (linked files are never touched by delete). Long-press
  a meeting to enter selection mode: archive, restore, select all, clear,
  invert, or bulk delete. Archived meetings live behind *Show archived* and
  keep all data. Every row also shows a persisted transcription-status icon
  (queued, processing, waiting for model, completed, failed) immediately left
  of the overflow menu.
- **Privacy** — recording starts immediately; there is no blocking consent
  dialog. Recording people without consent is illegal in many places, so let
  everyone know and obtain the consent they require — the app never claims
  otherwise.

### On-device meeting intelligence (Phase 2)

- **Transcription** — whisper.cpp (vendored under `app/src/main/cpp`) runs
  fully on the device through a JNI bridge. Audio is decoded to 16 kHz mono
  with `MediaCodec` (stereo is down-mixed and resampled), then transcribed.
  With the multilingual model the spoken language is auto-detected (no
  hard-coded language), and timestamped segments are stored in Room and shown
  on the transcript screen.
- **Transcript editing** — search, tap a segment to seek playback to it, edit
  or delete segments; edits are persisted.
- **Minutes** — rule-based extraction of decisions and action items from the
  completed transcript, stored as editable markdown-style minutes.
- **Action items** — extracted items can be added, completed/uncompleted and
  deleted; regenerating minutes does not duplicate existing items.
- **Email draft** — builds a subject/body from the recording, transcript,
  minutes and action items and hands it to any installed email app via the
  system share sheet.
- **Model management** — *Settings* downloads, validates, retries and deletes
  the default multilingual `base` whisper.cpp model. Models are stored in
  app-private storage and are not bundled in the APK. Model selection is not
  exposed in Settings; the app uses the single default model.

### Playback and automatic transcription (Phase 4)

- **Invert selection** — in the library selection toolbar, *Invert* swaps the
  selection state of every meeting currently visible (search query and active/
  archived view applied). Hidden and archived meetings are never selected, and
  the selected count in the toolbar updates immediately.
- **Video full-screen** — the detail screen keeps the platform `MediaPlayer`
  and its position. Full-screen fits the video by its real aspect ratio,
  switches to sensor landscape, hides the app bars and system bars, and shows
  overlay controls (play/pause, ±10 s, progress, current/total time) that
  auto-hide after three seconds while playing and reappear on tap. A visible
  exit button (and system Back) restores the previous layout, orientation and
  system bars without restarting playback. Device volume keys control the
  media stream as usual.
- **Automatic background transcription** — after an audio/video recording is
  stopped, finalized and saved, and after an import is saved, transcription is
  queued automatically. Manual *Transcribe*, *Retry* and *Re-transcribe* remain
  available and use the same unique work per recording, so no duplicate job can
  start. Jobs run as a foreground WorkManager worker with a notification and
  survive navigation and app restarts; queued/processing/waiting states are
  persisted in Room and reflected by the library badge. If no Whisper model is
  installed the job is stored as *waiting for model* with an explanation and
  resumes when a validated model becomes available — no mock transcript is ever
  produced.
- **Data safety** — transcript text and segments, and existing minutes, are
  only replaced by a successful run. Failed retries, cancellations and
  waiting-for-model states keep the previous successful content. Transcript
  and segment writes are transactional.

### Visual design

- **App wallpapers** — two 1440 × 2560 portraits share one visual language
  (flowing audio ribbons, a sparse collaboration mesh, implied microphone
  capsules, micro-texture) while keeping the top and bottom thirds dark or
  quiet so app bars and navigation always read:
  - night artwork — deep navy, teal, cyan and violet, used by the dark theme;
  - day artwork — soft blue-white with the same geometry, used by the light
    theme.
  Both ship as WebP in `res/drawable-nodpi` (55 KB / 48 KB) and are drawn by
  `ui/theme/AppBackground.kt` behind the whole app, with a gentle vertical
  scrim for contrast. Screens use a transparent `Scaffold`; app bars, cards
  and dialogs keep their opaque surfaces, so lists and text remain fully
  legible over the artwork. The light/dark choice follows the theme setting in
  *Settings*.
- **Reproducible artwork** — `design/generate_background.py` regenerates both
  variants deterministically (fixed seed, Pillow), and
  `design/prepare_supplied.py` prepares the source artwork. Exported PNG/JPG/
  WebP files live in `design/` for previews and store use; only the WebP
  resources are bundled in the APK.

## Tech

- Kotlin, Jetpack Compose, Material 3
- Room (metadata only; media lives outside the DB)
- CameraX (`camera-video`), platform `MediaRecorder` / `MediaPlayer`
- Storage Access Framework for imports
- WorkManager (transcription runs as a unique foreground job per recording)
- whisper.cpp + ggml via CMake/NDK (arm64-v8a only)
- ViewModel + coroutines/Flow throughout
- minSdk 26, target/compile SDK 36, AGP 8.13.2, Kotlin 2.2.10, Gradle 8.14.3,
  NDK 28.2.13676358

### Package layout

```
com.prakash.pmeeting
├─ data          Room entities/DAO + repository (recordings, transcripts,
│                minutes, action items)
├─ recording     AudioRecorder (MediaRecorder), VideoRecorder (CameraX)
├─ playback      MediaPlayback (platform MediaPlayer)
├─ transcription WhisperEngine (JNI), AudioDecoder, LinearResampler,
│                ModelManager, TranscriptionService
├─ minutes       rule-based minutes generation
├─ actionitem    action-item service
├─ permissions   runtime permission helpers
├─ ui            Compose screens (home / audio / video / detail / transcript /
│                minutes / action items / email draft / settings), theme
│                (including the app wallpaper), shared dialogs/components,
│                navigation
└─ util          formatting helpers
```

## Build

1. Open the folder in Android Studio (JDK 17/21, NDK 28.2.13676358 installed).
2. Let Gradle sync, then *Run* the `app` configuration.

Command line:

```bash
gradlew.bat :app:assembleDebug          # debug APK (builds the native engine)
gradlew.bat :app:testDebugUnitTest      # JVM unit tests
gradlew.bat :app:lintDebug              # Android Lint
```

The debug APK lands at `app/build/outputs/apk/debug/app-debug.apk`.

If you have a JDK installed elsewhere, add to `gradle.properties`:

```
org.gradle.java.home=C:/path/to/jdk
```

The native whisper engine is built only for `arm64-v8a` (all current
64-bit ARM phones). On other ABIs the library is absent and transcription
fails with a clear error instead of crashing.

## Using transcription

1. Open *Settings* and download a model (requires internet for this step only).
   If you skip this, recordings still queue transcription and show a *waiting
   for model* status until a model is downloaded and validated.
2. Record or import a meeting. Transcription is queued automatically once the
   file is finalized and saved; no need to stay on the detail screen.
3. Open the recording to watch progress, or tap the row status icon. When the
   job completes, generate *Minutes*, manage *Action items*, or create an
   *Email draft*. Manual *Transcribe* / *Retry* / *Re-transcribe* remain
   available.

Transcription time and memory scale with recording length; very long meetings
may take several minutes. Jobs run as a foreground WorkManager job, so leaving
the detail screen, navigating in the app, or restarting the app does not lose
the queue. Transcriptions are serialized — only one inference runs at a time,
so a second request waits for the first to finish.

## Database upgrades

The database is at schema version 5:

- `1 → 4` preserves recording metadata created by earlier Phase 1 builds.
- `4 → 5` adds the `recordings.archived` flag used by the archive feature.

Phase 4 added transcription statuses (`PROCESSING`, `WAITING_FOR_MODEL`) but
the status is stored as its enum name in the existing TEXT column, so no schema
change or migration was needed. Unknown status strings from older or corrupt
rows are read as `FAILED` instead of crashing.

There is no destructive fallback: an unknown schema fails loudly instead of
silently wiping the user's library.

## Runtime permissions

- `RECORD_AUDIO` — requested on the audio/video record screens.
- `CAMERA` — requested on the video screen.
- `POST_NOTIFICATIONS` (Android 13+) — optional; lets the recording and
  foreground transcription notifications be shown. Denial does not block
  recording or transcription.
- `WAKE_LOCK` — held only while a take runs, so the CPU is not suspended with
  the screen off.

All are declared in the manifest and requested at runtime only. No storage
permission is used (app-private storage + SAF). `INTERNET` is also declared,
solely for the optional model download; recording, playback and transcription
never use the network. `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_MICROPHONE`
and `FOREGROUND_SERVICE_CAMERA` keep an active take legitimate in the
background; `FOREGROUND_SERVICE_DATA_SYNC` is used by the transcription
worker.

## Known limitations

- Audio recording continues with the screen locked and while the app is
  backgrounded, as long as the process survives (Android may still reclaim it
  under extreme memory pressure; the take is then finalized and saved with a
  default title). Custom ROMs/OEM power savers can kill foreground services
  unless P-Meeting is excluded from battery optimisation.
- Video capture needs an active camera session: the screen is kept awake
  during a take. Pressing the power button releases the camera on most
  devices, which finalizes the take instead of continuing it.
- Codec coverage for imported files depends on the device's decoders.
- Import links can break if the user moves/deletes the source file; the app
  shows a playback error for those entries.
- Transcription is limited to the arm64-v8a native build and to the model the
  user downloads; no diarization (speaker labels) or punctuation recovery.
- Automatic transcription uses a foreground WorkManager worker. Android can
  still defer or stop background work (battery saver, OEM task killers, user
  force-stop); the persisted status stays correct and queued work is resumed on
  the next app start. Native inference cannot be interrupted mid-call, so a
  cancellation discards the result instead of aborting instantly.
- If the media container reports no video size, full-screen falls back to 16:9
  to avoid distorting the picture.
- Minutes/action-item extraction is a keyword heuristic, not a language model;
  edit the results before sharing.
- Decoded 16 kHz audio is held in memory during transcription, so extremely
  long recordings can hit the device's memory limits.
