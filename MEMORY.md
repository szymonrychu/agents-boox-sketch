# agents-boox-sketch memory

Lessons that outlive a session. Newest first.

## 2026-11-04 Pen ink latency after #40 (boox-latency)

- While raw drawing is live (`StrokeInput.rawLive`: Onyx raw drawing open and enabled, no fallback)
  a committed pen stroke does not touch the board surface at all: `BoardSurfaceView.show` only adds
  the stroke's view-pixel bounds to a pending dirty rect. Why: #40 (`drawCommitted`) posted a full
  SurfaceView frame (`lockCanvas()` without a dirty rect, every stroke of the page) on every pen-up
  while the panel was in handwriting DRAWING state; folding that frame into the scribble layer
  stalled the next stroke's firmware ink, and quick writing queued the stalls ("unbearable" lag
  against stock BOOX apps). Stock apps leave committed ink in the overlay and sync their own view
  only when the pen is idle or before leaving scribble mode.
- The surface catches up through `CommittedInkSurface.syncCommitted` (dirty rect only,
  `lockCanvas(rect)`, clipped, freehand strokes outside skipped, raw drawing left on): when the pen
  goes idle (the SDK's `RawInputCallback.onPenUpRefresh`, re-armed on every pen-up and cancelled on
  a press; `setPenUpRefreshTimeMs(700)` after every open, default 500, so a pause between letters
  does not count; skipped while the pen is down again), and in `OnyxStrokeInput.disable`/`close`
  and before a re-open for new rects, i.e. before every hold, pause, refresh bracket (undo, redo,
  erase, clear, page switch, full refresh), shape tool and stop (leaving the board, surface gone,
  fallback). `show` also syncs before it shows any other change. So the surface holds every
  committed stroke before the overlay goes, and the #40 blink cannot return. The input finds the
  surface as its host view (`host as? CommittedInkSurface`), so nothing is registered or leaked.
- Send and PNG export read the notebook model, not the surface; the Send tap and its outcome
  dialog go through the toolbar hold and `pause`, which sync first anyway.
- Touch fallback and shape tools are unchanged (`rawLive` false: the refresher path).
  `StrokeInput.drawCommitted` and `BoardSurfaceView.committer` were removed (2026-11-04): the raw-active
  branch was dead, the committer always behaved like the refresher. `inkBounds` now bounds shapes too
  (outline incl. arrow head, plus stroke width), so a dirty append always covers them.
- `PenUpReplayTest`'s #40 assertion (a `draw` at pen-up while raw stays on) encoded the lag and was
  replaced; its undo/erase test now expects the dirty flush before the bracket, and the test
  settles the debounced load full refresh first (it landed inside the undo step on slow runs and
  made the test flaky at 85b124e).
- Not verified on the device: whether the panel shows the idle sync frame or keeps it frozen
  until the next legitimate refresh (either is invisible), and that `onPenUpRefresh` arrives at
  all on the Note Air 6C (`SFTouchRender`). If it never arrives, the surface still syncs before
  every raw-off; add a 700 ms timer from `onEndRawDrawing` only then (one mechanism).

## 2026-11-04 Pen limit spans the system bars on the full-screen board; relayout waits for overlay rects
- The deleted test `board_bounds_passed_to_stroke_input_do_not_overlap_system_bar_insets` guarded the old
  SplitLayout, which padded the board by the bar insets. The old code never excluded the bars explicitly; the
  limit was just the board's own bounds. The board is now full screen by design (2026-11-03), so the Onyx limit
  rect is the whole view and covers the bars. Bars are not excluded: the dock and panel keep clear of them via
  insets (`board_ignores_system_bar_insets` covers that) and the OS owns bar touches itself. The test no longer applies.
- The rotation relayout hold no longer settles after one fixed frame: it also waits for the dock (and the panel
  when composed) to have reported non-empty rects for their current state, like `dockSettled`.

## 2026-11-04 Relayout hold cannot deadlock on equal rects (fix of 7a60809)

- A relayout drops the stored dock and panel rects and waits for fresh reports, but the dock and panel
  only reported when their rects changed, so a 180 degree rotation (same rects) never released the hold.
  `ChromeHolds.relayout` now bumps `layoutEpoch`; `ToolbarDock` and `SidePanelHost` report again on
  every epoch change even with equal rects (`relayout_with_identical_rects_still_releases`).
- Defence in depth: if no fresh report comes within `ChromeHolds.RELAYOUT_REPORT_TIMEOUT_MS` = 600 ms,
  the hold is released with the rects measured before (`acceptStaleRects`) and a warning goes to the
  log (`Log.w`, tag BoardScreen; the app's only diagnostics sink besides the Onyx fallback reason,
  which would be misleading here). Why 600 ms: the relayout's full refresh runs 300 ms after the
  draw (`FULL_REFRESH_DELAY_MS`, same as `RAW_CALLBACK_TIMEOUT_MS`), a healthy report takes one or two
  frames, so 600 ms (= `AUTO_HIDE_AFTER_STROKE_MS`) never cuts a healthy wait short and a stuck hold
  ends before the next auto-hide.

## 2026-11-04 Board wave A rebased onto the documents wave (#38), pairing code (#39) and flicker fix (#40)

