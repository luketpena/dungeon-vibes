\# CLAUDE.md



\## Project



A native Android app (Kotlin, Jetpack Compose) for controlling smart bulbs during tabletop RPG sessions. Users build lighting scenes, group them into adventures, and tap a scene to apply it to every bulb.



\*\*`REQUIREMENTS.md` is the source of truth.\*\* Read it at the start of every session. If anything here conflicts with it, REQUIREMENTS.md wins.



\## About me



I'm an experienced programmer (game dev in GML, web dev professionally) but new to Kotlin and Android. Talk code with me normally. When an Android- or Kotlin-specific concept affects a decision (lifecycles, Gradle, Compose recomposition, etc.), explain it in a sentence or two. Web analogies are welcome.



\## How we work



\- Build one phase at a time, in the order listed in REQUIREMENTS.md.

\- At the end of each phase:

&#x20; 1. Run all unit tests and make sure the build succeeds.

&#x20; 2. Give me the phase's checklist as plain steps I can follow on my phone or tablet.

&#x20; 3. Stop and wait for my sign-off before starting the next phase.

\- Make technical decisions yourself, but \*\*never change product behavior\*\* described in REQUIREMENTS.md without asking me first.

\- When REQUIREMENTS.md is silent, choose the simplest option that fits existing patterns and record it in `DECISIONS.md` (one line: the decision and why).

\- Ask before adding any dependency that isn't listed in the tech stack section.



\## Commands



\- Build: `./gradlew assembleDebug`

\- Unit tests: `./gradlew testDebugUnitTest`

\- Install on connected device: `./gradlew installDebug`

\- Instrumented/UI tests (device required): `./gradlew connectedDebugAndroidTest`

\- List connected devices: `adb devices`



\## Testing realities



\- The emulator generally cannot reach bulbs on my Wi-Fi. Anything involving real bulbs must run on my physical phone or tablet.

\- You can't see or touch the bulbs. Use `FakeLightAdapter` for all automated tests, and ask me to confirm real-bulb behavior.

\- Unit-test the LIFX packet encoding byte-for-byte against the official protocol docs.



\## Code style



\- Write efficient code: return early when a check allows it, cache values used repeatedly in loops (like collection sizes), and avoid allocations on hot paths such as live preview. If efficiency conflicts with readability or a requirement, tell me and let me decide.

\- Follow standard Kotlin conventions and Android's recommended architecture (Compose screens → ViewModels → repositories).

\- All bulb communication goes through `LightController` / `LightAdapter`. UI code never talks to a protocol directly.

\- All scene copy/promote/reset/delete logic lives in `SceneRepository`, inside Room transactions.



\## Git



\- Commit after each meaningful step with a clear message, and at the end of every phase.

\- Never push, rewrite history, or delete branches without asking.

