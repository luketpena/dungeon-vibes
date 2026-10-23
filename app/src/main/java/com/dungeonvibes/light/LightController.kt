package com.dungeonvibes.light

import com.dungeonvibes.data.db.BulbApplyTarget
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicReference
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope

/**
 * Bulbs that didn't confirm the scene, and the slots they belong to (for warning badges on the
 * active tile). Everything else was applied.
 */
data class ApplyResult(
    val failedBulbIds: List<Long>,
    val failedSlotIds: Set<Long> = emptySet(),
)

/**
 * The single entry point for bulb communication. Routes each call to the adapter for the bulb's
 * protocol and owns the sending rules from REQUIREMENTS.md → Bulb control → Sending rules.
 */
@Singleton
class LightController @Inject constructor(
    adapters: Set<@JvmSuppressWildcards LightAdapter>,
    @ApplicationScope private val scope: CoroutineScope,
    /** Every command is recorded here for the screen to follow (the default serves tests). */
    private val mirror: LightMirror = LightMirror(),
    /** Scales the brightness of everything sent (the lights menu's master dimmer). */
    private val dimmer: MasterDimmer = MasterDimmer(),
) {
    private val adapterList: List<LightAdapter> = adapters.toList()
    private val adaptersById: Map<String, LightAdapter> = adapters.associateBy { it.protocolId }

    /** Last power state we know of per bulb (from discovery and our own commands). */
    private val knownPower = ConcurrentHashMap<String, Boolean>()

    /** The scene apply in flight, cancelled when a newer one starts. */
    private val currentApply = AtomicReference<Job?>(null)

    /** One conflated channel and sender coroutine per bulb being previewed. */
    private val previewChannels = ConcurrentHashMap<String, Channel<LightState>>()

    /** What each lit bulb was last asked to show, before the dimmer, so a dimmer change can resend it. */
    private val lastLook = ConcurrentHashMap<String, Pair<BulbAddress, LightState>>()

    /** [state] with the master dimmer applied; remembered (undimmed) as the bulb's look. */
    private fun dimmed(address: BulbAddress, key: String, state: LightState): LightState {
        lastLook[key] = address to state
        val level = dimmer.level.value
        return if (level >= MasterDimmer.FULL) state else state.copy(brightness = state.brightness * level)
    }

    /**
     * Sets the master dimmer and moves every lit bulb to its look at the new level over
     * [DIMMER_FADE_MS]. Running effects pick it up with their next frame anyway.
     */
    fun setDimmer(level: Float) {
        if (!dimmer.set(level)) return
        for ((key, look) in lastLook) {
            if (knownPower[key] != true) continue
            val (address, state) = look
            val adapter = adapterFor(address) ?: continue
            val sent = dimmed(address, key, state)
            mirror.record(key, sent, DIMMER_FADE_MS)
            scope.launch { adapter.setState(address, sent, DIMMER_FADE_MS, reliable = false) }
        }
    }

    /* Discovery */

    /** Scans every available adapter at once; completes after [timeoutMs]. */
    fun discover(timeoutMs: Long): Flow<DiscoveredBulb> {
        val available = adapterList.filter { it.isAvailable }
        if (available.isEmpty()) return emptyFlow()
        return available.map { it.discover(timeoutMs) }.merge().onEach { bulb ->
            val on = bulb.isOn ?: return@onEach
            val key = keyOf(bulb.address)
            knownPower[key] = on
            // The glow follows what bulbs report until the app sends them something itself.
            if (!on) {
                mirror.seed(key, null)
            } else {
                bulb.state?.let { mirror.seed(key, it) }
            }
        }
    }

    /* Applying a scene (reliable) */

    /**
     * Applies a scene: every target turns on to its settings or off, in parallel, with
     * acknowledgements. Starting a new apply cancels the previous one, so rapid taps on several
     * tiles end with only the last scene applied. Awaiting a cancelled result throws
     * CancellationException.
     */
    fun applyScene(targets: List<BulbApplyTarget>, fadeMs: Int): Deferred<ApplyResult> {
        val job = scope.async(start = CoroutineStart.LAZY) { applyAll(targets, fadeMs) }
        currentApply.getAndSet(job)?.cancel()
        job.start()
        return job
    }

    private suspend fun applyAll(targets: List<BulbApplyTarget>, fadeMs: Int): ApplyResult = supervisorScope {
        val count = targets.size
        if (count == 0) return@supervisorScope ApplyResult(emptyList())

        val results = ArrayList<Deferred<Boolean>>(count)
        for (i in 0 until count) {
            val target = targets[i]
            results.add(async { applyOne(target, fadeMs) })
        }
        var failed: MutableList<Long>? = null
        var failedSlots: MutableSet<Long>? = null
        for (i in 0 until count) {
            if (results[i].await()) continue
            if (failed == null) failed = ArrayList()
            if (failedSlots == null) failedSlots = HashSet()
            failed.add(targets[i].bulbId)
            failedSlots.add(targets[i].slotId)
        }
        ApplyResult(failed ?: emptyList(), failedSlots ?: emptySet())
    }

    private suspend fun applyOne(target: BulbApplyTarget, fadeMs: Int): Boolean {
        val address = target.toAddress()
        val state = target.toLightStateOrNull()
        val result = if (state == null) {
            turnOff(address, fadeMs)
        } else {
            turnOn(address, state, fadeMs)
        }
        return result.isSuccess
    }

    /* Single-bulb commands (reliable) */

    /**
     * Turns a bulb on to [state] with a [fadeMs] fade. A bulb that may be off first gets its color
     * set instantly, then fades its power up, so it never flashes its old color.
     */
    suspend fun turnOn(address: BulbAddress, requested: LightState, fadeMs: Int): Result<Unit> {
        val adapter = adapterFor(address) ?: return unavailable(address)
        val key = keyOf(address)
        val state = dimmed(address, key, requested)
        mirror.record(key, state, fadeMs)
        if (knownPower[key] == true) {
            return adapter.setState(address, state, fadeMs, reliable = true)
        }
        val colorResult = adapter.setState(address, state, 0, reliable = true)
        if (colorResult.isFailure) return colorResult
        val powerResult = adapter.setPower(address, true, fadeMs, reliable = true)
        if (powerResult.isSuccess) knownPower[key] = true
        return powerResult
    }

    /** Fades a bulb's power up, keeping whatever color it last had. */
    suspend fun powerOn(address: BulbAddress, fadeMs: Int): Result<Unit> {
        val adapter = adapterFor(address) ?: return unavailable(address)
        val result = adapter.setPower(address, true, fadeMs, reliable = true)
        if (result.isSuccess) knownPower[keyOf(address)] = true
        return result
    }

    suspend fun turnOff(address: BulbAddress, fadeMs: Int): Result<Unit> {
        val adapter = adapterFor(address) ?: return unavailable(address)
        val key = keyOf(address)
        mirror.record(key, null, fadeMs)
        lastLook.remove(key)
        val result = adapter.setPower(address, false, fadeMs, reliable = true)
        if (result.isSuccess) knownPower[key] = false
        return result
    }

    suspend fun identify(address: BulbAddress): Result<Unit> {
        val adapter = adapterFor(address) ?: return unavailable(address)
        adapter.identify(address)
        return Result.success(Unit)
    }

    /* Live preview (fire-and-forget) */

    /**
     * Sends a preview of [state] (null = off) to one bulb. Never blocks: each bulb sends at most
     * every [PREVIEW_INTERVAL_MS], always its newest value, and stale values are dropped.
     */
    fun preview(address: BulbAddress, state: LightState?) {
        val key = keyOf(address)
        val channel = previewChannels[key] ?: previewChannels.computeIfAbsent(key) { startPreviewSender(address) }
        channel.trySend(state ?: PREVIEW_OFF)
    }

    private fun startPreviewSender(address: BulbAddress): Channel<LightState> {
        // CONFLATED keeps only the newest value, like a single-slot mailbox.
        val channel = Channel<LightState>(Channel.CONFLATED)
        scope.launch {
            for (state in channel) {
                sendPreview(address, state)
                delay(PREVIEW_INTERVAL_MS)
            }
        }
        return channel
    }

    private suspend fun sendPreview(address: BulbAddress, requested: LightState) {
        val adapter = adapterFor(address) ?: return
        val key = keyOf(address)
        if (requested === PREVIEW_OFF) lastLook.remove(key)
        val state = if (requested === PREVIEW_OFF) requested else dimmed(address, key, requested)
        mirror.record(key, if (requested === PREVIEW_OFF) null else state, PREVIEW_FADE_MS)
        if (requested === PREVIEW_OFF) {
            if (adapter.setPower(address, false, PREVIEW_FADE_MS, reliable = false).isSuccess) {
                knownPower[key] = false
            }
            return
        }
        if (knownPower[key] == true) {
            adapter.setState(address, state, PREVIEW_FADE_MS, reliable = false)
            return
        }
        adapter.setState(address, state, 0, reliable = false)
        if (adapter.setPower(address, true, PREVIEW_FADE_MS, reliable = false).isSuccess) {
            knownPower[key] = true
        }
    }

    /* Effects (fire-and-forget) */

    /**
     * One effect frame, faded over [transitionMs]. Assumes the bulb is on: EffectRunner turns its
     * bulbs on (reliably) before animating. Failures are ignored; the next frame tries again.
     */
    suspend fun sendEffectFrame(address: BulbAddress, requested: LightState, transitionMs: Int) {
        val adapter = adapterFor(address) ?: return
        val key = keyOf(address)
        val state = dimmed(address, key, requested)
        mirror.record(key, state, transitionMs)
        adapter.setState(address, state, transitionMs, reliable = false)
    }

    /* Internals */

    private fun adapterFor(address: BulbAddress): LightAdapter? {
        val adapter = adaptersById[address.protocolId] ?: return null
        return if (adapter.isAvailable) adapter else null
    }

    private fun unavailable(address: BulbAddress): Result<Unit> =
        Result.failure(LightException("No available adapter for protocol '${address.protocolId}'"))

    private fun keyOf(address: BulbAddress): String = LightMirror.keyOf(address.protocolId, address.serial)

    companion object {
        const val PREVIEW_FADE_MS = 100

        /** How quickly bulbs follow the master dimmer. */
        const val DIMMER_FADE_MS = 300
        const val PREVIEW_INTERVAL_MS = 100L

        /** Sentinel meaning "off" in the preview channel, compared by identity. */
        private val PREVIEW_OFF = LightState(0f, 0f, 0f, null)
    }
}
