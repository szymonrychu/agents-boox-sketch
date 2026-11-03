# agents-boox-sketch memory

Lessons that outlive a session. Newest first.

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

## 2026-11-03 Release keystore backup and restore test

- The release keystore (PKCS12, RSA 4096, 30-year validity) was generated once, not rotated. The
  four signing secrets live in the `release` GitHub environment, the signing certificate digest
  in a repo variable, `PUBLIC_DENYLIST` as a repo secret. Why: the same key must sign every
  update, so it is never regenerated.
- A SOPS-encrypted backup of all of these lives in the agents-helmfile repo under
  `backups/agents-boox-sketch/`. Restore test on 2026-11-03: decrypted into a temp dir, rebuilt
  the keystore (byte-identical) and its certificate fingerprint matched the original. Temp files
  were shredded. No values are recorded here.
