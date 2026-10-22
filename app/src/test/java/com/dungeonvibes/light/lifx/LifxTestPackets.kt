package com.dungeonvibes.light.lifx

/**
 * Builds reply packets byte by byte, independently of [LifxProtocol]'s encoder, so tests don't
 * check the encoder against itself.
 */
object LifxTestPackets {

    fun hex(text: String): ByteArray {
        val clean = text.filter { !it.isWhitespace() }
        return ByteArray(clean.length / 2) { i -> clean.substring(i * 2, i * 2 + 2).toInt(16).toByte() }
    }

    fun toHex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it.toInt() and 0xFF) }

    /** A device-to-client packet: not tagged, target = the device's serial. */
    fun reply(type: Int, source: Int, serial: String, sequence: Int, payload: ByteArray = ByteArray(0)): ByteArray {
        val size = 36 + payload.size
        val p = ByteArray(size)
        p[0] = size.toByte(); p[1] = (size shr 8).toByte()
        p[2] = 0x00; p[3] = 0x14 // protocol 1024, addressable
        p[4] = source.toByte(); p[5] = (source shr 8).toByte(); p[6] = (source shr 16).toByte(); p[7] = (source shr 24).toByte()
        val serialBytes = hex(serial)
        System.arraycopy(serialBytes, 0, p, 8, 6)
        p[23] = sequence.toByte()
        p[32] = type.toByte(); p[33] = (type shr 8).toByte()
        System.arraycopy(payload, 0, p, 36, payload.size)
        return p
    }

    fun stateServicePayload(port: Int = 56700): ByteArray =
        byteArrayOf(1, port.toByte(), (port shr 8).toByte(), (port shr 16).toByte(), (port shr 24).toByte())

    fun stateVersionPayload(product: Int): ByteArray =
        byteArrayOf(1, 0, 0, 0, product.toByte(), (product shr 8).toByte(), 0, 0, 0, 0, 0, 0)

    fun lightStatePayload(hue: Int, saturation: Int, brightness: Int, kelvin: Int, power: Int, label: String): ByteArray {
        val p = ByteArray(52)
        fun u16(offset: Int, v: Int) { p[offset] = v.toByte(); p[offset + 1] = (v shr 8).toByte() }
        u16(0, hue); u16(2, saturation); u16(4, brightness); u16(6, kelvin)
        u16(10, power)
        val labelBytes = label.toByteArray(Charsets.UTF_8)
        System.arraycopy(labelBytes, 0, p, 12, labelBytes.size)
        return p
    }

    fun u16At(packet: ByteArray, offset: Int): Int =
        (packet[offset].toInt() and 0xFF) or ((packet[offset + 1].toInt() and 0xFF) shl 8)

    fun u32At(packet: ByteArray, offset: Int): Int =
        u16At(packet, offset) or (u16At(packet, offset + 2) shl 16)
}
