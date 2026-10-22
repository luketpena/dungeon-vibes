package com.dungeonvibes.light.lifx

import com.dungeonvibes.light.LightState
import kotlin.math.roundToInt

/**
 * LIFX LAN packet encoding and decoding, written from lan.developer.lifx.com.
 * All multi-byte fields are little-endian. Every packet starts with a 36-byte header:
 *
 *  0..1   size            total packet length
 *  2..3   protocol(12 bits)=1024 | addressable(bit 12)=1 | tagged(bit 13) | origin(bits 14-15)=0
 *  4..7   source          our client id, echoed in replies
 *  8..15  target          6-byte serial + 2 zero bytes; all zero = every device
 *  16..21 reserved
 *  22     res_required(bit 0) | ack_required(bit 1)
 *  23     sequence        per-device counter, echoed in replies
 *  24..31 reserved
 *  32..33 type            message type
 *  34..35 reserved
 */
object LifxProtocol {
    const val PORT = 56700
    const val HEADER_SIZE = 36

    private const val PROTOCOL_NUMBER = 1024
    private const val ADDRESSABLE_BIT = 1 shl 12
    private const val TAGGED_BIT = 1 shl 13

    /* Message types */
    const val GET_SERVICE = 2
    const val STATE_SERVICE = 3
    const val GET_VERSION = 32
    const val STATE_VERSION = 33
    const val ACKNOWLEDGEMENT = 45
    const val GET_COLOR = 101
    const val SET_COLOR = 102
    const val LIGHT_STATE = 107
    const val SET_LIGHT_POWER = 117

    const val SERVICE_UDP = 1

    /** Kelvin sent with scene colors; the docs' examples use 3500. */
    const val COLOR_MODE_KELVIN = LightState.COLOR_KELVIN

    private const val SET_COLOR_PAYLOAD = 13
    private const val SET_LIGHT_POWER_PAYLOAD = 6
    private const val SERIAL_BYTES = 6
    private const val LABEL_BYTES = 32

    /* Encoding */

    /** Discovery broadcast: tagged, all-zero target. */
    fun getService(source: Int, sequence: Int): ByteArray {
        val packet = ByteArray(HEADER_SIZE)
        writeHeader(packet, tagged = true, source, target = null, ackRequired = false, resRequired = false, sequence, GET_SERVICE)
        return packet
    }

    /** Any message with an empty payload sent to one bulb (GetColor, GetVersion, ...). */
    fun emptyRequest(type: Int, target: ByteArray, source: Int, sequence: Int): ByteArray {
        val packet = ByteArray(HEADER_SIZE)
        writeHeader(packet, tagged = false, source, target, ackRequired = false, resRequired = false, sequence, type)
        return packet
    }

    /**
     * SetColor (102). Color mode sends hue and saturation with state.colorKelvin (3500 unless an
     * effect blend changes it), clamped to the product's range; white mode
     * (state.kelvin != null) sends saturation 0 and the kelvin clamped to the product's range.
     */
    fun setColor(
        target: ByteArray,
        source: Int,
        sequence: Int,
        ackRequired: Boolean,
        state: LightState,
        productId: Int?,
        durationMs: Int,
    ): ByteArray {
        val packet = ByteArray(HEADER_SIZE + SET_COLOR_PAYLOAD)
        writeHeader(packet, tagged = false, source, target, ackRequired, resRequired = false, sequence, SET_COLOR)
        val white = state.kelvin
        // Byte 36 is reserved (0).
        putU16(packet, 37, hueToU16(state.hue))
        putU16(packet, 39, if (white != null) 0 else unitToU16(state.saturation))
        putU16(packet, 41, unitToU16(state.brightness))
        putU16(packet, 43, LifxProducts.clampKelvin(productId, white ?: state.colorKelvin))
        putU32(packet, 45, durationMs.coerceAtLeast(0))
        return packet
    }

    /** SetLightPower (117): level 65535 = on, 0 = off, with a transition. */
    fun setLightPower(
        target: ByteArray,
        source: Int,
        sequence: Int,
        ackRequired: Boolean,
        on: Boolean,
        durationMs: Int,
    ): ByteArray {
        val packet = ByteArray(HEADER_SIZE + SET_LIGHT_POWER_PAYLOAD)
        writeHeader(packet, tagged = false, source, target, ackRequired, resRequired = false, sequence, SET_LIGHT_POWER)
        putU16(packet, 36, if (on) 0xFFFF else 0)
        putU32(packet, 38, durationMs.coerceAtLeast(0))
        return packet
    }

    private fun writeHeader(
        packet: ByteArray,
        tagged: Boolean,
        source: Int,
        target: ByteArray?,
        ackRequired: Boolean,
        resRequired: Boolean,
        sequence: Int,
        type: Int,
    ) {
        putU16(packet, 0, packet.size)
        putU16(packet, 2, PROTOCOL_NUMBER or ADDRESSABLE_BIT or (if (tagged) TAGGED_BIT else 0))
        putU32(packet, 4, source)
        if (target != null) System.arraycopy(target, 0, packet, 8, SERIAL_BYTES)
        var flags = 0
        if (resRequired) flags = flags or 0x01
        if (ackRequired) flags = flags or 0x02
        packet[22] = flags.toByte()
        packet[23] = sequence.toByte()
        putU16(packet, 32, type)
    }

