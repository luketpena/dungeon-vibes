package com.dungeonvibes.light.fx

import com.dungeonvibes.data.db.BrightnessMode
import com.dungeonvibes.data.db.BrightnessShiftSettings
import com.dungeonvibes.data.db.ColorOrder
import com.dungeonvibes.data.db.ColorShiftSettings
import com.dungeonvibes.data.db.FlashSettings
import com.dungeonvibes.data.db.FxColor
import com.dungeonvibes.data.db.LightMode
import com.dungeonvibes.data.db.SpinSettings
import com.dungeonvibes.data.db.Spread
import com.dungeonvibes.data.scene.GroupEffects
import com.dungeonvibes.light.LightState
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.random.Random

/*
 * The effect engine (FX_REQUIREMENTS.md → How the layers mix, Spread). Pure math: no bulbs, no
 * coroutines. Time is "ms since the effect started", passed in; randomness comes from the Random
 * handed in, so a seeded Random makes every run reproducible in tests.
 *
 * Timelines only move forward: each keeps its current step (a color hold/blend, a brightness
 * cycle, a flash pulse) and moves on when asked about a later time.
 */

object EffectTiming {
    /** Regular update interval per bulb: at most 5 updates per second. */
    const val FRAME_MS = 200L

    /**
     * Each regular frame aims this far ahead and fades over this long: longer than [FRAME_MS], so
     * a frame that arrives a little late (Wi-Fi, a busy phone) still finds the bulb gliding
     * instead of parked, which is what made smooth effects look like stairs.
     */
    const val FRAME_TRANSITION_MS = 300L

    /** Shortest color or brightness step, so a zero setting can't spin forever. */
    const val MIN_STEP_MS = 50

    /** Shortest gap between flashes or bursts. */
    const val MIN_GAP_MS = 200

    const val MIN_WAVE_OFFSET_MS = 50
    const val MAX_WAVE_OFFSET_MS = 10_000

    /** After this many steps in one catch-up (e.g. after a long pause), a timeline restarts at now. */
    const val MAX_CATCH_UP_STEPS = 1_000
}

/** Mutable hue/saturation/brightness/kelvin, reused every frame so the hot path doesn't allocate. */
class Hsbk {
    /** 0–360 */
    var hue = 0f

    /** 0–1; 0 is a white at [kelvin]. */
    var saturation = 0f

    /** 0–1 */
    var brightness = 0f
    var kelvin = LightState.COLOR_KELVIN.toFloat()

    fun set(other: Hsbk) {
        hue = other.hue
        saturation = other.saturation
        brightness = other.brightness
        kelvin = other.kelvin
    }

    /** Whites (no saturation) go out in white mode; anything else as a color with this white point. */
    fun toLightState(): LightState {
        val k = kelvin.roundToInt()
        return if (saturation < WHITE_SATURATION) {
            LightState(hue, 0f, brightness, k)
        } else {
            LightState(hue, saturation, brightness, null, k)
        }
    }

    companion object {
        /** Below this, a blend reads as white (and LIFX can't show the difference anyway). */
        const val WHITE_SATURATION = 0.002f

        /** The engine's form of a scene base. */
        fun of(state: LightState): Hsbk = Hsbk().apply {
            hue = state.hue
            brightness = state.brightness
            val white = state.kelvin
            if (white != null) {
                saturation = 0f
                kelvin = white.toFloat()
            } else {
                saturation = state.saturation
                kelvin = state.colorKelvin.toFloat()
            }
        }
    }
}

/**
 * What a group shows when its effects stop or aren't running: its first palette color (at that
 * color's brightness) when it's Multi-color, otherwise its base.
 */
fun restingState(base: LightState, effects: GroupEffects?): LightState {
    if (effects == null || !effects.runsColorShift) return base
    val first = effects.palette[0]
    val brightness = first.brightness.coerceIn(0f, 1f)
    return if (first.mode == LightMode.WHITE) {
        LightState(first.hue, 0f, brightness, first.kelvin)
    } else {
        LightState(first.hue, first.saturation, brightness, null)
    }
}

/* Color math */

/** A palette or flash color split into the numbers blends need ([brightness]: palette colors only). */
internal class ColorPoint(val hue: Float, val saturation: Float, val kelvin: Float, val brightness: Float = 1f) {
    companion object {
        fun of(color: FxColor): ColorPoint = if (color.mode == LightMode.WHITE) {
            ColorPoint(color.hue, 0f, color.kelvin.toFloat(), color.brightness.coerceIn(0f, 1f))
        } else {
            ColorPoint(color.hue, color.saturation, LightState.COLOR_KELVIN.toFloat(), color.brightness.coerceIn(0f, 1f))
        }
    }
}