- Documents stays a panel tab (`PanelTabs`, `documents.DOCUMENTS_TAB_ORDER` = 20) inside the side panel that
  `AppShell` hosts over the board; it is not yet a top-level `TabRegistry` tab. Why: the Drawing tab must stay the
  only top-level tab in PR 1 (no tab bar, `MainActivityShellTest`), and moving Documents to the tab bar is wave C.
  `AppContainer` imports the panel order as `DOCUMENTS_PANEL_TAB_ORDER` because its companion now also declares the
  top-level `DOCUMENTS_TAB_ORDER` = 30 for wave C, which would otherwise shadow the import.
- An opened document (`AppContainer.openDocument`) still replaces the whole UI full screen through `DocumentHost`,
  exactly as on main where it replaced the split layout; the shell is not composed meanwhile. `MainActivity` hoists
  `rememberShellState()` above that switch, so closing the document returns to the board with the shell state it had
  (panel shown on the Documents tab, chrome). Why not inside the board slot: the document screen has its own top bar
  and close, and a future tab bar would overlay it; the full-screen switch keeps every behaviour of both sides.
- `BoardView` keeps both the documents `background` bitmap and the board-tap detector (`onBoardTap`); the
  tap detector only reads touch events and does not touch the raw-drawing path of #41 (no surface redraw while raw is live).

## 2026-11-04 Board overlays: ChromeHolds, side panel, tap to dismiss (board wave A, PR 1)

- Every overlay over the board (dock, side panel; popups next) changes state through
  `ChromeHolds`: the first change of a burst takes one `holdUi`, each overlay reports its state
  and rects, and the hold is released once, one frame after every awaited overlay reported the
  requested state with non-empty rects. Why: a tap that hides dock and panel together must not
  release after the first report and run raw drawing with the second overlay's stale rect.
  A panel key is awaited only while a panel host is composed (no host, no report, stuck hold).
- `ShellState.hide()` hides the panel too, so the dock's hide button, the auto-hide and a board
  tap all hide dock and panel in one burst (`OverlayControl.hideAll`).
- Pen taps dismiss through the stroke auto-hide, finger taps through `onBoardTap`. Why: a pen tap
  in raw mode is a stroke. The finger tap is detected in `BoardSurfaceView.dispatchTouchEvent`,
  before whichever touch listener the input installed (the shape drag and touch fallback inputs
  replace the view listener and may consume the event), so the plan's window-callback fallback
  was not needed for the listener swap. Whether the firmware still delivers finger events to the
  view while raw drawing is on is device-only (checklist item); if it does not, feed the detector
  from the `OnyxWithFallback` window callback mapped into view pixels.
- The panel and the dock swallow every pointer event (`awaitEachGesture { awaitPointerEvent() }`),
  so a tap between their buttons never falls through to the board and dismisses them.
- SplitLayout removed 2026-11-04 (T-36 Q3): the panel is an overlay hidden by default; the board
  is never resized, so its limit rect only changes on rotation. `SplitState`, `SplitStateStore`
  and `split.preferences_pb` are gone; the DataStore IO scope stays as `AppContainer.storeScope`
  for the token store. `MainActivity` hosts `AppShell` with `container.tabs` (Drawing tab,
  order 10; Tasks 20 and Documents 30 are reserved registration points).
- `MainActivityEraserTest.activity_shows_board_and_conversations_panel` now opens the panel via
  its indicator first: the test asserted the always-visible split panel, which no longer exists.
- Rotation is handled with `android:configChanges` (orientation, screen size, smallest screen
  size, screen layout, keyboard), not by recreating the activity. Why: a recreate closes and
  reopens the Onyx helper (first-frame gate plus GC flash, "Toolbar refresh" entry) and drops the
  open panel state. Before the change (Robolectric, `MainActivityRotationTest`) all five rotation
  tests failed: the activity was recreated. `BoardScreen` takes the `ChromeHolds` hold (key
  `layout`) and asks `chromeChanged` in the activity's `OnConfigurationChangedListener`, not in a
  composition effect. Why: the view tree is laid out with the new size before compose sees the
  new `LocalConfiguration`, so an effect-taken hold let the board restart its input with the old
  layout's rects while raw drawing was on; and a `chromeChanged` asked after the new layout drew
  waits for a draw that never comes, leaving the refresher's hold on. The layout key reports one
  frame after the new layout, the release follows through the usual settle. The board view
  restarts the input on its own layout change listener with the new size.
- The side panel layer is composed above the dock: the wrapped bottom dock reached the right
  edge on small screens and swallowed the panel indicator's tap.

## 2026-11-04 Documents tab and handwritten notes (plan boox-sketch-app 3.6)

- Frozen pages: a document version is paginated once into page PNGs (`AnnotationStore`, `layout.json` written last as the
  completeness marker) and the bitmap the user inks on is the bitmap exported; a freeze is never overwritten, so ink
  cannot move when the renderer changes. Ink is stored in those page coordinates, one notebook per (conversation,
  version); delete and unpair remove the annotation dir.
- Markdown is drawn with the JetBrains parser (`org.jetbrains:markdown` 0.7.17, the one `multiplatform-markdown-renderer`
  0.46.0 resolves to) plus `StaticLayout` onto a `Bitmap`, not with the Compose renderer: a Compose tree cannot be exported
  as the frozen bitmap without an offscreen capture. The renderer dependency stays unused. The app module now applies the
  kotlinx serialization plugin (layout.json).
- Q13 = A: only the latest version is annotatable, so `thread_items.annotatesVersion` and the "based on vN" marker do not
  exist; Room v2 adds only `specs.sketchIds` and `thread_items.serverId` (real `Migration(1, 2)`, no destructive
  fallback, `2.json` committed). `serverId` is set at the upload ack, in the transaction that stores the conversation id.
