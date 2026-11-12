package com.google.android.accessibility.ext.shell

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import android.os.SystemClock
import android.util.Log
import java.net.DatagramPacket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.MulticastSocket
import java.net.NetworkInterface
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.concurrent.Executor
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * 发现本机「无线调试」公布的端口。
 *
 * 两条路：
 *  - NsdManager：系统封装，但 adbd 的广播常常只带 SRV、不带 A/AAAA 地址，服务永远“不完整”，
 *    解析结果固定 port=0（本机实测），拿不到端口。
 *  - 自己发/收 mDNS：绑定 5353 + MulticastLock + 解析 SRV 记录，能拿到 NsdManager 拿不到的端口。
 *
 * 上层只需要端口（连接一律走 127.0.0.1），所以拿到 port 就够了。
 */
internal object AdbMdns {
    const val CONNECT = "_adb-tls-connect._tcp"
    const val PAIRING = "_adb-tls-pairing._tcp"

    private const val TAG = "XpqAdbShell"
    private const val MDNS_ADDR = "224.0.0.251"

    /** 发现并解析一条服务，返回 (host, port)；超时或无结果返回 null。 */
    fun discover(context: Context, serviceType: String, timeoutMs: Long = 5_000L): Pair<String, Int>? {
        // 自建 mDNS 更快也更可靠（NsdManager 对「只带 SRV」的服务永远解析不出端口）
        val raw = rawMdnsPort(context, serviceType, 2_500L)
        Log.i(TAG, "raw mDNS $serviceType -> port=$raw")
        if (raw > 0 && isLocalPortOpen(raw)) return "127.0.0.1" to raw
        val nsd = nsdDiscover(context, serviceType, timeoutMs)
        if (nsd != null && isLocalPortOpen(nsd.second)) return nsd
        return null
    }

    /**
     * 端口是否真的在本机监听。
     *
     * mDNS 缓存里可能还留着 adbd 重启前的旧端口（实测发现过），直接拿来连/配对一定失败，
     * 所以发现结果要先做一次 TCP 探测，只有打开的端口才认。
     */
    private fun isLocalPortOpen(port: Int): Boolean = runCatching {
        Socket().use { it.connect(InetSocketAddress("127.0.0.1", port), 300) }
        true
    }.getOrDefault(false)

    /**
     * 持续等待某个服务出现，发现可用端口就回调一次并返回。
     *
     * 对应 Shizuku 里「对话框一打开就开着 mDNS 发现」：配对服务只在系统
     * 「使用配对码配对设备」弹窗打开期间存在，而用户通常是先开浮窗、后开系统弹窗，
     * 所以不能只查一次（查早了必然拿不到），要一直重发 PTR 查询。
     *
     * 阻塞，需在后台线程调用；[isActive] 返回 false（如浮窗已关闭）时提前结束。
     */
    fun watchPort(
        context: Context,
        serviceType: String,
        queryIntervalMs: Long = 1_000L,
        isActive: () -> Boolean,
        onFound: (Int) -> Unit,
    ) {
        val wifi = runCatching {
            context.applicationContext.getSystemService(android.net.wifi.WifiManager::class.java)
        }.getOrNull()
        val lock = runCatching {
            wifi?.createMulticastLock("xpq-mdns-watch")?.apply {
                setReferenceCounted(false)
                acquire()
            }
        }.getOrNull()

        var socket: MulticastSocket? = null
        try {
            val group = InetAddress.getByName(MDNS_ADDR)
            val groupAddr = InetSocketAddress(group, 5353)
            socket = MulticastSocket(null).apply {
                reuseAddress = true
                bind(InetSocketAddress(5353))
                // 短超时只为让 isActive 有机会被检查
                soTimeout = 300
            }
            NetworkInterface.getNetworkInterfaces().toList()
                .filter { it.isUp && it.supportsMulticast() && !it.isLoopback }
                .forEach { nif -> runCatching { socket.joinGroup(groupAddr, nif) } }

            val query = buildPtrQuery("$serviceType.local")
            val buf = ByteArray(4096)
            val localAddrs = localAddresses()
            var nextQueryAt = 0L
            while (isActive()) {
                val now = SystemClock.elapsedRealtime()
                if (now >= nextQueryAt) {
                    runCatching { socket.send(DatagramPacket(query, query.size, group, 5353)) }
                    nextQueryAt = now + queryIntervalMs
                }
                val packet = DatagramPacket(buf, buf.size)
                try {
                    socket.receive(packet)
                } catch (_: SocketTimeoutException) {
                    continue
                }
                val src = packet.address?.hostAddress
                if (src == null || src !in localAddrs) continue
                for (port in parseSrvPorts(buf, packet.length, serviceType)) {
                    if (isLocalPortOpen(port)) {
                        Log.i(TAG, "watch mDNS $serviceType -> port=$port")
                        onFound(port)
                        return
                    }
                    Log.i(TAG, "watch mDNS stale port $port ignored ($serviceType)")
                }
            }
        } catch (t: Throwable) {
            Log.w(TAG, "watch mDNS failed type=$serviceType", t)
        } finally {
            runCatching { socket?.close() }
            runCatching { lock?.release() }
        }
    }

