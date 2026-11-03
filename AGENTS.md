# agents-boox-sketch

Kotlin Android app for BOOX e-ink tablets (target device: Note Air 6C). Draw or handwrite on a
fast board with the Onyx pen SDK, send several pages to `agents-app-connector`, read the
returned markdown spec next to the board, refine it with more sketches or typed feedback and
hand it off to another agent session. Signed APKs are published as GitHub Releases.

Language: Kotlin (explicit exception to the project's Python-only rule, decided 2026-11-01).

## Build commands

Use the mise tasks, never raw tool invocations.

| Task | What it does |
|------|--------------|
| `mise run install` | installs the Android SDK (cmdline-tools, `platforms;android-36`, `build-tools;36.0.0`) under `ANDROID_HOME` (default `<repo>/.android-sdk`, gitignored) |
| `mise run lint` | `./gradlew ktlintCheck detekt lint` plus `actionlint` |
| `mise run test` | `./gradlew test` (JVM unit and Robolectric tests) |
| `mise run test:screenshot` | `./gradlew verifyRoborazziDebug` |
| `mise run screenshot:record` | `./gradlew recordRoborazziDebug` |
| `mise run test:instrumented` | `./gradlew connectedDebugAndroidTest` (needs an emulator or device; CI runs it) |
| `mise run build` | `./gradlew assembleDebug` |
| `mise run deps:verify-update` | regenerates `gradle/verification-metadata.xml` over every CI task type |
| `mise run fmt` | `./gradlew ktlintFormat` |

Gradle runs with `-Xmx3g` and `org.gradle.workers.max=2` (see `gradle.properties`). Dependency
verification is strict on CI: every change to a dependency re-runs `mise run deps:verify-update`
and commits the XML. On a rebase conflict in that file, regenerate it, never hand-merge.

## Module map

| Module | Role |
|--------|------|
| `:core:model` | pure Kotlin: strokes, pages, undo, hit test, `strokes.json` codec, export geometry, send selection |
| `:core:api` | pure Kotlin: connector DTOs, HTTP client, SSE, outbox policy, backoff (depends on `:core:model`) |
| `:ink:onyx` | Android library: Onyx pen SDK wrapper and the shared `StrokeInput` contract |
| `:app` | Android application: Compose UI, board view, Room, WorkManager, pairing, spec panel, diagnostics |

## Public-repo rule

This repository becomes public. No secrets, server origins, internal hostnames, registry or
cluster names, and no personal data (real name, personal email, device serials) in any commit,
ever. Commits are authored as `szymonrychu <ID+szymonrychu@users.noreply.github.com>`. Fixtures
use `https://connector.example` and fake tokens only (`test-token`; build the auth header
scheme word at runtime in tests, never as a literal next to a token-like string). No server URL or token is compiled into any build; debug may
read `defaultOrigin` from the gitignored `local.properties`. Keystores and `.env*` files are
gitignored and never committed.

## Conventions

- Plain ASCII, no em dashes or smart quotes. Conventional commits.
- Tests are not negotiable: never edit, delete or skip a test to make it pass.
- Read `MEMORY.md` and `ROADMAP.md` before working here; update `MEMORY.md` on every non-obvious
  decision, with the date and the why. `ROADMAP.md` is human-owned.
