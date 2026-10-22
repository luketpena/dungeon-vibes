package com.dungeonvibes.light.fx

import com.dungeonvibes.data.db.FlickerSettings
import com.dungeonvibes.data.db.PulseSettings
import kotlin.random.Random

/*
 * One-shot FX on one bulb (FX_REQUIREMENTS.md → Primitives). A shot is planned in full when it's
 * fired (every pulse, every flicker dip), so it's deterministic and can be sampled at any time.
 * Times are ms since the shot started on this bulb. A shot sits on top of whatever the bulb is
 * showing and takes precedence while it plays.
 */
internal interface Shot {
    /** When the shot is over. */
    val end: Long

    /** When this bulb's final release starts (or the shot ends, without one). Transitions start here. */
    val releaseStart: Long

    /** How long that final release is (0 for none). */
    val releaseMs: Int

    /** Changes at every edge (a pulse's attack, hold, release; a flicker change). */
    fun key(t: Long): Long

    /** The next edge after [t]; [end] at the latest. */
    fun nextEdge(t: Long): Long

    /** Applies the shot at [t] to [out]; returns the transition to send with, or null for the regular one. */
    fun apply(t: Long, out: Hsbk): Int?
}

/** A burst of Attack-Hold-Release pulses, moving only the chosen channels. */
internal class PulseShot(private val settings: PulseSettings, random: Random) : Shot {
    private val attack = settings.attackMs.coerceAtLeast(0)
    private val hold = settings.holdMs.coerceAtLeast(0)
    private val release = settings.releaseMs.coerceAtLeast(0)
    private val length = attack + hold + release
    private val color = ColorPoint.of(settings.color)

    private val starts: LongArray
    private val peaks: FloatArray
    private val count: Int

    /** Reused for the blend so [apply] doesn't allocate. */
    private val blended = Hsbk()

    init {
        count = random.between(settings.pulsesMin, settings.pulsesMax).coerceIn(1, MAX_PULSES)
        starts = LongArray(count)
        peaks = FloatArray(count)
        val high = maxOf(settings.peak, settings.peakMin).coerceIn(0f, 1f)
        val low = minOf(settings.peak, settings.peakMin).coerceIn(0f, 1f)
        // Like Flashes: one random pulse of a burst reaches the top, the rest land anywhere in range.
        val strong = if (count > 1) random.nextInt(count) else -1
        var at = 0L
        for (i in 0 until count) {
            starts[i] = at
            peaks[i] = if (i == strong) high else random.between(low, high)
            at += length + random.between(settings.spacingMinMs, settings.spacingMaxMs).coerceAtLeast(EffectTiming.MIN_STEP_MS)
        }
    }

    override val end: Long = starts[count - 1] + length
    override val releaseStart: Long = starts[count - 1] + attack + hold
    override val releaseMs: Int = release

    /** The pulse playing at [t], or the next one; [count] when over. */
    private fun pulseAt(t: Long): Int {
        for (i in 0 until count) if (t < starts[i] + length) return i
        return count
    }

    private fun segmentOf(i: Int, t: Long): Int {
        val rel = t - starts[i]
        return when {
            rel < 0 -> FlashTimeline.IDLE
            rel < attack -> FlashTimeline.ATTACK
            rel < attack + hold -> FlashTimeline.HOLD
            else -> FlashTimeline.RELEASE
        }
    }

    private fun levelAt(i: Int, t: Long): Float {
        val rel = t - starts[i]
        if (rel < 0 || rel >= length) return 0f
        if (rel < attack) return rel.toFloat() / attack
        if (rel < attack + hold) return 1f
        // A curved release: it stays near the pulse's color for most of the release and drops
        // quickly at the end, so the pulse reads as its color rather than the in-between ones
        // (purple to green passes through teal).
        val x = (rel - attack - hold).toFloat() / release
        return 1f - x * x
    }