    // ---------- NsdManager ----------

    private fun nsdDiscover(context: Context, serviceType: String, timeoutMs: Long): Pair<String, Int>? {
        val nsd = runCatching {
            context.applicationContext.getSystemService(NsdManager::class.java)
        }.getOrNull() ?: return null

        val found = LinkedBlockingQueue<Pair<String, Int>>()
        val stopping = java.util.concurrent.atomic.AtomicBoolean(false)

        fun stop(listener: NsdManager.DiscoveryListener) {
            if (stopping.compareAndSet(false, true)) {
                runCatching { nsd.stopServiceDiscovery(listener) }
            }
        }

        val listener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String) = Unit
            override fun onDiscoveryStopped(serviceType: String) = Unit
            override fun onServiceLost(serviceInfo: NsdServiceInfo) = Unit

            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                Log.w(TAG, "mDNS start failed type=$serviceType code=$errorCode")
                stopping.set(true)
            }

            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) = Unit

            override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                if (found.isNotEmpty()) return
                // 有些 ROM 在 found 阶段就带端口，能拿到就直接用
                val rawPort = serviceInfo.port
                Log.i(TAG, "mDNS found ${serviceInfo.serviceName} type=$serviceType port=$rawPort")
                if (rawPort > 0) {
                    found.offer((serviceInfo.host?.hostAddress ?: "127.0.0.1") to rawPort)
                    stop(this)
                    return
                }
                resolve(nsd, serviceInfo)?.let {
                    found.offer(it)
                    stop(this)
                }
            }
        }

        return try {
            nsd.discoverServices(serviceType, NsdManager.PROTOCOL_DNS_SD, listener)
            found.poll(timeoutMs, TimeUnit.MILLISECONDS).also { stop(listener) }
        } catch (t: Throwable) {
            Log.w(TAG, "mDNS discover failed type=$serviceType", t)
            stop(listener)
            null
        }
    }

    /** 解析服务地址：先试 API 34 的 callback，拿不到再退回废弃但兼容性更好的 resolveService。 */
    private fun resolve(nsd: NsdManager, info: NsdServiceInfo): Pair<String, Int>? {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            val result = LinkedBlockingQueue<Pair<String, Int>>()
            val executor = Executor { it.run() }
            val callback = object : NsdManager.ServiceInfoCallback {
                override fun onServiceInfoCallbackRegistrationFailed(errorCode: Int) {
                    Log.w(TAG, "mDNS callback register failed code=$errorCode")
                }

                override fun onServiceUpdated(serviceInfo: NsdServiceInfo) {
                    if (serviceInfo.port > 0) {
                        result.offer((serviceInfo.host?.hostAddress ?: "127.0.0.1") to serviceInfo.port)
                    }
                }

                override fun onServiceLost() = Unit
                override fun onServiceInfoCallbackUnregistered() = Unit
            }
            runCatching { nsd.registerServiceInfoCallback(info, executor, callback) }
                .onFailure { Log.w(TAG, "registerServiceInfoCallback failed", it) }
            val viaCallback = result.poll(1_500L, TimeUnit.MILLISECONDS)
            runCatching { nsd.unregisterServiceInfoCallback(callback) }
            if (viaCallback != null) return viaCallback
        }

        val legacy = LinkedBlockingQueue<Pair<String, Int>>()
        @Suppress("DEPRECATION")
        runCatching {
            nsd.resolveService(info, object : NsdManager.ResolveListener {
                override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                    Log.w(TAG, "mDNS resolve failed code=$errorCode")
                }

                override fun onServiceResolved(serviceInfo: NsdServiceInfo) {
                    if (serviceInfo.port > 0) {
                        legacy.offer((serviceInfo.host?.hostAddress ?: "127.0.0.1") to serviceInfo.port)
                    }
                }
            })
        }.onFailure { Log.w(TAG, "resolveService failed", it) }
        return legacy.poll(1_500L, TimeUnit.MILLISECONDS)
    }

    // ---------- 自建 mDNS ----------

    /**
     * 绑定 5353、加入 224.0.0.251 组播，发 PTR 查询并从 SRV 记录里读端口。
     * 需要 CHANGE_WIFI_MULTICAST_STATE（MulticastLock），否则部分机型收不到组播。
     */
    private fun rawMdnsPort(context: Context, serviceType: String, timeoutMs: Long): Int {
        val wifi = runCatching {
            context.applicationContext.getSystemService(android.net.wifi.WifiManager::class.java)
        }.getOrNull()
        val lock = runCatching {
            wifi?.createMulticastLock("xpq-mdns")?.apply {
                setReferenceCounted(false)
                acquire()
            }
        }.getOrNull()

        var socket: MulticastSocket? = null
        try {
            val group = InetAddress.getByName(MDNS_ADDR)
            val groupAddr = InetSocketAddress(group, 5353)
            socket = MulticastSocket(null).apply {
                reuseAddress = true
                bind(InetSocketAddress(5353))
                soTimeout = 300
            }
            // 在所有支持组播的网卡上加入组（Wi-Fi / 热点都可能承载 adbd 广播）
            val interfaces = runCatching {
                NetworkInterface.getNetworkInterfaces().toList().filter {
                    it.isUp && it.supportsMulticast() && !it.isLoopback
                }
            }.getOrDefault(emptyList())
            interfaces.forEach { nif ->
                runCatching { socket.joinGroup(groupAddr, nif) }
            }
            val query = buildPtrQuery("$serviceType.local")
            socket.send(DatagramPacket(query, query.size, group, 5353))

            val buf = ByteArray(4096)
            val deadline = SystemClock.elapsedRealtime() + timeoutMs
            val localAddrs = localAddresses()
            while (SystemClock.elapsedRealtime() < deadline) {
                val packet = DatagramPacket(buf, buf.size)
                try {
                    socket.receive(packet)
                } catch (_: SocketTimeoutException) {
                    continue
                }
                // 只采信本机网卡发回的响应：同一局域网可能有别的手机的 mDNS 广播
                val src = packet.address?.hostAddress
                if (src == null || src !in localAddrs) continue
                val ports = parseSrvPorts(buf, packet.length, serviceType)
                for (port in ports) {
                    // 缓存里可能是旧端口：探测通过才认，否则继续看同一个包里的其它记录
                    if (isLocalPortOpen(port)) return port
                    Log.i(TAG, "raw mDNS stale port $port ignored ($serviceType)")
                }
            }
            return 0
        } catch (t: Throwable) {
            Log.w(TAG, "raw mDNS failed type=$serviceType", t)
            return 0
        } finally {
            runCatching { socket?.close() }
            runCatching { lock?.release() }
        }
    }

    private fun buildPtrQuery(name: String): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        fun u16(v: Int) {
            out.write((v shr 8) and 0xFF)
            out.write(v and 0xFF)
        }
        u16(0) // id
        u16(0) // flags
        u16(1) // qdcount
        u16(0); u16(0); u16(0) // an / ns / ar
        for (label in name.split('.')) {
            if (label.isEmpty()) continue
            val b = label.toByteArray(Charsets.US_ASCII)
            out.write(b.size)
            out.write(b)
        }
        out.write(0)
        u16(12) // PTR
        u16(1) // IN
        return out.toByteArray()
    }

    /**
     * 解析响应里第一条**匹配 [serviceType]** 的 SRV 端口。
     *
     * 必须按服务类型过滤：无线调试开着时 adbd 会同时广播 `_adb-tls-connect` 和
     * `_adb-tls-pairing`，不过滤就会把连接端口当成配对端口（配对必失败）。
     */
    private fun parseSrvPorts(data: ByteArray, len: Int, serviceType: String): List<Int> {
        val result = ArrayList<Int>(2)
        var pos = 12
        val qd = readU16(data, 4)
        val total = readU16(data, 6) + readU16(data, 8) + readU16(data, 10)
        repeat(qd) {
            pos = skipName(data, pos)
            pos += 4
        }
        val wanted = serviceType.lowercase()
        repeat(total) {
            val nameStart = pos
            val name = readName(data, nameStart) ?: return result
            pos = skipName(data, nameStart)
            if (pos + 10 > len) return result
            val type = readU16(data, pos)
            val rdLen = readU16(data, pos + 8)
            val rdata = pos + 10
            if (type == 33 && rdLen >= 6 && rdata + 6 <= len &&
                name.lowercase().contains(wanted)
            ) {
                val port = readU16(data, rdata + 4)
                if (port > 0) result.add(port)
            }
            pos = rdata + rdLen
        }
        return result
    }

    /** 本机所有网卡地址，用来过滤掉局域网里其它设备回的 mDNS。 */
    private fun localAddresses(): Set<String> = runCatching {
        NetworkInterface.getNetworkInterfaces().toList()
            .flatMap { it.inetAddresses.toList() }
            .mapNotNull { it.hostAddress }
            .toSet()
    }.getOrDefault(emptySet())

    /** 解压 DNS 名字（支持 0xC0 压缩指针）。 */
    private fun readName(d: ByteArray, start: Int): String? {
        val sb = StringBuilder()
        var p = start
        var guard = 0
        while (p < d.size && guard++ < 64) {
            val b = d[p].toInt() and 0xFF
            if (b == 0) return sb.toString()
            if (b and 0xC0 == 0xC0) {
                if (p + 1 >= d.size) return null
                val ptr = ((b and 0x3F) shl 8) or (d[p + 1].toInt() and 0xFF)
                val tail = readName(d, ptr) ?: return null
                if (tail.isNotEmpty()) {
                    if (sb.isNotEmpty()) sb.append('.')
                    sb.append(tail)
                }
                return sb.toString()
            }
            if (p + 1 + b > d.size) return null
            if (sb.isNotEmpty()) sb.append('.')
            sb.append(String(d, p + 1, b, Charsets.US_ASCII))
            p += 1 + b
        }
        return sb.toString()
    }

    private fun readU16(d: ByteArray, p: Int): Int =
        ((d[p].toInt() and 0xFF) shl 8) or (d[p + 1].toInt() and 0xFF)

    private fun skipName(d: ByteArray, start: Int): Int {
        var p = start
        while (p < d.size) {
            val b = d[p].toInt() and 0xFF
            if (b == 0) return p + 1
            if (b and 0xC0 == 0xC0) return p + 2
            p += 1 + b
        }
        return p
    }
}