/**
 * Writes the blend of (aH, aS, aK) and (bH, bS, bK) at [f] (0 = a, 1 = b) into [out]'s color.
 * Hue takes the short way round the wheel. A white has no hue of its own, so blending to or from
 * one keeps the color's hue and only fades saturation and white point: no rainbow detour.
 */
internal fun blendColor(aH: Float, aS: Float, aK: Float, bH: Float, bS: Float, bK: Float, f: Float, out: Hsbk) {
    out.hue = when {
        aS <= 0f -> bH
        bS <= 0f -> aH
        else -> blendHue(aH, bH, f)
    }
    out.saturation = aS + (bS - aS) * f
    out.kelvin = aK + (bK - aK) * f
}

internal fun blendHue(a: Float, b: Float, f: Float): Float {
    var delta = b - a
    if (delta > 180f) delta -= 360f else if (delta < -180f) delta += 360f
    var h = a + delta * f
    if (h < 0f) h += 360f else if (h >= 360f) h -= 360f
    return h
}

/** Smallest distance between two hues, 0–180. */
internal fun hueDistance(a: Float, b: Float): Float {
    val d = abs(a - b) % 360f
    return if (d > 180f) 360f - d else d
}

internal fun Random.between(min: Int, max: Int): Int = if (max <= min) min else min + nextInt(max - min + 1)

internal fun Random.between(min: Float, max: Float): Float = if (max <= min) min else min + nextFloat() * (max - min)

/* Timelines */

/** Color shift: hold on one palette color (and its brightness), blend to the next, repeat. */
internal class ColorShiftTimeline(
    private val settings: ColorShiftSettings,
    private val palette: Array<ColorPoint>,
    private val random: Random,
    desync: Boolean,
) {
    private val count = palette.size
    private val randomOrder = settings.order == ColorOrder.RANDOM
    private val pingPong = settings.order == ColorOrder.PING_PONG

    /** Ping-pong: +1 walking up the palette, -1 walking back down. */
    private var direction = 1
    private var from: Int
    private var to: Int
    private var stepStart = 0L
    private var holdMs = 0
    private var blendMs = 0

    init {
        from = if (desync || randomOrder) random.nextInt(count) else 0
        to = nextIndex(from)
        pickDurations()
        // Independent bulbs start somewhere inside their first step, so they don't move in step.
        if (desync) stepStart = -random.nextLong((holdMs + blendMs).toLong())
    }

    fun sample(t: Long, out: Hsbk) {
        var steps = 0
        while (t >= stepStart + holdMs + blendMs) {
            if (++steps > EffectTiming.MAX_CATCH_UP_STEPS) {
                stepStart = t
                break
            }
            stepStart += holdMs + blendMs
            from = to
            to = nextIndex(from)
            pickDurations()
        }
        val a = palette[from]
        val inStep = t - stepStart
        if (inStep < holdMs) {
            out.hue = a.hue
            out.saturation = a.saturation
            out.kelvin = a.kelvin
            out.brightness = a.brightness
            return
        }
        val b = palette[to]
        val f = ((inStep - holdMs).toFloat() / blendMs).coerceIn(0f, 1f)
        blendColor(a.hue, a.saturation, a.kelvin, b.hue, b.saturation, b.kelvin, f, out)
        // Each palette color has its own brightness; the blend glides between them too.
        out.brightness = a.brightness + (b.brightness - a.brightness) * f
    }

    /** In sequence loops; random never repeats the color it's on. */
    private fun nextIndex(current: Int): Int {
        if (count < 2) return current
        if (pingPong) {
            // Turn round at either end: 1, 2, 3, 2, 1, 2…
            if (current + direction !in 0 until count) direction = -direction
            return current + direction
        }
        if (!randomOrder) return if (current + 1 == count) 0 else current + 1
        val pick = random.nextInt(count - 1)
        return if (pick >= current) pick + 1 else pick
    }

    private fun pickDurations() {
        holdMs = random.between(settings.holdMinMs, settings.holdMaxMs).coerceAtLeast(0)
        blendMs = random.between(settings.blendMinMs, settings.blendMaxMs).coerceAtLeast(0)
        if (holdMs + blendMs < EffectTiming.MIN_STEP_MS) blendMs = EffectTiming.MIN_STEP_MS - holdMs
    }
}

