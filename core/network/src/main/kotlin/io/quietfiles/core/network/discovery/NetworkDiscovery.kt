package io.quietfiles.core.network.discovery

import android.content.Context
import android.net.ConnectivityManager
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import dagger.hilt.android.qualifiers.ApplicationContext
import io.quietfiles.core.model.network.Protocol
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.SocketTimeoutException
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/** Сервер, найденный в локальной сети (F16.2). */
data class DiscoveredServer(val name: String, val host: String, val port: Int, val protocol: Protocol)

/**
 * Поиск серверов в локальной сети при добавлении подключения (F16.2): службы mDNS (Bonjour/Avahi —
 * NAS, Linux, macOS) и имена NetBIOS (Windows и Samba) в подсети текущего Wi-Fi.
 * Ничего не уходит за пределы локальной сети.
 */
@Singleton
class NetworkDiscovery @Inject constructor(@ApplicationContext private val context: Context) {

    /** Растущий список найденного; поток завершается сам через [DURATION_MS]. */
    fun discover(): Flow<List<DiscoveredServer>> = callbackFlow {
        val found = ConcurrentHashMap<String, DiscoveredServer>()
        fun add(server: DiscoveredServer) {
            val key = "${server.protocol}:${server.host}:${server.port}"
            if (found.putIfAbsent(key, server) == null) trySend(found.values.sortedWith(compareBy({ it.name.lowercase() }, { it.protocol })))
        }

        val nsd = context.getSystemService(NsdManager::class.java)
        val listeners = SERVICES.map { (type, protocol) ->
            object : NsdManager.DiscoveryListener {
                override fun onServiceFound(info: NsdServiceInfo) = resolve(nsd, info) { resolved ->
                    val host = resolved.host?.hostAddress ?: return@resolve
                    if (resolved.host !is Inet4Address) return@resolve
                    add(DiscoveredServer(resolved.serviceName, host, resolved.port, protocol))
                }
                override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) = Unit
                override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) = Unit
                override fun onDiscoveryStarted(serviceType: String) = Unit
                override fun onDiscoveryStopped(serviceType: String) = Unit
                override fun onServiceLost(info: NsdServiceInfo) = Unit
            }.also { runCatching { nsd?.discoverServices(type, NsdManager.PROTOCOL_DNS_SD, it) } }
        }

        val scan = launch(Dispatchers.IO) {
            scanNetBios { name, address -> add(DiscoveredServer(name, address, Protocol.SMB.defaultPort, Protocol.SMB)) }
        }
        val stop = launch {
            kotlinx.coroutines.delay(DURATION_MS)
            close()
        }
        awaitClose {
            scan.cancel()
            stop.cancel()
            listeners.forEach { runCatching { nsd?.stopServiceDiscovery(it) } }
        }
    }.flowOn(Dispatchers.Default)

    @Suppress("DEPRECATION") // registerServiceInfoCallback — только с Android 14; resolveService работает везде.
    private fun resolve(nsd: NsdManager?, info: NsdServiceInfo, onResolved: (NsdServiceInfo) -> Unit) {
        nsd ?: return
        runCatching {
            nsd.resolveService(
                info,
                object : NsdManager.ResolveListener {
                    override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) = Unit
                    override fun onServiceResolved(serviceInfo: NsdServiceInfo) = onResolved(serviceInfo)
                },
            )
        }
    }

    /** Опрос NBSTAT всех адресов подсети /24 текущей сети; ответы приходят за доли секунды. */
    private fun scanNetBios(onFound: (String, String) -> Unit) {
        val own = localIpv4() ?: return
        val prefix = own.address.copyOf(3)
        DatagramSocket().use { socket ->
            socket.soTimeout = 300
            for (last in 1..254) {
                val target = InetAddress.getByAddress(prefix + last.toByte())
                if (target == own) continue
                val request = NetBios.statusRequest(0x5100 + last)
                runCatching { socket.send(DatagramPacket(request, request.size, target, NetBios.PORT)) }
            }
            val buffer = ByteArray(1024)
            val deadline = System.currentTimeMillis() + NETBIOS_WAIT_MS
            while (System.currentTimeMillis() < deadline && !Thread.currentThread().isInterrupted) {
                val packet = DatagramPacket(buffer, buffer.size)
                try {
                    socket.receive(packet)
                } catch (_: SocketTimeoutException) {
                    continue
                }
                NetBios.parseStatus(packet.data, packet.length)?.let { onFound(it, packet.address.hostAddress ?: return@let) }
            }
        }
    }

    /** IPv4-адрес устройства в текущей сети (Wi-Fi или Ethernet). */
    fun localIpv4(): Inet4Address? {
        val connectivity = context.getSystemService(ConnectivityManager::class.java) ?: return null
        val network = connectivity.activeNetwork ?: return null
        val link = connectivity.getLinkProperties(network) ?: return null
        return link.linkAddresses.map { it.address }.filterIsInstance<Inet4Address>().firstOrNull { !it.isLoopbackAddress }
    }

    private companion object {
        const val DURATION_MS = 6_000L
        const val NETBIOS_WAIT_MS = 2_500L
        val SERVICES = listOf(
            "_smb._tcp" to Protocol.SMB,
            "_sftp-ssh._tcp" to Protocol.SFTP,
            "_ssh._tcp" to Protocol.SFTP,
            "_ftp._tcp" to Protocol.FTP,
            "_webdav._tcp" to Protocol.WEBDAV,
            "_webdavs._tcp" to Protocol.WEBDAV,
        )
    }
}
