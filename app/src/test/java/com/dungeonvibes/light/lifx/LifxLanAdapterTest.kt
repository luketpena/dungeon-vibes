package com.dungeonvibes.light.lifx

import com.dungeonvibes.light.BulbAddress
import com.dungeonvibes.light.LightState
import com.dungeonvibes.light.lifx.LifxTestPackets.u16At
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Runs the adapter against a fake network on virtual time: delays and timeouts complete instantly
 * but in the right order, and `currentTime` says when each packet went out.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class LifxLanAdapterTest {

    private val serial = "d073d5001337"
    private val bulb = BulbAddress(LifxLanAdapter.PROTOCOL_ID, serial, "192.168.1.50")
    private val red = LightState(0f, 1f, 1f, null)
    private val source = 0x12345

    private class Sent(val data: ByteArray, val ip: String, val atMs: Long) {
        val type: Int get() = u16At(data, 32)
        val sequence: Int get() = data[23].toInt() and 0xFF
        val ackRequired: Boolean get() = (data[22].toInt() and 0x02) != 0
        val serial: String get() = LifxProtocol.serialToString(data, 8)
    }

    private class FakeTransport(private val now: () -> Long) : LifxTransport {
        private val flow = MutableSharedFlow<Datagram>(extraBufferCapacity = 64)
        override val incoming: SharedFlow<Datagram> = flow

        val sent = mutableListOf<Sent>()

        /** Called for every outgoing packet; may reply through [reply]. */
        var onSend: (Sent) -> Unit = {}

        override suspend fun send(data: ByteArray, ip: String) = record(data, ip)

        override suspend fun broadcast(data: ByteArray) = record(data, BROADCAST)

        private fun record(data: ByteArray, ip: String) {
            val packet = Sent(data, ip, now())
            sent += packet
            onSend(packet)
        }

        fun reply(bytes: ByteArray, fromIp: String) {
            flow.tryEmit(Datagram(bytes, bytes.size, fromIp))
        }

        companion object {
            const val BROADCAST = "broadcast"
        }
    }

    private fun TestScope.newAdapter(): Pair<LifxLanAdapter, FakeTransport> {
        val transport = FakeTransport { testScheduler.currentTime }
        val adapter = LifxLanAdapter(transport, nowMillis = { testScheduler.currentTime }, source = source)
        return adapter to transport
    }

    private fun FakeTransport.ackEverything() {
        onSend = { packet ->
            if (packet.ackRequired) {
                reply(LifxTestPackets.reply(LifxProtocol.ACKNOWLEDGEMENT, source, packet.serial, packet.sequence), bulb.ip)
            }
        }
    }

    /* Reliable sends */

    @Test
    fun reliableSet_succeedsOnFirstAck_withoutRetry() = runTest {
        val (adapter, transport) = newAdapter()
        transport.ackEverything()

        val result = adapter.setState(bulb, red, fadeMs = 1_000, reliable = true)

        assertTrue(result.isSuccess)
        assertEquals(1, transport.sent.size)
        assertTrue(transport.sent.single().ackRequired)
        assertEquals(LifxProtocol.SET_COLOR, transport.sent.single().type)
        assertEquals(bulb.ip, transport.sent.single().ip)
    }

    @Test
    fun reliableSet_retriesOnceAfter300ms_whenNoAckArrives() = runTest {
        val (adapter, transport) = newAdapter()
        var calls = 0
        transport.onSend = { packet ->
            calls++
            // Drop the first packet's ack; acknowledge the retry.
            if (calls == 2) {
                transport.reply(LifxTestPackets.reply(45, source, packet.serial, packet.sequence), bulb.ip)
            }
        }

        val result = adapter.setPower(bulb, on = true, fadeMs = 500, reliable = true)

        assertTrue(result.isSuccess)
        assertEquals(2, transport.sent.size)
        assertEquals(0L, transport.sent[0].atMs)
        assertEquals(300L, transport.sent[1].atMs)
    }

    @Test
    fun reliableSet_failsAfterOneRetry_whenTheBulbNeverAnswers() = runTest {
        val (adapter, transport) = newAdapter()

        val result = adapter.setState(bulb, red, fadeMs = 0, reliable = true)

        assertTrue(result.isFailure)
        assertEquals(2, transport.sent.size)
        assertEquals(600L, currentTime)
    }

    @Test
    fun ackForADifferentSequence_doesNotCount() = runTest {
        val (adapter, transport) = newAdapter()
        transport.onSend = { packet ->
            transport.reply(LifxTestPackets.reply(45, source, packet.serial, (packet.sequence + 1) and 0xFF), bulb.ip)
        }

        assertTrue(adapter.setState(bulb, red, 0, reliable = true).isFailure)
    }

    @Test
    fun ackFromAnotherClientSource_doesNotCount() = runTest {
        val (adapter, transport) = newAdapter()
        transport.onSend = { packet ->
            transport.reply(LifxTestPackets.reply(45, source + 1, packet.serial, packet.sequence), bulb.ip)
        }

        assertTrue(adapter.setState(bulb, red, 0, reliable = true).isFailure)
    }

    /* Fire-and-forget and pacing */

    @Test
    fun fireAndForget_sendsOnceWithoutAck() = runTest {
        val (adapter, transport) = newAdapter()

        val result = adapter.setState(bulb, red, fadeMs = 100, reliable = false)

        assertTrue(result.isSuccess)
        assertEquals(1, transport.sent.size)
        assertTrue(!transport.sent.single().ackRequired)
        assertEquals(0L, currentTime)
    }

    @Test
    fun sendsToOneBulb_areSpacedAtLeast50msApart() = runTest {
        val (adapter, transport) = newAdapter()

        repeat(5) { adapter.setPower(bulb, on = it % 2 == 0, fadeMs = 0, reliable = false) }

        assertEquals(listOf(0L, 50L, 100L, 150L, 200L), transport.sent.map { it.atMs })
    }

    @Test
    fun differentBulbs_areNotThrottledTogether() = runTest {
        val (adapter, transport) = newAdapter()
        val other = BulbAddress(LifxLanAdapter.PROTOCOL_ID, "d073d5009999", "192.168.1.51")

        launch { adapter.setPower(bulb, true, 0, reliable = false) }
        launch { adapter.setPower(other, true, 0, reliable = false) }
        advanceUntilIdle()

        assertEquals(listOf(0L, 0L), transport.sent.map { it.atMs })
    }

    @Test
    fun sequence_wrapsFrom255To0() = runTest {
        val (adapter, transport) = newAdapter()

        repeat(257) { adapter.setPower(bulb, true, 0, reliable = false) }

        assertEquals(255, transport.sent[255].sequence)
        assertEquals(0, transport.sent[256].sequence)
    }

    /* Discovery */

    @Test
    fun discover_findsBulbOnce_readsLabelPowerColor_andLearnsProductForKelvinClamp() = runTest {
        val (adapter, transport) = newAdapter()
        transport.onSend = { packet ->
            when (packet.type) {
                LifxProtocol.GET_SERVICE -> transport.reply(
                    LifxTestPackets.reply(3, source, serial, packet.sequence, LifxTestPackets.stateServicePayload()),
                    "192.168.1.77",
                )
                LifxProtocol.GET_VERSION -> transport.reply(
                    LifxTestPackets.reply(33, source, serial, packet.sequence, LifxTestPackets.stateVersionPayload(product = 10)),
                    "192.168.1.77",
                )
                LifxProtocol.GET_COLOR -> transport.reply(
                    LifxTestPackets.reply(
                        107, source, serial, packet.sequence,
                        LifxTestPackets.lightStatePayload(0, 0, 65535, 2700, power = 65535, label = "Kitchen"),
                    ),
                    "192.168.1.77",
                )
            }
        }

        val found = adapter.discover(timeoutMs = 1_000).toList()

        // Three broadcasts, but the bulb is reported once.
        assertEquals(3, transport.sent.count { it.type == LifxProtocol.GET_SERVICE && it.ip == FakeTransport.BROADCAST })
        val bulbFound = found.single()
        assertEquals("Kitchen", bulbFound.label)
        assertEquals(true, bulbFound.isOn)
        assertEquals("192.168.1.77", bulbFound.address.ip)
        assertEquals(serial, bulbFound.address.serial)
        assertEquals(2700, bulbFound.state?.kelvin)

        // Product 10 (White 800) supports 2700–6500 K, so 9000 K is clamped.
        transport.sent.clear()
        adapter.setState(bulbFound.address, LightState(0f, 0f, 1f, 9_000), 0, reliable = false)
        assertEquals(6_500, u16At(transport.sent.single().data, 43))
    }

    @Test
    fun discover_withNoBulbs_completesAfterTimeout() = runTest {
        val (adapter, _) = newAdapter()

        val found = adapter.discover(timeoutMs = 2_000).toList()

        assertTrue(found.isEmpty())
        assertEquals(2_000L, currentTime)
    }

    @Test
    fun discover_bulbThatIgnoresGetColor_isStillReportedWithSerialAsName() = runTest {
        val (adapter, transport) = newAdapter()
        transport.onSend = { packet ->
            if (packet.type == LifxProtocol.GET_SERVICE) {
                transport.reply(
                    LifxTestPackets.reply(3, source, serial, packet.sequence, LifxTestPackets.stateServicePayload()),
                    "192.168.1.77",
                )
            }
        }

        val found = adapter.discover(timeoutMs = 3_000).toList().single()

        assertEquals(serial, found.label)
        assertEquals(null, found.isOn)
    }
}