    override fun key(t: Long): Long {
        val i = pulseAt(t)
        if (i == count) return -1L
        return i * 4L + segmentOf(i, t)
    }

    override fun nextEdge(t: Long): Long {
        for (i in 0 until count) {
            val s = starts[i]
            if (s > t) return s
            if (s + attack > t) return s + attack
            if (s + attack + hold > t) return s + attack + hold
            if (s + length > t) return s + length
        }
        return end
    }

    override fun apply(t: Long, out: Hsbk): Int? {
        val i = pulseAt(t)
        if (i == count) return null
        val segment = segmentOf(i, t)
        // During attack and release, aim for where the envelope will be one segment (or frame) on.
        var lead = 0L
        var transition: Int? = null
        if (segment == FlashTimeline.ATTACK || segment == FlashTimeline.RELEASE) {
            val segmentEnd = starts[i] + if (segment == FlashTimeline.ATTACK) attack else length
            lead = minOf(segmentEnd - t, EffectTiming.FRAME_MS).coerceAtLeast(0L)
            transition = lead.toInt()
        }
        val level = levelAt(i, t + lead)
        if (level <= 0f) return transition
        blendColor(out.hue, out.saturation, out.kelvin, color.hue, color.saturation, color.kelvin, level, blended)
        if (settings.affectsColor) {
            out.hue = blended.hue
            out.kelvin = blended.kelvin
        }
        if (settings.affectsSaturation) out.saturation = blended.saturation
        // Brightest wins: a pulse lifts the light toward its peak but never dims what's underneath
        // (e.g. a breathing effect already above the peak).
        val peak = peaks[i]
        if (settings.affectsBrightness && peak > out.brightness) out.brightness += (peak - out.brightness) * level
        return transition
    }

    companion object {
        const val MAX_PULSES = 5
    }
}

/** A failing light: hard dips of random depth at random moments, recovering between them. */
internal class FlickerShot(settings: FlickerSettings, random: Random) : Shot {
    private val starts: LongArray
    private val dips: FloatArray
    private val count: Int

    override val end: Long = settings.durationMs.coerceIn(MIN_DURATION_MS, MAX_DURATION_MS).toLong()
    override val releaseStart: Long get() = end
    override val releaseMs: Int = 0

    init {
        val depth = settings.depth.coerceIn(0f, 1f)
        // At most 10 changes a second per bulb (FX_REQUIREMENTS.md → Flicker).
        val minInterval = settings.intervalMinMs.coerceAtLeast(MIN_INTERVAL_MS)
        val maxInterval = settings.intervalMaxMs.coerceAtLeast(minInterval)
        val startList = ArrayList<Long>()
        val dipList = ArrayList<Float>()
        var at = 0L
        var dipping = true
        while (at < end) {
            startList.add(at)
            // A dip takes off at least 40 % of the depth, so every one is visible; between dips, full light.
            dipList.add(if (dipping) random.between(depth * 0.4f, depth) else 0f)
            at += random.between(minInterval, maxInterval)
            dipping = !dipping
        }
        count = startList.size
        starts = startList.toLongArray()
        dips = dipList.toFloatArray()
    }

    private fun stepAt(t: Long): Int {
        if (t >= end) return count
        var i = 0
        while (i + 1 < count && starts[i + 1] <= t) i++
        return i
    }

    override fun key(t: Long): Long {
        val i = stepAt(t)
        return if (i == count) -1L else i.toLong()
    }

    override fun nextEdge(t: Long): Long {
        val i = stepAt(t)
        if (i == count) return end
        return if (i + 1 < count) starts[i + 1] else end
    }

    override fun apply(t: Long, out: Hsbk): Int? {
        val i = stepAt(t)
        if (i == count) return null
        out.brightness *= 1f - dips[i]
        // Hard cuts: every change lands at once.
        return 0
    }

    companion object {
        const val MIN_INTERVAL_MS = 100
        const val MIN_DURATION_MS = 100
        const val MAX_DURATION_MS = 60_000
    }
}