/** Brightness shift: a smooth low-high-low cycle, or a glide between random levels at random intervals. */
internal class BrightnessTimeline(
    settings: BrightnessShiftSettings,
    private val random: Random,
    desync: Boolean,
) {
    private val low: Float
    private val high: Float
    private val flicker = settings.mode == BrightnessMode.FLICKER
    private val speedMin = settings.speedMinMs
    private val speedMax = settings.speedMaxMs
    private var stepStart = 0L
    private var lengthMs: Int

    /** Flicker glides from [fromLevel] to [level] over each step. */
    private var fromLevel = 0f
    private var level = 0f

    init {
        val a = settings.levelMin.coerceIn(0f, 1f)
        val b = settings.levelMax.coerceIn(0f, 1f)
        low = minOf(a, b)
        high = maxOf(a, b)
        lengthMs = pickLength()
        if (flicker) {
            fromLevel = random.between(low, high)
            level = random.between(low, high)
        }
        if (desync) stepStart = -random.nextLong(lengthMs.toLong())
    }

    fun sample(t: Long): Float {
        var steps = 0
        while (t >= stepStart + lengthMs) {
            if (++steps > EffectTiming.MAX_CATCH_UP_STEPS) {
                stepStart = t
                break
            }
            stepStart += lengthMs
            lengthMs = pickLength()
            if (flicker) {
                fromLevel = level
                level = random.between(low, high)
            }
        }
        val phase = (t - stepStart).toFloat() / lengthMs
        // Flicker: a straight glide to the next random level, so it never parks between changes.
        if (flicker) return fromLevel + (level - fromLevel) * phase
        // Smooth: starts at the low point, eases up to the high point and back down each cycle.
        return low + (high - low) * (1f - cos(2.0 * PI * phase).toFloat()) * 0.5f
    }

    private fun pickLength(): Int = random.between(speedMin, speedMax).coerceAtLeast(EffectTiming.MIN_STEP_MS)
}

/**
 * Flashes: Attack-Hold-Release pulses separated by random gaps. Single: one pulse per flash with
 * the layer's Attack, Hold and Release, its peak picked from the peak range. Flicker: a burst of
 * 2–3 pulses only a few milliseconds apart: faint echoes at 10–20 % strength, then the main
 * strike last, at the top of the range (owner request: the strike always ends the burst, so its
 * long fade is the one you see; the echoes are texture). Flicker pulses snap on and hold briefly
 * at a fixed speed; the echoes die away fast and the strike fades out over the layer's Release
 * (owner request: the Attack and Hold settings are for Single only).
 */