- Q12 = B: no Annotate mode. A finger swipe is read from the window callback of the Onyx wrapper (`FingerSwipeTracker`),
  because that callback also sees events while raw drawing owns the pen; pen events never count. On the plain touch
  fallback there is no swipe routing: finger drawing is switched off on a document page and Prev/Next page. Not confirmed
  on the device (checklist, Documents).
- Q11 = B: each inked page goes up as a full-page RGB PNG (frozen page plus ink, `PageRasterExporter`, no crop) with
  `meta.annotates`; `annotates.pages[].page` is the page number also used in `meta.pages` (the connector requires it to be
  one of them). More inked pages than `max_pages` become several uploads in page order, each with its own client id. The
  draft is marked sent after every upload is queued; a repeated tap returns `AlreadySent`.
- Spec sync triggers: after every `ConversationRepository.refresh()` success (`afterRefresh`), and the Documents tab
  refreshes on open and every 20 s while shown. No event stream consumer exists in the app, so no `spec` event trigger.
- The board wave (AppShell in MainActivity, tool strip) is not on main, so Documents is a panel tab (`PanelTabs`, order 20)
  and an opened document replaces the split layout full screen (`AppContainer.openDocument`), reusing `BoardScreen` with a
  background bitmap and `ToolbarOptions` (no page strip, "Send notes"). When the board wave lands this moves into its tab
  registry and strip in one place (`DocumentHost`).
- Sources: the connector's `GET /attachments/{id}` takes an attachment id, but a spec lists sketch (inbound) ids and the
  app has no call that maps one to the other. The remote fallback therefore asks for the sketch id and, until the
  connector offers a mapping, answers "no longer stored" for a sketch whose local thumbnails are gone. Local pages work.
- Mermaid: `mermaid.min.js` 11.4.1 (MIT) is bundled under `app/src/main/assets/mermaid`, sha256
  `a43bc1afd446f9c4cc66ac5dd45d02e8d65e26fc5344ec0ef787f88d6ddb6f9e` (the same constant is asserted by
  `MermaidRendererTest`). The renderer takes an async capture host, so the 5 s timeout lives in `MermaidRenderer` and tests
  never need a WebView; the real `WebViewCapture` is device-verified only.
- Robolectric in this sandbox: the `android-all-instrumented` jar is not cached and Maven Central answers 429 after a few
  downloads; tests were run with `JAVA_TOOL_OPTIONS=-Drobolectric.dependency.dir=<dir>` and the jar from the Google Maven
  Central mirror. Document, paginator and exporter tests need `GraphicsMode.NATIVE` (legacy mode has no real bitmaps or
  text measuring).

## 2026-11-04 PR 36 review: window-fed shape drags, ordered cancel, wrapping tool row

- Shape drags are also fed from the `OnyxWithFallback` window-callback wrapper (mapped into view
  pixels, same limit and exclude rects, same `TouchStrokeInput` logic through `WindowTouchFeed`),
  besides the view listener. Why: the firmware may still claim a stylus drag while raw drawing is
  open but disabled, and the window callback sees events before any view even in raw mode. Each
  event is handled once (`EventKey`: down time, event time, action, history size, rounded view
  position); both copies are counted per source in `InputSelector.shapeDrags`, shown on
  Diagnostics ("Shape drag events: window N, view M") for checklist round 1.
- Raw drawing stays open and disabled for shape tools (not `closeRawDrawing`). Why: reopening
  costs a first-frame gate and GC flash on every return to a pen, the live-ink regression surface.
- A touch stroke dropped without a commit (cancel, pause, stop, pen picked mid-drag) sends
  `StrokeEvent.Cancelled` on `events`. `BoardView` clears the shape preview only from `events`:
  a commit through `vm.onStroke`, a drop through `onShapeCancel`; `inProgress` going null never
  touches it. Why: `inProgress` and `events` are collected by two coroutines, so "commit before
  clear" at the source did not hold across collectors and the preview could blink off a frame
  before the shape (`ShapeDragWiringTest`).
- The tool row wraps (`FlowRow`) instead of scrolling, chips are at least 48 dp tall and the
  selected one gets the swatches' black frame. Why: rectangle and arrow were scrolled off-screen
  (also in the golden), and a scroll on e-ink ghosts.
- `OnyxRealInputShapeTest` drives the real `OnyxStrokeInput` (fake `RawDrawing` only) under the
  fallback wrapper across pause, resume and mid-stroke tool switches; app tests depend on
  `onyxsdk-pen` for `RawInputCallback` and `TouchPoint`.

## 2026-11-04 Colour and shape UI on the Onyx path

- Shape tools turn Onyx raw drawing off through `active()` (`!tool.isShape`), not through a
  `holdUi` count. Why: the off state is derived from the selected tool, so it cannot get stuck the
  way an unbalanced hold can; holds, pause, refresh and re-opens keep working unchanged and never
  re-enable raw drawing while a shape tool is selected. `rawActive` is false then, so the #33 watch
  never arms, and `OnyxWithFallback.setTool` disarms an armed watch when a shape tool is picked.
- The shape drag comes from `TouchStrokeInput`, started by `OnyxWithFallback` on the same view and
  rects when a shape tool is selected (it replaces the watch's view listener; going back to a pen
  stops touch and re-installs the watch listener). Pause and resume always reach touch too, so
  its paused flag is in step whenever it takes over. Not yet confirmed on the device: that a
  stylus drag reaches the view's touch listener while raw drawing is open but disabled; the window
  feed (PR 36 review entry above) covers the case where it does not.
