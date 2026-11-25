# Decisions

Short architecture decision records. Add new ones at the bottom; don't
rewrite old ones. If a decision is reversed, add a new record that replaces it.

Status values: **Accepted** · **Proposed** · **Superseded by #N**

---

## D1 — Keep the C++ core, rewrite only the UI
**Status:** Accepted (2026-09-28)

The core is about 170k lines of flight-critical code (engine, computer,
drivers, parsers) with years of edge-case fixes and a large test suite.
Rewriting it would be a multi-year project and a flight-safety risk. The UI
layer (`ui/`, `Form/`, `Widget/`, `Dialogs/`, `InfoBoxes/`, `Menu/`) is the
only part being replaced.

## D2 — Kotlin + Jetpack Compose, Android first; keep a path to iOS
**Status:** Accepted (2026-09-28)

Android is the primary target. Compose is the native, best-supported Android
UI toolkit, and the existing Java I/O code (Bluetooth, BLE, USB serial, IOIO,
sensors) can be reused as-is.

We start as a **plain Android project**, not Kotlin Multiplatform. The rule:
`:core` and the ViewModels must not import `android.*`. That keeps a later move
to KMP + Compose Multiplatform (for iOS) a mechanical change instead of a
rewrite.

*Rejected: Flutter.* It is a good cross-platform option, but it would add Dart
and a second FFI layer, cannot reuse the Java I/O code, and Android is the
priority.

## D3 — Plain C ABI between UI and core
**Status:** Accepted (2026-09-28)

`xcsoar_core.h` is plain C. There is no JNI in the API itself, and JNI glue
is a thin separate layer. This lets Kotlin/Native (iOS), Swift, or a desktop
test harness use the same API, and it keeps C++ types from leaking into the UI.

## D4 — Two data paths: POD snapshot (hot) and JSON (cold)
**Status:** Accepted (2026-09-28)

Live flight state is a fixed-layout struct pushed several times a second and
copied in one step. Everything else (task, waypoints, settings, lists) is JSON.
We will reconsider protobuf or FlatBuffers only if JSON becomes a measurable
performance or maintenance problem.

## D5 — Seams by link-time substitution in `core/host`
**Status:** Accepted (2026-09-28)

The backend reaches the UI only through `Protection.cpp`,
`InputEvents::processGlideComputer`, `Message::` and `CommonInterface::`. We
provide headless implementations of those symbols in `core/host`, the same
technique the C++ tests use in `test/src/Fake*.cpp`. We change existing
upstream files only when a symbol can't be replaced cleanly, and keep those
changes small enough to upstream.

## D6 — Reuse XCSoar's OpenGL map renderer first
**Status:** Accepted (2026-09-28)

This gives feature parity (terrain, airspace, task, topography, RASP, FLARM)
immediately. The map draws into a `SurfaceView` and Compose draws controls on
top. A MapLibre renderer may be evaluated later behind the same `xcs_map_*` API.

## D7 — Build the native library with the existing Makefile system
**Status:** Accepted (2026-09-28)

The Makefile already cross-compiles every third-party dependency for all
Android ABIs. Gradle calls `make` and packages the `.so`. We will not port
the build to CMake.

## D8 — Separate app ID, same data format
**Status:** Accepted (2026-11-19)

An application ID of its own, so the app installs alongside upstream
XCSoar: `app.corentin.buteo` since D23 (was
`org.xcsoar.mobile`). It reads the same `XCSoarData` layout and `.prf`
profiles, so pilots can migrate by copying files.

## D9 — The core is the single source for units and formatting
**Status:** Accepted (2026-09-28)

Upstream's rule is to reuse `Units/` and `Formatter/` and never build a
parallel API. Formatting every value in C++ and sending strings over JNI
several times a second would be wasteful, so the core exports its unit
conversion tables and per-unit format rules once (JSON), and Kotlin applies
them. A golden test compares Kotlin output with the C++ `Formatter` for the
same values, so the two can't drift apart.

Done 2026-10-02: `xcs_units_get` exports the unit table and the pilot's
choice; `UnitFormatter` (Kotlin) mirrors `Formatter/Units.cpp`, including
printf's rounding of the exact binary value and "-0.0".
`core/test/FormatGolden.cpp` (built from six sources, no libraries) writes
`mobile/core/src/test/resources/format-golden.txt`; the Kotlin test
replays it, and `make core-check` fails when the C++ output changes.
The app keeps two presentation choices of its own: vario values carry a
sign except at zero, and speeds have no decimals.

