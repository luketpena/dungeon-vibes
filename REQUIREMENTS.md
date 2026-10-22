TTRPG Atmosphere Lights — MVP Requirements
Oct 18, 2026 · @Luke
Overview
The MVP is a native Android app that lets a game master build lighting scenes for smart bulbs, group them into adventures, and apply a scene to every bulb with one tap during play. It runs entirely on the device, with no accounts or cloud.
In scope for the MVP
• Discovering and naming bulbs on the local network, then grouping them into named light slots.
• Scenes that set on/off, brightness, and either a color (hue and saturation) or a white color temperature per light slot, with a fade between scenes.
• A personal scene library, plus adventures that hold independent copies of scenes.
• Live preview on real bulbs while editing.
• Phone and tablet layouts.
• One bulb protocol: LIFX LAN. The code must be structured so more protocols can be added later.
Out of scope for the MVP
• Sound and soundscapes.
• Animated light effects (flicker, lightning, pulses).
• Cloud sync, login, sharing, import/export.
• Wi-Fi onboarding of new bulbs. A brand-new bulb must be connected to the home Wi-Fi once in the manufacturer's app; after that, all setup and control happens in this app. In-app onboarding can come with the Matter adapter.
• iOS.
How an agent should use this doc
• The owner is an experienced programmer (game development in GML, web development professionally) but new to Kotlin and Android. Talk about code in general terms freely, and briefly explain Android- or Kotlin-specific concepts when they matter to a decision; web analogies help. Make reasonable technical decisions without asking, but never change product behavior described here without asking.
• Build in the phases listed in Build phases. Stop at the end of each phase and give the owner plain-language steps to verify it.
• Where this doc is silent, pick the simplest option that fits the existing patterns, and note the choice in a DECISIONS.md file in the repo.
• Write efficient code: return early when a check allows it, cache values used repeatedly in loops (such as collection sizes), and avoid needless allocations on hot paths like live preview. If efficiency conflicts with readability or a requirement here, ask the owner.
Glossary
These terms are used exactly this way in the UI, the code, and the rest of this doc.
Term
Meaning
Bulb
One physical smart bulb found on the network. The user gives it a name in the app.
Light slot
A named role like "Ceiling" or "Left lamp." Scenes point at slots, never at bulbs. A slot holds zero or more bulbs. Shown to users as "Group" (owner decision, Oct 20, 2026); the code and this doc keep "light slot".
Scene
A saved look: per-slot on/off, brightness, and either a color (hue, saturation) or a white temperature (kelvin), plus a fade time and a name.
Library scene
A scene in the user's personal library, independent of any adventure. The "account scene" from the original brief.
Adventure
A named, ordered list of adventure scenes.
Adventure scene
A scene that belongs to exactly one adventure. Either built there from scratch or copied from a library scene.
Parent
The library scene an adventure scene was copied from. Used only for "Reset to library version."
Apply
Send a scene to the bulbs: slots in the scene go to their settings, every other slot turns off.
Promote
Save an adventure scene into the library as a new library scene.
Tech stack and constraints
Use a single-module Kotlin app with Jetpack Compose. Use the latest stable versions of each library when the project is created, and pin them in a Gradle version catalog.
Area
Choice
Language
Kotlin, coroutines and Flow for all async work
UI
Jetpack Compose, Material 3
Adaptive layout
Material 3 adaptive (window size classes, list-detail pane scaffold)
Navigation
Navigation Compose
Architecture
MVVM: Compose screens → ViewModels → repositories
Dependency injection
Hilt
Storage
Room (SQLite), single database
Networking
Raw UDP via java.net.DatagramSocket. No LIFX third-party library
Color picker
Custom Compose sliders for hue, saturation, brightness. No third-party picker
Tests
JUnit, Room in-memory database tests, Compose UI tests for key flows
Min SDK
26 (Android 8.0)
Target SDK
Latest stable
Required permissions: INTERNET, ACCESS_NETWORK_STATE, ACCESS_WIFI_STATE, CHANGE_WIFI_MULTICAST_STATE. Hold a Wi-Fi MulticastLock while discovering, because some devices drop broadcast replies without it. Release it when discovery ends.
Backups: leave Android Auto Backup enabled and include the Room database, so a user's scenes survive a phone change.
Layouts: phones use a bottom navigation bar and single-pane screens. Tablets (expanded width) use a navigation rail and list-detail panes. Both orientations must work on both device types.
Bulb control
The MVP controls LIFX bulbs over the LIFX LAN protocol, behind a protocol-neutral adapter interface. LIFX bulbs with Matter also still answer the LAN protocol: a Home Assistant user who added new LIFX bulbs through Matter found they were auto-discovered and controllable through the LIFX LAN integration as well (issue). So one bulb can later test both the LIFX and Matter adapters.
Adapter interface
All UI and scene logic talks only to a LightController, which routes each call to the adapter for that bulb's protocol. A sketch, to be refined by the agent:
interface LightAdapter {
    val protocolId: String              // "lifx_lan"
    fun discover(timeoutMs: Long): Flow<DiscoveredBulb>
    suspend fun setState(bulb: BulbAddress, state: LightState, fadeMs: Int, reliable: Boolean): Result<Unit>
    suspend fun setPower(bulb: BulbAddress, on: Boolean, fadeMs: Int, reliable: Boolean): Result<Unit>
    suspend fun identify(bulb: BulbAddress)  // brief blink so the user can find it
}

