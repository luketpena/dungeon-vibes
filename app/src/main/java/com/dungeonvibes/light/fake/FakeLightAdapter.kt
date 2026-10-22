package com.dungeonvibes.light.fake

import com.dungeonvibes.light.BulbAddress
import com.dungeonvibes.light.DiscoveredBulb
import com.dungeonvibes.light.LightAdapter
import com.dungeonvibes.light.LightException
import com.dungeonvibes.light.LightState
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.update

/**
 * Simulated bulbs for tests and for trying the app without hardware. Logs every command it gets.
 *
 * @param isEnabled read on every call; the debug setting "Use fake bulbs" drives it in the app.
 * @param commandDelayMs simulated network time per command.
 */
class FakeLightAdapter(
    private val isEnabled: () -> Boolean = { true },
    private val commandDelayMs: Long = 0,
) : LightAdapter {

    sealed interface Command {
        val serial: String

        data class SetState(override val serial: String, val state: LightState, val fadeMs: Int, val reliable: Boolean) : Command
        data class SetPower(override val serial: String, val on: Boolean, val fadeMs: Int, val reliable: Boolean) : Command
        data class Identify(override val serial: String) : Command
    }

    data class SimulatedBulb(val serial: String, val label: String, val isOn: Boolean, val state: LightState)

    override val protocolId: String = PROTOCOL_ID

    override val isAvailable: Boolean get() = isEnabled()

    private val bulbs = ConcurrentHashMap<String, SimulatedBulb>().apply {
        for (i in 1..BULB_COUNT) {
            val serial = serialFor(i)
            put(serial, SimulatedBulb(serial, "Fake bulb $i", isOn = false, state = LightState(0f, 0f, 1f, 2700)))
        }
    }

    private val _commands = MutableStateFlow<List<Command>>(emptyList())

    /** Every command received, oldest first (capped at [LOG_LIMIT]). */
    val commands: StateFlow<List<Command>> = _commands.asStateFlow()

    /** Serials listed here don't answer scans and fail every command. */
    val offlineSerials: MutableSet<String> = ConcurrentHashMap.newKeySet()

    fun bulb(serial: String): SimulatedBulb? = bulbs[serial]

    fun clearLog() {
        _commands.value = emptyList()
    }

    override fun discover(timeoutMs: Long): Flow<DiscoveredBulb> = flow {
        for (bulb in bulbs.values.sortedBy { it.serial }) {
            if (bulb.serial in offlineSerials) continue
            emit(
                DiscoveredBulb(
                    address = BulbAddress(PROTOCOL_ID, bulb.serial, ipFor(bulb.serial)),
                    label = bulb.label,
                    isOn = bulb.isOn,
                    state = bulb.state,
                ),
            )
        }
    }

    override suspend fun setState(bulb: BulbAddress, state: LightState, fadeMs: Int, reliable: Boolean): Result<Unit> =
        handle(Command.SetState(bulb.serial, state, fadeMs, reliable)) { it.copy(state = state) }

    override suspend fun setPower(bulb: BulbAddress, on: Boolean, fadeMs: Int, reliable: Boolean): Result<Unit> =
        handle(Command.SetPower(bulb.serial, on, fadeMs, reliable)) { it.copy(isOn = on) }

    override suspend fun identify(bulb: BulbAddress) {
        handle(Command.Identify(bulb.serial)) { it }
    }

    private suspend fun handle(command: Command, change: (SimulatedBulb) -> SimulatedBulb): Result<Unit> {
        if (commandDelayMs > 0) delay(commandDelayMs)
        if (!isEnabled()) return Result.failure(LightException("Fake bulbs are switched off"))
        val serial = command.serial
        if (serial in offlineSerials) return Result.failure(LightException("Fake bulb $serial is offline"))
        val current = bulbs[serial] ?: return Result.failure(LightException("Unknown fake bulb $serial"))
        bulbs[serial] = change(current)
        _commands.update { log -> if (log.size < LOG_LIMIT) log + command else log.drop(1) + command }
        return Result.success(Unit)
    }

    companion object {
        const val PROTOCOL_ID = "fake"
        const val BULB_COUNT = 3
        private const val LOG_LIMIT = 500

        /** "fake:01" … matches the serials used by the debug sample data. */
        fun serialFor(index: Int): String = "fake:" + index.toString().padStart(2, '0')

        private fun ipFor(serial: String): String = "0.0.0." + serial.substringAfter(':').toInt()
    }
}
