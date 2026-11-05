# agents-boox-sketch memory

Lessons that outlive a session. Newest first.

## 2026-11-05 Dismiss mode: a touch on the board closes the strip, popup and panel (T-46 P0)

- Reported bug: with raw drawing on, a pen tap on the board inked at once (the firmware draws before
  the app sees anything) and the overlays went away only 600 ms later; a finger tap might not reach the
  view at all. Decision (user, Q18): dismiss and consume. While the strip, a popup or the panel is open
  the board is in dismiss mode: the whole board rect is one more exclude in the same `ChromeHolds` change
  that shows the overlay (`BoardView` adds it in `startInput`, keyed on `dismissMode`), so the firmware
  draws nothing and `TouchStrokeInput.allowed` starts no stroke (shape tools and the eraser included).
- Any pen or finger DOWN on the board in dismiss mode calls the same `dismissAll` path as a tap and the
  gesture is swallowed up to its lift (`BoardSurfaceView.onGesture`, the view consumes it). The board
  stays excluded for the whole swallowed gesture (a `swallowing` state in `BoardView`), then the excludes
  drop to the indicator rects only. So the one GC flash and the hold/release pair are the usual ones.
- The DOWN also comes from the window callback (`StrokeInput.onWindowTouch`, wired in `OnyxWithFallback`
  next to `FingerSwipeTracker`), because the window sees events before any view and while raw drawing
  owns the pen; a down inside the overlay rects (strip, popup, panel, tab bar) is ignored there. Both
  sources feed one idempotent state machine, so a down seen twice dismisses once.
- Hold release can land before the lift (the overlays reported hidden), with the board still excluded;
  the lift then only narrows the exclude to the indicators, with no hold (nothing visible changes).
- Two `BoardChromeWiringTest` tests asserted the exact exclude list `[dock, bar]` of an open strip; they
  now expect the board rect too, because that list is no longer accurate by design.

## 2026-11-04 Fountain redraw keeps the live width (boox-fountain, fix/fountain-width)

