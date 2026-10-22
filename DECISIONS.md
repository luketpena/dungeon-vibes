# Decisions

Choices made where REQUIREMENTS.md is silent. One line each: the decision and why.

## Phase 1

- Application ID and package are `com.dungeonvibes`; short and neutral, and easy to change before any store release.
- Kotlin 2.4.21 is put on the build classpath in the root `build.gradle.kts`, because AGP 9 compiles Kotlin itself with an older bundled version, and this is AGP's documented override.
- KSP (not kapt) runs the Hilt compiler, because kapt is incompatible with AGP 9's built-in Kotlin.
- compileSdk and targetSdk are 37 (Android 17), the latest stable API level and the highest that AGP 9.4 supports.
- Navigation Compose uses string routes, not type-safe `@Serializable` routes, because the type-safe ones need the kotlinx-serialization plugin and library, which aren't in the tech stack.
- Navigation icons are vector drawables in `res/drawable`, not the Compose `material-icons` library, which is deprecated and not in the tech stack.
- Rail versus bottom bar is decided by window size: rail only when width ≥ 600dp and height ≥ 480dp, so a phone keeps the bottom bar in landscape and a tablet keeps the rail in portrait.
- Setup shows its Bulbs and Light slots tabs on phones as well as tablets, which keeps one layout for both.
- The theme follows the system light/dark setting and uses Material You dynamic color on Android 12 and later; it is the simplest standard Material 3 theme until a visual direction is chosen.
- The start screen is decided once in `MainViewModel` from `BulbRepository.hasAnyBulbs()`; phase 1 binds a placeholder that always returns false, so the app opens on Setup until phase 2 adds Room.
- Auto Backup uses Android's default rules, which already include app databases, so no custom backup rules file is needed.

## Phase 2

- Room tests run on the PC under Robolectric (test-only dependency, approved by owner), so `testDebugUnitTest` needs no device.
- A debug-only screen (bug icon on Setup, registered only when `BuildConfig.DEBUG`) seeds and clears sample data (approved by owner); its fake bulbs use protocolId `fake`.
- Ids are auto-increment Longs; future import/export can remap ids on the way in.
- Bulbs are unique on (protocolId, serial), so the same MAC seen through two protocols is two bulbs.
- `slot_bulbs` uses bulbId as its primary key, which enforces "a bulb belongs to at most one slot" in the database itself.
- A SceneLight row stores hue, saturation, kelvin and one brightness together, so mode switches and on/off keep every value (rule 7); brightness is shared between modes, as the data model lists one brightness field.
- New slots, scenes and adventures are appended (sortOrder = max + 1); reordering rewrites the list as 0..n-1.
- Copies from the library keep the source's name and fade and are appended in the order picked; promoted scenes keep the name and go to the end of the library.
- "Reset to library version" is available when an adventure scene's parentSceneId is non-null; the SET_NULL foreign key guarantees such a parent exists.
- Scene `updatedAt` changes on any edit to the scene or its lights; adventure `updatedAt` changes only when the adventure itself is renamed.
- Fade time is clamped to 0–10,000 ms in SceneRepository.
- "What applying a scene does" (rule 8) is a single SQL query, `SceneRepository.applyTargets`, that phase 3 will hand to LightController.
- Room's schema JSON is exported to `app/schemas/` and committed, so future migrations can be tested against it.
- BulbRepository became a concrete class; the phase 1 interface had only one implementation.

## Phase 3