## D10 — Upstream rules apply to the new code
**Status:** Accepted (2026-09-28)

`AGENTS.md` and `doc/architecture.rst` apply to everything in this project:
layer rules, the threading model (the core runs its own main-thread event
loop so `InMainThread()`, `UI::Notify` and `BlackboardListener` keep working),
TAP tests for C++ logic, the UI colour and touch guidelines, 79 columns and
SPDX headers in C++. For user-visible changes to upstream code: update
`NEWS.txt` and `doc/manual/en/`.

## D11 — Split ActionInterface.cpp instead of copying its setters
**Status:** Accepted (2026-09-30)

`ActionInterface.cpp` mixed UI-free setters (MacCready, ballast, bugs,
masses, radio, transponder, startup location, blackboard receive) with
code that pushes state to `MainWindow`. Backend code
(`ApplyExternalSettings`) and our C API need the setters. The
`MainWindow`/page functions (and `SetQNH`, which calls them) moved,
unchanged, to `src/ActionInterfaceUI.cpp`. Pure move, upstreamable. The
setters' remaining UI call (`InfoBoxManager::SetDirty`) is a no-op seam in
the core.

## D12 — The core main thread runs XCSoar's own event loop (VFB flavour)
**Status:** Accepted (2026-09-30)

`Replay` and device `Descriptor`s rely on `UI::Timer` and `UI::Notify`, and
several backend duties run on the UI thread (`UIReceiveBlackboard`). The core
therefore keeps a main thread running upstream's `UI::EventLoop`. On the host
this is the existing headless `VFB=y` flavour (poll backend, virtual
display), built into its own folder with `TARGET_DIR=MACOS_CORE`. On Android
no new flavour is needed: XCSoar's Android event queue is a plain C++ queue
(mutex, condition variable, timers) that Java merely feeds, so the core runs
it on its own thread. Upstream's `UI::EventLoop` needs a `TopWindow`, so the
core has its own small loop (`CoreEventQueue::Run()`: callbacks and timers
only) on both platforms.

## D13 — Reused Java I/O classes keep their `org.xcsoar` names
**Status:** Accepted (2026-09-30)

`libxcsoar_core.so` contains the JNI side of XCSoar's device and sensor I/O
(`NativePortListener`, `NativeSensorListener`, `NativeInputListener`,
`NativeDetectDeviceListener`, `StorageHotplugReceiver`: 27 entry points).
JNI binds by fully qualified class name, so the Java classes reused from
`android/src` (ports, Bluetooth/BLE/USB, IOIO, internal sensors) stay in
package `org.xcsoar` with unchanged names. The new app's own code lives in
its own package. The old UI's JNI (`NativeView`, `EventBridge`) is not in the
core library.

## D14 — Split ProcessTimer.cpp like ActionInterface.cpp
**Status:** Accepted (2026-09-30)

`ProcessTimer()` (twice per second on the UI thread) also does backend work:
it opens devices (`MainWindow::LateInitialise`, then `AutoReopen`), ticks
them, stops a replay on real movement, raises the GPS wait events, updates
the UTC offset, dumps ballast, degrades bugs and drives the network clients.
Following D11, those parts moved unchanged to `src/BackendProcessTimer.cpp`
(`BackendSettingsTimer()`, `BackendDeviceTimer()`), called by `ProcessTimer()`
at the same points; the core calls them from its own 500 ms timer.

## D15 — Hosts can run the core without devices
**Status:** Accepted (2026-09-30)

`xcs_config.flags = XCS_CONFIG_NO_DEVICES` leaves every device closed. Used by
replay, analysis and tests: on macOS the default device is the built-in GPS,
whose CoreLocation code needs the process main thread, which a test blocks
while waiting in `xcs_stop()`. For the iOS app (M8) this means the host's
main thread must never block on the core while devices are open.

## D16 — Flight screen follows the "modern cockpit" design canvas
**Status:** Superseded by D19

The flight screen follows the design canvas "XCSoar — modern cockpit"
(claude.ai artifact DNgXpmeJEMwtP2HTs1qzNy): pale map ground, white
floating cards, one dark vario instrument, Barlow / Barlow Condensed
(SIL OFL, bundled in `res/font`, licence in `assets/licenses`). Its
palette becomes the daylight theme and replaces the pure white/black
sunlight theme; every text colour keeps at least 4.5:1 contrast.

