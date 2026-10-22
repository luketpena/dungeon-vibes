package com.dungeonvibes.light

import com.dungeonvibes.data.db.BulbApplyTarget
import com.dungeonvibes.data.db.FxTransition
import com.dungeonvibes.data.db.LightMode
import com.dungeonvibes.data.db.SceneEntity
import com.dungeonvibes.data.fx.FxItem
import com.dungeonvibes.data.scene.GroupEffects
import com.dungeonvibes.data.scene.SceneRepository
import com.dungeonvibes.light.fx.EffectGroup
import com.dungeonvibes.light.fx.EffectRunner
import com.dungeonvibes.light.fx.FxFire
import com.dungeonvibes.light.fx.FxTarget
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * The scene most recently applied, so the play view can mark its tile. [failedSlotIds] holds the
 * slots whose bulbs didn't respond; it's empty while the apply is still running.
 */
data class ActiveScene(
    val sceneId: Long,
    val failedSlotIds: Set<Long> = emptySet(),
    /** An FX moved into this (library) scene: an adventure without it shows it as a ghost tile. */
    val fromFx: Boolean = false,
)

/**
 * The lights menu's state, as the app knows it (it can't read the bulbs; owner decision): whether
 * it turned every light off, and whether the default lighting is showing.
 */
data class RoomLights(
    val lightsOff: Boolean = false,
    val defaultOn: Boolean = false,
)

/**
 * Applies a saved scene to the bulbs: used by the library's play buttons and the play view's tiles.
 * A scene with effects first fades to its base, then its effects start (FX_REQUIREMENTS.md →
 * Playing scenes with effects).
 */