internal class FlashTimeline(
    private val settings: FlashSettings,
    private val random: Random,
) {
    private val flicker = settings.flicker
    private val userRelease = settings.releaseMs.coerceAtLeast(0)

    // The current pulse's envelope: the layer's settings for Single, fixed for Flicker (see
    // pickPulse); set before any pulse plays.
    private var attack = if (flicker) 0 else settings.attackMs.coerceAtLeast(0)
    private var hold = if (flicker) FLICKER_HOLD_MS else settings.holdMs.coerceAtLeast(0)
    private var release = userRelease
    private var pulseLength = attack + hold + release

    private val peakHigh = maxOf(settings.peak, settings.peakMin).coerceIn(0f, 1f)
    private val peakLow = minOf(settings.peak, settings.peakMin).coerceIn(0f, 1f)

    private var pulseStart: Long = gap().toLong()
    private var burstSize = 1
    private var pulseIndex = 0

    /** The pulse in this burst that reaches [peakHigh] (always the last); -1 for a single flash. */
    private var strongPulse = -1

    /**
     * How far the current pulse goes toward its color and peak: 1 for a single flash or a
     * Flicker's main pulse, a fraction for its faint echoes. Scales the envelope rather than the
     * peak, so an echo always lifts the light a little and never dims a bright scene.
     */
    var strength = 1f
        private set

    /** Counts pulses; changes whenever a new pulse becomes the current one. */
    var serial = 0L
        private set

    /** The current pulse's peak brightness. */
    var peak = 0f
        private set

    init {
        startBurst()
    }

    /** Moves on to the pulse that is playing at [t], or the next one if none is. */
    fun advance(t: Long) {
        var steps = 0
        while (t >= pulseStart + pulseLength) {
            val end = pulseStart + pulseLength
            if (++steps > EffectTiming.MAX_CATCH_UP_STEPS) {
                pulseStart = t + gap()
                startBurst()
                serial++
                break
            }
            if (pulseIndex + 1 < burstSize) {
                pulseIndex++
                pickPulse()
                pulseStart = end + random.between(FLICKER_SPACING_MIN_MS, FLICKER_SPACING_MAX_MS)
            } else {
                startBurst()
                pulseStart = end + gap()
            }
            serial++
        }
    }

    /**
     * Starts a burst at [start] (on this timeline's clock) unless one is already playing at [now]
     * ([now] ≤ [start]; they differ for Wave bulbs, whose clocks run behind). The next gap is
     * counted from the end of this burst, as usual.
     */
    fun trigger(start: Long, now: Long) {
        advance(now)
        if (segment(now) != IDLE) return
        pulseStart = start
        startBurst()
        serial++
    }

    /** Which part of the current pulse [t] falls in. Call [advance] with [t] (or later) first. */
    fun segment(t: Long): Int {
        val rel = t - pulseStart
        return when {
            rel < 0 -> IDLE
            rel < attack -> ATTACK
            rel < attack + hold -> HOLD
            rel < pulseLength -> RELEASE
            else -> IDLE
        }
    }

    /** When the segment [t] is in ends (for IDLE: when the next pulse starts). */
    fun segmentEnd(t: Long): Long = when (segment(t)) {
        ATTACK -> pulseStart + attack
        HOLD -> pulseStart + attack + hold
        RELEASE -> pulseStart + pulseLength
        else -> pulseStart
    }

    /** The current pulse's envelope at [t]: 0 → 1 over attack, 1 in hold, 1 → 0 over release. */
    fun levelAt(t: Long): Float {
        val rel = t - pulseStart
        if (rel < 0 || rel >= pulseLength) return 0f
        if (rel < attack) return rel.toFloat() / attack
        if (rel < attack + hold) return 1f
        return 1f - (rel - attack - hold).toFloat() / release
    }

    private fun gap(): Int =
        random.between(settings.gapMinMs, settings.gapMaxMs).coerceAtLeast(EffectTiming.MIN_GAP_MS)

    private fun pulses(): Int =
        if (flicker) random.between(FlashSettings.FLICKER_PULSES_MIN, FlashSettings.FLICKER_PULSES_MAX) else 1

    private fun startBurst() {
        burstSize = pulses()
        pulseIndex = 0
        strongPulse = if (burstSize > 1) burstSize - 1 else -1
        pickPulse()
    }

    /**
     * A single flash lands anywhere in the peak range. In a Flicker burst the echoes come first,
     * sharing the top of the range at a fraction of the strength and dying away fast so the next
     * can hit; the last pulse is the full strike and takes the layer's Release.
     */
    private fun pickPulse() {
        if (strongPulse < 0) {
            peak = random.between(peakLow, peakHigh)
            strength = 1f
            return
        }
        peak = peakHigh
        if (pulseIndex == strongPulse) {
            strength = 1f
            release = userRelease
        } else {
            strength = random.between(ECHO_STRENGTH_MIN, ECHO_STRENGTH_MAX)
            release = FLICKER_RELEASE_MS
        }
        pulseLength = attack + hold + release
    }

    companion object {
        const val IDLE = 0
        const val ATTACK = 1
        const val HOLD = 2
        const val RELEASE = 3

        /**
         * Time from the end of one Flicker pulse to the start of the next (owner request: 5–10 ms).
         * Wi-Fi delivery jitters by about that much, so on real bulbs the pulses land a little
         * unevenly, which suits a flicker.
         */
        const val FLICKER_SPACING_MIN_MS = 5
        const val FLICKER_SPACING_MAX_MS = 10

        /**
         * Every Flicker pulse snaps on (no attack) and holds this long, the fastest a bulb shows
         * reliably over Wi-Fi (about one network round trip).
         */
        const val FLICKER_HOLD_MS = 40

        /** How fast a Flicker pulse dies away before the next one; the last uses the layer's Release. */
        const val FLICKER_RELEASE_MS = 60

        /** A Flicker echo's strength as a share of the main pulse's (owner request: 20 % or less). */
        const val ECHO_STRENGTH_MIN = 0.1f
        const val ECHO_STRENGTH_MAX = 0.2f
    }
}