- Device report on v0.12.1, verbatim: "fountain gets thiner after redraw, but besides that it's all
  fixed (the rest of the tools don't change after redraw)". The SDK pens run on the device now.
- Cause: PR #51's `width - 3` (min 0) before `createNeoPenV2`. Bytecode (FountainShapes.javap):
  `config.width = width + 3.0f / scale` (`FOUNTAIN_PEN_V1_COMPENSATION`, scale = the 6th float arg,
  our DISPLAY_SCALE 1), so the SDK itself adds 3 px and that is the width the live ink has. Our
  subtraction was a guess ("live raw ink has no addition") and the device disproved it.
- Fix: `fountainWidth` returns the BrushMapping width unchanged. Only fountain changed. The test
  `the_fountain_width_cancels_the_3_px_that_create_neo_pen_v2_adds` asserted width-3 and is no
  longer accurate; replaced by `the_fountain_redraw_width_is_the_live_width_unchanged`. Checklist
  line under "Redrawn strokes are not squished" tightened.

## 2026-11-04 Redraw with the device pen settings, reset the e-ink mode (boox-squish)

- Device report on v0.11.4, all tools: "Tools before redraw look right, then look squished". The
  app geometry is ruled out (one uniform scale and offset; a 400x200 ellipse round-trips with
  aspect 2.000, scratch test in the `boox-squish` scratchpad). Four causes fixed in one PR because
  each device round trip is expensive; the InkDiag logs tell which one it was.
- E-ink mode (touch strokes and shape drags only, NOT the pen tools): `BoardSurfaceView` set `HAND_WRITING_REPAINT_MODE` for a touch
  stroke or shape drag and never reset it, so every later frame was near-binary (no grey,
  anti-aliasing or texture) and looked thinner. It is now reset (`EpdController.resetViewUpdateMode`,
  as the vendor demo's `PenManager.refreshPartial`) when the stroke or drag ends, before the frame
  with the result: `showLive(null)` resets without a frame (a frame there blinks the stroke out,
  the #40 flicker), and `show` resets when no drag preview and no live stroke is left. A commit of
  the previous stroke under a touch stroke in progress keeps the fast mode (no mode flip mid-stroke
  while writing quickly). Raw drawing is not touched, so the pen latency and flicker fixes stand.
  `handwriting` is set only by a live touch stroke or a shape drag; on the Onyx path the vendor
  raw layer draws pen ink, so fountain, brush, charcoals, ballpoint and marker never enter that
  mode. The reset is safe but does not fix a pen redraw: for the pen tools only the SDK pens (H1)
  and the cache padding (H4) can. A negative result on a pen tool after this PR says nothing
  about the mode reset.
- SDK pens follow the vendor OnyxPenDemo (`BrushScribbleShape`, `NewBrushScribbleShape`,
  `CharcoalScribbleShape`, `PenInfoUtils`): fountain is `FountainShapes.createNeoPenV2` plus
  `NeoPenRender` with a FILL paint (the V1 `NeoFountainPenWrapper` is gone); brush and both
  charcoals are driven through create, onPenDown, onPenMove and onPenUp and read back with
  `NeoPenUtils.readPointResult`/`readTextureResult`, with a full `NeoPenConfig` instead of the
  wrappers' bare one. Points carry the stored 0..1 pressure and `maxTouchPressure` is 1 (supersedes
  the raw pressure times device max of the boox-ink-match entry).
- Pen settings are the demo's constants, not `Device.getStrokeParameters` (its array layout for
  the fountain style is unverified): fountain and brush pressure sensitivity 0.3
  (`KEPLER_DEFAULT_PRESSURE_SENSITIVITY`, as `PenInfoUtils`, `NeoPenConfig` and `createNeoPenV2`;
  not the 0.375 of `PenUtils.DEFAULT_PRESSURE_SENSITIVITY`) and smooth level 0.6. The charcoals set
  only what `NeoCharcoalPenWrapper` sets (colour, width, tilt enabled, rotate angle, max pressure)
  and keep the SDK pressure, smoothing and tilt scale; only the tilt switch is read from the
  firmware ([tilt enabled, ...] as `CharcoalScribbleShape.loadTiltConfig`), and tilt stays off when
  the stroke kept no tilt. The brush is driven by our create/onPen* loop with the demo's
  constants; the demo itself calls `NeoBrushPenWrapper.computeStrokePoints` with a bare config.
- Fountain width: `createNeoPenV2` adds 3 px (`FOUNTAIN_PEN_V1_COMPENSATION`, `width + 3/f6`). The
  live raw ink is sized by `TouchHelper.setStrokeWidth(BrushMapping.width)`, which
  `RawInputReader.setStrokeWidth` hands unchanged to the native renderer (no addition in the Java
  code); the demo itself passes the same px to both and so redraws 3 px wider than it inks. We hand
  `createNeoPenV2` `width - 3` (min 0) (SUPERSEDED, see the boox-fountain entry below: wrong).
- Charcoal screen matrix as the demo's `RendererUtils.getPointMatrix`: a translation to the view's
  screen position (`getLocationOnScreen`), mapped with `NeoPenUtils.mapToPenCanvas` and back with
  `mapFromPenCanvas`. The document view (not touched here, another branch owns it) keeps origin 0,0.
  The bitmap cache does not key on the origin; it only moves the grain phase.
- Cache padding: fountain and brush now pad 6x the width across (was 3x, i.e. 1.5x each side),
  because the native pens size points from pressure and velocity and V2 adds 3 px; charcoal stays 8x.
- Diagnostics: every `OnyxPenRenderer` fallback (exception, or the SDK drew nothing) is logged as
  a warning with tag `InkDiag` and its exception and counted atomically in `InkDiag.fallbacks`,
  shown as "Pen redraw fallbacks" in the Diagnostics tab. Strokes arriving after the pens broke
  are counted too, and a redraw the cache repeats counts again (events, not strokes). Why: v0.11.4 showed "no improvement", and the silent
  fallback could have hidden that the SDK pens never ran. Every committed stroke logs its raw input
  box, redrawn ink bounds, view size and screen position and the stroke parameters
  (`adb logcat -s InkDiag`). Device checks: docs/device-checklist.md "Redrawn strokes are not squished".
- Not verified on the device: that the firmware's charcoal stroke parameters have the layout
  [tilt enabled, ...], that the fountain redraw width equals the live width, and that `resetViewUpdateMode` restores a non-flashing mode for the SurfaceView.

## 2026-11-04 Strip eraser erases with the pen tip on the Onyx path (boox-eraser)

- Device report on v0.11.2: the eraser does not work. Root cause: the eraser selection only ever
  reached `TouchStrokeInput.eraserMode` (`AppContainer.setEraser`, wired by `BoardScreen`'s
  `LaunchedEffect(eraser)`); `OnyxStrokeInput` had no eraser state, so with the eraser selected raw
  drawing stayed on and the pen tip inked a stroke. On the Onyx path only the stylus eraser end
  (`onRawErasing*`) erased. That code is identical at v0.10.0 (#44 changed only the strip UI, which
  sets `vm.eraser` correctly), so it is not provable from code why the eraser "worked before":
  either that run was on the touch fallback (Diagnostics "Input path"), or erasing was done with
  the eraser end. `git diff v0.10.0..v0.11.2 -- ink/` touches only the brush width.
- Fix: `StrokeInput.setEraser(on)` replaces the `setEraser` lambda chain (no second path). The
  eraser runs like a shape tool: `OnyxStrokeInput.active()` is false while it is on (raw drawing off,
  derived state, cannot get stuck), and `OnyxWithFallback` moves the pen to `TouchStrokeInput`
  (`touchDriven` = shape tool or eraser; view listener plus window feed), which erases in its
  eraser mode. Picking a pen turns raw drawing back on. `OnyxEraserTest` drives the strip tap and a
  window pen-tip drag through the real board and the real Onyx input.
- Not verified on the device: that the window feed delivers the pen-tip drag while raw drawing is
  open but disabled (same open question as shape drags; checklist).

## 2026-11-04 Redraw uses the Onyx SDK pens for fountain, brush and charcoal (boox-ink-match)

- Device report after #47: ballpoint matched, fountain and brush still changed on redraw, charcoal
  and charcoal_v2 looked right live but like a pen after the redraw.
- Root cause (decompiled onyxsdk-pen 1.5.8 and onyxsdk-penbrush 1.1.2, javap notes in the
  `boox-ink-match` scratchpad): the SDK pens behind the live ink compute per-point widths natively
  (`NeoPenUtils.computeStrokePoints` -> `NeoFountainPen`/`NeoBrushPen` in libneopen_jni, `NeoPenConfig`
  defaults pressureSensitivity 0.3, velocitySensitivity 0.5, smoothLevel 0.6) and draw charcoal as
  texture bitmaps (`NeoCharcoalPen(V2)` -> `readTextureResult` -> `drawBitmap`). `CanvasStrokeRenderer`
  drew a linear pressure ramp (fountain 0.35-1.0x, brush 0.2-1.0x, no velocity, no smoothing) and
  charcoal as a smooth translucent line, so no width factor could fix it and the curve is not in Java.
- Fix: `OnyxPenRenderer` (`:ink:onyx`) redraws those four pens with the SDK's own offline renderers,
  as the vendor demo does: `NeoFountainPenWrapper.drawStroke`, `NeoBrushPenWrapper.drawStroke`,
  `NeoCharcoalPenWrapper`/`NeoCharcoalPenV2Wrapper.drawNormalStroke` (identity screen matrix,
  points already in canvas pixels). Input is what the raw input drew: canvas pixels, width
  `BrushMapping.width(tool, width * scale)` (so charcoal_v2 keeps 1.2x on both sides), raw pressure
  (normalised * device max, fallback 4096) with the same max. Ballpoint, marker, shapes and single
  points stay with the app renderer.
- Seam: `CanvasStrokeRenderer(pen: PenRenderer?)` offers each committed freehand stroke to the pen
  first. `OnyxPenRenderer.onDevice()` is non-null only when `OnyxProbe` passes, and only the board surface
  and the document page view use it; PNG and Send exports keep the plain renderer. On JVM,
  Robolectric and screenshots the pen is null (or the native library is missing: the first
  `LinkageError`/`RuntimeException` turns it off for good), so the fallback renderer and every
  screenshot are unchanged. `drawBigStroke` (needs a content rect and render matrix) is not used.
- PR #48 review (2026-11-04): pressure is NOT normalised twice. Evidence: the charcoal wrappers
  divide each point by `createArgs.maxPressure` in Java and copy it into
  `NeoPenConfig.maxTouchPressure`, but `NeoNativePen` packs points with
  `PenUtils.getPointDoubleArray(point, 1f)` (default divisor 1) and libneopen_jni (arm64 and v7a)
  holds the JNI field names of every PenConfig field it reads (width, dpi, tiltEnabled,
  pressureSensitivity, ignorePressure, ...) but no `maxTouchPressure` string: the native pen never
  reads it. So the native charcoal sees stored pressure 0..1, as fountain and brush do. The live
  ink itself is drawn by the firmware (`EpdController.setStrokeStyle`), not reachable from the SDK.
- Tilt: stored points keep the raw tilt (Notebook storage; strokes.json drops it), so the redraw
  passes it in `TouchPoint(x, y, p, size, tiltX, tiltY, t)` and `PenRenderArgs.setTiltEnabled`
  when any point has tilt. Fountain and brush wrappers build their config without tilt; their
  points still carry it.
- The SDK pens run only for committed strokes, through `CachedPenRenderer`: one native run per
  stroke instance and placement, kept as a bitmap of the stroke's ink bounds (LRU, 1/8 of the
  heap), then blitted by every redraw (shape drag frames, dirty-rect sync, the document view's
  Compose draws). The shape preview and the touch stroke in progress go through
  `StrokeRenderer.drawPreview`, which never uses the pen. Why: the native pen per frame was O(n)
  native pen creations per drag event.