data class LightState(val hue: Float, val saturation: Float, val brightness: Float, val kelvin: Int?) // 0–360, 0–1, 0–1; kelvin non-null = white mode
Ship a FakeLightAdapter alongside the real one. It simulates a few bulbs, logs every command, and can be switched on from a debug setting. The agent cannot touch real bulbs, so all logic must be testable against the fake.
LIFX LAN specifics
Implement directly from the official docs at lan.developer.lifx.com.
• Transport is UDP on port 56700. Discovery is a broadcast GetService (2); each bulb replies with StateService (3). No account, key, or pairing is needed.
• Read each bulb's label and current color with Get (101) → State (107). Use the label as the default name.
• Set color with SetColor (102). Its duration field is the transition time in milliseconds, which gives the scene fade for free.
• Set power with SetLightPower (117), which also takes a duration.
• Colors are HSBK, each a 16-bit value. Convert hue 0–360 and saturation/brightness 0–1 to 0–65535. In color mode, send kelvin 3500. In white mode, send saturation 0 and the scene's kelvin, clamped to the bulb's supported range (from the LIFX product list; 2500–9000 K if unknown).
• Identify bulbs by serial (MAC), never by IP, since IPs change. Re-run discovery on app start and on pull-to-refresh to update IPs.
• LIFX recommends at most 20 messages per second per device (protocol docs). Enforce this in the adapter, per bulb.
Sending rules
• Applying a scene must be reliable. Request acknowledgements, and retry once after 300 ms if none arrives.
• Turning a bulb on to a new color: send SetColor with duration 0 first, then SetLightPower on with the fade. This stops it flashing its old color.
• Live preview must feel instant. Send fire-and-forget with a 100 ms transition, at most 10 updates per second per bulb, always sending only the newest value and dropping stale ones.
Data model
Adventure scenes are always independent copies; the only link back to the library is an optional parent reference used for reset. All copy, promote, reset, and delete logic lives in one SceneRepository, inside Room transactions, with unit tests for every rule below.
Entities
Entity
Fields
Bulb
id, protocolId, serial, lastKnownIp, name, lastSeenAt
LightSlot
id, name, sortOrder
SlotBulb
slotId, bulbId. A bulb belongs to at most one slot
Adventure
id, name, sortOrder, createdAt, updatedAt
Scene
id, name, adventureId (null = library scene), parentSceneId (null = no parent), sortOrder, fadeMs, createdAt, updatedAt, allLightsOn (added Oct 21, 2026)
SceneLight
sceneId, slotId, isOn, mode (COLOR or WHITE), hue, saturation, kelvin, brightness
Foreign keys
• Scene.adventureId → Adventure: delete cascades. Deleting an adventure deletes its scenes.
• Scene.parentSceneId → Scene: on delete, set null.
• SceneLight → Scene and → LightSlot: delete cascades.
• SlotBulb → LightSlot and → Bulb: delete cascades.
Rules
1. Add a library scene to an adventure: deep-copy the scene and its SceneLights into the adventure, with parentSceneId = the library scene. The same library scene can be added any number of times.
2. Edit an adventure scene: changes only that copy. The parent and other copies never change.
3. Edit a library scene: changes only the library scene. Existing copies never change.
4. Reset to library version: shown only when parentSceneId is set and the parent still exists. Replaces the copy's SceneLights, fade, and name with the parent's.
5. Promote: creates a new library scene copied from the adventure scene, then sets the adventure scene's parentSceneId to it.
6. Delete a library scene: its copies stay in their adventures. Their parentSceneId becomes null, so Reset disappears for them.
7. SceneLight with isOn = false keeps its color values, so turning it back on restores the last color. Switching between color and white mode also keeps both sets of values.
8. Slots with no SceneLight row, or isOn = false, turn off when the scene is applied. Bulbs not assigned to any slot are never touched. Exception (owner request, Oct 21, 2026): a scene with "All lights" on gives every assigned bulb the scene's All-lights settings (its own color/white, brightness and effects, or the defaults if never set); each slot's own settings are kept unchanged underneath.
9. New light slot: existing scenes get no row for it, so it stays off in them until the user edits them.
Screens and behavior
The app has three top-level destinations: Adventures, Scenes, and Setup. Adventures opens by default once setup is done; on first launch with no bulbs, the app opens Setup.
1. Setup → Bulbs
• Scans automatically on open, plus pull-to-refresh. Shows each bulb's name, online/offline status, and its slot.
• Tap a bulb to rename it. An Identify button blinks that bulb.
• Remove a bulb from the app (it reappears on the next scan if still on the network; removal is mainly for dead bulbs).
• Empty state explains: set up bulbs in the LIFX app first, and make sure the phone is on the same Wi-Fi.
2. Setup → Light slots
• Add, rename, delete, and drag to reorder slots.
• Assign bulbs to a slot with checkboxes. A bulb already in another slot shows that slot's name; picking it moves it.
• Deleting a slot asks for confirmation and says how many scenes use it.
3. Scenes (the library)
• List of library scenes, each showing its name and a row of color swatches, one per slot.
• Add, delete (with confirmation), and drag to reorder.
• Tap opens the scene editor. A play button on each row applies the scene.
4. Scene editor (shared by library and adventure scenes)
• Name field and a fade-time control. Default fade is 1.0 s; range 0–10 s.
• One row per light slot: an on/off switch, a color swatch, and a name. Tapping the swatch expands a Color/White toggle. Color shows hue, saturation, and brightness sliders; White shows temperature (warm to cool) and brightness sliders, defaulting to 2700 K.
• Live preview is on by default, with a toggle. While on, every change is sent to that slot's bulbs as described in Bulb control.
• Adventure scenes also show "Reset to library version" (rule 4) and "Save to library" (rule 5).
• Changes save automatically. Leaving the editor does not revert the bulbs.
5. Adventures
• List of adventures with scene counts. Add, rename, delete (with confirmation), and drag to reorder.
6. Adventure (play view)
• Scenes as large tiles in a grid, each showing its name and swatches. Tapping a tile applies the scene and marks it active.
• The screen stays awake while this view is open.
• An Edit toggle switches to edit mode: drag to reorder, delete, and open the editor. Tiles never apply in edit mode, to prevent mistakes at the table.
• "Add scene" offers "New blank scene" or "From library." The library picker allows selecting several scenes at once, each added as its own copy.
Tablet layouts
• Adventures: adventure list on the left, play view on the right.
• Scenes: library list on the left, scene editor on the right.
• Setup: tabs for Bulbs and Light slots, using the full width.
Edge cases and error handling
The app never blocks the user over a bulb problem; it applies what it can and shows what failed.
Situation
Behavior
No bulbs found
Empty state with three checks: bulbs set up in the LIFX app, phone on the same Wi-Fi, not on a guest network
Phone not on Wi-Fi
Banner on every screen; scan and apply are disabled
Bulb offline during apply
Other bulbs still update. The slot shows a warning badge on the active tile
Bulb's IP changed
Next discovery matches it by serial and updates the IP silently
Slot with no bulbs
Allowed. Editor shows "No bulbs assigned" on that row
Scene with every slot off
Allowed. It acts as a blackout scene
Duplicate names
Allowed for scenes, adventures, slots, and bulbs
Library scene deleted while a copy's editor is open
Reset button disappears; edits continue normally
Rapid taps on several tiles
Only the last tapped scene is applied; earlier sends are cancelled
App backgrounded
No background work. Bulbs keep their last state
Build phases
Build in six phases. Each ends with automated tests passing and a short checklist the owner can verify on a real phone, tablet, and bulbs.
1. Skeleton. Project setup, Hilt, navigation, the three destinations as placeholder screens, phone and tablet scaffolds.
    [ ] App installs and opens on a phone and a tablet.
    [ ] Bottom bar on phone, navigation rail on tablet; rotation works.