- Live colour: the raw brush is `setStrokeStyle` + `setStrokeWidth` + `setStrokeColor` (exists in
  onyxsdk-pen 1.5.8 `TouchHelper`), re-applied after every enable like style and width. Whether the
  Kaleido raw layer shows colour or grey is device-only; committed strokes carry the colour either way.
- The board's shape preview is drawn inside `BoardSurfaceView.show`, so a lift swaps preview for
  shape in one redraw (the cancel path moved to `StrokeEvent.Cancelled`, PR 36 review entry above).
- PNG export is not wired to Send on main yet, so the device checklist (`docs/device-checklist.md`)
  marks the export check as pending that flow.

## 2026-11-03 PR 33 review: watch disarm, native exclude, live touch ink, Diagnostics tab

- An armed 300 ms watch is cancelled by anything that turns raw drawing off before it fires
  (`holdUi`, `pause`, `refresh`, `stop`, a `start` with new rects, which re-opens raw drawing) and
  re-checks `rawActive` when it fires; the next stylus down on the board arms it again. Why: a hold
  or re-open inside the window gave a false fallback (review Important 1).
- Empty exclude: `OnyxStrokeInput` sends `NO_EXCLUDE` (1 px at -1,-1, outside the view) instead of
  an empty list, because `RawInputReader.setExcludeRect` skips an empty list (decompiled 1.5.8)
  and the old native exclude (the tab bar) would stay while the app treats the area as board.
  Chosen over closing and reopening the helper: no extra first-frame gate and GC flash on every
  chrome hide, and `mapToRawTouchRect` maps a non-empty rect without its "Empty region" path.
  Not yet confirmed on the device. `OnyxRawSessionTest` now expects one exclude rect for an empty
  list (the sentinel). Test fakes model the SDK skipping empty lists.
- Touch fallback draws ink live: `TouchStrokeInput.inProgress` publishes the stroke at every down
  and move, `BoardSurfaceView.showLive` redraws page plus stroke (view pixels) per event, after
  `Epd.handwritingMode` once; clearing waits for the committed redraw, so no blank frame. Defense
  in depth because the v0.3.2 root cause is inferred, not confirmed on the device.
- There was no Diagnostics screen before (the earlier entry claimed one): a "Diagnostics" panel tab
  (`DiagnosticsPanel`, registered in `AppContainer.registerFeatures`) shows the input path
  (Onyx raw or Touch fallback), whether raw drawing is enabled and the fallback reason, from
  `InputSelector.status` and `rawEnabled()`.

## 2026-11-03 Live ink lost in v0.3.2 (strokes appeared only on pen lift)

- Symptom (Note Air 6C, Android 16): toolbar worked, but ink showed up all at once on pen up.
  That is the touch fallback: `TouchStrokeInput` commits on ACTION_UP and raw drawing is closed.
- Root cause: the 300 ms raw-callback watch in `OnyxWithFallback` (`InputSelector.kt`) armed on
  any stylus ACTION_DOWN in the activity window. A pen tap on the toolbar (below the board,
  outside the raw limit rect), on the tab bar (an exclude rect since v0.3.2), or any pen down
  while raw drawing is deliberately off (v0.3.2 first-frame hold, per-session gate, toolbar
  refresh holds, refresh brackets, pause) can never get a raw callback, so the watch abandoned
  Onyx for the whole process. v0.3.2 made the toolbar visibly usable, so pen taps on it before
  the first stroke became the normal path.
- Rule: the fallback watch arms only for a stylus down that can get a raw callback: inside the
  limit rect, outside every exclude rect (window coordinates are mapped into the board view) and
  while `RawActivitySource.rawActive` (raw drawing open and enabled). Tests:
  `OnyxFallbackWatchTest` (app) and `OnyxLiveInkTest` (ink:onyx).
- SDK facts (decompiled onyxsdk-pen 1.5.8): `setRawDrawingEnabled` caches render and input
  flags and skips calls that match the cache; `openRawDrawing` resets the cache to off while the
  native pen state goes to PEN_START, so a disable right after an open is a no-op.
  `RawInputReader.setExcludeRect` ignores an empty list, so a cleared exclude keeps the old one.
  Callbacks run on the reader thread.

## 2026-11-03 Toolbar refresh while raw drawing is on (v0.1.1 device report)

- On the Note Air 6C the pen drew and toolbar taps took effect, but the toolbar never visibly
  changed: raw drawing freezes the EPD refresh of all UI, not only the board, and exclude rects
  only stop ink there. Fix: `UiRefresher` (`:ink:onyx`) behind the `UiRefresh` interface that
  `BoardToolbar` calls on every state change (tool, eraser, width, undo, redo, clear, pages,
  caption, Send menu); the pen path never calls it, and non-freezing inputs get `UiRefresh.None`.
- Each change: `holdUi` (raw off) -> wait for the next draw (pre-draw listener, then a main-thread
  post, so it runs after that traversal drew Compose) -> `Epd.partialRefresh` (GU invalidate of the
  window) -> `releaseUi` (raw on + brush). Width drags refresh at most every 150 ms with a
  trailing run, and once on release (`onValueChangeFinished`). Holds nest in `OnyxStrokeInput`
  with pause, so a paused input stays off.
- Raw drawing is held off from composition until the first frame is drawn; then a GC full refresh
  runs and only then is raw drawing enabled. The refresh runs before the enable (not after, as
  first sketched) because a refresh issued while raw drawing is on is frozen too.
