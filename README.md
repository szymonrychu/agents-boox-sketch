# agents-boox-sketch

Android app for BOOX e-ink tablets (Note Air 6C). Draw or handwrite on a fast board with the
Onyx pen SDK, press Send, and get back a markdown spec next to the board. Refine it with more
sketches or typed feedback, then hand it off to another agent session. The app talks to an
`agents-app-connector` device API whose origin and token you enter on the device at pairing
time; nothing server specific is compiled into the app.

Language: Kotlin. Modules: `:core:model`, `:core:api`, `:ink:onyx`, `:app`.

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
