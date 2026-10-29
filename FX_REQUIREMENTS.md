# Dungeon Vibes — Effects (FX) Requirements

Addendum to REQUIREMENTS.md · Oct 20, 2026 · @Luke

## Overview

Effects make a scene move: a group can drift through colors, pulse or flicker, and throw occasional flashes. Effects are **tools, not presets**. Nothing is themed or colored in advance; a DM combines a few building blocks to make their own fire, aurora, storm or "lightning bugs in a field". An **FX library** holds one-shot FX the DM builds (a pulse, a flicker...) and fires from the play view's **FX board** over the running scene; an FX can also end by turning the lights off or moving into a library scene, and a scene can open with an **intro FX**. Effects keep running with the screen off.

REQUIREMENTS.md still applies. Where this document says otherwise (notably background work), this document wins for effects.

## How to use this doc (for the agent)

Same rules as REQUIREMENTS.md: build in the phases below, stop after each with a checklist the owner can verify on real bulbs, record unspecified choices in DECISIONS.md, ask before adding dependencies or changing product behavior described here.

## In scope

- Per group in a scene: **Color shift** (the Multi-color mode, at most one), and any number of **layered effects** (owner request, Oct 23, 2026; up to 8 per group): **Brightness shift**, **Flashes** and **Spin**, several of one kind allowed (two Spins going opposite ways, two kinds of flash), in a priority order the DM drags.
- **Spread** per layer: Together, Independent, or Wave.
- Live preview of effects in the scene editor.
- Effects running when a scene is applied, including with the screen off or another app in front.
- An **FX library**, shared by every adventure, of DM-built one-shot FX, fired from the play view's FX board, with transitions and intro FX (see FX library and board).
- Effects copied, reset and promoted with their scenes, like every other scene setting.

## Out of scope (for now)

- Built-in effect presets, themes or named effects.
- Scene transitions with character (see REQUIREMENTS.md future scope).
- Timing effects to sound.
- Bulb-side waveforms (LIFX SetWaveform) as a requirement. They may be used later as an optimization; the engine must work without them.

## Glossary

| Term | Meaning |
|---|---|
| Base | A group's settings in a scene today: on/off, color or white, brightness. Effects build on it. |
| Effect layer | One of Color shift, Brightness shift, Flashes, attached to one group in one scene. |
| Spread | How a layer's timing relates across the bulbs in the group. |
| Envelope | A flash's shape over time: Attack, Hold, Release. |
| FX | A DM-built one-shot moment (one primitive: Pulse, Flicker, Strobe or Spin) aimed at chosen groups or All lights, kept in the FX library. |
| FX library | Every FX the DM has built, shared by all adventures; managed beside the scene library. |
| FX board | The play view's panel of buttons, one per FX in the library. |
| Transition | How an FX ends: back to what was running, lights off, or into a library scene. |
| Intro FX | An FX a scene plays as it's applied, landing in the scene at its peak's release. |
| Ghost tile | A dotted-outline tile in the play view for a library scene an FX moved into that isn't in the adventure. |

## The layers

Each layer is optional and can be switched off without losing its settings. Ranges marked *min–max* are two-thumb range sliders; each time the layer needs a value, it picks one at random inside the range.

### Color shift

Moves the group's color through a palette. In the editor this is the group's **Multi-color** mode, a third choice beside Color and White (owner decision after FX2): picking it replaces the single-color controls with the palette and its timing; picking Color or White switches it off and keeps the palette. Each palette color has its own brightness, which the shift holds and blends like the color; a brightness lock (on by default) keeps every color at one brightness. When effects stop, a Multi-color group rests on its first palette color at that color's brightness.

| Control | Range | Default |
|---|---|---|
| Palette | 2–8 colors, each a color (hue, saturation) with its own brightness; no white mode (colors made before version 7 may still be whites until edited) | Base color plus one more, at the group's brightness |
| Order | In sequence (loops) · Ping-pong (up then back, owner request Oct 23, 2026) · Random | In sequence |
| Blend time, min–max | 0–60 s | 2–4 s |
| Hold time, min–max | 0–60 s | 1–3 s |

- Random order never picks the color it's already on.
- Color shift sets color only. Brightness comes from the base, or from Brightness shift if that's on.

### Brightness shift