- Every new raw session (first start, or after the surface is re-created) is gated on its own
  next draw, then a GC full refresh, then enable. The gate is per session (`gated`), separate from
  the nested `holdUi` count: `stop()` clears it and a stale draw from a stopped or replaced session
  does nothing. Why: a shared counter left a stop-then-start with no second gate (re-review 3).
- 2026-11-03: Robolectric draws the first frame inside `setContent` (even with the compose clock
  paused), so wiring tests that need an outstanding UiRefresher hold create it with a toolbar
  change in the same main-thread task as leaving the board. Robolectric never creates the
  SurfaceView surface, so tests of `StrokeInput.start` call `surfaceCreated` themselves (raw input
  starts only while the surface exists). The AndroidView `onRelease` also stops the input before
  BoardScreen's dispose closes the refresher.
- `Epd.fullRefresh(view)` takes the view (Task 19 callers) but keeps the vendor demo's
  whole-screen `repaintEveryThing(GC)`; the view is unused by the vendor call.
- Known gap: pen-driven toolbar state (Undo enabling after a stroke) stays stale until the next
  toolbar interaction, to keep the pen path free of toggles.

## 2026-11-03 Onyx device fixes (Note Air 6C, Android 16)

- One raw drawing helper per host view, as in the vendor demo: `OnyxStrokeInput.start` on the
  same view only calls `setLimitRect`; a new view closes the old helper first; `stop` closes it.
  The SDK sits behind the `RawDrawing` interface so the session logic is tested with a fake.
- Pressure is normalised by `EpdController.getMaxTouchPressure()` (fallback 4096, the Note Air 6C
  level count) so Onyx and touch strokes share the 0..1 scale. Why: the vendor demos treat
  `TouchPoint.pressure` as device units, passing the maximum separately.
- Exclude rects (toolbar, `BoardScreen` overlays) are window rects offset by the board's
  `getLocationInWindow` and clipped to the board, i.e. view-local, matching the demo's
  `getRelativeRect`. Recomputed on every layout and every exclude change.
- Every app-side redraw (undo, redo, erase, page switch, load) runs inside `StrokeInput.refresh`,
  which for Onyx toggles `setRawDrawingEnabled(false)` -> redraw -> `true` and re-applies the brush
  (an enable resets the firmware brush). A refresh while paused only redraws.
- Full refresh is `EpdController.repaintEveryThing(GC)` (vendor EPD demo), debounced 300 ms,
  after load, page switch and clear, inside the same refresh bracket.
- Lifecycle: `InputGate` pauses raw drawing on ON_PAUSE or window focus loss and resumes when both
  are back; `surfaceDestroyed` closes raw drawing (`stop`), `surfaceCreated` starts again.
- `com.tencent:mmkv` is excluded: only the Onyx search-history helpers (`MMKVBuilder`) use it, not
  the pen path, and it ships an unaligned x86_64 .so (vendor issue 132).

### Device round 1 checklist (Note Air 6C)

- Raw ink appears with the stylus; no stale stroke flashes after pause/resume (vendor issue 135).
- Rotate and resize the split repeatedly: ink stays inside the board, no duplicate ink (one helper),
  and the new limit applies without reopening (if not, reopen on limit change).
- Toolbar and overlays: pen strokes over them draw nothing.
- Pressure: a light and a hard stroke give visibly different widths; check the logged
  `getMaxTouchPressure()` value and that stored pressures stay within 0..1.
- Undo, redo, erase, page switch, clear: the screen updates immediately while the pen is enabled.
- Page switch and clear end with one GC flash (debounced), not one per tap; if
  `repaintEveryThing(GC)` has no effect (vendor issue 78), try `invalidate(view, GC)`.
- Notification shade, dialog, app switch and back: pen is off while covered, back on afterwards.
- No missing `com.tencent.mmkv` class at runtime with mmkv excluded.
- First launch: the toolbar is visible immediately (one GC flash), and the pen draws right after.
- Tapping a tool, eraser, undo, redo, clear, page buttons: the toolbar shows the change within
  about one frame, without a full flash; the pen keeps drawing with the new tool afterwards.
- Dragging the width slider: the thumb follows in steps (about every 150 ms) and lands on the
  final value on release. If GU leaves ghosting or does nothing, try `refreshScreenRegion` or GC.
- Drawing strokes never flashes the toolbar. A committed stroke does not touch the surface while
  raw drawing is live (2026-11-04 latency entry); undo, erase, clear and page switch still toggle by
  design, after a dirty-rect sync.
- Draw ten quick strokes: each stays visible the whole time (no vanish-then-reappear); then undo,
  erase and a page switch still show the right ink afterwards.

## 2026-11-03 Onyx fast path (Task 18)

- `org.lsposed.hiddenapibypass` is pinned at 6.1: 6.2 requires compileSdk 37 and the project is
  fixed at compileSdk 36 (Q2). Bump together with compileSdk.
- Fallback lives in `InputSelector` (`:app`): it wraps `OnyxStrokeInput` so any exception, or a
  first stylus ACTION_DOWN without a raw callback within 300 ms, switches to touch and disables
  Onyx for the process (reason goes to `OnyxDiagnostics`, wired to Diagnostics in Task 19).
- Tilt comes from the raw `TouchPoint.tiltX/tiltY` (nonzero only). `Device.getStrokeParameters`
  returns brush tuning, not tilt, so it is not used. Why: avoids a second hidden-API path.