2. Data layer. Room entities, DAOs, and SceneRepository with every rule from Data model.
    [ ] One unit test per rule, all passing.
    [ ] Data survives closing and reopening the app.
3. Bulb control. LightAdapter, LightController, FakeLightAdapter, and the LIFX LAN adapter with packet encoding unit-tested byte-for-byte against the docs.
    [ ] A debug screen lists real bulbs found on the owner's Wi-Fi.
    [ ] Debug buttons turn a real bulb on, off, red, and blue, with a visible fade.
4. Setup screens. Bulbs and Light slots.
    [ ] Owner can name bulbs, and Identify blinks the right one.
    [ ] Owner can create slots and assign bulbs; changes persist.
5. Scenes. Library and scene editor with live preview.
    [ ] Dragging a slider changes the real bulb smoothly, with no lag build-up.
    [ ] Applying a library scene fades every slot correctly and turns unused slots off.
6. Adventures and play. Adventure list, play view, edit mode, add from library, reset, and promote.
    [ ] Owner can build an adventure from library and blank scenes and run it by tapping tiles.
    [ ] Editing a copy leaves the library scene unchanged; Reset restores it.
    [ ] Deleting a library scene keeps its copies, and their Reset button disappears.
    [ ] Tablet shows the two-pane layouts.