Where the design conflicts with ARCHITECTURE §7, §7 wins:
- controls are 56 dp, not the design's 44 px;
- MacCready steps by 0.1 m/s, like XCSoar, not 0.5;
- the 30 s average is drawn in updraft sky blue, not amber, because
  amber means caution;
- values the core does not publish yet are left out instead of shown
  with sample numbers, and the map area stays a placeholder until M3.

## D17 — The core draws the map on its main thread, into the app's surface
**Status:** Accepted (2026-10-02)

Refines D6. In XCSoar's OpenGL builds the UI thread paints the map
(`GlueMapWindow::OnPaintBuffer`), reading the blackboard directly. The
core does the same on its main thread, which also owns the EGL context:
the app passes the `Surface` of a `SurfaceView` (`xcs_map_attach`), the
core creates the EGL surface, paints a plain `MapWindow` (no parent
window, `Window::Create(nullptr, …)`) and swaps buffers after every
snapshot. No separate render thread, no locking beyond upstream's.

The core does its own projection (north up, aircraft position from the
app, zoom along XCSoar's scale list) instead of `GlueMapWindow`, whose
display modes, gestures and overlays belong to the old UI.

Seams (D5) for the map: images are decoded by the app's
`org.xcsoar.CoreGraphics` instead of the old `NativeView`
(`core/map/AndroidBitmap.cpp` replaces `ui/canvas/android/Bitmap.cpp` in
a core-only screen library); SkySight overlays, `GlueMapWindow` lookups
and download progress widgets are no-ops (`core/map/MapSeams.cpp`). One
upstream change: `TextUtil` uses the calling thread's `JNIEnv` instead of
the one cached at initialisation.

## D18 — The vario sound keeps upstream's OpenSL ES player
**Status:** Accepted (2026-10-02)

The plan said "AAudio sink". The core already runs XCSoar's own audio
chain on Android (`AudioVarioGlue`, `PCMMixer`, `AndroidPCMPlayer` over
OpenSL ES), set up like `Startup()` does; it plays on the media stream.
Keeping it means no new audio code and the same sound as XCSoar. OpenSL
ES is deprecated but still supported on current Android; replace it
upstream (for both apps) if it ever goes away.

The app only turns the sound on and off (`xcs_sound_set_option`, saved
under upstream's profile keys) and makes the volume keys control the
media stream.

## D19 — Map first: the "Design rework" canvas replaces the modern cockpit
**Status:** Accepted (2026-10-03)

The design brief ("XCSoar Mobile — Product and Design Brief", claude.ai
doc 76556a35-f3d5-43f3-acfb-8ee40fc07ff5) and the pilot's answers set the
direction: gliders only; the phone is mostly a backup next to a
certified vario; portrait first, landscape supported; same features as
XCSoar, but a modern look of its own.  The design canvas "XCSoar Mobile —
Design rework" (claude.ai artifact 5t6DJiHxmxJykMMzDUNvCA) draws it.

- The map gets most of the screen (about 70 % in portrait, was 40 %).
- The dark vario panel goes.  The vario is a bar on the map's left edge
  (number, bar from zero, 30 s average marker, netto); final glide is a
  bar on the right edge.  A backup instrument does not need half the
  screen.
- Sink is neutral grey, never orange: orange only ever means caution
  (arrival below glide, airspace ahead).  Lift stays green.
- No quick-action bar (ARCHITECTURE §7): it costs room for no gain.
  MacCready stays one tap away; "Set MC" appears beside it in lift.
- The Cruise / Circling switch leaves the flight screen: the tiles
  follow the flight mode, and the menu has Auto / Cruise / Circling.
- The menu is a sheet: four large in-flight buttons (Go to, Task, Flight
  setup, Analysis), then a list for the ground (Aircraft & crew,
  Flights, Data files, Settings, Replay).  Units, Map and Pilot &
  WeGlide move to a Settings page, which lists the planned settings as
  "Soon".
- Go to: only each row's "Go" button acts, so a bumpy tap on the list
  does not change the target.
- Map buttons sit in one row along the bottom of the map; the north mark
  is black, not red (red means warning).

## D20 — The core's own NetComponents
**Status:** Accepted (2026-10-03)

Live tracking, the thermal info map and NOTAMs live in upstream's
`NetComponents`, which the backend timer and the merge thread already
feed.  Its constructor also creates the RASP, xctherm and EDL download
glue, which reach dialogs and the main window.  The core defines the
`NetComponents` constructor, destructor and `BeginShutdown()` itself
(`core/host/CoreNetComponents.cpp`, a link-time seam like D5) and creates
only tracking, TIM and NOTAMs; the never-created members' destructors are
empty seams there too.  If one of those downloads is wanted later, its
seam goes and the real object is linked (the linker reports duplicates).

In the app, blocking network calls (WeGlide, weather) take their own
lock instead of the one every command takes, so a slow download never
holds up a MacCready change; stopping the core waits for both.


## D21 — One menu, uniform tiles
**Status:** Accepted (2026-10-04)

Amends D19 after flying it.

- Every tile has the same height: the InfoBox comment (e.g. the altitude
  in feet) shares the caption's line instead of adding a third line.
  A comment with a number is left out when it does not fit, never cut
  ("178…" for 1784 ft reads as another value); text ends with "…".
- MacCready is a tile spanning two columns, and the menu is the last
  tile beside it: one 3-column grid, no row of odd sizes.  (Replaced
  by D22.)
- "Set MC" floats over the bottom left of the map in lift, within reach
  of the thumb, since the grid has no free cell for it.
- The vario sound and map orientation buttons leave the map; the menu
  has a "Quick settings" group with Tiles, Map up (North / Track /
  Target) and a vario sound switch.  A turned map shows a passive north
  mark beside the zoom buttons.
- Quick settings also has the theme: System (the phone's, the default
  and first), White (sunlight) or Dark (night).  It is the app's own choice, kept in its
  preferences, not in XCSoar's profile; the status bar icons follow it.
- The Settings page goes: its entries are a section of the menu, each
  page listed once (Airspace and Data files were in both).  "Polar and
  masses" goes too: Aircraft & crew edits the plane.  The "Soon" rows
  go; they return when they are built.

## D22 — MacCready is a tile, Menu a map button
**Status:** Accepted (2026-11-19)

Amends D21: the pilot found the Menu tile took valuable room.  The row
of MacCready and Menu (as high as a tile, about 75 dp of a 720 dp
phone) goes, and the map gets it.

- MacCready is one of the six tiles: XCSoar's own MacCready InfoBox,
  third in both default layouts (it replaces Vopt in cruise, whose
  speed to fly is now its comment, and the time in the thermal when
  circling).  Manual shows as plain "MC" (green value), automatic as
  upstream's "MC AUTO" (blue), so the speed to fly fits.  A tap opens
  the − / + stepper over the bottom of the map, under the thumb; it
  closes 5 s after the last step.  The tile can be moved or replaced
  like any other.
- A layout saved before gets MacCready once, in its third tile, unless
  it has it (profile key "MobileTilesMacCready"; layouts saved since
  are the pilot's choice).
- Menu is a filled button left of the target card, as high as it,
  always there (in landscape beside the card in the side panel), where
  the hand that is not on the stick reaches.

## D23 — The app is Buteo
**Status:** Accepted (2026-11-19)

The XCSoar project does not make this app, so it does not carry
XCSoar's name or the project's `org.xcsoar` namespace.  Published from
the pilot's own account, "XCSoar Mobile" would read as the project's
app: Play's impersonation rule, and pilots taking its bugs to XCSoar
(and XCSoar's to it).  The app is Buteo, the buzzard genus: the bird
that thermals over every airfield.  No aviation app of that name was
found (2026-11-19).  It says what it is built on: XCSoar's flight
computer, under the GPL.

- Application ID `app.corentin.buteo` (the L5 app's: `.l5`), the
  author's choice.  A store keeps it for good after the first upload.
- Unchanged, because they are XCSoar's, not the app's identity: the
  Kotlin package `org.xcsoar.mobile` and the reused Java classes in
  `org.xcsoar` (D13); the `XCSoarData` folder and its formats (D8);
  the IGC files' logger identity (A record `XCS`, FR type): contest
  servers validate them with XCSoar's `vali-xcs`, chosen by that code;
  log tags.
- The app's text says Buteo where it means the app, and XCSoar where it
  means XCSoar's servers, repository and file formats.
- Not yet: the HTTP user agent still says `XCSoar/<version>`
  (`src/lib/curl/Setup.cxx`).  The core reuses the main program's
  objects (`build/core.mk`), so `make PRODUCT_NAME=Buteo` for the core
  would leak into XCSoar's own build in the same output directory; it
  needs a setting at run time.
- The launcher icon (2026-11-20, the pilot's pick of three drafts on
  the canvas https://claude.ai/artifact/2ZyJzfyFwS5KLieMtdbH9z):
  a buzzard under a flat-based cumulus, ink and white on the
  theme's updraft blue.  An adaptive icon (`mipmap-anydpi`) with a
  monochrome layer for themed icons; `ic_launcher-playstore.png` is
  the store's 512 px icon.  The status bar's notification icon is
  its buzzard alone.
- Not a hard fork: the name changes, the engine stays upstream's and
  the branch keeps merging it.
- The repository is `CorentinGrard/buteo` (was `CorentinGrard/XCSoar`;
  GitHub redirects the old URLs), its default branch `mobile`.  Its
  page shows `.github/README.md`, which GitHub prefers to the root
  `README.md`: upstream's stays as it is, so merges leave it alone.  The
  issue templates are Buteo's: an upstream merge that changes
  `.github/ISSUE_TEMPLATE/` keeps ours.

## D24 — Releases on GitHub, signed with Buteo's own key
**Status:** Accepted (2026-11-20)

- A tag `v<major>.<minor>.<patch>` runs
  `.github/workflows/mobile-release.yml`: versionName from the tag,
  versionCode major × 10000 + minor × 100 + patch; arm64-v8a only.
  The APK goes on a GitHub release whose notes are
  `mobile/docs/releases/<tag>.md` (the workflow stops without them);
  the Play bundle (`.aab`) is a workflow artifact for 90 days.
- The release key: `~/.android/buteo-release.jks` on the author's
  machine (PKCS12, RSA 4096, `CN=Buteo`, alias `buteo`, certificate
  SHA-256 `b7:9d:7e:87:3a:9c:85:54:2f:86:b7:4f:b9:e1:d7:58:d2:17:e4:80:34:95:24:46:5d:36:b1:c3:4a:7b:15:3e`),
  its passwords beside it in `buteo-release.properties`, and in the
  repository's secrets for the workflow.  Without it, no update can
  follow an installed release: keep a copy off this machine.
- Builds without the key's Gradle properties (`buteoKeystore`, …:
  local builds, CI on branches, L5) are signed with the debug key; the
  workflow refuses to publish one.  Android does not update an app
  across keys, so a debug build cannot replace a release on a phone.
- Google Play: open.  Play App Signing either keeps this key (uploaded
  once: GitHub and Play builds then update each other) or makes its own
  (they then cannot replace each other).

## D25 — The menu among the map buttons, a shorter menu
**Status:** Accepted (2026-11-21)

Amends D21 and D22 after the pilot flew the app on 2026-11-21: the
Menu button beside the target card was "lost space" in flight, and
the settings were too many.

- The flight screen is full screen (the phone's bars come back with a
  swipe from an edge; mobile/docs/PLAN.md, "In flight").
- Menu is the first of the map buttons, at the bottom left, where the
  map is free under the vario bar: the target card gets the whole
  width (in landscape too), and the button is under the thumb, in the
  same place on the ground and in flight.  "Set MC" sits beside it in
  lift, above it when the row has no room.
- The "Airspace alerts off" chip goes: warnings off is the pilot's own
  setting, not news; the menu's Airspace row says "Off".
- Two to four rows of three tiles: Quick settings has Rows (2 / 3 / 4,
  two by default), kept on the phone like the theme.  The core keeps
  twelve tiles per layout (profile keys as before; a layout saved with
  six gets the design's rows 3 and 4): final distance, final arrival
  and task speed in cruise, 30 s average, time in the thermal and
  final arrival when circling, then flight time, local time and
  battery in both, which the full-screen flight screen no longer shows
  in the status bar.
- A shorter menu: 11 rows instead of 17 under the in-flight buttons.
  Pages that belong together open from one another, as the plane
  editor from Aircraft & crew: the pilot and WeGlide from Aircraft &
  crew, the replay from Flights (its "Stop replay" row goes: the
  replay bar has Stop), NOTAMs from Airspace, units from the map's page
  ("Map & units"), the wind from Flight setup (the wind chip and wind
  tiles still open it directly).  The vario sound and the voice
  callouts are one Sound page.  The Airspace row says whether warnings
  are on.

## D26 — Google Play: drafts on the internal track, Buteo's own key
**Status:** Accepted (2026-11-21); the key is chosen in the Play Console

Amends D24's "Google Play: open".  The steps: `mobile/docs/PLAY.md`.

- A tag's bundle also goes to Play's internal track as a **draft**
  (`r0adkll/upload-google-play`, when the secret
  `PLAY_SERVICE_ACCOUNT_JSON` is set; skipped without it).  Rolling out
  and promoting stay in the Play Console: a person looks at each
  release of a flight instrument before pilots get it, and the service
  account may only release to testing tracks.  Play's "What's new":
  `mobile/docs/releases/<tag>.play.txt`, else a link to the GitHub
  release.
- Recommended app signing key: Buteo's own (uploaded with PEPK, and
  the upload key as well), so that Play, GitHub and Obtainium builds
  update one another.  With a Google-made key they would be two apps
  that only an uninstall, which deletes `XCSoarData`, can swap.  Play
  allows the change until a release reaches open testing or
  production.
- Only the core's ABIs (arm64-v8a) in the APK and the bundle:
  `androidx.graphics.path` made them claim 32-bit and x86 phones,
  where the core is missing.
- The privacy policy, required by Play for location, is
  `mobile/docs/PRIVACY.md`; it says what the optional features send
  where.

## D27 — A flight's page, read from its IGC file
**Status:** Accepted (2026-11-21)

The pilot asked to tap a recorded flight for what it was, to replay it
from there and to delete flights; Status, diagnostics, moves to the
bottom of the menu.

- A recorded flight's page is the summary after a landing (D25's
  Flights): its times, contest result over its path, climbs and highest
  point, from the IGC file (`xcs_analyse_flight_file()`,
  `core/host/CoreFlightFile.cpp`).  The core runs XCSoar's AnalyseFlight
  (the analysis SkyLines runs on uploaded flights): its replay and
  flight phase detector from `test/src`, built into the core; the
  contest and handicap of the settings.  It reads the file on the
  caller's thread, not the core's (seconds for a long flight), with the
  network calls' lock so the core cannot stop under it; the app keeps
  each analysis for the session.
- The page has Replay and Share at the bottom, then Upload to WeGlide
  and Delete (confirmed; the remembered upload goes too, as the logger
  may give a new file the name).  Replay and Delete only on the
  ground: in flight the logger writes the newest file.  The Flights list
  is rows that open it.
- The Replay page goes: its speed is the replay bar's, its demo flight
  went with it (`test/data/01lz1hq1.igc` stays in the l5 app, which
  its end-to-end test replays from Flights), and its "Open a file…"
  too: an IGC file copied into `XCSoarData/logs` is a flight like the
  others.  The `demo.igc` older versions copied there is deleted.

## D28 — Translations from XCSoar's catalogues and Buteo's own
**Status:** Accepted (2026-11-22)

The core already speaks the phone's language (tile names, status pages,
from XCSoar's built-in catalogues), the app's own words were English:
on a French phone, "Européen" sat under "Welcome".

- The English is Android's: `app/src/main/res/values/strings.xml`, read
  with `stringResource()`.  Android's resources, not gettext through the
  core: Compose reads them without JNI, the phone's per-app language
  applies, and Compose Multiplatform has the same format for iOS (D2).
- The translations are generated at build time
  (`mobile/tools/po2strings.py`, Gradle task `translatedStrings`) into
  `values-<lang>/strings.xml`, for XCSoar's 33 languages.  A text that
  XCSoar has (the same English and context, no placeholders) takes
  XCSoar's translation from `po/<lang>.po`, so existing translations
  carry over (ARCHITECTURE "Language"); Buteo's own texts are in
  `mobile/po/buteo.pot` and `mobile/po/<lang>.po`.  Kept apart from
  `po/`: upstream merges stay clean, and XCSoar's `make update-po` would
  drop msgids its sources do not have.
- Where the same English means something else, a `po:msgctxt` gives it
  a context of XCSoar's (`Button`, `Status`…): "Open" a tile's page is
  not XCSoar's "Open" ("Ouvert", an open airspace).
- A translation whose placeholders differ from the English is left out
  (the English shows): a wrong one throws when the text is formatted,
  in flight.  `TranslationsTest` formats every text in every language.
- French is complete, the pilot's language, and a test keeps it so.
  Other languages get XCSoar's translations and English for the rest
  until someone translates `mobile/po/<lang>.po`.
- Android's own language choice for the app (Settings → Apps → Buteo →
  Language, Android 13+) lists the complete languages only, English and
  French (`res/xml/locales_config.xml`): a mostly English "Deutsch"
  would mislead.  A phone in another language still gets what is
  translated.  The process goes on when the pilot changes it, so the
  core loads its catalogue again (`xcs_reload_language`).
- A message the logic makes (a failed save, "The task is full") is a
  `UiText` (`ui/UiText.kt`) in the state: the string resource and its
  arguments, resolved when the screen shows it, so it follows the
  phone's language, and the logic tests need no Android resources.  What
  the core or the network said stays as it came (`UiText.Plain`).  Code
  with no `Context` (the downloader) throws a `MessageException` that
  carries one.
- No plurals: Android's `<plurals>` are not in the pipeline, so a count
  is worded "Airspaces loaded: 812" and not "812 airspaces loaded".  A
  text with a literal percent is formatted with arguments
  (`%1$d %%`): aapt2 reads "% d" as a placeholder.
- The spoken callouts stay English: aviation English, a voice the phone
  may not have in the pilot's language, and a changed wording in flight
  is no small thing.  The Sound page quotes them as they are said.
- ICAO abbreviations and names stay as they are (`doc/i18n.rst`):
  "METAR, TAF, RASP", "ETE", "Cloud, SkyLines, LiveTrack24" are not in
  the resources.  Chips and tags are stored in sentence case ("No GPS",
  XCSoar's) and shown in capitals with the language's own rules.
- A French text is a third longer than the English, and a 320 dp phone
  has the room of a 4-inch one: the controls give way instead of
  cutting.  `Segmented` shrinks a label to 11 sp (a word is never
  broken) before it ellipsizes, a `Stepper` caption wraps to two lines
  and leaves the buttons their place, buttons that do not fit go to a
  second line (`FlowRow`).  The French screenshots are 390 dp wide and
  missed all of this: `fr_safety_320` and `fr_data_files_320` are the
  narrow ones, and a new page is worth a look in the `l5` emulator
  (320 dp) with `persist.sys.locale fr-FR`.

## D29 — The map in the design's colours, airspace as bands
**Status:** Accepted (2026-11-22)

The core draws XCSoar's map (D17) with XCSoar's looks: pure red and blue
airspace in thick ICAO bands, magenta landables, a violet task.  The
"Design rework" canvas (D19) draws airspace as a thin outline over a
light fill, red when inside; landables green when reachable and orange
when not; the task in magenta (the app's `task` colour).

- The core sets Buteo's look over XCSoar's after creating it
  (`core/map/CoreMap.cpp`, `ApplyButeoLook()`), in the app's colours
  (`ui/theme`).  Settings without a page in the app (landable style,
  airspace fill modes) are set in memory, not saved: the profile stays
  XCSoar's.  Airspace colours from the pilot's profile stay; XCSoar's
  pure primaries take the design's tones.
- Airspace keeps a band along the inside of its outline (XCSoar's
  padding, a third of its width, lighter) instead of the canvas's
  light fill: over France's stacked airspace the fills added up to a
  grey wash over the whole map.  An airspace with a warning is filled
  and outlined in orange, one the glider is inside of in red, as the
  warning banner, until acknowledged.
- Landables: XCSoar's "traffic lights" vector symbols, green reachable,
  orange otherwise (orange is caution: marginal and unreachable alike),
  with a thin ink outline.  The canvas rings them in white, but XCSoar
  draws the runway with the same pen, and ring and runway ran into one
  white blot.
- Two small upstream changes make this possible without changing
  XCSoar's own drawing: `AirspaceLook` has the fill alpha and optional
  warning colours (OpenGL), `WaypointLook` the landables' outline pen;
  their defaults are what XCSoar drew before.

## D30 — The task on the map: modes of the one map
**Status:** Accepted (2026-11-23)

Building a task and moving AAT targets want a map: upstream has a task
list and a separate target map window.  The app has one map, the core's
(D17), and it keeps running under the pages.

- The map has modes (`xcs_map_set_mode`): the flight, the edited task,
  or an area of the active task.  The task and target modes frame what
  they show in the part of the map the panel leaves free, north up, and
  stop following the glider; the flight mode follows it again.  The
  edited task is drawn from a task manager of its own (a copy, its last
  point a finish as on "Done"), so the active task keeps guiding.
- The flight screen keeps the map and the airspace warnings and swaps
  its tiles for a panel: the points (to size a zone or remove one) and
  "Done", or the area's target (upstream's distance and radial, the
  optimiser switch, the task time against the minimum).  A tap on the
  map, on lift-off, adds the waypoint under the finger
  (`xcs_map_items_at`) or puts the target there
  (`xcs_map_location_at`, inside the area only); holds and their "Go
  to" card are off meanwhile.
- A zone's size stays a stepper, not a drag on the map: it is precise
  with a glove in turbulence, and a drag would fight the pan.

## D31 — The data for a flight: checked before it, downloaded by itself
**Status:** Proposed (2026-11-25, on `map-lab` with the vector map)

The pilot's worry from the map review (2026-11-24): "before flight we
must be sure we have cached everything for the flight… it can be bad to
lose the map in flight".  Nothing in the air may depend on a network.

- A pre-flight page (Menu → Before and after the flight) checks each
  kind of data against the task, else 50 km around the glider (or the
  phone's last position): the terrain reaches 10 km around every leg and
  zone, the vector map's regions have no hole there, and how many
  airspaces and landables are near (none is a caution: a file for
  another region).  The core answers from its own data and spatial
  indexes (`xcs_data_coverage`), the same the glide computer uses; the
  vector map's coverage is its tiles' (`OfflineMap.firstGap`).
- On an unmetered network, on the ground, while the app runs (every 5
  minutes, and when the page opens), what can come without a choice
  downloads by itself: a newer file of the same name in XCSoar's
  repository (its `updated` after the phone's copy), and the vector map's
  regions the flight reaches.  A file that would cover what the pilot's
  does not (a larger map, another country's airspace) is offered on the
  page, not swapped in: that is the pilot's choice.  On a metered
  network the page offers "Download all".
- The vector map's regions: Protomaps' daily planet build (basemap
  schema 4, from OpenStreetMap), cut by region to zoom 11.  One map of
  the planet, so a tile on two regions' border is the same in both and
  the map has no seam; no build of our own (a planet build needs a
  machine of 64 GB for hours), and the map's style follows Protomaps'
  layers.  The regions are Geofabrik's smallest extracts (510 for the
  world; not those that join others, like "alps"), read by HTTP ranges
  (mobile/tools/map_regions.py): only their tiles, without buildings and
  the fields and meadows the map does not draw (85 % of the tiles is
  land use): Rhone-Alpes is 27 MB, France about 330 MB.
- Once a month, the workflow map-regions (GitHub Actions, one job a
  continent) cuts them and publishes them with their index, regions.json
  (each region's outlines, size, SHA-256, the day of its data), in the
  release "maps" of Buteo's repository: a pre-release, never "latest",
  so that Obtainium does not take it for the app.  The index's address
  is a build setting (`buteo.mapRegionsUrl`); the test app may reach a
  server on the computer through `adb reverse`, in clear text to
  127.0.0.1 only.
- No map from the network any more (OpenFreeMap's was another schema):
  without regions, the relief and the core's layers on a blank ground.
  The regions around the glider (or the phone) download too, 50 km
  around, beside the task's.
- Not done: running without the app open (WorkManager), deleting
  regions no flight needs, adding a second airspace file (the core takes
  one list per kind; with several files, an update loads at the next
  start).

## D32 — The task is the route; Back applies it, Go to pauses it
**Status:** Proposed (2026-11-25), the pilot to confirm the name

The pilot (2026-11-25): "rethink the way 'task' work … we could also
change the name as it is not super clear".  "Task" is the competition
word; most flights are a club pilot's out-and-return or triangle.

- In English the app says **Route** for its own texts (menu, page,
  editor, messages, pre-flight, analysis); French keeps XCSoar's
  "Circuit".  XCSoar's own names stay (InfoBoxes "Task speed", status
  pages): those are competition terms the core translates.  "Route"
  has a context of Buteo's (`po:msgctxt="Flight route"`): XCSoar's
  "Route" is its route planner.
- **No route yet:** the page opens on building one, not on "Edit".
- **Back applies** what was changed, as "Done" did: a half-done edit
  no longer waits unseen while the old route guides.  A route that is
  not valid keeps the pilot on the page with XCSoar's reason; "Cancel"
  drops the edit; nothing changed, nothing is applied (the route's
  progress stays).
- **Go to pauses the route.**  XCSoar keeps the task while it flies to
  a waypoint of "Go to", and the app showed the task as if flown.  The
  page now says "Route paused · Flying to X" with **Resume the route**
  (`XCS_TASK_RESUME`, upstream's `TaskManager::Resume()`; the task's
  JSON has the "mode" and the "goto" waypoint).
- **Finish at the start:** one tap appends the start again (the finish
  once applied) for the usual flight back home.
- Not done: the name on the flight screen's next-point card ("NEXT" also
  under Go to), naming saved routes by their points, a route list of
  recent flights.