- Raw ink, latency and the 300 ms rule are verified on device only (device round 1).

## 2026-11-03 scaffold (Task 2)

- Pinned versions: AGP 9.5.0, Gradle 9.8.2, JDK temurin 17, Kotlin 2.4.21, Compose 1.12.1,
  kotlinx-serialization 1.12.0, kotlinx-coroutines 1.11.0, OkHttp and MockWebServer 5.3.2,
  Room 2.8.5, KSP 2.3.13, WorkManager 2.12.0, activity-compose 1.13.0, lifecycle 2.11.0,
  datastore 1.3.0, core-ktx 1.19.1, multiplatform-markdown-renderer 0.46.0, Robolectric 4.17,
  Roborazzi 1.77.0, ktlint-gradle 14.2.0 with ktlint 1.8.0, detekt 1.23.8, Onyx pen 1.5.8 /
  device 1.3.6 / base 1.8.9. All in `gradle/libs.versions.toml`.
- Compose is 1.11.4 and core-ktx 1.18.0, not the planned 1.12.1 / 1.19.1: those require
  compileSdk 37, while decision Q2 fixes compileSdk 36. Revisit when compileSdk moves.
- 2026-11-03: OkHttp is 5.3.2, not 5.5.1: okhttp-android 5.5.1 requires compileSdk 37 (AAR metadata
  check failed on CI), same Q2 constraint.
- onyxsdk-base drags in pre-AndroidX support libraries (24.2.1) that duplicate AndroidX classes;
  they are excluded in `:app` and `:ink:onyx` (jetifier failed on duplicate namespaces). If the
  device rounds show a missing `android.support.*` class at runtime, revisit this exclusion.
- onyxsdk-base also needs `pub.devrel` and `com.tencent` artifacts that only the Onyx Maven
  repository serves, so `settings.gradle.kts` includes those two groups in its content filter.
  The Onyx repository only works from the JVM (OpenSSL 3 rejects its small DH key), so curl
  cannot probe it without `--ciphers 'DEFAULT@SECLEVEL=0'`.
- The Onyx pen AAR sets `allowBackup=true`; the app manifest overrides it with `tools:replace`.
- Commandline tools for `mise run install` are pinned by URL and sha256 in `mise.toml`.
- `:core:model` and `:core:api` are plain Kotlin/JVM modules (fast tests); `:ink:onyx` and
  `:app` are Android modules. AGP 9 has built-in Kotlin, so no `kotlin-android` plugin.
- The pod has no `unzip` and no `pgrep`; the install task extracts with Python's zipfile.

## 2026-11-03 decisions D30-D45 (plan section 8 Q1-Q16) and D47

- Q1 full app straight away, no probe build. Q2 minSdk 30, targetSdk 35, compileSdk 36. Q3 two
  separate pairing fields (origin, token), no pairing string. Q4 Onyx over HTTPS plus sha256
  dependency verification, strict on CI. Q5 Compose, board as `AndroidView` SurfaceView. Q6
  keystore backed up with SOPS, no rotation. Q7 release on every green main via
  `scripts/next_version.py`. Q8 emulator tests on every PR. Q9 conversation list with delete.
  Q10 public as soon as the full-history scan is clean. Q11 applicationId
  `io.github.szymonrychu.booxsketch`, name "Boox Sketch". Q12 Apache-2.0 plus NOTICE. Q13
  several pages per Send, each with its page index. Q14 mermaid rendered offline. Q15 hand-off
  sheet. Q16 Recommended scope.
- D47 (B): Send uploads only the non-empty pages changed since the last send; long-press "Send
  all pages" uploads every non-empty page.

## 2026-11-02 public-repo rule

- The repo becomes public, so no secrets, server origins, internal hostnames, registry or
  cluster names and no personal data in any commit, ever. Commits use the GitHub noreply
  identity. Why: history cannot be scrubbed cheaply once public.

## 2026-11-01 Kotlin exception

- Kotlin is allowed as an explicit exception to the project's Python-only rule, because the
  Onyx pen SDK is an Android library. Why: low-latency e-ink drawing needs the vendor SDK.

## 2026-11-03 Two stroke formats

- `:core:model` is `@Serializable` and is the local notebook format (Task 17 `NotebookStore`): it
  keeps stroke ids and pen tilt. The upload format is the separate `StrokesCodec` (Task 8):
  `strokes.json` version 1, no ids, no tilt, a per-page file. Why: the connector contract is
  fixed and minimal, while local autosave must round-trip everything needed to edit and undo.
- `UndoStack` keeps history per page index and ignores entries made stale by page deletion.

## 2026-11-03 Onyx fallback watch source

- The 300 ms no-raw-callback watch is armed from a wrapped Activity window callback
  (`dispatchTouchEvent`, stylus ACTION_DOWN), which sees events before views even in raw mode;
  the view touch listener stays as a second source (first wins). Switching to touch is posted to
  the main thread. Why: in raw drawing the SDK may swallow stylus events before the view.
- Device round 1 must confirm on a Boox that the watch actually arms (diagnostics shows the
  300 ms reason when the raw path is silent) and that TouchPoint tilt is populated.

## 2026-11-03 Release keystore backup and restore test

- The release keystore (PKCS12, RSA 4096, 30-year validity) was generated once, not rotated. The
  four signing secrets live in the `release` GitHub environment, the signing certificate digest
  in a repo variable, `PUBLIC_DENYLIST` as a repo secret. Why: the same key must sign every
  update, so it is never regenerated.