- Ink bounds of charcoal pad 8x the width (texture stamps scale with tilt, NeoPenConfig
  tiltScale 3, plus charcoal_v2 1.2x); the cache bitmap and the dirty rect use the same bounds,
  so grain outside them is neither drawn nor left stale.
- `SdkOnyxPens` unrolls the vendor wrappers (computeStrokePoints + drawStrokeByPointSize,
  computeStrokeRenderPoints per 1000 points + drawBitmap) so an empty result (no native pen)
  returns false and the stroke falls back to `CanvasStrokeRenderer` instead of vanishing.
- Device-only: that the SDK pens match the firmware live ink exactly (dpi 320 default) and the
  8x charcoal pad is enough; see the device checklist section "Redraw matches the live ink".

## 2026-11-04 Live ink width matches the redrawn stroke (boox-ink-width)

- Root cause: the raw brush got the slider width as is (`BrushMapping.width(width)`), while
  `CanvasStrokeRenderer.style` draws marker at 3x and charcoal_v2 at 1.2x that width, so a marker or
  charcoal_v2 stroke changed width when the app redrew it. `BrushMapping.width(tool, width)` now
  applies the same factors (full pressure; the SDK does its own pressure shaping live). The renderer
  is unchanged.
- No view-scale term: the slider width is in view pixels, the SDK `TouchHelper.setStrokeWidth` hands
  it to the renderer in pixels (no density), and a commit stores `width / scale` and the redraw
  multiplies by the same scale back. So rotation or relayout cannot skew it, and `setTool` (tool or
  width change) plus `enable` already re-apply the brush. `LiveInkWidthTest` checks live equals redrawn
  for every pen at three view scales. The factors in `BrushMapping` must follow `CanvasStrokeRenderer.style`.