- kotlinx-coroutines-test is a test dependency (approved by owner), so timing rules (300 ms retry, 20 msg/s, 10 previews/s) are tested on virtual time.
- Fake bulbs appear alongside real ones when "Use fake bulbs" is on (approved by owner); the switch lives on the debug screen and is stored in SharedPreferences.
- Android 17 (API 37) blocks local-network traffic for apps targeting it unless the user grants ACCESS_LOCAL_NETWORK at runtime; the app declares it and asks once at launch on Android 17+ (earlier versions grant it implicitly). Phase 4 will add the in-Setup explanation when it's denied.
- Kelvin ranges come from LIFX's official product registry (products.json), stored as a sorted table in `LifxProducts.kt`; the base range is used, not firmware-upgraded ranges, because it's valid on every firmware.
- Product ids are learned with GetVersion during discovery; until a bulb has been discovered this session, its kelvin is clamped to 2500–9000.
- Discovery broadcasts GetService three times within the scan window (UDP can drop packets), to both 255.255.255.255 and the subnet's broadcast address; a scan lasts 2 s.
- Discovery also sends GetVersion and GetColor (GetColor's LightState reply carries label and power) and retries each once; a bulb that never answers them is still recorded, named by its serial.
- Get requests (discovery, identify) use the same retry-once-after-300 ms rule as reliable sets.
- A bulb's on/off state is remembered from discovery and from our own commands; if the bulb is known to be on, changing its color is a single faded SetColor; otherwise SetColor (0 ms) then SetLightPower on (fade), per the sending rules.
- The 20 messages/s limit is enforced as at least 50 ms between packets to the same bulb; different bulbs aren't throttled together.
- Live preview is a per-bulb conflated channel: it sends the newest value, then waits 100 ms before taking the next.
- Identify reads the bulb's power, toggles it twice with 400 ms steps and restores it, so it works whether the bulb is on or off.
- Scene apply runs every bulb in parallel and returns the ids that didn't acknowledge; a newer apply cancels the one in flight.
- Discovery re-runs once on each app start (after the permission check) and from the debug screen's Scan button; pull-to-refresh comes with the Setup screens in phase 4.
- Bulbs that answered the latest scan are "online"; that set lives in memory in BulbDiscovery, not in the database.

## Phase 4

- Drag-to-reorder uses the Reorderable library (sh.calvin.reorderable 3.1.0, approved by owner), with a drag handle on each row; the new order is saved when the drag ends.
- "Not on Wi-Fi" means the default network is neither Wi-Fi nor Ethernet; one banner under each screen's title bar shows it, driven from the app root.
- The Bulbs tab scans each time it opens and on pull-to-refresh, but only when on Wi-Fi and (Android 17+) the local network permission is granted; otherwise it explains why instead.
- Tapping a bulb opens one dialog for rename, Identify and Remove; Remove asks for confirmation (the requirements don't say, but it's cheap safety).
- "Online" means the bulb answered the most recent scan this session.
- One dialog both adds and edits a slot: a name plus a checkbox per bulb; Save makes those exactly the slot's bulbs, moving any that were in another slot.
- "Scenes that use a slot" counts every scene with settings for it, on or off, since deleting the slot removes those settings either way.
- The local network permission card offers both the system prompt and the app's Settings page, because Android stops showing the prompt after two denials.

## Phase 5

- The Scenes tab uses Material 3's list-detail scaffold (`adaptive-layout`/`adaptive-navigation`, same library as the tech stack's adaptive entry): one pane on phones and narrow tablets, list beside editor when the window is wide enough; it also handles Back.
- "New scene" creates "New scene" with every slot off and opens it in the editor straight away; renaming happens there.
- A slot with no settings starts as off, warm white 2700 K at 80 % brightness, with hue 30° (warm orange) ready if switched to Color.
- The editor keeps an in-memory draft as the source of truth and saves 400 ms after the last change (and on leaving), so slider drags stay instant and write once; the small per-change map copy is cheap for a handful of slots.
- Live preview sends only the slot that changed; turning live preview back on sends the whole scene as edited (slots without settings go off). Opening the editor doesn't touch the bulbs.
- The temperature slider runs 1500–9000 K; the LIFX adapter clamps further to each bulb's own range.
- Fade is a 0–10 s slider in 0.1 s steps.
- Swatches show brightness compressed into 35–100 % so dim settings stay recognisable; white uses an approximate color-temperature curve.
- The library's play button is disabled off Wi-Fi; if some bulbs don't respond a snackbar says how many (the warning badges are for play-view tiles in phase 6).
- Reset to library version asks for confirmation first, since it discards the copy's changes.
- The editor's ViewModel takes its scene id through Hilt assisted injection, one instance per scene.
- `currentWindowAdaptiveInfo()` replaced with `currentWindowAdaptiveInfoV2()` (the old one is deprecated).
- Easy-to-miss side effects (a bulb moving out of another slot) are flagged with a golden-yellow warning icon and text (owner request); darker gold in light mode for contrast.

