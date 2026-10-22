package com.dungeonvibes.light.fx

import com.dungeonvibes.data.db.FlickerSettings
import com.dungeonvibes.data.db.FxPrimitive
import com.dungeonvibes.data.db.PulseSettings
import com.dungeonvibes.data.db.Spread
import com.dungeonvibes.data.scene.GroupEffects
import com.dungeonvibes.light.ApplicationScope
import com.dungeonvibes.light.BulbAddress
import com.dungeonvibes.light.LightController
import com.dungeonvibes.light.LightState
import com.dungeonvibes.light.MonotonicClock
import com.dungeonvibes.light.NetworkStatus
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.abs
import kotlin.random.Random
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.yield

/**
 * One group to animate: its scene base, its effect layers and its bulbs, in Wave order.
 * [base] is the group's scene settings (never "off"; groups that are off have no effects).
 */
data class EffectGroup(
    val slotId: Long,
    val base: LightState,
    val effects: GroupEffects,
    val bulbs: List<BulbAddress>,
)

/**
 * One bulb a fired FX plays on: its group, where to reach it, and what it shows underneath
 * ([base]; brightness 0 and [wasOff] for a bulb its scene has off, which is powered on for the FX
 * and off again after).
 */
data class FxTarget(
    val slotId: Long,
    val address: BulbAddress,
    val base: LightState,
    val wasOff: Boolean,
)

/**
 * A fired FX: one primitive on [targets] (in Wave order). [onRelease] (null for Return) runs once
 * every target's final release has begun, given that release's length, to carry out a transition.
 */
class FxFire(
    val primitive: FxPrimitive,
    val pulse: PulseSettings,
    val flicker: FlickerSettings,
    val spread: Spread,
    val waveOffsetMs: Int,
    val targets: List<FxTarget>,
    val onRelease: (suspend (releaseMs: Int) -> Unit)? = null,
)

/**
 * Plays effects on real bulbs (FX_REQUIREMENTS.md → Engine and sending). One run at a time:
 * starting again replaces the current run; [update] changes a run while it plays.
 *
 * Each bulb gets a regular frame every [EffectTiming.FRAME_MS] (5 per second) on a fixed grid,
 * aimed where the effect will be [EffectTiming.FRAME_TRANSITION_MS] ahead and faded over that
 * long, so the bulb is always mid-glide when the next frame lands and never stalls. The runner
 * also wakes exactly when a flash enters its attack, hold, release or idle part and sends straight
 * away, so flashes are crisp. A frame only goes out if it differs visibly from the last one sent.
 * Sends are fire-and-forget; a bulb that stops answering simply misses frames. Off Wi-Fi nothing
 * is sent, and sending resumes by itself when the phone is back.
 */
