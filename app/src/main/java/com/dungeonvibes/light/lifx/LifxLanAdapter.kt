package com.dungeonvibes.light.lifx

import com.dungeonvibes.light.BulbAddress
import com.dungeonvibes.light.DiscoveredBulb
import com.dungeonvibes.light.LightAdapter
import com.dungeonvibes.light.LightException
import com.dungeonvibes.light.LightState
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.random.Random
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull

/**
 * LIFX LAN protocol over UDP (lan.developer.lifx.com). Bulbs are addressed by serial; the IP comes
 * from the latest discovery.
 *
 * @param nowMillis monotonic clock, injectable so tests can use virtual time.
 * @param source our client id in every packet; LIFX advises against 0 and 1.
 */
@Singleton
class LifxLanAdapter internal constructor(
    private val transport: LifxTransport,
    private val nowMillis: () -> Long,
    private val source: Int,
) : LightAdapter {

    @Inject
    constructor(transport: LifxTransport) : this(
        transport = transport,
        nowMillis = { System.nanoTime() / 1_000_000 },
        source = Random.nextInt(2, Int.MAX_VALUE),
    )

    override val protocolId: String = PROTOCOL_ID

    /** Per-bulb sequence counters, wrapping 255 → 0. */
    private val sequences = ConcurrentHashMap<String, AtomicInteger>()

    /** Product id per serial, learned during discovery, used to clamp kelvin. */
    private val productIds = ConcurrentHashMap<String, Int>()

    /** Per-bulb send pacing, so no bulb gets more than 20 messages per second. */
    private class Throttle {
        val mutex = Mutex()
        var lastSentAt = Long.MIN_VALUE / 2
    }

    private val throttles = ConcurrentHashMap<String, Throttle>()

    /* Discovery */

    override fun discover(timeoutMs: Long): Flow<DiscoveredBulb> = channelFlow {
        val seen = HashSet<String>()
        // Listen before broadcasting so no reply is missed. UNDISPATCHED subscribes right away.
        val listener = launch(start = CoroutineStart.UNDISPATCHED) {
            transport.incoming.collect { datagram ->
                val header = LifxProtocol.parseHeader(datagram.data, datagram.length) ?: return@collect
                if (header.type != LifxProtocol.STATE_SERVICE || header.source != source) return@collect
                val service = LifxProtocol.parseStateService(datagram.data, datagram.length) ?: return@collect
                if (service.service != LifxProtocol.SERVICE_UDP) return@collect
                if (!seen.add(header.serial)) return@collect
                val address = BulbAddress(PROTOCOL_ID, header.serial, datagram.fromIp)
                // Describe each bulb in parallel; these children outlive the listener.
                this@channelFlow.launch { send(describe(address)) }
            }
        }
        // UDP can drop packets, so broadcast a few times within the window.
        val gap = timeoutMs / DISCOVERY_BROADCASTS
        try {
            for (i in 0 until DISCOVERY_BROADCASTS) {
                try {
                    transport.broadcast(LifxProtocol.getService(source, sequence = i))
                } catch (e: IOException) {
                    // No network right now; keep listening in case it's transient.
                }
                delay(gap)
            }
            delay(timeoutMs - gap * DISCOVERY_BROADCASTS)
        } finally {
            listener.cancel()
        }
    }

    /** Reads label, power, color and product. Missing answers fall back to the serial as the name. */
    private suspend fun describe(address: BulbAddress): DiscoveredBulb {
        val target = LifxProtocol.serialToBytes(address.serial)

        val version = request(address, LifxProtocol.STATE_VERSION) { seq ->
            LifxProtocol.emptyRequest(LifxProtocol.GET_VERSION, target, source, seq)
        }
        version?.let { LifxProtocol.parseProductId(it.data, it.length) }?.let { productIds[address.serial] = it }

        val colorReply = request(address, LifxProtocol.LIGHT_STATE) { seq ->
            LifxProtocol.emptyRequest(LifxProtocol.GET_COLOR, target, source, seq)
        }
        val light = colorReply?.let { LifxProtocol.parseLightState(it.data, it.length) }
        return DiscoveredBulb(
            address = address,
            label = light?.label?.takeIf { it.isNotBlank() } ?: address.serial,
            isOn = light?.isOn,
            state = light?.toLightState(),
        )
    }

    /* Commands */

    override suspend fun setState(bulb: BulbAddress, state: LightState, fadeMs: Int, reliable: Boolean): Result<Unit> {
        val target = targetOf(bulb) ?: return invalidSerial(bulb)
        val productId = productIds[bulb.serial]
        return sendSet(bulb, reliable) { seq, ack ->
            LifxProtocol.setColor(target, source, seq, ack, state, productId, fadeMs)
        }
    }

    override suspend fun setPower(bulb: BulbAddress, on: Boolean, fadeMs: Int, reliable: Boolean): Result<Unit> {
        val target = targetOf(bulb) ?: return invalidSerial(bulb)
        return sendSet(bulb, reliable) { seq, ack ->
            LifxProtocol.setLightPower(target, source, seq, ack, on, fadeMs)
        }
    }

    /** Toggles power twice and puts it back, so a bulb that's off flashes on and one that's on flashes off. */
    override suspend fun identify(bulb: BulbAddress) {
        val target = targetOf(bulb) ?: return
        val reply = request(bulb, LifxProtocol.LIGHT_STATE) { seq ->
            LifxProtocol.emptyRequest(LifxProtocol.GET_COLOR, target, source, seq)
        }
        val wasOn = reply?.let { LifxProtocol.parseLightState(it.data, it.length) }?.isOn ?: true
        try {
            repeat(IDENTIFY_BLINKS) {
                sendThrottled(bulb, LifxProtocol.setLightPower(target, source, nextSequence(bulb.serial), false, !wasOn, 0))
                delay(IDENTIFY_STEP_MS)
                sendThrottled(bulb, LifxProtocol.setLightPower(target, source, nextSequence(bulb.serial), false, wasOn, 0))
                delay(IDENTIFY_STEP_MS)
            }
        } catch (e: IOException) {
            // Best effort; identify never fails loudly.
        }
    }

    /**
     * Fire-and-forget sends once. Reliable sends ask for an acknowledgement and, if none arrives
     * within [ACK_TIMEOUT_MS], retry once with the same wait.
     */
    private suspend fun sendSet(
        bulb: BulbAddress,
        reliable: Boolean,
        build: (sequence: Int, ackRequired: Boolean) -> ByteArray,
    ): Result<Unit> {
        try {
            if (!reliable) {
                sendThrottled(bulb, build(nextSequence(bulb.serial), false))
                return Result.success(Unit)
            }
            for (attempt in 0 until RELIABLE_ATTEMPTS) {
                val sequence = nextSequence(bulb.serial)
                val packet = build(sequence, true)
                val ack = awaitReply(bulb.serial, sequence, LifxProtocol.ACKNOWLEDGEMENT) { sendThrottled(bulb, packet) }
                if (ack != null) return Result.success(Unit)
            }
            return Result.failure(LightException("No acknowledgement from bulb ${bulb.serial}"))
        } catch (e: IOException) {
            return Result.failure(LightException("Couldn't reach bulb ${bulb.serial}", e))
        }
    }

    /** Sends a Get and waits for its State reply, retrying once. Null if the bulb never answers. */
    private suspend fun request(bulb: BulbAddress, replyType: Int, build: (sequence: Int) -> ByteArray): Datagram? {
        try {
            for (attempt in 0 until RELIABLE_ATTEMPTS) {
                val sequence = nextSequence(bulb.serial)
                val packet = build(sequence)
                val reply = awaitReply(bulb.serial, sequence, replyType) { sendThrottled(bulb, packet) }
                if (reply != null) return reply
            }
        } catch (e: IOException) {
            return null
        }
        return null
    }

    /**
     * Subscribes for the reply first, then runs [send], then waits up to [ACK_TIMEOUT_MS] for a
     * packet matching (source, sequence, serial, type).
     */
    private suspend fun awaitReply(serial: String, sequence: Int, type: Int, send: suspend () -> Unit): Datagram? =
        coroutineScope {
            val reply = async(start = CoroutineStart.UNDISPATCHED) {
                transport.incoming.first { datagram ->
                    val header = LifxProtocol.parseHeader(datagram.data, datagram.length)
                    header != null &&
                        header.type == type &&
                        header.source == source &&
                        header.sequence == sequence &&
                        header.serial == serial
                }
            }
            try {
                send()
            } catch (e: IOException) {
                reply.cancel()
                throw e
            }
            val result = withTimeoutOrNull(ACK_TIMEOUT_MS) { reply.await() }
            if (result == null) reply.cancel()
            result
        }

    /** Waits as needed so this bulb gets at most one packet per [MIN_SEND_INTERVAL_MS]. */
    private suspend fun sendThrottled(bulb: BulbAddress, packet: ByteArray) {
        val throttle = throttles.computeIfAbsent(bulb.serial) { Throttle() }
        throttle.mutex.withLock {
            val wait = throttle.lastSentAt + MIN_SEND_INTERVAL_MS - nowMillis()
            if (wait > 0) delay(wait)
            transport.send(packet, bulb.ip)
            throttle.lastSentAt = nowMillis()
        }
    }

    private fun nextSequence(serial: String): Int =
        sequences.computeIfAbsent(serial) { AtomicInteger(0) }.getAndIncrement() and 0xFF

    private fun targetOf(bulb: BulbAddress): ByteArray? =
        try {
            LifxProtocol.serialToBytes(bulb.serial)
        } catch (e: IllegalArgumentException) {
            null
        }

    private fun invalidSerial(bulb: BulbAddress): Result<Unit> =
        Result.failure(LightException("Not a LIFX serial: ${bulb.serial}"))

    companion object {
        const val PROTOCOL_ID = "lifx_lan"

        /** REQUIREMENTS.md: retry once after 300 ms. */
        const val ACK_TIMEOUT_MS = 300L
        private const val RELIABLE_ATTEMPTS = 2

        /** LIFX recommends at most 20 messages per second per device. */
        const val MIN_SEND_INTERVAL_MS = 50L

        private const val DISCOVERY_BROADCASTS = 3
        private const val IDENTIFY_BLINKS = 2
        private const val IDENTIFY_STEP_MS = 400L
    }
}