/* One group */

/**
 * Spin on one bulb: its turn comes every lap ([SpinSettings.stepMs] × bulbs + the lap gap), at
 * [index] steps into the lap; each turn is an Attack-Hold-Release envelope. A release longer than
 * a lap overlaps the next turn, and the brighter of the two counts. Times are on the group's clock.
 */
internal class SpinTimeline(settings: SpinSettings, index: Int, bulbCount: Int) {
    private val attack = settings.attackMs.coerceAtLeast(0).toLong()
    private val hold = settings.holdMs.coerceAtLeast(0).toLong()
    private val release = settings.releaseMs.coerceAtLeast(0).toLong()
    private val length = attack + hold + release
    private val step = settings.stepMs.coerceAtLeast(MIN_STEP_MS).toLong()
    private val period = (step * bulbCount + settings.lapGapMs.coerceAtLeast(0)).coerceAtLeast(MIN_STEP_MS.toLong())
    // Reverse runs the same order backwards: the last bulb goes first.
    private val offset = step * (if (settings.reverse) bulbCount - 1 - index else index)
    val peak = settings.level.coerceIn(0f, 1f)

    /** Time into the current turn (0 until the period), and which lap it is. */
    private fun rel(t: Long): Long = Math.floorMod(t - offset, period)

    private fun lap(t: Long): Long = Math.floorDiv(t - offset, period)

    /** 0–1 envelope at [rel] ms into a turn (0 outside it). */
    private fun envelope(rel: Long): Float {
        if (rel < 0 || rel >= length) return 0f
        if (rel < attack) return rel.toFloat() / attack
        if (rel < attack + hold) return 1f
        return 1f - (rel - attack - hold).toFloat() / release
    }

    /** The envelope at [t]: this turn's, or the previous turn's trail if that's brighter. */
    fun levelAt(t: Long): Float {
        val r = rel(t)
        val now = envelope(r)
        if (length <= period) return now
        return maxOf(now, envelope(r + period))
    }

    /** Attack, hold, release or idle of the current turn (the runner sends on every change). */
    fun segment(t: Long): Int {
        val r = rel(t)
        return when {
            r < attack -> FlashTimeline.ATTACK
            r < attack + hold -> FlashTimeline.HOLD
            r < length -> FlashTimeline.RELEASE
            else -> FlashTimeline.IDLE
        }
    }

    fun key(t: Long): Long = lap(t) * 4 + segment(t)

    /** When the current segment ends, on the group's clock. */
    fun segmentEnd(t: Long): Long {
        val r = rel(t)
        val turnStart = t - r
        val end = when {
            r < attack -> attack
            r < attack + hold -> attack + hold
            r < length -> length
            else -> period
        }
        return turnStart + end
    }

    companion object {
        const val MIN_STEP_MS = 50
    }
}

/**
 * Runs one group's effect layers for its bulbs (indexed 0 until [bulbCount], in the group's bulb
 * order, which Wave follows). Not thread-safe: one runner coroutine drives it.
 *
 * Mixing per bulb (FX_REQUIREMENTS.md → How the layers mix): base → Color shift replaces the
 * color → Brightness shift replaces the brightness → a playing flash blends everything toward its
 * color and peak by the envelope level.
 */
