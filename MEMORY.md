# agents-boox-sketch memory

Lessons that outlive a session. Newest first.

## 2026-11-08 Every open is paused for real: the tap-to-dismiss regression (T-78, fix/live-ink-held-open-pause)

- Root cause (analysis: `.scratchpad/boox-fixes/fable-tap-dismiss-regression.md` in the workbench; medium-low
  confidence, the firmware is a black box): since #57 the raw session the user writes in is in a state v0.13.2 never
  produced. `openRawDrawing` leaves the firmware pen in START and a `setRawDrawingEnabled(false)` right after it is a
  no-op (the SDK's cached flag is already off), so under a hold (popup or panel open) the pen stayed live; a pen tap on
  the board that dismissed the overlay was drawn by the firmware in START and dropped by the app; the reopen for the
  changed rects ran at the lift of that tap; the enable then went START -> DRAWING. v0.13.2 always went
  PAUSE -> START -> DRAWING, never drew a stroke in START and never opened at a lift. The vendor demo
  (`PenManager.java`) force-pauses after every open.
- Fix: `OnyxStrokeInput.open` calls `forceSetEnabled(false)` right after every open (held or not), so every enable is
  PAUSE -> DRAWING and a dismiss tap meets a paused pen (`dropsEvent` is now a safety net). `BoardView` no longer starts
  the input at the lift of a swallowed gesture: `LaunchedEffect(exclude, swallowing)` applies the rect change once the
  swallow ends, after the hold of the gesture is released. Toggles A and B (#70) stay, default OFF
  (`InkExperiments.DEFAULT_ON = false`), as the analysis recommends.
- Not done: the analysis's optional fix (3), one pause/resume at the first pen idle after an enable. It is marked the
  least principled and droppable if (1)+(2) hold, and it is a brush write at pen idle, the shape #59 broke the device
  with. Next step only if device check c below fails.
- Tests: `OnyxBrushAfterOpenTest.SdkLikeRawDrawing` models the pen states (STOP, START, DRAWING, PAUSE) and logs every
  SDK call; new `no_session_is_ever_enabled_from_start`, `a_held_reopen_ends_paused`, `a_held_first_start_ends_paused`,
  `a_first_start_waiting_for_its_draw_is_paused`, `a_dismiss_tap_followed_by_a_stroke_goes_pause_start_drawing_..`
  (no SDK call inside a stroke). Changed because they pinned the old behaviour: the two
  `..under_an_outstanding_hold_leaves_the_live_firmware_pen..` tests (pen live in START under a hold, now paused);
  `both_toggles_off_reproduce_the_v0_18_0_call_sequence` is `the_default_toggles_off_sequence_pauses_after_every_open`
  (pins the default sequence, `force:false` after each open); `the_default_toggles_are_on` is `..are_off`; the
  both-toggles tests name the toggles explicitly; the region config open sequence gains `force:false`;
  `BoardDismissModeTest.a_swallowed_gesture_keeps_raw_drawing_held_and_not_reopened_until_the_lift` is
  `..reopens_after_the_release` (the reopen ran under the gesture's hold at the lift, now after its release).
- These prove the call sequence, not the firmware. Device check (toggles A and B off, `adb logcat -s InkDiag`):
  a. launch, write 5 lines in black fountain without touching the strip; b. open a popup and close it with the same
  strip button, write 5 lines; open a popup and close it with a pen tap on the board, write 5 lines; c. write a line,
  hide and show the strip with its chevron, write 5 lines. Every line must be right in a and b, and c must change
  nothing. Still red: next is toggle B on (region config), then a build with the grid off (`BoardGrid.draw`).

## 2026-11-08 Red live ink diagnostic build: firmware API dump, vendor resume order, region pen config (T-78, fix/live-ink-firmware-pen)

- Four fixes (#58, #59, #60, #67) only reordered `setStrokeStyle/Width/Color` and none held on the device. Our firmware
  calls are a superset of v0.13.2's, so this build tells the hypotheses apart instead of guessing a fifth order
  (research: `.scratchpad/boox-fixes/research-red-ink.md` in the workbench, sections 4 to 6).
- What the build adds:
  - Diagnostics "Firmware API dump" (`FirmwareBrushApi.dump`, also logged as `InkDiag firmware API: ...`): device
    class, `getColorType`, `getPenState`, every declared method of `android.onyx.ViewUpdateHelper`, the
    `android.onyx.handwriting.PenButton` int constants, and set/null/absent of the device class's
    `sMethodSetStroke{Style,Color,Width,Parameters}`, `sMethod{Set,Clear}ScreenHandWritingRegionConfig`, `sPenButtonClass`.
  - InkDiag `pen down shortcut=<bool> stroke=<n>` on every `onBeginRawDrawing`, n counting pen-downs since the last brush
    (logging only, no SDK call inside a stroke).
  - Toggle A "Vendor resume order" (`InkExperiments.vendorResumeOrder`, H2): enable is `setBrushRawDrawingEnabled(true)`,
    brush, `setStrokeParameters` (the firmware's own params of the style written back), `setRawDrawingEnabled(true)`,
    `forceSetRawDrawingEnabled(true)`; on colour screens (`getColorType() > 0`) it runs 500 ms later
    (`NoteConstant.COLOR_DEVICE_PEN_RESUME_DELAY_TIME_MS`) and only if no disable, close or newer enable came in
    between and raw drawing may still be on. A tool or colour change while live adds `setRawDrawingRenderEnabled`
    false then true.
  - Toggle B "Region pen config" (`regionPenConfig`, H1): after every open, enable and brush change,
    `EpdController.setScreenHandWritingRegionConfig(limit in screen pixels, StrokeConfig per PenButton name from the
    dump)` with the current style, width and colour; `clearScreenHandWritingRegionConfig` on close and stop (only if
    one was set). Failures are logged under InkDiag, never thrown; no pen buttons found logs a skip.
  - Review fixes (2026-11-08): the delayed vendor enable re-checks every 100 ms while the pen is down (no SDK call mid-stroke);
  `refresh` re-enables without the 500 ms delay (only resumes are delayed); turning toggle B off clears the region config at
  once (`OnyxStrokeInput.experimentsChanged`, wired through `InkExperimentPrefs.onChange`); `FirmwareBrushApi.read` also
  catches `LinkageError`.
- Both toggles persist (`InkExperimentPrefs`, SharedPreferences `ink_experiments`), take effect at the next open,
    enable or brush change, and default from one constant, `InkExperiments.DEFAULT_ON` (true; flip it for a build
    that ships them off). `OnyxStrokeInput`'s own default env is `InkExperiments.OFF` so a bare construction (tests)
    keeps the v0.18.0 sequence; the app wires the prefs.
- Reading the device result:
  - Dump shows `sMethodSetStrokeColor=null` or `absent`: H4 (colour never lands); the region config methods null too
    means toggle B cannot help either.
  - Red with both toggles off, right with B on (A off): H1, the firmware reloads a stored per-pen-button config; make
    B the default brush path.
  - Right with A on (B off): H2, vendor order and timing; make A the default.
  - `pen down shortcut=true` on the red strokes: H5, the side-button shortcut pen.
  - Red with every combination and v0.13.2 from Releases also red: H3, device state (the BOOX Notes pen); still try B.
- The tests (`OnyxInkExperimentsTest`, `FirmwareBrushApiTest` dump cases, `InkExperimentPrefsTest`,
  `DiagnosticsPanelTest`) prove the model of the calls, not the firmware: toggle A order and its colour delay,
  toggle B carrying the current brush and limit and clearing on close, no SDK call between pen-down and pen-up with
  both on, toggles off reproducing the v0.18.0 sequence call for call, and the dump format. Only the device answers
  which hypothesis holds.

## 2026-11-07 The left strip never hides on its own (fix/live-ink-toolbar, 0.17.0 report)

- User, verbatim: "I liked that left toolbar stayed open initially. Keep it like this. Never autohide". This
  supersedes part of Q18 A (2026-11-05 dismiss mode) and the board wave A stroke auto-hide.
- The stroke auto-hide is gone (ShellState tick, `BoardChrome.onStrokeCommitted`/`autoHideHandler`, the AppShell
  timer): it only ever hid the strip in practice, since an open popup or panel holds raw drawing (no strokes).
- Dismiss mode is now a popup or the panel open (`OverlayControl.anyDismissable`), not the strip. A tap or pen down on
  the board closes the popup and the panel in one burst and leaves the strip. With only the strip open, raw drawing
  runs with the strip rect excluded (the v0.13.2 setup), so writing with the strip open draws normally. The strip's
  hide chevron still hides it (a user action, not an auto-hide).
- Tests: the auto-hide tests in `AppShellTest`, `ShellStateTest`, `BoardChromeWiringTest` and
  `MainActivityShellTest.chrome_is_wired_so_auto_hide_works` pinned the removed behaviour; the last two now assert
  the strip stays. `BoardDismissModeTest` used the open strip as its dismiss overlay and now opens the panel. Closing
  only the panel relayouts nothing else in the Robolectric window, so those tests ask for a frame (`drawFrames`)
  before counting the refresher's hold.

## 2026-11-07 Live ink brush right after every open (fix/live-ink-toolbar, 0.17.0 report)

- Device report on v0.17.0: live ink red and ballpoint-like until a full redraw, which shows the right tool and colour.
- Root cause: `openRawDrawing` resets the firmware style to 0, starts the reader and puts the firmware pen in START,
  and clears the SDK's cached render flag, so `setRawDrawingEnabled(false)` right after it is a no-op (javap of
  TouchHelper/SFTouchRender 1.5.8). The brush was applied only on enable, so every open under an outstanding hold
  (first start at launch with the strip up, a rect change while an overlay is open) left the firmware pen live with
  the reset style and the firmware colour until the hold ended. The app still read the strokes with the selected
  tool and colour, hence the right redraw. v0.14.3..v0.17.0 adds no open or enable path (`ink/` unchanged; the text
  layer swaps are plain hold/release pairs); the exposure was the dismiss hold of the always-open strip, under which
  the user wrote (pen events do not reach the window while the firmware owns the pen, so the dismiss never fired)
  until the stroke auto-hide released it.
- Fix: `OnyxStrokeInput.open` applies the brush right after every open (first start and reopen), on top of the
  brush on every enable. No new firmware call kinds (no force-disable): the brush calls are the ones v0.13.2 made.
- Tests: `OnyxBrushAfterOpenTest` reopen and first start under an outstanding hold (the SDK-like fake keeps the pen
  live after an open). `OnyxRawSessionTest` golden orders now expect `brush` right after `open`: they pinned
  "no brush after the open", which is the bug.

## 2026-11-07 Image regions, inline images, share bundle (T-46 P6, Tasks 6.1-6.6, v0.18.0)

- Tool: `Tool.IMAGE_REGION` ("image_region" in strokes.json) is a box shape like the rectangle (`Tool.isBox`). The
  strip has separate rectangle and arrow buttons (no shape picker existed), so the shape popup gained a row of the
  three shapes above the colour grid and the strip's box button stands for rectangle or image region, showing the
  one in use. Pending regions are dashed dark grey (0xFF555555) whatever the pen colour.
- Region members: visible strokes whose bbox centre lies in the region rect, the region stroke included
  (`ImageRegions`). They are the image op's `strokes` and never segmented, also after the op was applied.
- Send: one image op per region no pending op carries; `region.<part>.<k>.png` crops the text layer and visible ink
  inside the rect (no grid, no region frames) at canvas resolution, 0.85x shrink under `max_png_bytes`, floor 16 px.
  `after_line` is the base-version run ending nearest above the rect top. The cap of 4 new regions per page is a
  client constant (`SendUseCase.MAX_REGIONS_PER_PAGE`, the connector default) until `/config` exposes it.
- Why no op without a stored spec version: the connector requires `base_version` for any op (422), and a first send
  has none. Then the region and its ink go as plain drawing ink (still not segmented) and the frame stays dashed;
  it is sent as an op on a later Send once a version exists: an unsent region then counts as a page change
  (`selectPagesForSend(regionsSendable)`), and the 4-region cap is only enforced when ops will be built.
- Applied state: `Page.appliedRegions`. Applied frames leave `visibleStrokes` (not drawn by the stroke renderer, not
  in strokes.json) and the text layer draws them as a 2 px solid frame with a 20 px "image" label.
  Done there (not in `CanvasStrokeRenderer`, as the plan said) so the swap rides the text layer's pen-idle hold and
  the renderer interface stays per-stroke. Rejected image ops leave the region pending again. Image-only markdown
  lines are not placed as text runs. `appliedRegions` maps the region stroke id to its region id; every placement
  (revert or newer version) drops the entries whose `images/<region id>.png` the shown markdown no longer references
  (OQ2 A, 2026-11-07 review fix): the frame is dashed again, the page counts as changed and Send re-sends the region.
  The eraser hits `visibleStrokes` only (2026-11-07 review fix): applied frames and consumed strokes cannot be erased
  from the board, so an applied region keeps its ink excluded from segments; ink inside it stays erasable. The export
  text layer (`TextLayer.renderForExport`) has no pending ticks and no region frames, so crops and page PNGs are clean.
  A row shift (and an overflow move) treats a region and its members (bbox centre inside the rect) as one unit,
  decided by the region's own centre (`shiftCentres`, 2026-11-07 review fix): a shift line inside a region no longer
  splits frame from ink. `UndoStack.shiftRows` decides each stroke id once (page first, then the history entries) and
  applies that decision to the page and every entry, so undo/redo restore exact positions even when the step that drew
  the region is undone.
- Rebase onto P5a/P5b (2026-11-07): image ops ride the edit-op pipeline (`PendingOp` stamped with the base version,
  per-upload release, drop on failure). The ink of every region (`ImageRegions.excluded`) is excluded from both the
  `Segmenter` and the `AnnotationClassifier` (`classify(exclude = ...)`), so region ink is never a strike, replace or
  insert; classified ops come first in the layout, then the image ops. A rejected image op does not add its strokes to
  `rejectedStrokes` (only rejected edit ops do): the region is simply pending again and re-sent.
- Spec images: `SpecImageCache` keeps `files/spec-images/<conversation server id>/<region id>.png`, written once
  (region images never change), removed with the conversation (delete and server-side removal). Only PNG bodies (signature checked) are kept, written through a unique temp file. Unpair deletes all of it and
  the share zips. Ids are checked
  against `[A-Za-z0-9-]+` before any path is built. Documents prefetches a version's images (concurrently) before paginating. A
  freeze made while any referenced image is missing (offline, images route failing) is provisional: it carries an
  `images-missing` marker and, like a failed diagram, is discarded and paginated anew on the next open while no stroke
  and no sent mark exist (2026-11-07 review fix). After ink or send it stays, as ink is in page coordinates.
- Share bundle: `cache/share/spec-v<N>.zip` through FileProvider `<applicationId>.share` (`res/xml/file_paths.xml`),
  `spec-v<N>.md` unchanged, `images/<id>.png`, `MISSING.txt` for images not available. A new bundle deletes the earlier
  files of the dir (spec content must not linger; one share is live at a time, so no per-conversation names).
- Test fixtures changed: `SpecImageCacheTest` and `DocumentsFlowTest` png bodies got the full 8-byte PNG signature (the cache now checks it).
- Test changed: `StrokeTest.shape_tools_are_shapes_and_pens_are_not` expected exactly rectangle and arrow as shapes;
  the image region is a third shape. Golden `board_tool_icons.png` re-recorded for the new glyph; new goldens
  `region_frame_pending`, `region_frame_applied`, `document_with_inline_image`.

## 2026-11-07 Handwritten edit ops (T-46 P5a, Tasks 5.1-5.4, v0.16.0)

- Word boxes (`WordBoxes`) measure each word with the text layer's own `TextMeasure`; `WordBoxes.rows` is now the one
  source of a run's wrapped rows for both `TextLayer` and the classifier, so a hit box never drifts from the drawing.
- `TextRun.source` keeps the markdown source line (set by `RunPlacer`): an op's quote must equal the base version's
  source text at `[from, to)` (422 `stale_target`), and the display text has no markup. It defaults to `display`
  (plain lines in tests). Target `to` is the source offset after the last display char, so `**buy**` quotes `buy`.
- Classifier choices the plan left open (Global Constraints thresholds otherwise): a strike also needs width >= 0.3
  cell (no dots) and at least half its height in the text band; a scribble stays within the band +-10 percent and
  turns back at least 3 times (x and y reversals, 0.15 band hysteresis). Strikes over overlapping words of one run are
  one delete. A replace group overlapping two runs on a row goes to the one with the most covered word width.
- Inserts (`ToolInsertMarks`): the box counts as in the margin when >= 80 percent of its width is right of the margin
  line (the P5b loop rule); it needs pen strokes centred inside; the first snapped arrow wins. A tip on a text band
  with words inserts inline at the nearest boundary; anywhere else after the run whose last row is above the tip's row.
  No run above: no insert, the marks stay ink. `InsertMark` uses the model `RectF`/`Vec` (P5b uses them too), and
  `InsertMarkFinder.find` gets the candidate strokes (not consumed, not pending) besides the page.
- Send classifies only the runs whose line is in the base layout (the same filter as the runs), so ops cannot exist
  without `base_version`; `SendOutcome.NoBaseVersion` guards it anyway. Without a `measure` (plain tests) nothing is
  classified. Pending ops' strokes are skipped by the classifier and the `Segmenter`; the pending poll also waits on
  pending ops (an edit-only upload has no segments).
- `RunPlacer` needed no op logic beyond P4: applied ops consume their strokes, rejected ones only leave the pending
  list, an op line with a new id is placed after the previous line (insert after_line shifts rows below), a replaced or
  partly deleted line is `kept` and updated in place. The tests pin it.
- Strike preview: computed from page state (`StrikePreview.rects`, visible strokes only, so undo, erase, an applied
  delete and a stale base all agree), shown only through `StrikePreviewSync` at pen idle under one hold, or by
  `NotebookSync` inside its version swap; `BoardViewModel.strikeOverlay` is keyed by page index and never saved. The
  exported PNG has no overlay (the translator sees the strike itself). A rejected strike keeps its grey preview while
  its ink is on the page (it is still a strike the user may re-send or erase).
- Notes of rejected ops go to the Documents feedback strip as local `FeedbackEntity` rows with negative ids:
  `maxId` (the connector cursor) ignores them and `observe` orders by `createdAt` then id, so the note is the latest.

## 2026-11-07 P4 review fixes (T-46)

- `base_version` (connector 2.1.0 `_check_base`): it must be the latest stored version, and EVERY run line id must
  be in that version's layout, else 422 "line id Lx is not in the base version". Send now uses the latest stored
  version and sends only runs whose line id its layout has (none when it has no layout). Omitting base_version was
  rejected: without it the translator gets no base, so no line can be `kept` and every run's line would be replaced.
  Text follow-ups carry the previous layout's line ids (`_carried_layout`), so after the first notebook version every
  version normally has a layout; the filter covers a revert to a layout-less version and a board not yet synced.
- Revert replay: the connector answers a revert replayed after success with 409 `stale_base`. On that answer the
  outbox fetches version base + 1; when its layout says `reverted_from` = the target (or, without layouts, it has the
  target's markdown and sketch ids) the item is accepted.
- Row shifts are not undo steps any more (`Command.ShiftRows` removed): `UndoStack.shiftRows` moves the page's ink
  and shifts every history entry the same way, so Undo takes back the user's last stroke and stays aligned with the
  text. Rows sent to an overflow page drop that page's history (`UndoStack.forget`).
- Column floor: a run's column is at least 6 cells (`RunPlacer.MIN_COLUMN_CELLS`); near the margin x0 moves left.
  The column is stored on the run (`TextRun.column`, 0 = to the margin) so `TextLayer` wraps at the placer's offsets.
- A second overflow of the same page in one placement pushes the overflow page's content down first: those rows came
  before the first batch. A revert ignores its lines' segment origins. A first origin-less line goes just above the
  next shown line's run or segment (the free row above, else its row pushing it down) instead of page 0 row 0.
- Board races: `bindConversation` loads first, then saves the shown notebook until no change landed during the save,
  then swaps without suspending (a Send marking pages or a stroke during the bind is no longer lost). A failed upload
  of a conversation the board does not show edits that conversation's notebook file. A deleted or pruned bound
  conversation resets the board without saving. `NotebookSync` places a version only while the board shows its
  conversation (or the unbound notebook it is about to adopt), checked in the same main step as the placement.
- The pending-segments poll backs off 5 s to 1 min and stops after about 30 min; a new upload restarts it.

## 2026-11-07 Notebook text rows on the board (T-46 P4, Tasks 4.4-4.8, v0.15.0)

- Placement (`RunPlacer`, pure Kotlin): lines in source order; a line with a run keeps it (refit), a segment line
  lands at its first segment's page, row and x0 and consumes its strokes, any other line (origin null OQ6 A, a kept
  line with no run, an op line) gets a new row right after the previous line's run at the text column start. Runs
  whose line is gone are removed first; their ink stays hidden, except on a revert (`reverted_from`), where it
  becomes visible ink again (OQ2 A). Fenced blocks, table rows and blank lines are never placed; their segments'
  strokes stay ink. The rest of an answered upload's pending segments is dropped (ink kept).
- Which upload a version answers: segment ids repeat across uploads (`p<part>-r<row>-<n>`, part = upload part
  index), so the client id holding most of the version's segment ids wins, ties to the oldest in page order.
- Fit: scale 1, else shrink to fit down to 0.85, else wrap at word boundaries at 0.85. The column ends at the margin
  or at the next run or pending segment on the same row. Rows the line needs that hold runs, visible ink or pending
  segments shift down by whole rows with everything below (all strokes by bounding-box centre, consumed ones too, so
  a later un-hide lands right). The ink move does not mark the page changed and is not an undo step (see the review
  fixes above); text runs are not in the undo history (text never lives in the stroke list). Rows pushed past the
  last row go to a new page inserted right after (OQ4 A); foot ink moves only when this placement shifted rows.
- Text layer: `TextLayer` draws runs into a transparent 1404 x 1872 ARGB bitmap (sans 40 px x scale, headings bold,
  colour spans, baseline 11 px above the row line, grey tick at x 23 for rows with pending segments, 50 percent grey
  overlay rects for P5). `TextLayerCache` keeps only the selected page's bitmap and returns a NEW instance on every
  change instead of `inBitmap` reuse: the board redraws on a new instance (identity) and must never show a layer
  half drawn into the bitmap it is displaying. Render time: 5 ms per full page in Robolectric (native graphics);
  the device number is a checklist item.
- Swap (Review Focus 3): `NotebookSync` places on the main thread (cheap), renders off it, then under ONE
  `holdUi`/`releaseUi` pair at pen idle (no stroke in progress and 700 ms since the last pen event, the SDK's
  pen-up refresh time) commits the notebook and calls `BoardSurfaceView.swapText` (sync committed, draw the dirty
  rows, GU refresh). The next `show()` sees the same strokes and layer and does nothing, so there is no second
  frame or raw toggle. If the board changed during the render (a stroke), it places again. Not `ChromeHolds`: that
  waits for overlay rect reports, which a text swap never makes; it uses the same input hold underneath.
- Specs reach the board: `AppContainer.runNotebookSync` (MainActivity, while paired) observes the stored versions
  of the board's conversation and applies each new one with a layout in order; versions without a layout (typed
  feedback, document notes) leave the board. While segments are pending the conversations are refreshed every 5 s
  (nothing else syncs specs when the Documents tab is closed).
- Send: a layout part is written only for a page with new handwriting rows or text runs (plain drawing pages need
  none, and the existing payload tests stay accurate). Every board page is exported whole as text layer plus
  visible ink (spec 5.1) so the PNG matches the layout's page px; the cropped `PngExporter` path remains only
  when no text layer function is given (tests). `base_version` is the latest STORED spec version of the
  conversation (the plan's "newest run version first" would answer 409 after any newer version), with only the runs
  its layout knows (see the review fixes above). Page selection counts visible strokes only.
- Errors (Q17 A, plan wording): 409 `stale_base` and `busy` fail the item with "The document changed; check the
  latest version" / "A translation is running; try again when it finishes" (`OutboxReasons`). A failed upload drops
  its pending segments and marks its pages changed again, so nothing is lost and Send re-sends the ink;
  `stale_base` also refreshes the conversations so the board re-renders first.
- One notebook per conversation (OQ1 A): `notebook-<localId>.json` via `NotebookFiles`; `notebook.json` is only the
  board drawn before any conversation exists, adopted by the first selected one. The selection drives the binding
  (`runNotebookSync`), the undo history restarts on a switch, a deleted or pruned conversation deletes its notebook.
- Revert (Q15 B; OQ7 decided A on 2026-11-07, the Documents version picker with a confirm dialog, as built):
  "Revert to vN" next to the version picker on an older version, confirm dialog, outbox kind `REVERT`
  (`revert.json`, base = latest), accepted revert refreshes the specs and the board re-renders.
- The instrumented `NotebookSendTest` needs `mockwebserver` as an androidTest dependency (same artifact as the unit
  tests, so the verification metadata did not change). It talks to 127.0.0.1 (the only cleartext host allowed).
- `openapi.json` re-copied (`mise run api:refresh`) from the committed connector branch `feat/notebook-annotations`
  (openapi.json at 19122df): identical to the copy from P4a, so the earlier refresh note is resolved; ContractTest green.

## 2026-11-07 Notebook text rows, model and wire foundation (T-46 P4, Tasks 4.1-4.3)

- Model: `Page` gains `text`, `consumed`, `pendingSegments`, `pendingOps`; `Notebook` gains
  `conversationLocalId` (OQ1 A: one notebook per conversation, bound in Task 4.7). All have defaults, so a
  notebook saved by v0.14.x decodes through normal defaulting (no migration code).
- `SourceMap`/`stripWithColours`: `displayToSource` has `display.length + 1` entries. `[[colour]]` opens a
  span reusing `COLOUR_MARKER` of `MarkerSpan.kt` (made internal), `[[/]]` closes it, an unclosed marker
  colours to the end of the line (no label text like Documents' `[red]`: the board colours the text itself).
  `*`, backticks and `_` at word edges are dropped; a leading `* ` bullet, `- ` and `1. ` stay as typed text;
  `snake_case` keeps its underscore; other `[[...]]` markers stay plain text.
- `LayoutCodec` lives in `:core:model` (it needs `Page`) and builds the JSON by hand like `StrokesCodec`; the
  DTOs in `:core:api` (`PageLayoutDto` and friends) mirror it and `ContractTest` decodes the codec output
  strictly through `PageLayoutDto`, so the two cannot drift. No `kind` on the wire (Q2: every page is a
  notebook page). Stroke ids become indices into the visible-stroke list; an id that is not visible throws.
- `StrokesCodec` and `PngExporter` now write `page.visibleStrokes`: consumed strokes are never exported, so the
  indices in the layout match `strokes.json` (the layout and strokes parts must come from the same page state).
- Bug found by Review Focus 5: `UndoStack.withStrokes` rebuilt the page as `Page(strokes, true)`, dropping
  `text`, `consumed` and the pending lists on every undo/redo/add/erase. It now uses `copy`. Test:
  `SegmenterTest.undo_after_apply_keeps_consumed_strokes_out_of_segments`.
- Errors: `StaleBase`, `OpReplayed`, `Busy` (409) and `StaleTarget(detail)` (422 `stale_target`); the outbox
  treats all four as permanent failures until Task 4.6 gives the user the re-render and re-send path.
- `openapi.json` was refreshed from the uncommitted working tree of the connector P2 branch (the revert route is
  not committed there yet); refresh again once P2 merges and re-run `ContractTest`.
- `Segmenter`: row = row of the vertical centre of the stroke's bounding box; strokes with the centre in the
  margin (any tool) are neither segments nor drawing (they are P5 marks); shapes outside the margin, strokes
  taller than 1.5 rows and strokes centred in the top margin or below the last row are `drawing`. A gap of
  exactly two cells (106 px) does not split, more does.

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

## 2026-11-06 Grid contrast, vanishing grid lines and red ballpoint live ink (boox-grid-ink-fixes)

Device report on v0.14.0: (1) grid lines and dots too faint but usable, (2) the live stroke is red and
ballpoint-like until the redraw, which is right, (3) grid lines vanish after some redraws and come back
later, dots stay. Root-cause analysis: `.scratchpad/boox-grid-ink-fixes/root-cause.md` in the workbench.

### Grid (issues 1 and 3)

- Confirmed: lines were 1 px non-AA at 0xD3 (17 percent ink), dots 0xBD; neither is one of the 16 e-ink
  grey levels (multiples of 0x11).
- Hypothesis (strong, not device-measured) for issue 3: the idle `syncCommitted` frame is posted while raw
  drawing is live, so the panel shows it with the fast handwriting waveform, which dithers greys. Under a
  4x4 ordered dither a 1 px 0xD3 row gets zero black pixels when its y mod 4 is 0 or 2, while 3x3 px dots
  always keep some: "lines vanish, dots stay"; the next GC/GU frame brings the lines back. A binary
  threshold mode is ruled out by the report itself (0xBD dots would vanish too).
- Fix: lines 0x999999 (level 9) 2 px wide on an integer y (rows y-1 and y), margin line likewise; dots
  0x777777 (level 7). Every Bayer row has a threshold that keeps 0x99, so every line keeps pixels in every
  4 px span. `BoardGridDitherTest` renders the real grid with native graphics and dithers it. Rejected
  alternative: 1 px lines at 0x77 (survives a binary cut too, but reads harder); the device check decides.
- Clip edge: `BoardGrid.draw` widens the page range by the line width each side, so a dirty rect starting
  or ending on a line's pixel row redraws that line (float error used to drop it, and the white fill then
  erased it inside the rect).
- Tests changed: `BoardGridTest.colours_and_widths` and `row_lines_run_over_the_text_column_only_at_every_row_pitch`
  pinned the old colours, 1 px width and half-pixel centre, which the fix replaces on purpose.

### Live ink red and ballpoint-like (issue 2)

- Root cause NOT confirmed. Bounded SDK check (javap of onyxsdk-pen 1.5.8, onyxsdk-device 1.3.6,
  onyxsdk-baselite 1.1.2) of the calls added since charcoal last looked right live (2026-11-04 boox-ink-match
  entry): `ResManager.init` (#53) only stores the app context; `Epd.handwritingMode`/`resetUpdateMode` (#51)
  call `ViewUpdateHelper.setViewDefaultUpdateMode/resetViewUpdateMode`, a view refresh mode with no stroke
  fields, and run only on the touch-fallback path; `Device.getStrokeParameters` (#51) is a getter. None of them
  touches the firmware stroke style or colour on the Java side. Not checkable without the device: native side
  effects of the offline SDK pens, which really run only since #53 (before that every SDK pen threw).
- Confirmed from bytecode: `openRawDrawing` (and `closeRawDrawing`) reset the firmware style to 0 (pencil,
  the ballpoint look) via `SFTouchRender.resetStrokeStyle`, colour and width are kept; `setRawDrawingEnabled(false)`
  right after an open is a no-op (cached flag), so the firmware pen stayed in START; the vendor demo calls
  `forceSetRawDrawingEnabled(false)` there. #57 added two re-opens per toolbox use, which makes the style reset
  frequent. Style and colour reach the firmware only through `SDMDevice`-style static `Method` fields called
  with `ReflectUtil.invokeMethodSafely`, which swallows errors.
- Hypotheses: H1 the style/colour calls do not land (null fields), the live ink uses the firmware's global
  pen (red from another app) and our open's style 0; H2 the firmware reads the colour int other than ARGB
  (black 0xFF000000 read as red); H3 an asynchronous reset after an open leaves style 0 (explains ballpoint,
  not red).
- Fix (covers H3 and the demo divergence, harmless for H1/H2): the brush is applied after every open
  (first start too, where it used to precede the open), an open that stays disabled is force-disabled, and
  the first pen-down after an open re-applies the brush once (`brushDirty`). Evidence: InkDiag lines
  `raw open`, `raw enable`, `raw force-disabled after open`, `setBrush style= width= color=#AARRGGBB tool=`
  and the first-pen-down re-apply; Diagnostics "Firmware brush API: ok / missing (<device class>: <methods>)"
  reads the device class's `sMethodSetStrokeStyle/Color` static fields by reflection (`FirmwareBrushApi`).
- Device test that decides: checklist "Live ink in each colour". "missing" or the BOOX Notes blue-fountain
  carry-over is H1 (fix: make the firmware calls land, check the hidden-API exemption runs before the first
  `Device.currentDevice()`); all colours red/magenta is H2 (convert ARGB in `TouchHelperRawDrawing.setBrush`
  only); right colour with ballpoint style is H3 (send the InkDiag log around a dismiss).
- Tests changed: `OnyxRawSessionTest.changed_rects_are_applied_and_raw_drawing_is_reopened_as_in_the_vendor_demo`
  and `first_start_sets_rects_brush_opens_...` (renamed `first_start_sets_rects_opens_applies_the_brush_...`)
  pinned the brush before the open, which is the order this fix changes.

## 2026-11-06 Live ink brush re-applied at every stroke boundary (fix/ink-style-every-stroke)

- Device report on v0.14.1: after a redraw (toolbox open and close) the first line has the selected tool and
  colour, every later line is red and ballpoint-like again until the next redraw. The lines/dots grid fix was
  confirmed and is untouched.
- Root cause: the v0.14.1 repair was one-shot. `OnyxStrokeInput` re-applied the brush only after an open and on
  the first pen-down after it (`brushDirty`, cleared by that pen-down), on the assumption that only an open resets
  the firmware brush. The device also resets style and colour after every stroke, so stroke 2..N ran on the
  firmware default until the next open re-applied it.
- Where the per-stroke reset comes from is not in the SDK bytecode (javap of onyxsdk-pen 1.5.8, -device 1.3.6):
  the only Java callers of `EpdController.setStrokeStyle/setStrokeColor` are our `TouchHelper.setStroke*` calls
  (via `SFTouchRender`/`EpdPenManager`) and `SFTouchRender.resetStrokeStyle` on open/close; `RawInputReader` sends
  nothing per stroke; `setRawDrawingEnabled` only adds `resetPenDefaultRawDrawing` (brush/eraser raw-drawing
  flags); libneopen_jni (offline pens) is pure geometry. So the reset is native or firmware side (the reader's
  `PenManager::resetStrokeWidth` symbol, or the idle frame posted while scribbling), invisible to the app.
- Fix: since the reset cannot be observed, the brush is treated as volatile and re-applied at every point it can
  be lost while raw drawing is live: open, enable (as before), and every stroke boundary, pen-down, pen-up, end
  of a raw erase and the pen idle sync (after `syncCommitted` posted its frame). Nothing is applied while raw
  drawing is off (eraser, shape tool, hold, pause). Why every boundary and not only pen-down: pen-down runs
  after the reader thread posts to main, so the first points may already be drawn; pen-up and idle put the
  brush back before the next stroke, pen-down covers a reset that lands in between. The cost is three
  firmware calls per boundary. InkDiag `setBrush ...` lines carry `at=<boundary>`.
- Tests: `OnyxBrushAfterOpenTest` fakes a firmware that resets style, width and colour after each stroke and
  asserts strokes 2..5 keep the selected brush. `the_first_pen_down_after_an_open_reapplies_the_brush_once`
  asserted that a second pen-down does NOT re-apply, which pinned the bug; it is now
  `the_first_pen_down_after_an_open_reapplies_the_brush` without that assertion.

## 2026-11-06 Live ink regression since v0.14.0: dismiss hold, v0.13.2 brush, ink bounds (fix/ink-regression-since-0.14)

Device facts: v0.13.2 live ink was right for every tool and colour. v0.14.0 (#57) made it red and
ballpoint-like until a redraw; #58 and #59 made it worse (brush/charcoal/charcoal_v2/marker strokes after the
first only appear after a full redraw, ballpoint lines trace from the pen-down point to the upper-right
corner, InkDiag ink right edge Int.MIN_VALUE). Evidence: `.scratchpad/boox-ink-brush-regression/`
(bisect-research.md, sdk-research.md, bounds-research.md) in the workbench.

### Root causes

1. #57 (dismiss mode) sent the firmware a state it never saw before: an exclude rect equal to the limit rect
   (`boardExcluded`, empty drawable region) whenever the dock or panel was up, which is the default at launch,
   and it restarted raw drawing (setLimitRect + openRawDrawing) on every dismissMode/swallow flip, one of them
   while the pen was still on the board. `OnyxStrokeInput`/`RawDrawing` were byte-identical in v0.13.2 and
   v0.14.0, so this is the only firmware-facing delta. Why exactly red is not visible in the SDK (inferred).
2. #59 wrote the brush from stroke callbacks. `onBeginRawDrawing` comes from the reader thread and was posted
   to main, so the write always landed after the system renderer had started the stroke: a mid-stroke style
   change fits the lost NEO-pen strokes and the corner trace. The vendor demo never sets the brush per stroke.
3. `inkBounds` seeded a RectF with inverted extremes and used `union(x, y)`, whose `else if` never moves
   right/bottom for a stroke drawn right to left or upwards, or a dot: right/bottom stayed -Float.MAX, so
   `defer` dropped the stroke from `pendingDirty`, `paint` filtered it out and `CachedPenRenderer` declined it.
   Present since #41; tests only used strokes whose first point was the minimum.

### What changed

- Dismiss mode keeps raw drawing off through the existing hold path, the one v0.13.2 ran for every overlay:
  `HoldWhile(input, dismissMode || swallowing)` in `BoardView` (one `holdUi`, released exactly once when both
  end, the input changes or the board leaves composition). The board rect is never an exclude and flips no
  longer restart the input. `startInput` does nothing during a swallowed gesture; the lift starts the input
  with the current rects (still under the hold, released right after), so no open runs under the pen. A
  surface destroyed mid-gesture ends the swallow, so its hold cannot leak. Tap-to-dismiss and the grid are
  unchanged. Trade-off: while the dock is up the screen is not frozen (the board takes no ink then anyway).
- `OnyxStrokeInput`/`RawDrawing`: back to v0.13.2 semantics. Reverted: the brush writes at pen-down, pen-up,
  raw erase end and pen idle (#59); the brush right after every open (#58; redundant, the enable that always
  follows applies it, and v0.13.2 applied it before the first open); `forceOff`/`RawDrawing.forceDisable`
  (#58; vendor demo behaviour that v0.13.2 never ran and that was never verified on the device; the plain
  disable after an open is a no-op in the SDK, which v0.13.2 tolerated). Kept: InkDiag `raw open`,
  `raw enable`, `setBrush ...` lines (without `at=`), `FirmwareBrushApi` and its Diagnostics line.
- `inkBounds` uses min/max over the outline points.

### Tests changed and why

- `BoardDismissModeTest.open_strip_excludes_the_whole_board_from_raw_drawing` and
  `excludes_keep_the_board_until_the_lift_then_drop_to_the_indicator_only` pinned the whole-board exclude and
  the restarts under the pen; replaced by `open_strip_holds_raw_drawing_without_excluding_the_board` and
  `a_swallowed_gesture_keeps_raw_drawing_held_and_not_reopened_until_the_lift`. New: hold balance after dismiss,
  on disposal, on input change and on a surface destroyed mid-gesture.
- `BoardChromeWiringTest`: `hide_updates_exclude_while_held` and `show_applies_toolbar_rect_before_release`
  expected the board rect in the excludes (now: never), and the latter expected raw drawing on with the strip
  open (now: held by dismiss mode). The hold-balance assertions (`releases() == holds()`, `enabledAt`, the gap
  replay) counted from a cleared log and assumed no hold outstanding while an overlay is open; they now count
  the hold outstanding at the clear (`FreezingInput.heldAtClear`) and expect exactly the dismiss mode hold
  while the strip or panel stays open (`dismissHold()`, `overlaysSettledAt()`).
- `OnyxBrushAfterOpenTest`: removed `the_first_pen_down_after_an_open_reapplies_the_brush`,
  `every_stroke_carries_the_selected_brush_when_the_firmware_resets_it_after_each_stroke` and
  `the_brush_is_repaired_at_pen_up_and_after_the_idle_sync_before_the_next_stroke` (they pinned the #59
  per-stroke writes and a per-stroke firmware reset that was never observed); the first-start and held-reopen
  cases asserted the brush and a paused pen right after the open (#58 order and force-disable) and now assert
  the brush once raw drawing is enabled; `lift_after_dismiss_reopens_with_the_brush` modelled the whole-board
  exclude and is now `reopen_for_new_rects_while_live_draws_with_the_selected_brush` (a plain rect change). New: `no_brush_write_at_any_stroke_boundary`.
- `OnyxRawSessionTest`: the reopen and first-start call orders are back to their v0.13.2 form (no brush right
  after the open).
- New bounds tests: `InkBoundsTest` (right to left, bottom to top, dot, start at the rightmost and lowest point,
  every tool), `CachedPenRendererTest.a_right_to_left_stroke_is_rendered_by_the_pen_not_declined`,
  `DeferredStrokeSyncTest` (syncCommitted draws such strokes). All failed on the old code under Robolectric.

### Not verified

On the device. Checks: fresh launch with the dock up, pen-tap the board to dismiss, then 5 lines per tool in
black and in blue; all must be right live. If stroke 2..N are still red and ballpoint-like, the next step is
the per-region firmware config (`EpdController.setScreenHandWritingRegionConfig`), see sdk-research.md (c).
- Follow-up: the hold is a no-op right after an open, so the firmware pen can stay live under a swallowed gesture, and the SDK delivers the raw stroke at or after pen-up, often after the lift ended the swallow. The board drops Committed and Erase events while `swallowing` (unless a stroke was live at the swallowing down, `swallowedLive`), and afterwards those whose first point lies within touch slop of the swallowed down point (`swallowedAt`, view pixels); a Committed consumes it, the next unswallowed down clears it. No timeouts.

## 2026-11-07 P5a review fixes

- Rejected op strokes are `Page.rejectedStrokes` (persisted): plain ink, no strike preview, not classified or
  segmented again. Why: the grey preview over words implied a delete the text did not have, and every send re-sent it.
- `TextMeasure.width(text, scale, bold)` (default: regular width); headings are measured bold in `WordBoxes`, wrap and
  fit, as `TextLayer` draws them. Strike merges are transitive. Feedback is ordered by `julianday(createdAt)`.
- Not changed: P4 notebooks saved without `TextRun.source` (review 9) - P4 has not shipped.

## 2026-11-07 Freehand loop and arrow recognition (T-46 P5b, Task 5.5)

- `FreehandShapes` (`:core:model`, `model/notebook`, pure Kotlin) recognises a pen-drawn closed loop and a
  pen-drawn arrow; the wiring into the insert classifier is Task 5.6. It reuses `Vec` and `RectF`
  instead of the plan's `PointF2` and `RectF4`, which do not exist (no Task 4.1 type needed).
- Loop: closed when the end is within 1 cell of the start, or the last 15 percent of the path crosses an
  earlier segment (the polygon is then the part after the crossing, so an overshoot does not count). Area
  >= (1.5 cells)^2, bounds >= 1 cell each way (a letter O, D or 0 is about 0.6 cell), and the path length at
  most 1.8x the bounds perimeter (a wiggly word that happens to self-cross is not a loop). Shape-tool
  strokes never qualify.
- Arrow shaft: chord >= 2 cells, mean chord deviation < 0.25 chord, path <= 1.5x chord, and the first fifth
  of the path within 45 degrees of the chord. The last check is not in the spec: without it a bracket
  "[" passed as a hook arrow (its 90 degree return at the foot reads as a 100+ degree head turn).
- Head in the same stroke: the tip is the point farthest from the start among those with 5-20 percent of the
  path left; the rest turns back 100-170 degrees and is >= 8 px. Head in following strokes (1 or 2): each arm
  stroke < 0.6 cell (a single chevron stroke holds both arms, so < 1.2 cell), touching a shaft end within 0.3
  cell, arms >= 8 px, on opposite sides of the shaft axis and on the tail side of the tip. Either shaft end
  may carry the head (shaft drawn tip first).
- Fixtures in `core/model/src/test/resources/freehand/` are generated point lists with a seeded jitter
  (6 loops, 6 arrows, 6 negatives), not device traces; the device checklist counts real misses.

## 2026-11-07 Freehand recognition review fixes (T-46 P5b)

- Loop closure gap is now min(1 cell, 0.35 x the stroke's larger bounds side), so a 1.5-2 cell "C" or "U"
  (gap 0.8-0.99 cell) is no longer a loop. The end may also close on a point in the first half of the path
  (a lead-in or an overshoot past the start is cut off), and the path-ratio test uses the cut polygon's own
  length, not the whole stroke.
- Hook arm minimum is max(0.25 cell, 5 percent of the stroke's larger bounds side) instead of 8 px, so a
  pen-lift flick at the end of a straight line is not an arrow. V-head arms keep the 8 px floor.
- A double-headed arrow (shaft plus a V head at each end) consumes both heads in `strokeIds`; `tip` is the
  first head found (shaft end drawn last first). Not supported: a head drawn before its shaft.

## 2026-11-07 Freehand insert marks wired into the classifier (T-46 P5b, Task 5.6)

- `FreehandInsertMarks` (next to `ToolInsertMarks`) is the second finder in `SendUseCase`. A pen `Loop` with at
  least 80 percent of its bounds right of the margin line is the box; pen strokes centred inside its polygon
  (even-odd test) are the note. The arrow tail must be within `SNAP_RADIUS` of the loop outline (distance to the
  polygon segments, not to four anchors, since a loop has none), the tip in the text column on a row.
- Arrows are shared: `insertArrows` offers tool `ARROW` strokes and `FreehandShapes.arrow` results to both
  finders, so tool rectangle plus pen arrow and pen loop plus tool arrow count. Why: a user mixes tools freely
  (Q12 C). The classifier's `used` set stops two finders claiming the same strokes; non-insert margin ink stays
  ordinary ink (OQ3 A).
- Device number to record: misses over 10 freehand loop plus arrow tries (docs/device-checklist.md).
- 2026-11-08 (T-78): strokes consumed by an applied edit op (replace, delete, insert) stay hidden but are restored on a
  revert that undoes the edit (option C of the stroke-removal research). `Page.opConsumedAt` (stroke id -> version that
  consumed it, default empty so old notebook JSON loads) is set in `RunPlacer.applyOps`; a layout with `reverted_from` = N
  un-consumes the op strokes consumed by versions after N, marks the page changed and the rows dirty. Keyed on the version,
  not on a line vanishing, because an inline insert has no run. Why: segment ink was already restored on revert (OQ2 A);
  op ink stayed hidden forever and the user lost handwriting.
- 2026-11-08 (T-78): margin ink goes to the translator as `note` ops (connector contract 2.2.0, route 2 of the
  stroke-removal research). `AnnotationClassifier` clusters the margin strokes no insert claims (`marginInk`) by box gap
  of up to one cell, adds each arrow (tool or freehand) whose tail lies within `SNAP_RADIUS` of a cluster (an arrow
  touching two clusters joins them), and returns one `OpKind.NOTE` op per cluster in `Classification.notes` (strokes in
  drawing order, rect = bounds of all its strokes, top to bottom). Notes run after inserts and before strikes and
  replaces, so a note's arrow head is never read as a strike. `notes` is a separate field so `ops` and `opStrokeIds`
  keep meaning edit ops with a target. `SendUseCase` sends notes after the edit ops only on a page that has a layout
  anyway (segments, runs or edit/image ops): a plain drawing page stays a picture. Applied notes are consumed and
  revert-restorable through the existing `RunPlacer.applyOps` path; rejected ones become `rejectedStrokes` and are never
  classified again. Why: the translator no longer applies margin ink that no op names, and without an op id the app
  could not hide the ink it applied, so the same note was re-sent and re-applied.
