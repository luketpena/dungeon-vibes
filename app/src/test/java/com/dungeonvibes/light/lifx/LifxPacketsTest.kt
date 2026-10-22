package com.dungeonvibes.light.lifx

import com.dungeonvibes.light.LightState
import com.dungeonvibes.light.lifx.LifxTestPackets.hex
import com.dungeonvibes.light.lifx.LifxTestPackets.toHex
import com.dungeonvibes.light.lifx.LifxTestPackets.u16At
import com.dungeonvibes.light.lifx.LifxTestPackets.u32At
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Packet encoding checked byte for byte against lan.developer.lifx.com. */
class LifxPacketsTest {

    private val serial = "d073d5001337"
    private val target = LifxProtocol.serialToBytes(serial)

    /**
     * The worked example from lan.developer.lifx.com/docs/encoding-a-packet: SetColor to green at
     * full saturation and brightness, kelvin 3500, duration 0, source 2, sequence 1, ack_required,
     * target d073d5001337.
     */
    @Test
    fun setColor_matchesTheDocsExampleExactly() {
        val expected = hex(
            """
            3100001402000000
            d073d50013370000
            0000000000000201
            0000000000000000
            66000000005555ff
            ffffffac0d000000
            00
            """,
        )

        val packet = LifxProtocol.setColor(
            target = target,
            source = 2,
            sequence = 1,
            ackRequired = true,
            state = LightState(hue = 120f, saturation = 1f, brightness = 1f, kelvin = null),
            productId = null,
            durationMs = 0,
        )

        assertEquals(toHex(expected), toHex(packet))
    }

    @Test
    fun getService_isA36ByteTaggedBroadcastWithZeroTarget() {
        val packet = LifxProtocol.getService(source = 0x01020304, sequence = 7)

        val expected = hex(
            """
            2400 0034 04030201
            0000000000000000
            000000000000 00 07
            0000000000000000
            0200 0000
            """,
        )
        assertEquals(toHex(expected), toHex(packet))
    }

    @Test
    fun getColorRequest_hasNoAckOrResFlagsAndType101() {
        val packet = LifxProtocol.emptyRequest(LifxProtocol.GET_COLOR, target, source = 2, sequence = 9)

        assertEquals(36, packet.size)
        assertEquals(36, u16At(packet, 0))
        assertEquals(0x1400, u16At(packet, 2)) // not tagged
        assertEquals(0, packet[22].toInt()) // no ack_required, no res_required
        assertEquals(9, packet[23].toInt())
        assertEquals(101, u16At(packet, 32))
        assertArrayEquals(target, packet.copyOfRange(8, 14))
    }

    @Test
    fun setLightPower_onAndOff_encodeLevelAndDuration() {
        val on = LifxProtocol.setLightPower(target, source = 2, sequence = 3, ackRequired = true, on = true, durationMs = 1_500)
        val off = LifxProtocol.setLightPower(target, source = 2, sequence = 4, ackRequired = false, on = false, durationMs = 0)

        assertEquals(
            "2a00001402000000d073d500133700000000000000000203000000000000000075000000ffffdc050000",
            toHex(on),
        )
        assertEquals(42, off.size)
        assertEquals(0, off[22].toInt())
        assertEquals(0, u16At(off, 36))
        assertEquals(0, u32At(off, 38))
    }

    @Test
    fun setColor_whiteMode_sendsZeroSaturationAndClampedKelvin() {
        val white = LightState(hue = 200f, saturation = 0.7f, brightness = 0.5f, kelvin = 9_000)

        // Product 10 (LIFX White 800) supports 2700–6500 K.
        val clamped = LifxProtocol.setColor(target, 2, 0, false, white, productId = 10, durationMs = 250)
        assertEquals(0, u16At(clamped, 39))
        assertEquals(32768, u16At(clamped, 41)) // 0.5 → 32767.5 rounds to 32768
        assertEquals(6_500, u16At(clamped, 43))
        assertEquals(250, u32At(clamped, 45))

        // Unknown product falls back to 2500–9000 K.
        val warm = LifxProtocol.setColor(target, 2, 0, false, white.copy(kelvin = 1_000), productId = null, durationMs = 0)
        assertEquals(2_500, u16At(warm, 43))
    }

    @Test
    fun setColor_colorMode_sendsKelvin3500ByDefault() {
        val color = LightState(hue = 0f, saturation = 0.25f, brightness = 1f, kelvin = null)

        val packet = LifxProtocol.setColor(target, 2, 0, false, color, productId = 10, durationMs = 0)

        assertEquals(3_500, u16At(packet, 43))
        assertEquals(16384, u16At(packet, 39)) // 0.25 → 16383.75 rounds to 16384
    }

