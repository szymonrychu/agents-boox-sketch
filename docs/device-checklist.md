# Device test checklist: Note Air 6C

Run this on a BOOX Note Air 6C (colour Kaleido panel, Android 16) with the debug or release APK
after every change to the board, the toolbar or the pen input. Work top to bottom; note the app
version and mark each line pass or fail. Robolectric covers the logic; this list covers what
only the panel and the Onyx pen SDK can show.

## Before you start

- Open the Diagnostics tab: "Input path: Onyx raw", "Raw drawing enabled: yes" with a pen tool
  selected, "Fallback reason: none". If it says "Touch fallback", note the reason and stop here.
- Select the ballpoint tool and black.

## Live ink in each colour

For each swatch (black, red, blue, green, orange, purple):

- [ ] Tap the swatch: it gets a black frame and a white centre mark; the frame is easy to spot on
      the e-ink panel, also on the black swatch.
- [ ] Draw a stroke: ink appears under the pen while it moves (not only on lift).
- [ ] Note the colour of the live ink. The Onyx raw layer may draw it in grey or black; that is
      expected if it happens, as long as the stroke turns into the selected colour once lifted.
- [ ] After lifting, the stroke shows in the selected colour.

Then:

- [ ] Switch tools (fountain, brush, marker) with a colour selected: the colour is kept.
- [ ] Eraser, undo and redo work on coloured strokes.
- [ ] Diagnostics still says "Onyx raw", "Fallback reason: none".

## Rectangle and arrow snapping

- [ ] Select "rectangle". Diagnostics shows "Raw drawing enabled: no" while it is selected.
- [ ] Note "Shape drag events: window N, view M" on Diagnostics, draw one rectangle, tap Refresh
      on Diagnostics (or reopen the tab) and note it again. Both counts should grow by the
      events of the drag. Window growing while view stays the same means the open but disabled
      raw layer claims the stylus drag and only the window feed draws shapes: still a pass, but
      report the numbers. Neither growing while a drag draws nothing: fail, report it.
- [ ] Drag with the pen: a rectangle preview follows the pen; no freehand ink is drawn along the
      drag. Lift: the rectangle stays, in the selected colour.
- [ ] A tiny drag (a pen tap or a few pixels) leaves nothing behind.
- [ ] Draw a second rectangle away from the first.
- [ ] Select "arrow". Drag from near a corner or side midpoint of the first box to near one of
      the second: the preview snaps both ends onto those anchor points while you drag, and the
      committed arrow keeps the snapped ends and has a visible head.
- [ ] Drag an arrow that starts and ends far from any box: it is not snapped.
- [ ] Undo removes the last shape; the eraser removes a rectangle only when you touch its
      outline, not its inside.
- [ ] Back to "ballpoint": live ink appears immediately again, Diagnostics shows "Raw drawing
      enabled: yes". Repeat the switch a few times; the pen must never stay dead.

## Auto-hide dock

- [ ] Draw a stroke with the dock visible: about 0.6 s after lifting, the dock hides and the
      small "Show tools" indicator remains.
- [ ] The same after a rectangle or an arrow.
- [ ] Tap the indicator: the dock comes back, readable, without animation.
- [ ] Right after a show or hide, draw over the area where the dock was: ink appears where the
      dock is gone, never over a visible dock.
- [ ] With the dock visible, pen taps on swatches and tool chips change the selection and never
      leave ink on the toolbar.

## Export

- [ ] Once Send is wired to the export (not yet on main): send a page with coloured strokes and
      both shapes, then check the received PNG keeps the colours and the arrow heads and box
      corners are not cut off at the edges. Until then the export is covered by `PngExporterTest`.

## Documents

Open the Documents tab (side panel), pick a document with at least one spec version.

- [ ] The list shows each document with its latest version and date, "Translating" while a
      sketch is on its way and "Failed" after a failed one; Prev and Next page by ten.
- [ ] The document opens as one fixed-width page with the counter "2/7" and no scrolling. Prev
      and Next turn pages.
- [ ] Finger swipe turns the page while the pen is down-capable under raw drawing (Q12 device
      check): right to left is next, left to right previous. If finger events never reach the
      app, record it here and keep Prev and Next as the only paging; do not add a mode.
- [ ] The pen always inks on the latest version; a finger never leaves ink.
- [ ] Ghosting on a page turn: after the turn the old page is gone within a moment and the pen
      draws on the new page at once (no dead pen, no ink on the old page).
- [ ] Legibility of the uploaded raster at 1568 px: Send notes, open the received PNG on the
      server side, the printed text and the handwriting are both readable.
- [ ] Kaleido ink colours over printed text: red, blue, green strokes over text look right in
      the live ink and after lifting.
- [ ] A mermaid block shows as a diagram (captured by the offline renderer); with the web view
      failing or after 5 s it shows the diagram source as code text instead. Note which.
- [ ] Rotate the tablet: the page is scaled to fit and centred and the ink stays aligned with
      the text it was written over.
- [ ] Open an older version from the "v3" picker: it is read-only ("Read only"), saved ink of
      that version shows, the pen draws nothing. Share .md opens the system share sheet.
- [ ] Sources lists the drawings and notes the version was made from; a purged one reads "no
      longer stored" (the connector answers 410 for a purged file).
- [ ] Send notes, then wait for the next spec version: the old draft turns read-only, the new
      latest version opens clean.

## Diagnostics tab

- [ ] After the whole run, Diagnostics still says "Input path: Onyx raw" and "Fallback reason:
      none", and "Raw drawing enabled" matches the selected tool after a tap on Refresh.
- [ ] Notification shade, app switch and back: the pen draws again with the selected tool and
      colour.

## Stroke flicker

- [ ] Draw ten quick strokes with the ballpoint: each stays on screen the whole time, with no
      brief vanish and reappear after the pen lifts. Then undo, erase and switch page: the
      board shows the right ink after each (these still redraw through a raw drawing toggle).