Lights menu and default lighting (added after the MVP, owner request, Oct 22, 2026)
• Default lighting: how a neutral room looks. It's a full scene (groups, All lights, effects), edited with the scene editor from Setup → Default. It's hidden from the library and can't be added to adventures or saved to the library. It starts as All lights in the default warm white.
• Every screen's title bar has a lights menu (a bulb icon, filled while the lights are on) with two entries:
    – Turn off lights / Turn on lights. "Turn off lights" shows while any light is on and fades every assigned bulb off. "Turn on lights" shows only once the app has turned them off, and brings back whatever was showing (a scene, or the default lighting). With nothing showing before (fresh launch, or after an FX that ended in Lights off), it turns on to the default lighting.
    – Default lighting (a switch). On: the default lighting shows. Off: the scene that was showing before comes back; with none, the lights turn off.
• On/off comes first: while the lights are off, the default lighting switch can't be changed.
• Playing any scene (a tile, the library, an FX moving into a scene, or opening a scene in the editor) switches the default lighting off and counts as the lights being on.
• The app can't read the bulbs' power, so "on" and "off" are the app's own record: at launch the lights are assumed on, and changes made outside the app aren't seen.
Future scope
These are planned but not part of the MVP. The MVP design should not make any of them harder.
• More protocols: Matter, Philips Hue, WiZ, Govee, Yeelight, each as a new LightAdapter.
• Soundscapes: layered audio loops and randomized one-shots per scene.
• Animated effects: specified in FX_REQUIREMENTS.md (effect layers built from tools rather than presets, per-layer spread, an FX board, and effects that keep running with the screen off). Not part of the MVP; built in the FX phases defined there.
• Scene transitions with character (idea, not scheduled): one-shot moves between scenes, e.g. a "spell cast" flash that settles into the next scene, or a sunrise that unfolds over several minutes. (Largely covered by FX transitions and intro FX in FX_REQUIREMENTS.md, phases FX4a/FX4b.)
• Per-bulb calibration: measured latency, max command rate, and color correction.
• Multiple locations: several slot-to-bulb mappings, for playing at different tables.
• Import/export: sharing adventures and scenes as files.
• Preset scene library (idea, owner, Oct 21, 2026): ready-made scenes users can add to their own library. Presets use "All lights", so they work on any bulb setup without knowing its groups.
Sources
• LIFX LAN protocol: changing a device
• LIFX LAN protocol limits (archived docs)
• Home Assistant issue: LIFX Matter bulbs also discovered over LAN
• LIFX Matter updates