- A SOPS-encrypted backup of all of these lives in the agents-helmfile repo under
  `backups/agents-boox-sketch/`. Restore test on 2026-11-03: decrypted into a temp dir, rebuilt
  the keystore (byte-identical) and its certificate fingerprint matched the original. Temp files
  were shredded. No values are recorded here.

## 2026-11-03 PKCS12 has one password

- Release run 41640174670 failed in `:app:packageRelease` with "Given final block not properly
  padded". Why: the keystore was generated as PKCS12 with different store and key passwords, but
  PKCS12 supports only one (keytool warns and ignores `-keypass`), so the key password is the
  keystore password. Reproduced with a throwaway keystore.
- Fix: Gradle `keyPassword` and the workflow read only `ANDROID_KEYSTORE_PASSWORD`;
  `ANDROID_KEY_PASSWORD` is no longer used (the secret may be deleted by the owner).

## 2026-11-03 v0.1.0 shipped without the board wired

- Release v0.1.0 showed only the app name: MainActivity was still the scaffold, the Task 17 board
  and Task 18 InputSelector were never hosted. Why it slipped: both were tested in isolation only.
- Hotfix (fix/host-board): MainActivity hosts BoardScreen full screen, input from InputSelector
  (Onyx on BOOX, touch elsewhere; any probe or selection failure falls back to touch; finger
  drawing is on only for touch). Robolectric test asserts the toolbar is present. Task 19 (split
  layout, AppContainer) replaces this wiring.

## 2026-11-03 Split layout and panel shell (Task 19)

- Device dp for Note Air 6C screenshots: landscape `w1240dp-h930dp-land-xhdpi`, portrait
  `w930dp-h1240dp-port-xhdpi`. Why: one fixed size keeps the Roborazzi goldens comparable.
- `SplitState(ratio, collapsed)` lives in a Preferences DataStore file (`datastore/split.preferences_pb`);
  ratio clamped to 0.3..0.8 on read and write. The divider is a 48 dp strip with the collapse button;
  the board slot keeps its composition position so the SurfaceView survives a collapse.
- `InputSelector.start()` installs one window-callback wrapper even when called twice.

## 2026-11-03 Gradle memory caps

- Agent pods (8Gi limit) were OOMKilled by Gradle builds (-Xmx3g plus Kotlin daemon plus test JVMs).
  Now: `org.gradle.jvmargs=-Xmx1536m`, `kotlin.daemon.jvmargs=-Xmx1g`, `org.gradle.daemon=false`,
  test tasks `maxHeapSize=1g`, `maxParallelForks=1`. Why: keep total peak well under 4 GiB.

## 2026-11-03 Shapes are strokes, colour defaults to black

- Shapes are strokes (C2): RECTANGLE and ARROW are `Tool` entries, so undo, erase, hit test and
  export reuse the stroke paths unchanged. `Stroke.color` defaults to `PenColor.BLACK` so notebooks
  saved before the colour feature load unchanged (C5).

## 2026-11-03 Arrow snapping: 40 px radius, same-anchor rule

- Arrow tail and tip snap to the nearest anchor (8 per rectangle: corners, side midpoints) of any
  RECTANGLE on the page within `SNAP_RADIUS` = 40 canvas px (C4). Why: an e-ink pen tip is imprecise
  and a finger-width radius makes connecting boxes forgiving without grabbing distant ones.
- When both ends would snap to the same anchor, the tip keeps its raw position. Why: otherwise the
  arrow collapses to a point and is dropped by the `MIN_SHAPE_SIZE` = 8 px check.
- Hit test works on `shapeOutline`: a rectangle is hit only on its outline, never its interior, so
  the eraser cannot remove a box by touching a shape drawn inside it.

## 2026-11-03 PNG export is RGB colour type 2

- PNG export is RGB colour type 2, 8 bit, 3 bytes per pixel (D91, C6), because the translator must
  see pen colours; grayscale would drop them. Size is measured by `dense_colour_page_stays_under_1mb`
  and the 0.85x shrink loop still guards `max_png_bytes`. Bounds use `shapeOutline` so arrow heads
  and rectangle corners are never clipped by the crop.

## 2026-11-03 strokes.json v2

- strokes.json v2, decoder v2 only: upload-only format, no stored v1 files; outbox v1 bytes still upload unchanged (C5).

## 2026-11-03 Dock show/hide holds raw drawing until the new rects are applied (Task 8)

- The refresher's hold ends one draw after the tap, but the new exclude list reaches the input
  later (measure, SideEffect report, recomposition, BoardView restart). A Robolectric log showed
  `hold, release, start:[dock], start:[], start:[indicator]`: raw drawing on with an empty exclude.
- BoardScreen therefore takes its own `holdUi` on a dock tap and releases it one frame after the
  composition that hands BoardView the requested state's rects (`BoardChromeWiringTest`). Holds nest,
  so the refresher's release cannot re-enable drawing early. Stale starts happen only while held.
- Auto-hide (2026-11-03, T8 finish): the shell's timer calls `BoardChrome.autoHideHandler`, which BoardScreen
  sets to the same `ui.chromeChanged()` + `changeDock` hold path as a tap. The handler refuses (returns
  false) while `StrokeInput.strokeInProgress` (pen down to pen up, set from the Onyx raw begin/end
  callbacks and touch DOWN/UP), because the hold restarts the input and would drop that stroke; the
  shell then waits another full quiet gap and asks again. Without a registered handler (non-board
  tab, fake boards) the shell hides directly. The tab bar vanishing is part of the same state change,
  so the same hold covers it.