## 2026-11-05 Notebook page grid in page pixels (T-46 P1; replaces the 2026-11-04 5 mm view-pixel grid)

- Every page is a notebook page (user, Q2: no page kinds). The grid is no longer 5 mm from the display
  dpi anchored at the view corner: that drifted from the strokes in landscape, where the page is scaled
  0.75x. Rows, bands and margin are defined in page-canvas pixels (`NotebookGeometry`, `:core:model`,
  pure Kotlin) and `BoardGrid` draws them through the frame's `BoardGeometry` (scale, dx, dy), the same
  mapping the ink uses, so they stay locked to strokes in both orientations.
- Numbers (user, Q3 and Q4): cell 53 px (6 mm in portrait), row pitch 106 px (12 mm), top margin one row,
  16 rows (70 px foot), row line closes its row (annotation band above, text band below, for the later
  text layer), right margin 30 percent (x from 983). Row lines 1 px `0xFFD3D3D3` over the text column
  only (x 53..983), no verticals there; one 1 px vertical at the margin; sparse dots `0xFFBDBDBD`, 2 page
  px (never below 2 view px), at cell intersections; a dot that a row line would cover is skipped.
- Same paint discipline as the first version: painted in `BoardSurfaceView.paint` right after the white
  fill (under the document background and the ink), one pass, only primitives inside the dirty clip,
  two reused float buffers, no new surface frame and no raw drawing toggle. 1 px lines sit on the view
  pixel row `floor(y) + 0.5` so a non-anti-aliased line covers exactly one row.
