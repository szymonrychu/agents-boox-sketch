# agents-boox-sketch roadmap

Human-owned requirements. Agreed scope: the Recommended level (decision Q16, 2026-11-03).

## MVP

- Repo scaffold with mise, CI (lint, unit, Robolectric, Roborazzi), signed release on every
  green main with a stable latest link, dependency verification, public-check.
- Board with the Onyx fast path plus a touch fallback, all six SDK brushes (fountain, brush,
  ballpoint, charcoal, charcoal v2, marker), eraser, undo, redo, clear, autosave.
- Export PNG plus `strokes.json`; outbox with idempotent retry.
- Pairing with two separate fields (origin, token); single current conversation with "new
  conversation"; events by long-poll.
- Spec rendered as markdown; feedback messages; split layout in both orientations with a
  collapsible panel; diagnostics screen; keystore with SOPS backup.

## Recommended (agreed)

- SSE with long-poll fallback.
- Conversation list with delete.
- Hand-off sheet.
- Offline mermaid rendering.
- Spec version picker and share as `.md`.
- CI emulator instrumented tests on every PR.
- Obtainium-friendly release naming.
- Several pages per Send, each with its page index; Send uploads only the non-empty pages
  changed since the last send, long-press "Send all pages" uploads every non-empty page.

## Complete (not in scope now)

- Sketch history thumbnails from the connector's attachments.
- "Send debug info" route.
- In-app update check against the latest release.
- Tilt in `strokes.json` v2.
- Second device support and per-device settings sync.