@Singleton
class ScenePlayer @Inject constructor(
    private val scenes: SceneRepository,
    private val lights: LightController,
    private val effects: EffectRunner,
) {
    private val _active = MutableStateFlow<ActiveScene?>(null)

    /** In memory only: after an app restart nothing is marked active, since we can't know the bulbs' state. */
    val active: StateFlow<ActiveScene?> = _active.asStateFlow()

    /** Whether effects are playing (from a scene, or the editor's preview). */
    val effectsRunning: StateFlow<Boolean> = effects.isRunning

    private val _room = MutableStateFlow(RoomLights())

    /** In memory only: at launch the lights are assumed on, with no default showing. */
    val room: StateFlow<RoomLights> = _room.asStateFlow()

    /** What Turn on brings back: the scene showing when the lights went off (null: the default). */
    private var beforeOff: ActiveScene? = null

    /** What turning the default off brings back: the scene showing when it went on. */
    private var beforeDefault: ActiveScene? = null

    /**
     * Makes [sceneId] the active scene without applying it: whatever was playing (and its effects)
     * stops where it is, and the caller (the scene editor's live preview) takes over the bulbs.
     * [isDefault]: it's the default lighting, being edited from Setup.
     */
    fun activate(sceneId: Long, isDefault: Boolean = false) {
        effects.stop(settleFadeMs = -1)
        if (isDefault) defaultSceneId = sceneId
        enter(sceneId, isDefault)
        _active.value = ActiveScene(sceneId)
    }

    /**
     * Bookkeeping as a scene takes over the bulbs: the lights are on again, and the default shows
     * exactly when it's the default scene (remembering what it replaced, to go back to).
     */
    private fun enter(sceneId: Long, isDefault: Boolean) {
        if (isDefault) {
            val current = _active.value
            if (current != null && current.sceneId != sceneId && !_room.value.defaultOn) beforeDefault = current
        } else {
            beforeDefault = null
        }
        _room.value = RoomLights(lightsOff = false, defaultOn = isDefault)
    }

    /** The default scene's id, once known (looked up when first needed). */
    @Volatile
    private var defaultSceneId: Long = NO_SCENE

    private suspend fun defaultScene(): Long {
        val known = defaultSceneId
        if (known != NO_SCENE) return known
        return scenes.ensureDefaultScene().also { defaultSceneId = it }
    }

    /* The lights menu */

    /** Every assigned bulb fades off; Turn on brings back whatever was showing. */
    suspend fun turnOffLights() {
        if (_room.value.lightsOff) return
        beforeOff = _active.value
        allOff(TURN_OFF_FADE_MS)
        _room.value = RoomLights(lightsOff = true, defaultOn = false)
    }

    /**
     * Brings back what was showing when the lights went off (the default lighting included), or
     * the default lighting if nothing was. Only while the app has the lights off.
     */
    suspend fun turnOnLights() {
        if (!_room.value.lightsOff) return
        val back = beforeOff
        beforeOff = null
        if (back != null) play(back.sceneId, fadeOverrideMs = null, fromFx = back.fromFx) else play(defaultScene(), null, false)
    }

    /**
     * Shows the default lighting, or (off) goes back to the scene it replaced. With no scene to go
     * back to, turning it off turns the lights off. Does nothing while the lights are off: on/off
     * comes first (owner decision).
     */
    suspend fun setDefaultLighting(on: Boolean) {
        val room = _room.value
        if (room.lightsOff || room.defaultOn == on) return
        if (on) {
            play(defaultScene(), fadeOverrideMs = null, fromFx = false)
            return
        }
        val back = beforeDefault
        beforeDefault = null
        if (back != null) {
            play(back.sceneId, fadeOverrideMs = null, fromFx = back.fromFx)
        } else {
            beforeOff = null
            allOff(TURN_OFF_FADE_MS)
            _room.value = RoomLights(lightsOff = true, defaultOn = false)
        }
    }

    /** Stops effects where they are and fades every assigned bulb off; nothing is active after. */
    private suspend fun allOff(fadeMs: Int) = coroutineScope {
        effects.stop(settleFadeMs = -1)
        _active.value = null
        for (target in scenes.applyTargets(NO_SCENE)) {
            val address = target.toAddress()
            launch { lights.turnOff(address, fadeMs) }
        }
    }

    /** Stops the effects; the bulbs settle on their scene's base. */
    fun stopEffects() {
        effects.stop()
    }

    /**
     * Applies the scene with its own fade and marks it active straight away (a tap should feel
     * instant). Returns null if the scene doesn't exist or a newer apply replaced this one before it
     * finished; otherwise which bulbs didn't confirm.
     */
    suspend fun play(sceneId: Long): ApplyResult? = play(sceneId, fadeOverrideMs = null, fromFx = false)

    /** Plays a library scene an FX moved into again, keeping it shown as that adventure's ghost tile. */
    suspend fun replayFromFx(sceneId: Long): ApplyResult? = play(sceneId, fadeOverrideMs = null, fromFx = true)

    /**
     * [play], with the fade replaced by [fadeOverrideMs] (an FX's release, so the FX lands in the
     * scene) and the scene marked as reached [fromFx].
     */
    private suspend fun play(sceneId: Long, fadeOverrideMs: Int?, fromFx: Boolean): ApplyResult? {
        val scene = scenes.getScene(sceneId) ?: return null
        if (scene.isDefault) defaultSceneId = scene.id
        enter(sceneId, scene.isDefault)
        _active.value = ActiveScene(sceneId, fromFx = fromFx)
        // The previous scene's effects stop where they are; this scene fades in from there.
        effects.stop(settleFadeMs = -1)
        val (targets, sceneEffects, roomSlotId) = sceneTargets(scene)
        val fadeMs = fadeOverrideMs ?: scene.fadeMs
        val apply = lights.applyScene(targets, fadeMs)
        val result = try {
            apply.await()
        } catch (e: CancellationException) {
            // If *we* were cancelled, keep cancelling; if only the apply was superseded, report null.
            currentCoroutineContext().ensureActive()
            return null
        }
        // Only record failures if this scene is still the active one (a later tap may have won).
        _active.update { current ->
            if (current?.sceneId == sceneId) current.copy(failedSlotIds = result.failedSlotIds) else current
        }
        // Start this scene's effects, unless another scene has been tapped meanwhile.
        if (_active.value?.sceneId == sceneId) {
            if (roomSlotId != null) {
                startRoomEffects(roomSlotId, targets, sceneEffects[roomSlotId], fadeMs)
            } else if (!scene.allLightsOn) {
                startEffects(targets, sceneEffects, fadeMs)
            }
        }
        return result
    }

    /**
     * What applying [scene] sends each bulb, its effects, and (with All lights on) the room slot.
     * With All lights on, every bulb shares the All-lights settings (the hidden room slot's).
     */
    private suspend fun sceneTargets(scene: SceneEntity): Triple<List<BulbApplyTarget>, Map<Long, GroupEffects>, Long?> {
        val roomSlotId = if (scene.allLightsOn) scenes.roomSlotId() else null
        val sceneEffects = scenes.getSceneEffects(scene.id)
        val effectsOf: (Long) -> GroupEffects? =
            if (scene.allLightsOn) { _ -> roomSlotId?.let(sceneEffects::get) } else sceneEffects::get
        return Triple(withRestingColors(scenes.applyTargets(scene.id), effectsOf), sceneEffects, roomSlotId)
    }

    /* FX (FX_REQUIREMENTS.md → Firing an FX) */

    /**
     * Fires [item] over whatever its targets show: the active scene (and its running effects), or
     * nothing yet. Its transition runs as its final release starts, unless [withTransition] is
     * false (the FX editor's Try it). [adventureId] is the adventure being played, if any: moving
     * into a library scene plays that adventure's copy of it when it has one. Returns false if the
     * FX has no bulbs to play on.
     */
    suspend fun fireFx(item: FxItem, adventureId: Long?, withTransition: Boolean = true): Boolean {
        if (item.hasNoTargets) return false
        val fx = item.fx
        val scene = _active.value?.sceneId?.let { scenes.getScene(it) }
        // Every assigned bulb, as the active scene has it (or all off, before any scene).
        val all = if (scene != null) sceneTargets(scene).first else scenes.applyTargets(NO_SCENE)
        val chosen = if (fx.targetAll) all else all.filter { it.slotId in item.targetSlotIds }
        if (chosen.isEmpty()) return false
        // Wave runs across the targets by bulb name.
        val ordered = chosen.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER, BulbApplyTarget::bulbName))
        val targets = ArrayList<FxTarget>(ordered.size)
        for (target in ordered) {
            val state = target.toLightStateOrNull()
            targets.add(
                if (state != null) {
                    FxTarget(target.slotId, target.toAddress(), state, wasOff = false)
                } else {
                    FxTarget(target.slotId, target.toAddress(), darkState(target), wasOff = true)
                },
            )
        }
        val libraryId = fx.transitionSceneId
        var onRelease: (suspend (Int) -> Unit)? = null
        if (withTransition) {
            if (fx.transition == FxTransition.LIGHTS_OFF) {
                onRelease = { ms: Int -> lightsOff(ms) }
            } else if (fx.transition == FxTransition.SCENE && libraryId != null) {
                // A Scene transition without its scene (deleted) is a Return.
                onRelease = { ms: Int -> moveInto(libraryId, adventureId, ms) }
            }
        }
        effects.fire(FxFire(fx.primitive, fx.pulse, fx.flicker, fx.spread, fx.waveOffsetMs, targets, onRelease))
        return true
    }

    /**
     * An FX's Lights off: every bulb fades out over [fadeMs]; nothing is active afterwards, so
     * Turn on brings up the default lighting (owner decision).
     */
    private suspend fun lightsOff(fadeMs: Int) {
        allOff(fadeMs)
        beforeOff = null
        _room.value = RoomLights(lightsOff = true, defaultOn = false)
    }

    /** An FX lands in a library scene: the adventure's copy of it if there is one, else the scene itself. */
    private suspend fun moveInto(libraryId: Long, adventureId: Long?, fadeMs: Int) {
        val copy = adventureId?.let { scenes.findCopyInAdventure(adventureId, libraryId) }
        if (copy != null) {
            play(copy, fadeOverrideMs = fadeMs, fromFx = false)
        } else {
            play(libraryId, fadeOverrideMs = fadeMs, fromFx = true)
        }
    }

    /** A bulb its scene has off, as an FX sees it: its color at zero brightness. */
    private fun darkState(target: BulbApplyTarget): LightState = LightState(
        hue = target.hue ?: 0f,
        saturation = target.saturation ?: 0f,
        brightness = 0f,
        kelvin = if (target.mode == LightMode.COLOR) null else (target.kelvin ?: 2_700),
    )

    /**
     * A Multi-color group fades in to its first palette color (at that color's brightness) rather
     * than its (hidden) single color, so its effects pick up from there without a jump.
     */
    private fun withRestingColors(
        targets: List<BulbApplyTarget>,
        effectsOf: (Long) -> GroupEffects?,
    ): List<BulbApplyTarget> {
        val count = targets.size
        val result = ArrayList<BulbApplyTarget>(count)
        for (i in 0 until count) {
            val target = targets[i]
            val fx = effectsOf(target.slotId)
            if (!target.isOn || fx == null || !fx.runsColorShift) {
                result.add(target)
                continue
            }
            val first = fx.palette[0]
            result.add(
                target.copy(mode = first.mode, hue = first.hue, saturation = first.saturation, kelvin = first.kelvin, brightness = first.brightness),
            )
        }
        return result
    }

    /** Groups that are lit and have effects, with their bulbs in name order (targets come sorted). */
    private fun startEffects(targets: List<BulbApplyTarget>, sceneEffects: Map<Long, GroupEffects>, sceneFadeMs: Int) {
        if (sceneEffects.isEmpty()) return
        val bulbsBySlot = LinkedHashMap<Long, MutableList<BulbAddress>>()
        val baseBySlot = HashMap<Long, LightState>()
        for (target in targets) {
            val fx = sceneEffects[target.slotId] ?: continue
            if (!fx.hasAny) continue
            val base = target.toLightStateOrNull() ?: continue
            baseBySlot[target.slotId] = base
            bulbsBySlot.getOrPut(target.slotId) { ArrayList(2) }.add(target.toAddress())
        }
        if (bulbsBySlot.isEmpty()) return
        val groups = ArrayList<EffectGroup>(bulbsBySlot.size)
        for ((slotId, bulbs) in bulbsBySlot) {
            groups.add(EffectGroup(slotId, baseBySlot.getValue(slotId), sceneEffects.getValue(slotId), bulbs))
        }
        // The bulbs confirmed the scene, but its fade is still playing out on them: let it finish.
        effects.start(groups, EFFECT_START_FADE_MS, startDelayMs = sceneFadeMs)
    }

    /**
     * All lights: the room's effects run as one group over every bulb, in name order, so an
     * Independent or Wave spread covers the whole room rather than each group separately.
     */
    private fun startRoomEffects(roomSlotId: Long, targets: List<BulbApplyTarget>, fx: GroupEffects?, sceneFadeMs: Int) {
        if (fx == null || !fx.hasAny || targets.isEmpty()) return
        val base = targets[0].toLightStateOrNull() ?: return
        val bulbs = targets.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER, BulbApplyTarget::bulbName)).map { it.toAddress() }
        effects.start(listOf(EffectGroup(roomSlotId, base, fx, bulbs)), EFFECT_START_FADE_MS, startDelayMs = sceneFadeMs)
    }

    companion object {
        /** No scene: apply targets come back with every bulb off. */
        private const val NO_SCENE = -1L

        /** How long Turn off lights (and turning the default off with nothing to go back to) fades. */
        const val TURN_OFF_FADE_MS = 1_000

        /** The scene's own fade has just finished; effects ease in from its base over this long. */
        const val EFFECT_START_FADE_MS = 300
    }
}