    @Test
    fun setColor_colorMode_sendsAnEffectsWhitePoint_clamped() {
        val tinted = LightState(hue = 30f, saturation = 0.25f, brightness = 1f, kelvin = null, colorKelvin = 2_900)
        val packet = LifxProtocol.setColor(target, 2, 0, false, tinted, productId = 10, durationMs = 0)
        assertEquals(2_900, u16At(packet, 43))
        assertEquals(16384, u16At(packet, 39))

        // Product 10 stops at 2700 K.
        val tooWarm = LifxProtocol.setColor(target, 2, 0, false, tinted.copy(colorKelvin = 2_000), productId = 10, durationMs = 0)
        assertEquals(2_700, u16At(tooWarm, 43))
    }

    @Test
    fun hueAndUnitConversions_coverTheFullRange() {
        assertEquals(0, LifxProtocol.hueToU16(0f))
        assertEquals(21845, LifxProtocol.hueToU16(120f))
        assertEquals(65535, LifxProtocol.hueToU16(360f))
        assertEquals(0, LifxProtocol.hueToU16(-10f))
        assertEquals(65535, LifxProtocol.unitToU16(1f))
        assertEquals(65535, LifxProtocol.unitToU16(2f))
        assertEquals(120f, LifxProtocol.u16ToHue(21845), 0.01f)
    }

    @Test
    fun parseHeader_readsSerialSequenceTypeAndSource() {
        val reply = LifxTestPackets.reply(LifxProtocol.ACKNOWLEDGEMENT, source = 0x7FFF0001, serial = serial, sequence = 200)

        val header = LifxProtocol.parseHeader(reply, reply.size)

        assertNotNull(header)
        assertEquals(serial, header!!.serial)
        assertEquals(200, header.sequence)
        assertEquals(45, header.type)
        assertEquals(0x7FFF0001, header.source)
    }

    @Test
    fun parseHeader_rejectsShortOrForeignPackets() {
        assertNull(LifxProtocol.parseHeader(ByteArray(10), 10))
        val wrongProtocol = LifxTestPackets.reply(45, 2, serial, 0).also { it[2] = 0x01 }
        assertNull(LifxProtocol.parseHeader(wrongProtocol, wrongProtocol.size))
    }

    @Test
    fun parseStateService_readsServiceAndPort() {
        val reply = LifxTestPackets.reply(3, 2, serial, 0, LifxTestPackets.stateServicePayload(56700))

        val service = LifxProtocol.parseStateService(reply, reply.size)!!

        assertEquals(1, service.service)
        assertEquals(56700, service.port)
    }

    @Test
    fun parseLightState_readsColorPowerAndNullTerminatedLabel() {
        val payload = LifxTestPackets.lightStatePayload(
            hue = 21845, saturation = 65535, brightness = 32768, kelvin = 3500, power = 65535, label = "Kitchen",
        )
        val reply = LifxTestPackets.reply(107, 2, serial, 0, payload)

        val state = LifxProtocol.parseLightState(reply, reply.size)!!

        assertEquals("Kitchen", state.label)
        assertTrue(state.isOn)
        val light = state.toLightState()
        assertEquals(120f, light.hue, 0.01f)
        assertEquals(1f, light.saturation, 0.0001f)
        assertNull(light.kelvin)
    }

    @Test
    fun parseLightState_zeroSaturationReadsAsWhite_andPowerZeroIsOff() {
        val payload = LifxTestPackets.lightStatePayload(0, 0, 65535, 2700, power = 0, label = "")
        val reply = LifxTestPackets.reply(107, 2, serial, 0, payload)

        val state = LifxProtocol.parseLightState(reply, reply.size)!!

        assertFalse(state.isOn)
        assertEquals(2700, state.toLightState().kelvin)
    }

    @Test
    fun parseProductId_readsStateVersion() {
        val reply = LifxTestPackets.reply(33, 2, serial, 0, LifxTestPackets.stateVersionPayload(product = 91))

        assertEquals(91, LifxProtocol.parseProductId(reply, reply.size))
    }

    @Test
    fun serial_roundTrips() {
        assertEquals(serial, LifxProtocol.serialToString(LifxProtocol.serialToBytes(serial), 0))
    }

    @Test
    fun productTable_clampsByProduct() {
        assertEquals(1_500, LifxProducts.clampKelvin(91, 1_000)) // LIFX Color 800lm: 1500–9000
        assertEquals(2_700, LifxProducts.clampKelvin(87, 9_000)) // LIFX White: fixed 2700
        assertEquals(9_000, LifxProducts.clampKelvin(99_999, 12_000)) // unknown product
    }
}