- `dockSettled` requires non-empty reported rects: right after a hide the indicator is not measured
  yet and the dock reports an empty list; releasing then would run raw drawing with no exclude.
  Robolectric measures it in the same frame, so the test drives the gap with a fake dock slot
  (`BoardScreenWith(dock = ...)`).
- The board is full screen under the system bars (decided with the review, 2026-11-03): AppShell no
  longer pads the board; `BoardChrome.insets` is applied only to the tab bar (top, sides), the dock
  (bottom, sides) and to the other tabs' content.

## 2026-11-04 T8 on top of live ink (#33)

- The chrome feeds the #33 fallback watch only through the exclude list: the watch arms for stylus downs
  outside excluded rects while raw is active, so a stale dock rect would silence the watch there and a
  hold that never releases would keep raw off. Both are bounded by `dockSettled` (non-empty rects of the
  requested state) and by the hold being released on dispose; `BoardChromeWiringTest` replays the log for
  each path. `strokeInProgress` of `OnyxWithFallback` follows whichever input is live (touch after fallback).

## 2026-11-04 Send flow and conversations tab (Task 21)

- Outbox drains are single-flight and FIFO per conversation; the conversation is resolved at the
  first send of a local conversation. Why: items of one thread must reach the connector in order.
- WorkManager names: `outbox` (kick, KEEP, no delay) and `outbox-later-a`/`-b` (backoff). Why:
  REPLACE from inside the running worker cancelled that worker, and one shared name made a new
  send wait behind a pending backoff delay. Unpair cancels all (superseded in part: see
  2026-11-04 later() entry).
- `AppContainer.runSend` catches every Exception (not only IOException) except cancellation, so a
  Room or export failure becomes a message instead of crashing appScope.
- On a refused token (401) the thread items of paused outbox rows become PAUSED; re-pairing sets
  them back to QUEUED. The selected panel tab lives in `PanelTabs.selectedId` so
  `onOpenConversations` can select the Conversations tab (order 0, Diagnostics stays at 900).
- Conversation buttons carry an explicit 48 dp minimum height (E-ink touch target rule).

- 2026-11-04: the POST_NOTIFICATIONS request moved from launch to the first send. Why: the
  permission dialog at launch left MainActivity PAUSED on the CI emulator and failed LaunchTest
  (expects RESUMED); MainActivityPermissionTest guards it.

- 2026-11-04: `OutboxScheduler.later` keeps at most one pending backoff run. A pending run due at
  or before the new one covers it; a later one is replaced (REPLACE) on a name that is not
  running. Why: APPEND_OR_REPLACE chained runs, so a 1-5 s backoff waited behind an older 300 s
  run; two alternating names let the running worker call later() without cancelling itself. The
  runner calls it on Dispatchers.IO because it reads WorkInfos (blocking).
- 2026-11-04: the main input is wrapped in `CountedPause` (MainActivity). Why: the lifecycle gate
  (system POST_NOTIFICATIONS dialog) and the send outcome dialog both pause one input; a boolean
  made the gate's resume un-pause input under the outcome dialog. Last resume wins now.
- 2026-11-04: upload ack deletes any other row with the same serverId in the transaction that
  sets serverId (a refresh in between inserted a twin). Refresh prunes local rows whose serverId is
  missing from the successful `GET /conversations` (not paged, so success means complete; a failure
  returns before any write). Only rows whose serverId was known before the request started, and
  never rows with queued outbox items: a conversation acked while the request was in flight may
  be missing from the answer.

- 2026-11-04: remote Sources fetch `/attachments/{sketchId}` for page 0. Verified on connector main
  (plugins/sketch/router.py): the page 0 png attachment is inserted with id = the inbound sketch id,
  so no extra route is needed. Other pages of a purged local sketch are not reachable by sketch id.

- 2026-11-04 boox-pairing-code: pairing takes the server origin plus a 6-digit code (supersedes the 2026-11-03 two-field token decision). The app calls the public `POST /pair` (no Authorization header), proves the returned token with `GET /config`, then stores origin and token in the Keystore-backed `TokenStore` as before; nothing is stored on any failure. The code goes through six boxes fed by an on-screen 3x4 keypad (no IME, 72 dp keys, black borders, no ripple), Pair enables at six digits and never auto-submits (a typo would silently burn the server's 5-attempt budget per code); every failed attempt clears the digits and nothing auto-retries. `invalid_code` (400) becomes `CodeRejected` (the server gives one body for unknown, expired, used and locked), 429 becomes `RateLimited(retryAfter)`. A bare host in the origin field gets `https://` (no path, https only; debug still allows http for 127.0.0.1). No default origin is compiled in (public repo rule). `PairedDto.toString` masks the token.

- 2026-11-04 stroke-flicker (superseded by the 2026-11-04 pen ink latency entry: drawCommitted was removed): a committed pen stroke redraws the board through
  `StrokeInput.drawCommitted`, which on Onyx with raw drawing active does NOT toggle raw drawing;
  `BoardSurfaceView.show` uses it only when strokes were appended to the same page, index,
  background and preview. Why: `refresh` disables raw drawing (pen state PAUSE), which drops the
  firmware ink overlay, and the app frame posted by the SurfaceView lands later, so each stroke
  vanished briefly and came back. Undo, erase, clear, page switch and preview changes keep the
  disable, draw, enable bracket (the frame must replace the overlay). Guarded by `PenUpReplayTest`.
