# agents-boox-sketch

Android app for BOOX e-ink tablets (Note Air 6C). Draw or handwrite on a fast board with the
Onyx pen SDK, press Send, and get back a markdown spec next to the board. Refine it with more
sketches or typed feedback, then hand it off to another agent session. The app talks to an
`agents-app-connector` device API whose origin and token you enter on the device at pairing
time; nothing server specific is compiled into the app.

Language: Kotlin. Modules: `:core:model`, `:core:api`, `:ink:onyx`, `:app`.

## Drawing

- Pens: fountain, brush, ballpoint, charcoal, charcoal v2 and marker, with a width slider, an
  eraser, undo, redo and clear, on several pages.
- Colours: black, red, blue, green, orange and purple, picked from the swatch row in the toolbar
  (the selected swatch has a black frame and a white centre mark). Strokes keep their colour in
  the notebook, in `strokes.json` and in the exported PNG. On the Onyx raw path the live ink is
  asked for in the selected colour; if the panel draws it grey, the stroke takes its colour once
  the pen lifts.
- Shapes: pick "box" (a diagram node), "picture" (a dashed frame around a freehand drawing, sent as an
  image on every Send) or "arrow" and drag with the pen. A preview follows the pen and the
  shape is committed when you lift. Arrow ends snap to the corners and side midpoints of
  rectangles on the page within 40 page pixels, so boxes connect like in draw.io. While a shape
  tool is selected the Onyx raw ink is off, so no freehand line is drawn along the drag.
- The toolbar dock hides itself shortly after a stroke; tap the indicator to bring it back.

## Documents

The Documents tab lists the conversations that have a converted spec. Opening one shows the
latest spec version as fixed-width pages (the notebook canvas width in both orientations, no
scrolling): a finger swipe or Prev and Next turn pages, the pen always inks over the page, and
"Send notes" uploads the inked pages (page picture plus ink) with the spec version they were
written over, so the agent returns the next version. Older versions (the "v3" picker) are
read-only, Share .md sends the markdown, Sources shows the sketches a version was made from.
Mermaid blocks are drawn offline by a bundled renderer and fall back to code text.

## Tasks

The Tasks tab is the taskboard of the web UI, through the connector's task relay: a paged list
(Active, Done, Dropped, All; filter by session and labels; tap a label or an assignee to filter),
New task, and a detail with Overview (description and labels, waiting sessions), Decisions
(answer with an option or your own text, plus a note), Todo (three states per item, add and
remove), Comments and History. Status, title, assignee and todo text are not editable here, as in
the web. The list refreshes live while the tab is on screen; there is no badge or notification.
Everything is paged with Prev and Next, no animations, 48 dp targets, forms are full screen.

Test on the device with `docs/device-checklist.md`.

## Build

    mise run install      # Android SDK under .android-sdk (once)
    mise run build        # debug APK at app/build/outputs/apk/debug/app-debug.apk
    mise run lint
    mise run test

See `AGENTS.md` for every task. For a default pairing origin in debug builds, put
`defaultOrigin=https://...` in the gitignored `local.properties`.

## Download

Filled in with the release link when the first signed release is published.

## License

Apache-2.0, see `LICENSE` and `NOTICE`.