- View-only: `PngExporter`, `PageRasterExporter` and `StrokesCodec` never see it (pinned by
  `GridNotExportedTest`, now also checking the dot colour). `BoardPreviewCanvas` does not draw it, so
  the screenshot goldens are unchanged.

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

## 2026-11-04 Translator feedback and colour markers (T-40)

- Feedback lives in Room v3 table `feedback` (real Migration(2, 3)), synced by `FeedbackSync` after every
  conversations refresh (hooked next to SpecSync) from `latest_feedback_id`; it fetches from the stored max id,
  page by page. Why: same trigger and failure policy as specs, nothing new to schedule. The open document
  now refreshes every 20 s (was once) so new notes appear while reading.
- Feedback is shown apart from the document: a one-line latest strip and a "Notes (N)" list overlay, never inside
  the paginated markdown. Answering stays the existing typed-message path (not changed here).
- `[[red]]`-style markers (black, blue, green, orange, purple too, case-insensitive) render as a bold `[red]`
  label in the ink colour, in the spec pages (MarkdownPaginator text blocks, including inline code spans, not code blocks or tables) and in feedback
  text. Why the label stays: on greyscale e-ink the colour alone would vanish. `[[?word]]` and `[[illegible]]`
  stay as written. Pure parsing is `splitColourMarkers` in :core:model.
- `core/api/src/test/resources/openapi.json` refreshed from connector 2.0.0; plan and contract agreed
  (path prefix /app/v1/sketch, `latest_feedback_id` nullable, feedback page `{items, cursor}`).