## Phase 6

- The Adventures tab uses the same list-detail scaffold as Scenes: adventure list, then its play view; side by side on wide windows.
- Editing an adventure scene opens the shared scene editor full screen (route `adventures/scene/{id}`, still under the Adventures tab), since the play view already fills the detail pane.
- The active scene is tracked in memory by ScenePlayer (any play button or tile), so a tile is only marked active if it's what the bulbs were last told to show; after an app restart nothing is marked.
- A tile becomes active the moment it's tapped; when the apply finishes, any slots whose bulbs didn't answer get a golden "!" on that tile's swatch, and the tile says some bulbs didn't respond.
- Tiles are dimmed and don't apply while off Wi-Fi.
- Edit mode is a pencil/check toggle in the title bar; in it, tap opens the editor, long-press-and-drag reorders, and each tile has a delete button (with confirmation). "Add scene" shows in edit mode and whenever the adventure is empty.
- "Add scene" asks New blank scene or From library; a blank scene opens straight in the editor. The library picker adds the ticked scenes in library order.
- Creating an adventure asks for its name, then opens it. Rename is a pencil on the list row; delete confirms and says how many scenes go with it.
- The screen is kept awake (View.keepScreenOn) whenever the play view is on screen, in edit mode too.

## Polish pass 1 (owner requests)

- Light slots are called "Groups" everywhere users see them (Setup tab, buttons, messages); code, database and REQUIREMENTS.md keep "light slot", with a note in the glossary.
- Scene palettes (scene list, adventure tiles, library picker) show only lit groups; a scene with nothing lit says "Empty scene".
- Name fields (scenes, groups, bulbs, adventures) capitalize the first letter of each word.
- Checkbox lists put the checkbox at the top left with the name lined up beside it; warning notes keep their icon beside the first line.

## FX1 (engine and data)