Moves the group's brightness inside a range.

| Control | Range | Default |
|---|---|---|
| Mode | Smooth (pulse/breathe) · Flicker | Smooth |
| Brightness, min–max | 0–100 % | 20–80 % |
| Speed, min–max | Smooth: 0.5–60 s per full cycle · Flicker: 0.1–5 s per change | Smooth 3–5 s · Flicker 0.15–0.6 s |

- Smooth eases between the range's low and high points, picking a new cycle length from the speed range each cycle.
- Flicker glides to a new random level in the range at random intervals from the speed range, never parking between changes (owner feedback after FX1: stepped flicker looked choppy).
- Brightness shift replaces the base brightness.

### Spin (owner request, Oct 23, 2026)

One extra color layered on top of whatever else the group does: it steps through the group's bulbs in their order, lighting each with an Attack-Hold-Release envelope as its turn comes, lap after lap.

| Control | Range | Default |
|---|---|---|
| Color | One color or white | Cyan |
| Brightness | 0–100 % (brightest wins over what's underneath) | 100 % |
| Time to the next bulb | 0.05–5 s | 0.3 s |
| Pause after each lap | 0–60 s | 0 s |
| Direction | Forward (the group's order) · Reverse (owner request, Oct 23, 2026) | Forward |
| Attack · Hold · Release | 0–10 s each | 0.1 · 0.1 · 0.5 s |

- A release longer than a step leaves a trail behind the moving light. Spread doesn't apply: the order is the point.

### Flashes

Occasional pulses that take over the group's color and brightness while they play.

| Control | Range | Default |
|---|---|---|
| Style | Single · Flicker | Single |
| Flash color | One color or white | White, 6500 K |
| Peak brightness, min–max | 0–100 % | 100–100 % |
| Attack | 0–10 s | 0.05 s |
| Hold | 0–10 s | 0.1 s |
| Release | 0–10 s | 0.3 s |
| Gap between flashes, min–max | 0.2–300 s, from the end of one flash (or burst) to the start of the next | 5–20 s |

- **Single**: each flash is one pulse, its peak picked anywhere in the peak range.
- **Flicker** (owner request, Oct 22, 2026; replaces the pulses-per-burst and pulse-spacing controls): each flash is a burst of 2 or 3 pulses (picked at random), 5–10 ms apart (end of one pulse to the start of the next). The last pulse is always the main strike and reaches the top of the peak range (owner request: its fade is the one you see); the ones before it are faint echoes at 10–20 % of its strength (they move the light 10–20 % of the way toward the flash color and peak, so they never dim a bright scene). Flicker hides Attack and Hold (they only shape Single flashes): each snaps on and holds 40 ms; every pulse but the last dies away in 60 ms so the next can hit, and the last fades over the Release setting (owner request). During any flash's release the scene's color returns faster than its brightness (color follows the envelope squared), because bulbs show low saturation as near-white and an even color fade looked white until it snapped to the color at the end (owner feedback).

## How the layers mix

Per bulb, at each update:

1. Start from the **base**.
2. If **Color shift** is on, its current color replaces the base color.
3. **Brightness shift** layers: one alone replaces the base brightness with its level; several multiply together (owner decision), so a slow breath and a fast flicker give a flickering breath.
4. **Flashes** and **Spin** layers are laid on from the lowest priority up, so where two are lit at once the higher one wins (owner decision). For each, if a flash is playing, blend steps 1–3 toward the flash color and peak brightness by the envelope's level (0 → 1 over Attack, 1 during Hold, 1 → 0 over Release). At level 1 the flash fully wins; as it releases, the layers underneath show through again at wherever they have moved to.

Blends between a color and a white are handled by the engine (the bulb is in one mode or the other); the DM never has to think about it.

## Spread

Each layer has its own Spread:

- **Together** — every bulb in the group follows one shared timeline.
- **Independent** — each bulb runs its own random timeline (fire, a forest, lightning bugs).
- **Wave** — one shared timeline, each bulb delayed by a set offset (0.05–10 s, default 0.5 s) after the previous one. Bulbs follow the group's own order (Setup → Groups → the group's page; owner request, Oct 23, 2026); All lights goes group by group in Setup's order.

Groups with one bulb behave the same under all three.

## Engine and sending

- The engine is protocol-neutral: it computes a LightState per bulb and sends it through LightController, like live preview.
- It sends only when a bulb's target has meaningfully changed, at most **5 regular updates per second per bulb** (well inside the LIFX limit), each aimed slightly ahead and faded over a bit longer than the update interval, so bulbs are always mid-glide when the next update lands. Flash edges (the start of an attack or release) are sent the moment they happen, outside that rate, so short flashes stay crisp. Continuous changes faster than 5 per second (e.g. a very fast flicker) are smoothed at that rate.
- Sends are fire-and-forget, newest value wins, like live preview.
- A bulb that stops answering is skipped; the rest keep running.
- Off Wi-Fi, effects stop sending and resume automatically when the phone is back on Wi-Fi.
- Time and randomness are injectable so the engine can be unit-tested deterministically.

## Data model

- Effect layers belong to a group's settings in a scene (a SceneLight). A group with no settings, or switched off, has no running effects.
- Color shift stores its settings per group (`scene_light_effects`), its palette's colors in order. Each layered effect is a row of `scene_effect_layers` (database version 13): its group, priority position, type and that type's settings. Upgrading turned every effect that was on into a layer (Flashes above Spin above Brightness shift, as they used to mix) and dropped those that were off (owner decision).
- Deleting a scene or a group deletes its layers.
- **Rules:** copying a library scene into an adventure, resetting to library version, and promoting to the library all include effect layers, exactly like the rest of a group's settings (REQUIREMENTS.md rules 1–5). Editing one copy's effects never changes another's.
- FX data is described under FX library and board.
- The database upgrades from the MVP schema without losing any existing data, with a migration test.

## Scene editor

- Each group's expanded panel offers **Color | White | Multi-color** (Multi-color is the Color shift layer). Under it, the mode's settings sit in outlined boxes: Color → a **Color** box (hue, saturation, brightness); White → a **Color** box (temperature, brightness); Multi-color → a **Color** box (palette swatches with add/remove, then the chosen color's hue, saturation and brightness with a lock) and a **Behavior** box (order, spread, blend, hold). Then a **Layered effects** section (owner request, Oct 23, 2026): a header with **+**, which opens a dialog listing Brightness shift, Flashes and Spin with icon and description; picking one adds it with default settings at the bottom of the list and opens it in a **full-screen layer editor** that slides over the scene editor (title: the effect; subtitle: scene › group; back closes it, returning exactly where you were; a bin removes the layer after confirming). Below the header each layer is an outlined card: drag handle, icon, name, swatch (Flashes and Spin: their color; Brightness shift: none, owner decision) and ✕ (removes after confirming). Tap a card to edit it; drag to set priority (top wins).
- A group that's off shows its effects disabled (kept, but not editable until it's on).
- With **live preview** on, changing any effect setting runs that group's effects live on its bulbs; turning live preview off stops them and leaves the base.
- **Opening the editor activates the scene** (owner decision, replacing "opening the editor doesn't touch the bulbs"): whatever was playing stops, the scene becomes the active scene and is shown in full, effects included. Closing the editor leaves it playing.
- The Flashes card has a **Flash now** button (with live preview on) that plays one burst straight away, so a flash's shape can be judged without waiting out its gap.
- Scene cards and tiles show a white ✦ inside each swatch whose group has effects, and a group with Color shift shows its palette as a color-wheel swatch instead of its base color.

## Playing scenes with effects

- Applying a scene fades to each group's base as today (a Multi-color group to its first palette color), waits for that fade to finish, then eases its effects in.
- Applying another scene stops the previous scene's effects and fades from wherever the bulbs are. Rapid taps still apply only the last scene.
- While effects are running, the play view shows that effects are on and offers **Stop effects** (bulbs settle on the active scene's base).

## Running in the background

- While any effects (or an FX) are running, the app runs a **foreground service** with a notification ("Dungeon Vibes: effects running") that has a **Stop** button. It holds a Wi-Fi lock (and a partial wake lock) only while effects run.
- Effects keep running with the screen off or another app in front.
- **Swiping the app away stops effects**; bulbs settle on the active scene's base. The notification's Stop does the same.
- When no effects are running, nothing runs in the background (REQUIREMENTS.md "no background work" still holds).
- Android 13+ asks for notification permission the first time effects start; effects still run if it's denied.

## FX library and board

Owner decisions (Oct 21, 2026): FX live in one library shared by all adventures, not per adventure; each FX is a single primitive; FX can transition only into library scenes (they live outside adventures); targets work like a scene's (groups or All lights).

### An FX

| Setting | Options |
|---|---|
| Name | Free text |
| Primitive | One of Pulse, Flicker (FX4a); Strobe, Spin (FX4b) |
| Targets | **All lights** (every bulb in a group, as one group) or one or more groups |
| Spread | Together · Independent · Wave (with wave delay), as for layers |
| Transition | **Return** (default) · **Lights off** · **Into a library scene** (picked from the scene library) |

An FX with group targets but none left (all deleted) can't be fired until targets are picked again.

### Primitives

Each primitive's ranges follow the layers above; *min–max* ranges pick a fresh value each time.

- **Pulse** — the Flashes envelope as a one-shot: color (color or white), peak brightness min–max, Attack, Hold, Release, pulses per burst min–max, pulse spacing min–max, with one full-strength pulse per burst. **Channels**: which of Color (hue and white point), Saturation and Brightness the pulse moves (at least one); a brightness-only pulse keeps the room's colors, a color-only pulse tints without brightening. The release is gently curved (it lingers a little on the pulse's color, then drops faster at the end; softened after owner feedback). Brightest wins: a pulse lifts each bulb toward its peak but never dims one that is already brighter (e.g. mid-breath). A slow Pulse (long attack and release, one pulse) is a "shift": the room glides to a look, holds and glides back or on.
- **Flicker** — a failing light: for a duration (0.2–30 s) the brightness drops by a random amount up to a depth (0–100 %) at random moments (change interval min–max, 0.1–1 s), as hard cuts, recovering between them. At most 10 changes a second per bulb. Ending in Lights off makes a light that dies out.
- **Strobe** (built Oct 23, 2026) — even on/off flashes in a color and brightness for a duration, at 0.5–4 flashes a second (capped for photosensitive viewers), with hard cuts. Off is dark. The editor's photosensitivity note is deferred (owner decision, Oct 23, 2026).
- **Spin** (redefined by the owner, Oct 23, 2026) — a color stepping through the target bulbs in order (groups in Setup's order, each group's bulbs in its order) at a set time per bulb, for 1–20 laps, Forward or Reverse, each bulb lighting with an Attack-Hold-Release envelope. Spread doesn't apply.

### Firing an FX

- Tapping a board button plays the FX once on its targets, over whatever they're doing: it takes precedence by its envelope, like Flashes, and Return hands back to the running scene and its effects.
- **Lights off**: at the start of the FX's final release (Pulse) or its end (Flicker, Strobe, Spin), every bulb fades out over that release time (or 0.5 s); the active scene is cleared.
- **Into a library scene**: at the same moment, the scene is applied with that release time as its fade (every bulb, not only the FX's targets), so the FX lands in it; the scene's effects then start as usual; its intro FX, if any, is skipped (no chains).
    - If the current adventure holds a copy of that library scene (its parent), the **copy** plays and its tile is marked active.
    - Otherwise the library scene plays and a **ghost tile** (dotted outline, the scene's name and palette) appears in the grid as the active scene. Tapping it plays that library scene again; in edit mode it offers "Add to this adventure" (a normal copy, rule 1). It disappears as soon as another scene is applied.
- A newer FX on the same bulbs replaces one still playing; a newer transition replaces an earlier one still waiting.
- Return FX are disabled until something has been applied this session (there's nothing to return to otherwise). All FX are disabled off Wi-Fi.
- FX count as running effects for the background service, so they finish with the screen off.

### Intro FX (FX4b)

- A scene (library or adventure) can pick an **intro FX** from the library (or none). In the editor it sits in an **Introduction** box above the lights with the scene's Fade in (owner request, Oct 23, 2026); tapping it opens a dialog to pick one from a scrolling list of the library or clear it. The default lighting has no intro FX.
- Applying the scene plays its intro FX on the FX's targets from whatever the bulbs are doing, and lands in the scene exactly like "Into a library scene" above (the FX's own transition setting is ignored): bulbs rise to the FX's peak, then come down into the scene, and the scene's effects start after.
- Intro FX is copied, reset and promoted with its scene; deleting the FX clears it from scenes.

### Where it lives

- **Library**: the Library tab (called Scenes before FX4a) gets **Scenes | FX** at the top. The FX list shows each FX's name, primitive and a preview swatch, with a try button, drag to reorder, duplicate, and delete (with confirmation, then Undo). Tapping one opens the **FX editor**: name, primitive and its controls (the same slider and color controls as the scene editor), targets, spread, transition, and **Try it** (fires on the bulbs; Return isn't needed to try).
- **Board**: the play view's title bar gets an **FX** button that opens the board: a sheet of buttons on phones, a column beside the tiles on tablets (wide windows). Each adventure can star its own favorite FX (owner request, Oct 23, 2026): the board opens on them, in the order starred, and **All FX** shows the whole library in library order with a star by each. With no favorites it shows the whole library and a hint to star some. Tap fires.

### FX data

- `fx`: name, sort order, primitive, its settings (stored like layer settings), targets-all flag, spread and wave delay, transition kind, transition scene (a library scene; deleting it falls back to Return), created/updated.
- `fx_targets`: the FX's groups. Deleting a group removes it from every FX.
- `adventure_fx` (database version 10): each adventure's favorite FX and their order. Deleting the adventure or the FX removes the row.
- `scenes.introFxId` (FX4b): the scene's intro FX; deleting the FX clears it.
- New database versions upgrade without losing data, with migration tests.

## Edge cases

| Situation | Behavior |
|---|---|
| Effects on a group with no bulbs | Allowed; nothing is sent. |
| Color shift with one color | Not allowed; the palette needs at least 2 colors. |
| min equal to max | Allowed; that value is used every time. |
| Changing a group's base while its effects preview | The base moves underneath the running effects, which keep their timing. Changing an effect setting restarts that group's effects. |
| App killed by the system | Effects stop; bulbs keep their last state. |
| Phone reboots | Nothing restarts automatically. |

## Build phases

Each phase ends with all tests passing and a checklist on the owner's real bulbs.

1. **FX1 — Engine and data.** Effect data model and migration, copy/reset/promote rules, the mixing engine with spread, sending at ≤5 updates/s. A debug-screen harness that runs hard-coded layers on real bulbs.
    - [ ] Flicker on one group looks like fire-ish flicker; independent spread makes two bulbs differ.
    - [ ] A flash with a sharp attack and short release reads as a lightning strike over a dim base; Flicker flashes read as rolling lightning.
    - [ ] A slow, soft, independent flash over a dark base reads as lightning bugs.
    - [ ] Upgrading from the MVP keeps all existing scenes and adventures.
2. **FX2 — Editor.** Effects section with all three layers and live preview; ✦ and palette swatches on cards and tiles.
    - [ ] Owner builds a fire and an aurora from scratch in the editor, watching them live.
    - [ ] Copying a scene with effects into an adventure, editing the copy, and Reset all behave like other settings.
3. **FX3 — Playing and background.** Applying starts effects; Stop effects; foreground service and notification; swipe-away stops.
    - [ ] A storm scene keeps flashing with the phone locked for 10 minutes.
    - [ ] Notification Stop and swiping the app away both stop effects and leave the base.
4. **FX4a — FX library and board.** FX data and migration; Scenes | FX library and FX editor; Pulse (with channels) and Flicker; targets (groups or All lights) and spread; transitions (Return, Lights off, into a library scene) with ghost tiles; FX board in the play view.
    - [ ] Owner builds a "Thunder" (white Pulse bursts, All lights) and a "Heal" (soft green, brightness-and-color Pulse on one group) and fires them over a running effect scene; both hand back to it.
    - [ ] A "Power failure" Flicker set to Lights off sputters and dies out.
    - [ ] A "Teleport" Pulse into a library scene that isn't in the adventure lands in that scene and shows a ghost tile, which disappears when another tile is tapped; one that is in the adventure marks its copy instead.
5. **FX4b — Intro FX, Strobe and Spin.** Intro FX on scenes; Strobe and Spin primitives.
    - [ ] A scene with an intro FX rises to the FX's peak and comes down into the scene, then its effects start.
    - [ ] Strobe stays at or under 4 flashes a second; Spin reads as a magical color whirl.