- 2026-11-04 board-pr2 (T-36 Q4-Q7): the bottom text-chip toolbar (`BoardToolbar`, `ToolbarDock`) is replaced by a BOOX style left icon strip (`ToolStrip`, 56 dp, 48 dp buttons, black frame on the selected tool) with popups to its right (`ToolPopupContent`), hosted by `ToolboxDock`. Rules: a tap on an unselected pen/eraser/shape selects it and closes any popup; a tap on the selected tool, or on colour, pages or send, toggles its popup (`ToolboxState`). Pen types and the free width slider live in the pen popup; rectangle and arrow always draw `BoardViewModel.SHAPE_WIDTH` (4f, the slider value is kept for the next pen); colours are a 3x2 grid (6 colours, Q7); arrow snapping stays anchors only (Q6). Icons are drawn on a Canvas (no `material-icons-extended`, so no dependency verification change).
- 2026-11-04 board-pr2 popup holds: a popup open or close is its own hold (`holds.change("popup", target, needsRects = open)`) and a full refresh (`chromeChanged`), because popup residue ghosts on e-ink. The popup rect is reported under its own key `popup` (separate from the strip's `dock` key, a deviation from the plan's single exclude list): the dock key is already settled when a popup opens, so only a separate key makes the hold wait for the popup to be measured. The popup rect is keyed on the popup kind, so switching pen -> send drops the old rect and waits for the new one. The popup closes on tap on the board, auto-hide, hide and rotation (anchored to a button of the old layout).
- 2026-11-04 board-pr2 layout: the strip starts below the tab bar (the tab bar spans the top edge while the chrome is visible), and on a window too short for ten 48 dp buttons they shrink to fit instead of clipping. The eraser popup also holds Clear page (the old toolbar's Clear must not be lost; it is undoable, so no confirm). The send button keeps the long press for "Send all pages" (ledger ruling) next to the explicit button. Tests: the harness traverses the view tree only on a layout request, so wiring tests that wait for the refresher's next draw call `requestLayout` (the old chips' ripple used to do it).
- 2026-11-04 board-pr2 review: the send caption moved from `rememberSaveable` to `ToolboxState` (plain state): it survives rotation and popup close but not process death. A finger tap on the board with a popup open hides the whole chrome (as the old toolbar did), not only the popup.

## 2026-11-04 CachedPenRenderer declines up front (boox-ink-match review)
- `PenRenderer.handles(stroke)` lets the cache decide before any bitmap exists (ballpoint, marker, shapes, one point, broken latch). The remaining runtime declines (native drew nothing) sit in a 256-entry LRU. The bitmap is clipped to the canvas (canvas size is part of the key), a stroke wholly off-canvas allocates nothing, and an OutOfMemoryError clears the cache and falls back to `CanvasStrokeRenderer`.

## 2026-11-04 Mermaid renders reliably and says why it fails (fix/mermaid-render)

- Report: "Mermaid graph didn't render on the tablet preview". The trigger was never proven (no device); every failure used
  to fall back silently to code text. So a failed diagram now shows the code plus a one-line caption
  `diagram failed: <reason>` (timeout, blank capture, mermaid parse error with mermaid's message, web view unavailable)
  and logs the same text under tag `BooxMermaid`. The reason on the device tells which candidate it was.
- Capture: the offscreen web view gets an explicit measure and layout; `render.html` calls `Android.done` only after
  `mermaid.render` resolved, fonts are ready and two animation frames ran; the host then re-lays out at the diagram height
  and draws after `postVisualStateCallback` (700 ms timer as fallback for a view that never gets a frame). A blank bitmap is
  retried once. Web view creation failure is caught (it used to crash the main-thread post).
- Timeout 5 s -> 20 s per attempt: the budget covers cold web view creation plus parsing the 2.5 MB `mermaid.min.js` and the
  first render on an e-ink CPU, which was never measured below 5 s on the device. 20 s only delays a doc that would
  have shown code text anyway, and pagination already runs off the main thread. Revisit once the device logs show real times.
- A failed render is never final: pages with a failed diagram are flagged (`RenderedPage.diagramFailed`), the freeze
  writes a `diagram-failed` marker, and opening the version again discards that freeze and paginates anew while no stroke
  exists in the saved notebook and no sent mark exists. The test is "any stroke", not "notebook.json exists", because
  showing a page saves an empty notebook (review finding). After ink or send the freeze stays, as ink is in page coordinates.
- Review fixes (PR #50): `render.html` races a 300 ms timer against the two animation frames (an unattached web view may
  never run frames; covered by `scripts/tests/test_render_html.py`, node). The host destroys the view on every path,
  including its own timeout timer, and draws inside try/catch (failure -> `Crashed` reason; no draw after finish).
  Network is blocked (`blockNetworkLoads`, no content/file access, `shouldInterceptRequest` answers 403 for anything but
  the `mermaid` asset dir). Not made asynchronous: the freeze is the ink coordinate system, so a placeholder swap would move
  pages under ink; instead a total budget of 60 s per document (`MermaidRenderer.DOCUMENT_BUDGET_MS`) after which
  remaining diagrams become captioned code ("skipped, document diagram time used up") and are retried on the next open.
- Tests changed because the `WebCapture` callback now reports `CaptureResult` instead of `Bitmap?` (MermaidRendererTest only).

- 2026-11-04 boox-tasks: the Tasks tab reads and writes the taskboard only through the connector relay (`/app/v1/sketch/tasks`), as the user, with web parity (Q1). Decisions: Q8 waiting sessions are text rows; a tap filters the list by that session id (the server's `session` filter matches ids, the slug is only shown). Q9 no status, title, assignee or todo text edit anywhere; `TasksClient` has no such method (a reflection test guards it). Q10 live refresh of the visible Tasks tab only: the SSE flow is collected from a `LaunchedEffect`, so leaving the tab cancels it; no badge, no notification channel. The real taskboard API differs from the plan sketch: decision ids, todo item ids are integers and the answer `choice` is the option index; `POST /comments` returns the whole task. Events are hints only (`task_changed {key}`); a lost stream emits one refresh hint and reconnects with backoff capped at 30 s. A 429 sets `retryAfterS`, stops automatic refreshes until it elapsed and never loops. A 401 calls `pairingRepository.rejectToken`, so the gate shows pairing. The connector dependency: agents-app-connector 2.0.0 task relay with the delegate token configured (503 `taskboard_unconfigured` otherwise, shown as "Tasks are not set up on the server."). Navigation and the view models live in `TasksController` on the container, so a tab switch keeps filters, page and the open task; unpair resets it.


- 2026-11-04 boox-resman (fix/onyx-resmanager-init): device logcat on v0.11.6 showed the first SDK pen redraw throwing
  `java.lang.ExceptionInInitializerError at com.onyx.android.sdk.pen.NeoNativePen.onPenDown`, caused by
  `kotlin.UninitializedPropertyAccessException: lateinit property appContext has not been initialized at
  com.onyx.android.sdk.base.utils.ResManager.getAppContext(ResManager.kt:377)`, called from `DimenUtils.dp2PxFloat`
  from `PenUtils.<clinit>(PenUtils.kt:45)`. The app never initialised ResManager (onyxsdk-baselite), so every SDK pen
  fell back to plain for the rest of the process. Fix: `App.onCreate` calls `OnyxSdk.init(this)` (`ResManager.INSTANCE.init(context)`)
  before the container is built, guarded by `OnyxProbe`, as the vendor OnyxAndroidDemo does in `Application.onCreate`. The first
  clinit/LinkageError in `OnyxPenRenderer` is logged under InkDiag and shown in Diagnostics as
  `SDK pens off: <exception class>: <message>` (an ExceptionInInitializerError is unwrapped to its cause, as its own message is empty).
  Deferred: tilt in the upload format, a pressure-varying ballpoint.

- 2026-11-04 boox-tilt: strokes.json is version 3. A point stays `[x, y, pressure, t]` and gains `tiltX, tiltY` (six values) only when the pen reported tilt; a missing axis is written 0, a finger point stays four values. The decoder accepts versions 2 and 3 and 4 or 6 values per point (the only reader of old files is our own notebook storage). Why this shape: agents-app-connector `validate_strokes` (images.py) checks only that the file is JSON, `version` is an int, `canvas` has w, h, dpi and `strokes` is a list; it never inspects points or the version value, so no connector change is needed. Readers that index points[0..3] keep working. Ballpoint redraw width now follows pressure: `width * (0.6 + 0.4 * pressure)`, averaged over the segment ends like fountain and brush. The live pencil style (`STROKE_STYLE_PENCIL`) is drawn by the vendor native layer, so its curve is not in the SDK bytecode (javap notes only list `NEOPEN_PEN_TYPE_PENCIL = 7` as a constant); 0.6x-1x is a modest guess, tuned on the device (checklist "Tilt and ballpoint pressure"). Marker and shapes are unchanged. Test changes: `strokes-example.json` and `StrokesCodecTest` (version 3, the "without tilt" roundtrip test asserted tilt was dropped, which is no longer accurate).

## 2026-11-04 Mermaid timeout root cause: View.post on an unattached view (fix/mermaid-timeout)

- Device report on v0.13.0: "diagram failed- timed out after 20s". Root cause (found by code reading, reproduced by a
  failing Robolectric test, not yet seen on the device): the JS bridge callbacks `Android.done` and `Android.fail` ran
  `view.post { ... }` on the offscreen web view, which is never attached to a window. `View.post` on an unattached view
  queues the runnable until attach, so it never ran; no done, no fail, so only the timeout could end the render. The URL
  check was ruled out: the page loads `file:///android_asset/mermaid/render.html` and `.../mermaid.min.js`, both pass
  `isAllowedUrl` (test pins them). `postOnAnimation` had the same flaw (the 700 ms timer masked it). Both now go through
  the main handler (`CaptureBridge`, `main.post`).
- Diagnostics: the timeout caption carries a hint, `timed out after 20 s (blocked: <url>)`, `(load error: ...)` or
  `(js: <first console error>)`, from `CaptureNotes` fed by `shouldInterceptRequest`, `onReceivedError` and a
  `WebChromeClient.onConsoleMessage`; all of it is also logged under `BooxMermaid`. The host timer fires 500 ms before the
  renderer's own wait so the hint is not lost to the race.