    /* Decoding */

    class Header(val size: Int, val source: Int, val serial: String, val sequence: Int, val type: Int)

    /** Returns null for anything that isn't a well-formed LIFX packet. */
    fun parseHeader(data: ByteArray, length: Int): Header? {
        if (length < HEADER_SIZE) return null
        val size = getU16(data, 0)
        if (size < HEADER_SIZE || size > length) return null
        if (getU16(data, 2) and 0x0FFF != PROTOCOL_NUMBER) return null
        return Header(
            size = size,
            source = getU32(data, 4),
            serial = serialToString(data, 8),
            sequence = data[23].toInt() and 0xFF,
            type = getU16(data, 32),
        )
    }

    class StateService(val service: Int, val port: Int)

    fun parseStateService(data: ByteArray, length: Int): StateService? {
        if (length < HEADER_SIZE + 5) return null
        return StateService(service = data[36].toInt() and 0xFF, port = getU32(data, 37))
    }

    class LightStateReply(
        val hue: Int,
        val saturation: Int,
        val brightness: Int,
        val kelvin: Int,
        val power: Int,
        val label: String,
    ) {
        val isOn: Boolean get() = power != 0

        /** Saturation 0 reads as white mode; anything else as color mode. */
        fun toLightState(): LightState = LightState(
            hue = u16ToHue(hue),
            saturation = u16ToUnit(saturation),
            brightness = u16ToUnit(brightness),
            kelvin = if (saturation == 0) kelvin else null,
        )
    }

    fun parseLightState(data: ByteArray, length: Int): LightStateReply? {
        if (length < HEADER_SIZE + 52) return null
        return LightStateReply(
            hue = getU16(data, 36),
            saturation = getU16(data, 38),
            brightness = getU16(data, 40),
            kelvin = getU16(data, 42),
            // 44..45 reserved
            power = getU16(data, 46),
            label = readLabel(data, 48),
        )
    }

    /** StateVersion (33): returns the product id (vendor is bytes 36..39). */
    fun parseProductId(data: ByteArray, length: Int): Int? {
        if (length < HEADER_SIZE + 12) return null
        return getU32(data, 40)
    }

    /* Conversions */

    fun hueToU16(hue: Float): Int = (hue.coerceIn(0f, 360f) / 360f * 65535f).roundToInt()

    fun unitToU16(value: Float): Int = (value.coerceIn(0f, 1f) * 65535f).roundToInt()

    fun u16ToHue(value: Int): Float = value * 360f / 65535f

    fun u16ToUnit(value: Int): Float = value / 65535f

    /** "d073d5001337" → 6 bytes. Throws on malformed input. */
    fun serialToBytes(serial: String): ByteArray {
        require(serial.length == SERIAL_BYTES * 2) { "LIFX serial must be 12 hex digits: $serial" }
        val bytes = ByteArray(SERIAL_BYTES)
        for (i in 0 until SERIAL_BYTES) {
            bytes[i] = serial.substring(i * 2, i * 2 + 2).toInt(16).toByte()
        }
        return bytes
    }

    private const val HEX = "0123456789abcdef"

    fun serialToString(data: ByteArray, offset: Int): String {
        val chars = CharArray(SERIAL_BYTES * 2)
        for (i in 0 until SERIAL_BYTES) {
            val b = data[offset + i].toInt() and 0xFF
            chars[i * 2] = HEX[b ushr 4]
            chars[i * 2 + 1] = HEX[b and 0x0F]
        }
        return String(chars)
    }

    private fun readLabel(data: ByteArray, offset: Int): String {
        var end = offset
        val limit = offset + LABEL_BYTES
        while (end < limit && data[end] != 0.toByte()) end++
        return String(data, offset, end - offset, Charsets.UTF_8)
    }

    /* Little-endian helpers */

    private fun putU16(buf: ByteArray, offset: Int, value: Int) {
        buf[offset] = value.toByte()
        buf[offset + 1] = (value ushr 8).toByte()
    }

    private fun putU32(buf: ByteArray, offset: Int, value: Int) {
        buf[offset] = value.toByte()
        buf[offset + 1] = (value ushr 8).toByte()
        buf[offset + 2] = (value ushr 16).toByte()
        buf[offset + 3] = (value ushr 24).toByte()
    }

    private fun getU16(buf: ByteArray, offset: Int): Int =
        (buf[offset].toInt() and 0xFF) or ((buf[offset + 1].toInt() and 0xFF) shl 8)

    private fun getU32(buf: ByteArray, offset: Int): Int =
        (buf[offset].toInt() and 0xFF) or
            ((buf[offset + 1].toInt() and 0xFF) shl 8) or
            ((buf[offset + 2].toInt() and 0xFF) shl 16) or
            ((buf[offset + 3].toInt() and 0xFF) shl 24)
}
