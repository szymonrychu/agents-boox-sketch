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
- [ ] Tap the eraser in the strip: Diagnostics shows "Raw drawing enabled: no"; drag the pen tip
      over a stroke: no ink is drawn and the stroke is erased. Tap the pen: raw ink draws again.
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

## Left icon strip and popups

- [ ] Launch: a narrow icon strip on the left edge below the tab bar (pen, eraser, rectangle, arrow,
      colour, undo, redo, pages "1/1", send, hide chevron); the selected tool has a thick black frame.
- [ ] Tap an unselected tool: it is selected, no popup. Tap the selected tool again: its popup opens to
      the right of the strip at that button's height; tap it a third time: the popup closes.
- [ ] Pen popup: six pen types (fountain, brush, ballpoint, charcoal, charcoal_v2, marker) are told apart
      by their glyph and label; the width slider changes the stroke width, and a draw right after uses it.
- [ ] Draw one stroke per pen type at the same slider width: its width while the pen moves equals its width
      after the app redraws it (undo then redo, or switch page and back), marker and charcoal_v2 included.
- [ ] Rectangle or arrow selected, popup open: only the six colours, no slider; draw a rectangle after
      moving the pen slider: its width is the same fixed one every time.
- [ ] Colour popup: six colours in a 3x2 grid, the selected one framed with a white centre mark; the colour
      icon on the strip shows the current colour.
- [ ] Open and close a popup: one GC flash, no popup residue left on the board, the pen draws right after.
- [ ] Pen strokes started over the strip or over an open popup draw nothing and the popup keeps working.
- [ ] Hide chevron: the strip collapses to a small "Show tools" tab on the left edge; tap it to bring the
      strip back. A finger tap on the board closes popup, strip and panel in one flash.
- [ ] Pages popup: previous, next, add and delete work and the strip counter follows. Eraser popup: Clear
      page clears the page and undo brings it back. Send popup: caption, Send, Send all pages.
- [ ] Rotate with a popup open: the popup closes, the strip stays on the left edge, the pen draws right after.

## Side panel and tap to dismiss

- [ ] Launch: no panel, a small "Show panel" indicator at the right centre; tap it: the panel
      covers the right edge (about half the width in landscape), the board is not resized.
- [ ] With raw drawing on (ballpoint selected), a finger tap on the board hides the dock and the
      panel at once with one GC flash; the pen draws right after. If nothing happens, finger
      events do not reach the board view in raw mode (see MEMORY "tap to dismiss").
- [ ] A finger tap on the panel or between toolbar buttons does not hide anything.
- [ ] Pen strokes over the open panel and over the panel indicator draw nothing.
- [ ] Rotate with panel and a popup open: both stay inside the screen, one GC flash, pen draws
      right after, nothing draws under the panel.

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
      failing or after 20 s it shows the diagram source as code text plus a one-line caption
      "diagram failed: <reason>". Note which.
- [ ] Mermaid, both cases: render a simple flowchart (`graph TD; A-->B`), it shows as a picture;
      render a deliberately broken one (`graph TD; A-->`), it shows the code plus a caption
      "diagram failed: mermaid parse error: ...". Check logcat tag `BooxMermaid` for the same
      reason, and that reopening the unannotated document retries the render.
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

## Pen latency

- [ ] With Diagnostics showing "Input path: Onyx raw", "Raw drawing enabled: yes" and "Fallback
      reason: none", write a full line of handwriting quickly, then the same line in the stock
      BOOX Notes app: the ink keeps up with the nib as in Notes, with no lag that grows along the
      line.
- [ ] Pause about 1 s after writing: nothing visibly changes (no blink, no flash). Then undo,
      erase, switch page, open the toolbar, leave and return to the app: the right ink each time
      and no stroke vanishes.

## Redraw matches the live ink (fountain, brush, charcoal, charcoal_v2)

For each pen: write a word with light and heavy pressure, some fast and some slow strokes, then
pause about 1 s (idle sync), then undo and redo, then switch page and back.

- [ ] Fountain: after each step the strokes keep the width profile they had while the pen moved
      (thin light and fast parts, thick heavy parts), not thinner or more uniform.
- [ ] Brush: the same check; the tapering of the stroke ends is kept after the redraw.
- [ ] Charcoal: after each step the stroke keeps its grainy charcoal texture and does not turn
      into a smooth grey pen line.
- [ ] Charcoal_v2: the same as charcoal, at the same (1.2x) width as the live stroke.
- [ ] A page with many charcoal strokes: undo and page switch redraw without a noticeable pause,
      and quick writing still keeps up with the nib (pen latency section).
- [ ] Charcoal written with the pen held tilted: the redraw keeps the wider, softer tilted grain
      (tilt is passed to the SDK pen) and the grain at the stroke edges is not cut off or left stale
      after an idle sync next to it.
- [ ] Drag a rectangle over a page with many charcoal strokes: the preview follows the pen without
      lag (committed strokes are blitted from the cache, the preview is never drawn by the SDK pens).
- [ ] Open a sent page in the document view and swipe between pages: charcoal looks as on the
      board and swiping does not stall.
- [ ] Charcoal pressure range is not flattened: a light stroke and a hard stroke redraw with visibly
      different grain weight (the redraw passes the stored 0..1 pressure with maxTouchPressure 1).
- [ ] Known gap: PNG exports and the images sent to the connector keep the app's own (smooth)
      look for these pens.

## Redrawn strokes are not squished (device pen settings, e-ink mode reset)

Before the steps: `adb logcat -c`, then keep `adb logcat -s InkDiag` running. Each committed
stroke logs one `stroke ...` line (raw input box, redrawn ink bounds, view size and screen
position, the firmware stroke parameters); each SDK pen fallback logs a `fallback #N` warning
with its exception.

- [ ] Logs and counter: draw one stroke with each pen (ballpoint, fountain, brush, marker,
      charcoal, charcoal_v2, rectangle, arrow). Every stroke logs a `stroke` line; the
      charcoals show the firmware `params` (or `none`); fountain and brush use fixed constants.
      Open the Diagnostics tab: "Pen redraw fallbacks" is 0 and logcat has no `fallback` line. If
      it is not 0, note the exception of the first `fallback` line.
- [ ] 500 px stroke box: with the fountain pen draw one straight horizontal line about 500 px
      long, then a diagonal one. In each `stroke` line the raw box is about 500 px wide and the ink
      box contains it with a margin on every side (ink width >= raw width, ink height >= raw
      height); the view size matches the board and its position is plausible.
- [ ] Shapes per tool: for each pen write the same word, then pause about 1 s (idle sync), undo
      and redo, and switch page and back. After every step the strokes look like the live ink:
      same thickness, grey and anti-aliased edges, charcoal grain, and the edges of wide fountain
      and brush strokes are not cut off. A rectangle and an arrow, dragged and lifted, show grey
      anti-aliased lines after the lift, not the thin black drag preview.
- [ ] Before and after a GC refresh: write a line with each pen, photograph it, then trigger a
      full refresh (switch page and back, or clear and undo). The strokes look the same before and
      after the GC refresh; a stroke that only looks right after the GC refresh means a frame was
      still drawn in the handwriting (near-binary) e-ink mode. That mode is set only by touch
      strokes and shape drags, so a pen tool that is still wrong here is not an e-ink mode
      problem; look at the SDK pen redraw instead.
- [ ] Fountain width: at the same selected width, the committed fountain stroke is as wide as the
      live ink was (not about 3 px fatter).