- Effects are stored as one row per group in a scene (`scene_light_effects`, every layer's settings with an enabled flag each) plus an ordered palette table; both hang off the scene_lights row, so deleting a scene or group cascades and turning a layer off keeps its settings.
- The FX board's tables wait for FX4 (one more automatic migration then), so this phase only adds what it uses.
- The 1 → 2 database upgrade is a Room auto-migration generated from the exported schemas (only new tables, nothing existing changes); the migration test builds a real version 1 file from `1.json` without adding the room-testing dependency.
- Copy, reset and promote copy effects inside the same transaction as the lights; reset gets the library's effects back by deleting the copy's lights (which cascades) and copying again.
- Whites are saturation 0 inside the engine, and color-mode packets now carry a white point (`LightState.colorKelvin`, default 3500 as before), so a blend between a white and a color glides without a jump or a rainbow detour.
- Smooth brightness starts each cycle at the range's low point and eases up and back (a cosine); Flicker glides in a straight line from one random level to the next over each interval (owner feedback: holding each level looked choppy).
- Wave uses identical timelines (same random seed) on delayed clocks; Independent bulbs also start at a random point in their first step so they don't begin in sync.
- Ranges with min above max use min; zero-length steps are stretched to 50 ms (flash gaps to 200 ms) so a setting can never lock the engine.
- Regular frames go out on a fixed 200 ms grid (5/s), each aimed 300 ms ahead and faded over 300 ms; the overlap means a late packet never leaves a bulb parked (the first version faded over exactly 200 ms and let late wake-ups stretch the gap, which showed as stair steps).
- The runner sleeps until the next frame or the next flash edge, whichever is first, and sends the start of a flash's attack and release the moment it happens with that segment's own length as the transition, so a 30 ms attack really is 30 ms.
- Frames that don't visibly differ from the last one sent (0.5° hue, 0.4 % saturation/brightness, 10 K) aren't sent.
- Starting effects turns each bulb on (reliably) to its first frame with a fade and waits for the fade before animating; stopping fades each bulb back to its group's base over 0.5 s.
- The runner watches Wi-Fi itself, sends nothing while off it, and resends every bulb when it returns.
- The debug screen's test patterns (fire, lightning, rolling lightning, lightning bugs, breathing, color drift) are hard-coded for checking the engine and never appear outside the debug screen; leaving the debug screen stops them until FX3 adds the background service.
- The flash peak range keeps the existing `peak` column as its top and adds `peakMin` (database version 3, an automatic migration defaulting to 100 %), so existing flash settings keep behaving as before.

## FX2 (editor)

- Each group's expanded panel shows its base controls, then an Effects section with three switchable cards; a group that's off shows a one-line hint instead (its effects are kept).
- Effects are part of the editor's in-memory draft and save with the same 400 ms pause as everything else, after the group's own settings (effects need that row).
- Live preview hands groups with effects to the effect runner and everything else to the plain preview, as before. The runner takes new settings at most every 150 ms while something is dragged; a group whose effects are unchanged keeps its timing and only moves its base, and a group whose effects changed restarts. When a group leaves the runner, its plain settings follow 150 ms later so a frame still in flight can't land on top.
- Switching live preview off stops the editor's effects and fades those bulbs to their base; a run started elsewhere (another screen) is left alone. (Leaving the editor used to do the same; see "Editing activates the scene" below.)
- "Flash now" plays one burst on the group straight away (following its spread, so Wave still ripples).
- Time sliders use a squared scale (short times get most of the travel) and snap to 10 ms below 1 s, 100 ms below 10 s, whole seconds above.
- Switching Brightness shift between Smooth and Flicker swaps in that mode's default speeds (3–5 s, 0.15–0.6 s), since one mode's speeds make a poor version of the other.
- A first Color shift palette starts from the group's own color plus one 40° round the wheel (or a white 1500 K warmer/cooler); "Add color" adds a neighbour of the selected color.
- Color shift groups show their palette as a sweep-gradient circle on cards, tiles and editor rows, so a scene still shows one circle per lit group.
- Wave order is the group's bulbs by name, everywhere effects run.

## FX polish 1 (owner requests)

- Every slider in the editor moves only on a deliberate sideways drag: touching the track does nothing and the thumb follows the finger from where it was, so scrolling can't bump values. Built as one shared component (`SteadySlider` / `SteadyRangeSlider`) drawn on a Canvas; dragging also puts the keyboard away.
- Color shift is presented as the Multi-color mode beside Color and White, with the same storage (the layer's enabled flag), so no database change. Leaving Multi-color keeps the palette; the single color and white values are kept while in it.
- A Multi-color group rests on its first palette color (at the group's brightness) whenever its effects stop.
- The scene name field takes focus only when tapped, or when the scene has no name; focus Android hands it on its own is cleared. Opening a group, dragging a slider, scrolling by hand or pressing Done drops focus.
- Opening a group scrolls it to the top of the list; each group row has a chevron that turns when it opens.

## FX3 (playing and background)

- ScenePlayer stops any running effects where they are, applies the scene as before (a Multi-color group fading to its first palette color), then starts the scene's effects once the scene's own fade has had time to finish, easing them in over 0.3 s. A tap on another scene in the meantime cancels them.
- Stop effects (play view and notification) fades the bulbs to their scene base over 0.5 s.
- The background service is a foreground service of type `connectedDevice` (it drives smart bulbs on the local network; its prerequisite permission is the multicast one the app already holds). It runs whenever the effect runner is running, including the editor's live preview, and stops 3 s after effects stop, so moving from one effect scene to the next doesn't make the notification blink.
- While it runs it holds a partial wake lock (capped at 8 hours as a safety net) and two Wi-Fi locks: high-performance, which keeps the radio awake with the screen off, and low-latency (Android 10+) for when the screen is on.
- Swiping the app away and the notification's Stop both stop effects and wait for the settle to be sent before the service ends.
- Notification permission (Android 13+) is asked once per install, the first time effects start; the answer isn't needed because effects run either way.
- The play view shows an "✦ Effects running · Stop effects" bar while effects run (hidden in edit mode).
- Scene apply targets list a group's bulbs in name order (they were in id order), the order Wave follows.

## Polish pass 2 (owner requests)

- The active adventure tile is filled with the theme's primary color and everything on it uses the matching "on primary" color (the ✦ and "Empty scene" follow the card's text color), instead of a light container with primary-colored text.
- Fake bulbs are gone from the app: no adapter binding, no debug switch, and sample data no longer creates bulbs. FakeLightAdapter stays in the code only because the unit tests drive it. Database version 4 deletes any fake bulbs (and their group assignments) already stored.
- "All lights" is a scene setting (`scenes.allLightsOn`, version 4, default off) with its own look. While it's on, every bulb in a group plays the All-lights settings (color, white or Multi-color, brightness, effects), and the groups below are locked; each group's own settings are kept untouched and come back when it's off (owner decision). It's copied, reset and promoted with the scene.
- The All-lights settings are stored as a hidden "room" light slot (`light_slots.isRoom`, version 5): one per database, created on first use, never shown in Setup and never given bulbs. Its scene_lights and effect rows make copy, reset, promote, Multi-color and effects work through the existing code.
- With All lights on, the room's effects run as one group over every bulb in name order, so Independent and Wave spread across the whole room. Cards and tiles show a single swatch for the shared look (with a "!" if any bulb failed).
- Default group settings live in one place (`LightDefaults`), shared by the editor and the apply query.
- Editing activates the scene (owner decision): opening the editor with live preview on (once the scene, groups and bulbs have loaded) stops whatever was playing, marks the edited scene active and previews it in full (groups without settings go off). This replaces the MVP rule that opening the editor doesn't touch the bulbs. Closing the editor leaves it playing, effects included; turning live preview off still stops its effects and settles on the base, and turning it back on takes over again.

## FX4a (FX library and board)

- FX live in two new tables, `fx` and `fx_targets` (database version 6, an automatic migration). Pulse and Flicker settings are both stored on every FX, so switching primitive and back keeps them. A Scene transition is stored only into an existing library scene; anything else is saved as Return, and deleting the scene clears it (it then plays as Return).
- The Scenes tab has a Scenes | FX switch above the list; the FX editor opens in the same detail pane as the scene editor and saves 0.4 s after the last change, like it.
- A fired FX is planned in full when it fires (every pulse and flicker dip), per bulb, and the effect runner plays it on top of whatever that bulb shows: a running effect carries on underneath, and a bulb with no running effect is borrowed for the FX and handed back when it ends. An FX fired with nothing running starts a short run of its own (the background service and "Effects running" bar appear while it plays).
- A bulb its scene has off is powered on at zero brightness for an FX and off again after, so Pulses (brightness channel) light it up from dark.
- A newer FX on the same bulbs replaces one still playing, and its transition (or none, for Return) replaces one still waiting.
- Transitions start as the last bulb's final release starts and use that release as their fade; a Flicker (no release) uses 0.5 s. Lights off turns every assigned bulb off; moving into a library scene plays the adventure's copy of it when the adventure has one, otherwise the library scene itself.
- Flicker steps alternate between a dip (40–100 % of the depth) and full light, at most 10 changes a second; its interval range is 0.1–1 s and its duration 0.2–30 s.
- Wave across FX targets follows bulb names (all targets together, not per group).
- Before any scene has been played, FX can fire over all-off bulbs, but only ones that end in Lights off or a scene (Return would have nothing to return to); the board says so.
- The FX board is a bottom sheet on phones and a 240 dp column beside the tiles on screens at least 720 dp wide, opened from a ⚡ button in the play view's top bar. Buttons follow library order; Try it in the FX library and editor fires without the transition.
- The ghost tile (dotted outline, "From your library") appears after the adventure's tiles while an FX-reached library scene is active and the adventure has no copy of it. Tapping replays it; in edit mode it offers "Add to this adventure". It goes away once another scene is played.
- The FX editor's Try it is an extended FAB (bottom right) so it stays in reach while scrolling; it dims and does nothing off Wi-Fi or with no groups picked (owner request).
- Applying a scene (tile, ghost tile, library play button) gives a crisp Confirm haptic; firing an FX (board, library and editor Try it) gives a heavier Long-press haptic, so the two feel different. Both follow the phone's touch-feedback setting (owner request).
- The Scenes tab is now called Library (owner request), since it holds scenes and FX.
- FX show a swatch (library rows and board buttons): a Pulse's color, or a hollow ring for an FX with no color of its own (a Flicker, or a brightness-only Pulse).
- "Remove color" sits right under the palette swatches instead of below the color sliders (owner request).
- Pulse brightness: brightest wins (owner decision). A pulse lifts a bulb toward its peak but never pulls down a bulb whose running effect is already brighter; the pulse's color still applies.
- Pulse release is curved (level 1 − x², x = progress through the release): it stays near the pulse's color and drops fast at the end, so a pulse between distant hues (purple → green) reads as its color instead of the teal in between. Flashes keep their straight release. New Pulses default to a 0.3 s hold (was 0.1 s); existing FX keep theirs (owner decision).
- The ✦ for effects moved from after a scene's palette into each swatch whose group has effects (white, with a soft shadow so it shows on pale swatches), so you can see which groups move (owner request).

## Group panel layout (owner requests)

- A reusable outlined box (`SettingsCard`: header, optional hint, optional on/off switch) holds each set of settings; the effect layer cards use it too.
- Color and White each show one "Color" box (hue, saturation, brightness / temperature, brightness). Multi-color shows a "Color" box (palette, then the chosen color's hue, saturation and brightness) and a "Behavior" box (order, spread, blend, hold). The palette caption is gone.
- Palette colors have no Color/White switch: they're always colors. Whites saved earlier keep working and turn into colors when their hue or saturation is first moved. New palettes for a White group start from the group's stored hue at full saturation.
- Each palette color has a brightness (database version 7, hand-written migration: every existing palette color takes its group's brightness, locked). Color shift holds and blends brightness like the color; the group's own brightness slider is no longer shown in Multi-color, and a Multi-color group rests on (and fades in to) its first color at that color's brightness. Brightness shift, when on, still sets brightness itself, as before.
- The brightness lock (padlock button beside the slider) is stored per group (`colorShift_brightnessLocked`, default on). Locked: the slider sets every color. Locking snaps every color to the chosen color's brightness. New colors copy the chosen color's brightness.
- The brightness column lives on the shared color type, so flash and pulse colors carry it too (always 100 %, unused: they take brightness from their peak). Palette swatches now show each color's brightness the way group swatches do.

## Lights menu and default lighting (owner request)

- The default lighting is a hidden scene (`scenes.isDefault`, database version 8, hand-written migration), created on first use as All lights in the default warm white. Library queries skip it, and it can't be promoted or picked as an FX transition (it isn't a library scene). Its editor (Setup → Default → Edit) has no name field or library actions, just a short hint.
- The menu's state (lights off, default showing) lives in ScenePlayer, in memory only, next to the active scene. Turn off fades every assigned bulb off over 1 s; Turn on and switching the default off replay the remembered scene with its own fade.
- Switching the default off with no scene to go back to turns the lights off (the choice that doesn't leave the room in an unclear state).
- An FX ending in Lights off counts as the lights being off, with nothing to bring back (Turn on shows the default, owner decision). An FX that returns doesn't change the on/off state.
- The lights menu sits at the end of every title bar. A detail pane shown beside its list (tablets) leaves it to the list pane, so there's only ever one on screen.