class GroupEffectRun(
    base: Hsbk,
    effects: GroupEffects,
    val bulbCount: Int,
    random: Random,
) {
    private val base = Hsbk().apply { set(base) }

    private val colorLayer: List<ColorShiftTimeline>?
    private val colorDelay: LongArray?
    private val brightnessLayer: List<BrightnessTimeline>?
    private val brightnessDelay: LongArray?
    private val flashLayer: List<FlashTimeline>?
    private val flashDelay: LongArray?
    private val flashColor: ColorPoint?
    private val spinLayer: Array<SpinTimeline>?
    private val spinColor: ColorPoint?

    init {
        if (effects.runsColorShift) {
            val settings = effects.colorShift
            val palette = Array(effects.palette.size) { ColorPoint.of(effects.palette[it]) }
            colorLayer = buildLayer(settings.spread, random) { r, desync -> ColorShiftTimeline(settings, palette, r, desync) }
            colorDelay = delays(settings.spread, settings.waveOffsetMs)
        } else {
            colorLayer = null
            colorDelay = null
        }
        if (effects.brightnessShift.enabled) {
            val settings = effects.brightnessShift
            brightnessLayer = buildLayer(settings.spread, random) { r, desync -> BrightnessTimeline(settings, r, desync) }
            brightnessDelay = delays(settings.spread, settings.waveOffsetMs)
        } else {
            brightnessLayer = null
            brightnessDelay = null
        }
        if (effects.flashes.enabled) {
            val settings = effects.flashes
            flashLayer = buildLayer(settings.spread, random) { r, _ -> FlashTimeline(settings, r) }
            flashDelay = delays(settings.spread, settings.waveOffsetMs)
            flashColor = ColorPoint.of(settings.color)
        } else {
            flashLayer = null
            flashDelay = null
            flashColor = null
        }
        // Spin runs across the bulbs in their order; spread doesn't apply.
        if (effects.spin.enabled && bulbCount > 0) {
            val settings = effects.spin
            spinLayer = Array(bulbCount) { SpinTimeline(settings, it, bulbCount) }
            spinColor = ColorPoint.of(settings.color)
        } else {
            spinLayer = null
            spinColor = null
        }
    }

    /** Moves the group's base (its scene color) without restarting any timeline. */
    fun setBase(newBase: Hsbk) {
        base.set(newBase)
    }

    /**
     * Plays a burst now (at [t]) on every bulb, following the layer's spread: Together and
     * Independent bulbs flash at once, Wave bulbs one offset after another. No-op without Flashes.
     */
    fun triggerFlash(t: Long) {
        val layer = flashLayer ?: return
        val delays = flashDelay!!
        for (i in 0 until bulbCount) layer[i].trigger(t, layerTime(t, delays, i))
    }

    /**
     * Identifies the flash segment bulb [bulb] is in at [t]; it changes at every attack, hold,
     * release and idle start, which is when the runner sends straight away. -1 without Flashes.
     */
    fun flashKey(bulb: Int, t: Long): Long {
        val spin = spinLayer?.get(bulb)?.key(t) ?: 0L
        val layer = flashLayer ?: return if (spinLayer == null) -1L else spin
        val ft = layerTime(t, flashDelay!!, bulb)
        val timeline = layer[bulb]
        timeline.advance(ft)
        return (timeline.serial * 4 + timeline.segment(ft)) * 31 + spin
    }

    /**
     * When bulb [bulb]'s current flash segment ends, on the run's clock (call [flashKey] with [t]
     * first). The runner wakes then, so edges aren't late. [Long.MAX_VALUE] without Flashes.
     */
    fun nextFlashEdge(bulb: Int, t: Long): Long {
        val spinEdge = spinLayer?.get(bulb)?.segmentEnd(t) ?: Long.MAX_VALUE
        val layer = flashLayer ?: return spinEdge
        val delays = flashDelay!!
        return minOf(spinEdge, layer[bulb].segmentEnd(layerTime(t, delays, bulb)) + delays[bulb])
    }

    /**
     * Writes bulb [bulb]'s target at [t] into [out] and returns the transition to send it with.
     * Color and brightness shifts are taken [lookAheadMs] ahead (the runner fades to them over
     * that long, so the bulb arrives on time). During a flash's attack or release the target is
     * where the envelope will be at the end of that segment (or one frame ahead, if sooner), so
     * short attacks stay crisp.
     */
    fun sample(bulb: Int, t: Long, out: Hsbk, lookAheadMs: Long = 0L): Int {
        out.set(base)
        val ahead = t + lookAheadMs
        val colors = colorLayer
        if (colors != null) colors[bulb].sample(layerTime(ahead, colorDelay!!, bulb), out)
        val brightness = brightnessLayer
        if (brightness != null) out.brightness = brightness[bulb].sample(layerTime(ahead, brightnessDelay!!, bulb))

        val regular = (if (lookAheadMs > 0L) lookAheadMs else EffectTiming.FRAME_MS).toInt()
        val spun = applySpin(bulb, t, out, regular)
        val flashed = applyFlash(bulb, t, out, regular)
        return minOf(spun, flashed)
    }

    /**
     * Lays bulb [bulb]'s Spin at [t] over [out] (color toward the spin color, brightness up to its
     * level: brightest wins) and returns the transition: [regular], or the time to the end of an
     * attack or release (aimed there, so short attacks stay crisp), or 0 to snap on for a hold.
     */
    private fun applySpin(bulb: Int, t: Long, out: Hsbk, regular: Int): Int {
        val timeline = spinLayer?.get(bulb) ?: return regular
        val segment = timeline.segment(t)
        var lead = 0L
        var transition = regular.toLong()
        if (segment == FlashTimeline.ATTACK || segment == FlashTimeline.RELEASE) {
            lead = minOf(timeline.segmentEnd(t) - t, EffectTiming.FRAME_MS).coerceAtLeast(0L)
            transition = lead
        } else if (segment == FlashTimeline.HOLD) {
            transition = 0L
        }
        val level = timeline.levelAt(t + lead)
        if (level > 0f) {
            val spin = spinColor!!
            blendColor(out.hue, out.saturation, out.kelvin, spin.hue, spin.saturation, spin.kelvin, level, out)
            val peak = timeline.peak
            if (peak > out.brightness) out.brightness += (peak - out.brightness) * level
        }
        return transition.toInt()
    }

    /**
     * Blends bulb [bulb]'s flash at [t] into whatever [out] holds and returns the transition to
     * send it with: [regular], or shorter during a flash's attack or release, so short attacks
     * stay crisp. [sample] uses it for the group's own bulbs; the runner also uses it to lay this
     * group's flashes over a bulb another group owns (owner decision: a bulb in several lit
     * groups flashes with every one of them). Returns [regular] untouched without Flashes.
     */
    fun applyFlash(bulb: Int, t: Long, out: Hsbk, regular: Int): Int {
        val flashes = flashLayer ?: return regular
        val ft = layerTime(t, flashDelay!!, bulb)
        val timeline = flashes[bulb]
        timeline.advance(ft)
        val segment = timeline.segment(ft)
        var lead = 0L
        var transition = regular.toLong()
        if (segment == FlashTimeline.ATTACK || segment == FlashTimeline.RELEASE) {
            lead = minOf(timeline.segmentEnd(ft) - ft, EffectTiming.FRAME_MS).coerceAtLeast(0L)
            transition = lead
        } else if (segment == FlashTimeline.HOLD) {
            // The hold sits at the flash's full level: show it at once. With no attack (Flicker,
            // or a Single flash set to 0) this is what snaps the flash on.
            transition = 0L
        }
        val envelope = timeline.levelAt(ft + lead)
        if (envelope > 0f) {
            val strength = timeline.strength
            val level = envelope * strength
            // On the way out the scene's color comes back faster than its brightness (squared
            // envelope): bulbs show low saturation as near-white, so an even color fade looked
            // white for most of the release and then snapped to the color (owner feedback).
            val colorLevel = if (segment == FlashTimeline.RELEASE) envelope * envelope * strength else level
            val flash = flashColor!!
            blendColor(out.hue, out.saturation, out.kelvin, flash.hue, flash.saturation, flash.kelvin, colorLevel, out)
            out.brightness += (timeline.peak - out.brightness) * level
        }
        return transition.toInt()
    }

    /** Time on a layer's own clock: Wave delays later bulbs; nothing runs before 0. */
    private fun layerTime(t: Long, delays: LongArray, bulb: Int): Long {
        val shifted = t - delays[bulb]
        return if (shifted < 0L) 0L else shifted
    }

    /**
     * Together: every bulb shares one timeline. Wave: identical timelines (same seed), each bulb
     * on a delayed clock. Independent: a differently seeded, desynchronised timeline per bulb.
     */
    private inline fun <T> buildLayer(spread: Spread, random: Random, make: (Random, Boolean) -> T): List<T> {
        val seed = random.nextLong()
        val list = ArrayList<T>(bulbCount)
        when (spread) {
            Spread.TOGETHER -> {
                val shared = make(Random(seed), false)
                for (i in 0 until bulbCount) list.add(shared)
            }
            Spread.WAVE -> for (i in 0 until bulbCount) list.add(make(Random(seed), false))
            Spread.INDEPENDENT -> for (i in 0 until bulbCount) list.add(make(Random(random.nextLong()), true))
        }
        return list
    }

    private fun delays(spread: Spread, waveOffsetMs: Int): LongArray {
        val result = LongArray(bulbCount)
        if (spread != Spread.WAVE) return result
        val offset = waveOffsetMs.coerceIn(EffectTiming.MIN_WAVE_OFFSET_MS, EffectTiming.MAX_WAVE_OFFSET_MS).toLong()
        for (i in 0 until bulbCount) result[i] = offset * i
        return result
    }
}
