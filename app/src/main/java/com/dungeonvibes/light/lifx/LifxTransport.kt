package com.dungeonvibes.light.lifx

import android.content.Context
import android.net.ConnectivityManager
import com.dungeonvibes.light.ApplicationScope
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** One received UDP packet. [data] is a copy owned by the receiver. */
class Datagram(val data: ByteArray, val length: Int, val fromIp: String)

/** Raw UDP on the LIFX port. Separate from the adapter so tests can swap in a fake network. */
interface LifxTransport {
    /** Every packet received on our socket. Subscribe before sending a request. */
    val incoming: SharedFlow<Datagram>

    suspend fun send(data: ByteArray, ip: String)

    /** Sends to every broadcast address on the current network. */
    suspend fun broadcast(data: ByteArray)
}

@Singleton
class UdpLifxTransport @Inject constructor(
    @ApplicationContext private val context: Context,
    @ApplicationScope private val scope: CoroutineScope,
) : LifxTransport {

    private val _incoming = MutableSharedFlow<Datagram>(
        extraBufferCapacity = 64,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    override val incoming: SharedFlow<Datagram> = _incoming.asSharedFlow()

    @Volatile
    private var socket: DatagramSocket? = null

    override suspend fun send(data: ByteArray, ip: String) = withContext(Dispatchers.IO) {
        socket().send(DatagramPacket(data, data.size, InetAddress.getByName(ip), LifxProtocol.PORT))
    }

    override suspend fun broadcast(data: ByteArray) = withContext(Dispatchers.IO) {
        val socket = socket()
        var sentAny = false
        var lastError: IOException? = null
        for (address in broadcastAddresses()) {
            try {
                socket.send(DatagramPacket(data, data.size, address, LifxProtocol.PORT))
                sentAny = true
            } catch (e: IOException) {
                lastError = e
            }
        }
        if (!sentAny && lastError != null) throw lastError
    }

    /** Opens the socket and starts the receive loop on first use. */
    private fun socket(): DatagramSocket {
        socket?.let { return it }
        synchronized(this) {
            socket?.let { return it }
            val created = DatagramSocket(null).apply {
                reuseAddress = true
                broadcast = true
                bind(InetSocketAddress(0)) // any free local port; bulbs reply to it
            }
            socket = created
            startReceiving(created)
            return created
        }
    }

    private fun startReceiving(socket: DatagramSocket) {
        scope.launch(Dispatchers.IO) {
            val buffer = ByteArray(RECEIVE_BUFFER_BYTES)
            val packet = DatagramPacket(buffer, buffer.size)
            while (isActive) {
                try {
                    packet.setLength(buffer.size)
                    socket.receive(packet) // blocks this IO thread until a packet arrives
                    val length = packet.length
                    _incoming.tryEmit(Datagram(buffer.copyOf(length), length, packet.address.hostAddress ?: ""))
                } catch (e: IOException) {
                    if (socket.isClosed) return@launch
                }
            }
        }
    }

    /** 255.255.255.255 plus the subnet's own broadcast address, which some routers require. */
    private fun broadcastAddresses(): List<InetAddress> {
        val result = ArrayList<InetAddress>(2)
        result.add(InetAddress.getByName("255.255.255.255"))
        val connectivity = context.getSystemService(ConnectivityManager::class.java) ?: return result
        val network = connectivity.activeNetwork ?: return result
        val links = connectivity.getLinkProperties(network)?.linkAddresses ?: return result
        for (link in links) {
            val ipv4 = link.address as? Inet4Address ?: continue
            val prefix = link.prefixLength
            if (prefix !in 1..30) continue
            val ip = ipv4.address
            val ipInt = ((ip[0].toInt() and 0xFF) shl 24) or ((ip[1].toInt() and 0xFF) shl 16) or
                ((ip[2].toInt() and 0xFF) shl 8) or (ip[3].toInt() and 0xFF)
            val hostMask = (1 shl (32 - prefix)) - 1
            val bcast = ipInt or hostMask
            result.add(
                InetAddress.getByAddress(
                    byteArrayOf((bcast ushr 24).toByte(), (bcast ushr 16).toByte(), (bcast ushr 8).toByte(), bcast.toByte()),
                ),
            )
        }
        return result
    }

    private companion object {
        const val RECEIVE_BUFFER_BYTES = 1024
    }
}