@Singleton
class EffectRunner @Inject constructor(
    private val lights: LightController,
    private val network: NetworkStatus,
    private val clock: MonotonicClock,
    @ApplicationScope private val scope: CoroutineScope,
) {
    private val _isRunning = MutableStateFlow(false)
    val isRunning: StateFlow<Boolean> = _isRunning.asStateFlow()

    private var runJob: Job? = null
    private var settleJob: Job? = null
    private var groups: List<EffectGroup> = emptyList()
    private var runId = 0L

    /* Messages to the running loop */

    private val pendingGroups = AtomicReference<List<EffectGroup>?>(null)
    private val pendingFlashes = ConcurrentLinkedQueue<Long>()
    private val pendingFires = ConcurrentLinkedQueue<Pair<FxFire, Random>>()

    /** What FX target bulbs outside the running groups show underneath, for settling on stop. */
    private val fxBases = HashMap<String, FxTarget>()

    /** Wakes the loop early when an update or flash arrives. */
    private val wake = Channel<Unit>(Channel.CONFLATED)

    /**
     * After [startDelayMs] (e.g. a scene's own fade, so it isn't cut short), turns every bulb on to
     * its first frame with a [startFadeMs] fade, waits for that fade, then animates. Groups without
     * effects or bulbs are skipped. Returns this run's id, for [stopIfCurrent]. [random] is
     * replaceable for tests.
     */
    @Synchronized
    fun start(
        groups: List<EffectGroup>,
        startFadeMs: Int,
        random: Random = Random.Default,
        startDelayMs: Int = 0,
    ): Long {
        runJob?.cancel()
        settleJob?.cancel()
        settleJob = null
        pendingGroups.set(null)
        pendingFlashes.clear()
        pendingFires.clear()
        fxBases.clear()
        runId++
        val active = playable(groups)
        this.groups = active
        if (active.isEmpty()) {
            runJob = null
            _isRunning.value = false
            return runId
        }
        _isRunning.value = true
        val id = runId
        runJob = scope.launch {
            if (startDelayMs > 0) delay(startDelayMs.toLong())
            run(active, startFadeMs.coerceAtLeast(0), random, id)
        }
        return runId
    }

    /**
     * Changes what's playing without a restart (live preview in the editor). Groups whose effects
     * and bulbs are unchanged keep their timing and only move their base; changed or new groups
     * start fresh (their bulbs fade on over [UPDATE_FADE_MS]); groups left out stop where they
     * are, for the caller to set. With nothing running this is [start]. Returns the run's id.
     */
    @Synchronized
    fun update(groups: List<EffectGroup>, startFadeMs: Int, random: Random = Random.Default): Long {
        if (runJob == null) return start(groups, startFadeMs, random)
        val active = playable(groups)
        if (active.isEmpty()) {
            stop(settleFadeMs = -1)
            return runId
        }
        this.groups = active
        pendingGroups.set(active)
        wake.trySend(Unit)
        return runId
    }

    /**
     * Plays a one-shot FX over whatever its targets show: on top of a running run, or in a run of
     * its own that ends when the FX does. A newer FX on the same bulbs replaces one still playing,
     * and its transition (or lack of one) replaces any still waiting.
     */
    @Synchronized
    fun fire(fx: FxFire, random: Random = Random.Default) {
        if (fx.targets.isEmpty()) return
        // The first base seen for a bulb is its scene's; later FX on it see the same scene.
        for (target in fx.targets) fxBases.getOrPut(keyOf(target.address)) { target }
        pendingFires.add(fx to random)
        if (runJob != null) {
            wake.trySend(Unit)
            return
        }
        settleJob?.cancel()
        settleJob = null
        runId++
        val id = runId
        _isRunning.value = true
        runJob = scope.launch { run(emptyList(), 0, random, id) }
    }

    /** Plays a burst now on [slotId]'s group, if it's running with Flashes (the editor's "Flash now"). */
    fun triggerFlash(slotId: Long) {
        pendingFlashes.add(slotId)
        wake.trySend(Unit)
    }

    /**
     * Stops animating. With [settleFadeMs] ≥ 0 the bulbs then fade to their groups' base; with a
     * negative value they're left wherever the last frame put them. Returns the settle, if any,
     * for callers that must wait for it (the background service before it stops).
     */
    @Synchronized
    fun stop(settleFadeMs: Int = SETTLE_FADE_MS): Job? {
        val job = runJob
        val stopped = groups
        val fxStopped = ArrayList(fxBases.values)
        runJob = null
        groups = emptyList()
        fxBases.clear()
        pendingFires.clear()
        _isRunning.value = false
        if (job == null) return null
        if (settleFadeMs < 0) {
            job.cancel()
            return null
        }
        val settling = scope.launch {
            // Wait for the last frame to finish sending, so it can't land after the settle.
            job.cancelAndJoin()
            settle(stopped, fxStopped, settleFadeMs)
        }
        settleJob = settling
        return settling
    }

    /** [stop], but only if run [id] is still the one playing (another screen may have taken over). */
    @Synchronized
    fun stopIfCurrent(id: Long, settleFadeMs: Int = SETTLE_FADE_MS) {
        if (id == runId) stop(settleFadeMs)
    }

    /** True while [id] is the run playing (a newer start or update by someone else replaces it). */
    @Synchronized
    fun isCurrent(id: Long): Boolean = id == runId && runJob != null

    private fun playable(groups: List<EffectGroup>): List<EffectGroup> =
        groups.filter { it.effects.hasAny && it.bulbs.isNotEmpty() }

    private suspend fun settle(groups: List<EffectGroup>, fxTargets: List<FxTarget>, fadeMs: Int): Unit = coroutineScope {
        val settled = HashSet<String>()
        for (group in groups) {
            for (address in group.bulbs) {
                settled.add(keyOf(address))
                launch { lights.turnOn(address, group.base, fadeMs) }
            }
        }
        // Bulbs an FX borrowed go back to their scene: their color, or off.
        for (target in fxTargets) {
            if (!settled.add(keyOf(target.address))) continue
            launch {
                if (target.wasOff) lights.turnOff(target.address, fadeMs) else lights.turnOn(target.address, target.base, fadeMs)
            }
        }
    }

    /** Ends run [id] once it has nothing left to play; false if a newer run has replaced it. */
    @Synchronized
    private fun finishRun(id: Long): Boolean {
        if (id != runId) return false
        if (pendingFires.isNotEmpty() || pendingGroups.get() != null) return false
        runJob = null
        groups = emptyList()
        fxBases.clear()
        _isRunning.value = false
        return true
    }

    private fun keyOf(address: BulbAddress): String = address.protocolId + '/' + address.serial

    /* The loop */

    /**
     * One running group; [origin] is when it (re)started, on the run's clock. [forFx] marks a
     * single bulb borrowed by an FX from outside the running groups, dropped once its FX ends.
     */
    private class GroupState(var group: EffectGroup, val run: GroupEffectRun, val origin: Long, val forFx: Boolean = false) {
        val tracks: Array<BulbTrack> = Array(group.bulbs.size) { BulbTrack(this, it, group.bulbs[it]) }
    }

    /** One bulb's sending state, and the FX shot playing on it, if any. */
    private class BulbTrack(val owner: GroupState, val index: Int, val address: BulbAddress) {
        var lastKey = Long.MIN_VALUE
        var nextFrame = 0L
        val sent = Hsbk()
        var hasSent = false

        var shot: Shot? = null

        /** When [shot] starts on this bulb, on the run's clock (Wave delays included). */
        var shotStart = 0L
        var lastShotKey = Long.MIN_VALUE

        /** The shot just ended: send the plain frame once more. */
        var shotEnding = false

        /** The bulb was off in its scene: power it off again once its FX is over. */
        var powerOffAfterShot = false
        var finished = false
    }

    private suspend fun run(initial: List<EffectGroup>, startFadeMs: Int, random: Random, id: Long): Unit = coroutineScope {
        // Drop any wake-up left over from an earlier run.
        while (wake.tryReceive().isSuccess) continue

        var states = ArrayList<GroupState>(initial.size)
        for (group in initial) states.add(GroupState(group, newRun(group, random), 0L))
        var tracks = flatten(states)
        val work = Hsbk()

        // Collecting keeps the network status live even with no screen watching it.
        val online = AtomicBoolean(true)
        val watcher = launch { network.isOnLocalNetwork.collect { online.set(it) } }

        /* Start: every bulb on to its first frame, then let the fade finish */

        if (tracks.isNotEmpty()) {
            coroutineScope {
                for (track in tracks) {
                    val state = firstFrame(track, work)
                    launch { lights.turnOn(track.address, state, startFadeMs) }
                }
            }
            delay(startFadeMs.toLong())
        }

        /* Animate */

        var transition: (suspend (Int) -> Unit)? = null
        var transitionAt = 0L
        var transitionMs = 0

        val t0 = clock.nowMs()
        while (true) {
            val t = clock.nowMs() - t0

            val update = pendingGroups.getAndSet(null)
            if (update != null) {
                states = reconcile(states, update, t, random, work)
                tracks = flatten(states)
            }
            while (true) {
                val slotId = pendingFlashes.poll() ?: break
                for (state in states) if (state.group.slotId == slotId) state.run.triggerFlash(t - state.origin)
            }
            while (true) {
                val (fire, fireRandom) = pendingFires.poll() ?: break
                states = applyFire(states, fire, fireRandom, t)
                tracks = flatten(states)
                // The newest FX decides how things end: its transition, or none for Return.
                transition = fire.onRelease
                if (transition != null) {
                    val (at, ms) = releaseOf(tracks, fire)
                    transitionAt = at
                    transitionMs = ms
                }
            }

            var wakeAt = t + EffectTiming.FRAME_MS
            if (!online.get()) {
                // Whatever the bulbs show after a Wi-Fi gap is unknown: resend everything.
                for (track in tracks) track.hasSent = false
            } else {
                for (track in tracks) {
                    val owner = track.owner
                    val run = owner.run
                    val bulb = track.index
                    val gt = t - owner.origin
                    val key = run.flashKey(bulb, gt)

                    // The FX shot on this bulb, if any: ended, waiting for its (Wave) start, or playing.
                    var shot = track.shot
                    var shotTime = 0L
                    var shotKey = NO_SHOT
                    if (shot != null) {
                        shotTime = t - track.shotStart
                        if (shotTime >= shot.end) {
                            track.shot = null
                            track.shotEnding = true
                            shot = null
                        } else if (shotTime >= 0) {
                            shotKey = shot.key(shotTime)
                        }
                    }

                    val frameDue = gt >= track.nextFrame
                    if (frameDue || key != track.lastKey || shotKey != track.lastShotKey || track.shotEnding) {
                        track.lastKey = key
                        track.lastShotKey = shotKey
                        if (frameDue) {
                            // A fixed grid: a late wake-up doesn't push every later frame back.
                            track.nextFrame += EffectTiming.FRAME_MS
                            if (track.nextFrame <= gt) track.nextFrame = gt + EffectTiming.FRAME_MS
                        }
                        var frameTransition = run.sample(bulb, gt, work, EffectTiming.FRAME_TRANSITION_MS)
                        if (shot != null && shotTime >= 0) shot.apply(shotTime, work)?.let { frameTransition = it }
                        val last = track.sent
                        if (!track.hasSent || !looksSame(last, work)) {
                            last.set(work)
                            track.hasSent = true
                            lights.sendEffectFrame(track.address, work.toLightState(), frameTransition)
                        }
                        if (track.shotEnding) {
                            track.shotEnding = false
                            track.finished = true
                            if (track.powerOffAfterShot) {
                                track.powerOffAfterShot = false
                                val address = track.address
                                launch { lights.turnOff(address, 0) }
                            }
                        }
                    }
                    // Sleep until the next frame, flash edge or FX edge, whichever comes first.
                    val frameAt = track.nextFrame + owner.origin
                    if (frameAt < wakeAt) wakeAt = frameAt
                    val edge = run.nextFlashEdge(bulb, gt)
                    if (edge != Long.MAX_VALUE) {
                        val edgeAt = edge + owner.origin
                        if (edgeAt in (t + 1) until wakeAt) wakeAt = edgeAt
                    }
                    if (shot != null) {
                        val shotEdge = if (shotTime < 0) track.shotStart else track.shotStart + shot.nextEdge(shotTime)
                        if (shotEdge in (t + 1) until wakeAt) wakeAt = shotEdge
                    }
                }
            }

            // Bulbs borrowed by an FX go once it's over on all of them.
            if (states.any { it.forFx && it.tracks.all { track -> track.finished && track.shot == null } }) {
                states = states.filterTo(ArrayList()) { !it.forFx || it.tracks.any { track -> !track.finished || track.shot != null } }
                tracks = flatten(states)
            }

            // A transition starts as the FX's final release does: hand it to the app scope, since
            // carrying it out (applying a scene) replaces this run.
            val pending = transition
            if (pending != null) {
                if (t >= transitionAt) {
                    transition = null
                    val ms = transitionMs
                    this@EffectRunner.scope.launch { pending(ms) }
                } else if (transitionAt < wakeAt) {
                    wakeAt = transitionAt
                }
            }

            // Nothing left to play (an FX-only run whose FX is over): end the run.
            if (states.isEmpty() && transition == null && finishRun(id)) {
                watcher.cancel()
                return@coroutineScope
            }

            val wait = wakeAt - (clock.nowMs() - t0)
            if (wait > 0) withTimeoutOrNull(wait) { wake.receive() } else yield()
        }
    }

    /**
     * Puts [fire]'s shots on its targets' tracks, borrowing bulbs that aren't in a running group
     * (they show their [FxTarget.base] underneath). Spread works as for layers: Together shares
     * one plan, Wave repeats it a delay apart, Independent plans each bulb on its own.
     */
    private fun CoroutineScope.applyFire(
        states: ArrayList<GroupState>,
        fire: FxFire,
        random: Random,
        t: Long,
    ): ArrayList<GroupState> {
        val byAddress = HashMap<String, BulbTrack>()
        for (state in states) for (track in state.tracks) byAddress[keyOf(track.address)] = track
        val targets = fire.targets
        val count = targets.size
        val seed = random.nextLong()
        val shared = if (fire.spread == Spread.TOGETHER) makeShot(fire, Random(seed)) else null
        val offset = fire.waveOffsetMs.coerceIn(EffectTiming.MIN_WAVE_OFFSET_MS, EffectTiming.MAX_WAVE_OFFSET_MS).toLong()
        for (i in 0 until count) {
            val target = targets[i]
            val key = keyOf(target.address)
            var track = byAddress[key]
            if (track == null) {
                val group = EffectGroup(target.slotId, target.base, GroupEffects(), listOf(target.address))
                val borrowed = GroupState(group, newRun(group, random), t, forFx = true)
                states.add(borrowed)
                track = borrowed.tracks[0]
                byAddress[key] = track
                if (target.wasOff) {
                    // Its scene has it off: power it up (at brightness 0) for the FX, and off after.
                    track.powerOffAfterShot = true
                    val address = target.address
                    launch { lights.powerOn(address, 0) }
                }
            }
            track.shot = when (fire.spread) {
                Spread.TOGETHER -> shared!!
                Spread.WAVE -> makeShot(fire, Random(seed))
                Spread.INDEPENDENT -> makeShot(fire, Random(random.nextLong()))
            }
            track.shotStart = t + if (fire.spread == Spread.WAVE) offset * i else 0L
            track.lastShotKey = Long.MIN_VALUE
            track.shotEnding = false
            track.finished = false
        }
        return states
    }

    private fun makeShot(fire: FxFire, random: Random): Shot = when (fire.primitive) {
        FxPrimitive.PULSE -> PulseShot(fire.pulse, random)
        FxPrimitive.FLICKER -> FlickerShot(fire.flicker, random)
    }

    /** When the last target's final release starts, and how long the longest one is. */
    private fun releaseOf(tracks: Array<BulbTrack>, fire: FxFire): Pair<Long, Int> {
        val wanted = HashSet<String>()
        for (target in fire.targets) wanted.add(keyOf(target.address))
        var at = 0L
        var ms = 0
        for (track in tracks) {
            val shot = track.shot ?: continue
            if (keyOf(track.address) !in wanted) continue
            at = maxOf(at, track.shotStart + shot.releaseStart)
            ms = maxOf(ms, shot.releaseMs)
        }
        return at to (if (ms > 0) ms else TRANSITION_FALLBACK_MS)
    }

    private fun newRun(group: EffectGroup, random: Random) =
        GroupEffectRun(Hsbk.of(group.base), group.effects, group.bulbs.size, random)

    /** The frame a bulb starts on (its group's time 0), remembered as already sent. */
    private fun firstFrame(track: BulbTrack, work: Hsbk): LightState {
        val run = track.owner.run
        run.flashKey(track.index, 0L)
        run.sample(track.index, 0L, work)
        track.sent.set(work)
        track.hasSent = true
        return work.toLightState()
    }

    /**
     * Applies an [update]: unchanged groups (same effects and bulbs) keep their timelines and just
     * take the new base, resending at once; anything else starts fresh at [t], fading its bulbs on.
     */
    private fun CoroutineScope.reconcile(
        states: List<GroupState>,
        update: List<EffectGroup>,
        t: Long,
        random: Random,
        work: Hsbk,
    ): ArrayList<GroupState> {
        val result = ArrayList<GroupState>(update.size)
        for (group in update) {
            val kept = states.firstOrNull {
                it.group.slotId == group.slotId && it.group.effects == group.effects && it.group.bulbs == group.bulbs
            }
            if (kept != null) {
                if (kept.group.base != group.base) {
                    kept.run.setBase(Hsbk.of(group.base))
                    // Show the new base right away instead of at the next frame.
                    for (track in kept.tracks) track.nextFrame = t - kept.origin
                }
                kept.group = group
                result.add(kept)
                continue
            }
            val fresh = GroupState(group, newRun(group, random), t)
            for (track in fresh.tracks) {
                val state = firstFrame(track, work)
                launch { lights.turnOn(track.address, state, UPDATE_FADE_MS) }
            }
            result.add(fresh)
        }
        // Bulbs an FX borrowed stay until their FX is over.
        for (state in states) if (state.forFx) result.add(state)
        return result
    }

    private fun flatten(states: List<GroupState>): Array<BulbTrack> {
        var total = 0
        for (state in states) total += state.tracks.size
        val result = arrayOfNulls<BulbTrack>(total)
        var i = 0
        for (state in states) for (track in state.tracks) result[i++] = track
        @Suppress("UNCHECKED_CAST")
        return result as Array<BulbTrack>
    }

    companion object {
        const val SETTLE_FADE_MS = 500

        /** Fade for bulbs joining a run that's already playing. */
        const val UPDATE_FADE_MS = 300

        /** Transition fade after an FX with no release of its own (e.g. a Flicker). */
        const val TRANSITION_FALLBACK_MS = 500

        private const val NO_SHOT = -2L

        /** Differences smaller than these aren't worth a packet. */
        private const val HUE_STEP = 0.5f
        private const val UNIT_STEP = 0.004f
        private const val KELVIN_STEP = 10f

        internal fun looksSame(a: Hsbk, b: Hsbk): Boolean =
            abs(a.brightness - b.brightness) < UNIT_STEP &&
                abs(a.saturation - b.saturation) < UNIT_STEP &&
                abs(a.kelvin - b.kelvin) < KELVIN_STEP &&
                // Hue doesn't matter for a white.
                (maxOf(a.saturation, b.saturation) < Hsbk.WHITE_SATURATION || hueDistance(a.hue, b.hue) < HUE_STEP)
    }
}
