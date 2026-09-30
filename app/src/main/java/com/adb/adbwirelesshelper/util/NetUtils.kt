package com.adb.adbwirelesshelper.util

import android.content.Context
import android.net.wifi.WifiManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.ConnectException
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.Socket

/**
 * 网络工具：本机 IP / 网段推算 / 并发探活 / 组播锁。
 * 纯工具，不持有任何状态，不引用 Android UI 类型。
 */
object NetUtils {

    private const val TAG = "NetUtils"
    private const val MULTICAST_LOCK_TAG = "adb-discovery"

    /**
     * 取本机 IPv4 地址。优先返回 wlan 接口，其次任意非回环、已启用的 IPv4 地址。
     * 没有可用网络时返回 null。
     */
    fun localIpAddress(): String? {
        return runCatching {
            var fallback: String? = null
            val interfaces = NetworkInterface.getNetworkInterfaces() ?: return null
            while (interfaces.hasMoreElements()) {
                val netIf = interfaces.nextElement()
                if (!netIf.isUp || netIf.isLoopback) {
                    continue
                }
                val addresses = netIf.inetAddresses ?: continue
                while (addresses.hasMoreElements()) {
                    val addr = addresses.nextElement()
                    if (addr.isLoopbackAddress || addr !is Inet4Address) {
                        continue
                    }
                    val ip = addr.hostAddress ?: continue
                    val name = netIf.name.orEmpty()
                    if (name.startsWith("wlan") || name.startsWith("eth") || name.startsWith("ap")) {
                        return ip
                    }
                    if (fallback == null) {
                        fallback = ip
                    }
                }
            }
            fallback
        }.onFailure {
            Logx.w(TAG, "获取本机 IP 失败: ${it.message}")
        }.getOrNull()
    }

    /**
     * 本机 /24 网段前缀，形如 "192.168.1."（**含末尾的点**）。
     * 无法推算时返回 null。
     */
    fun localSubnetPrefix(): String? {
        val ip = localIpAddress() ?: return null
        val lastDot = ip.lastIndexOf('.')
        if (lastDot <= 0) {
            return null
        }
        return ip.substring(0, lastDot + 1)
    }

    /**
     * 展开网段内 1..254 的主机地址。
     *
     * @param prefix 形如 "192.168.1."（含末尾点）
     */
    fun subnetHosts(prefix: String): List<String> {
        val normalized = if (prefix.endsWith(".")) prefix else "$prefix."
        val hosts = ArrayList<String>(254)
        for (i in 1..254) {
            hosts.add(normalized + i)
        }
        return hosts
    }

    /**
     * TCP 探活的**三态**结果。区分「端口关闭」与「根本没人应答」是 ports scan 提速的关键：
     * 前者说明主机在线（RST 即时回包），后者说明这台 IP 大概率不存在，再扫它的 200 个端口纯属浪费。
     */
    enum class ProbeResult {
        /** 握手成功，端口开放。 */
        OPEN,

        /** 对方回了 RST —— 端口关闭，但**主机在线**（回包是毫秒级的）。 */
        REFUSED,

        /** 超时 / 不可达 —— 主机不在线，或防火墙静默丢包。 */
        UNREACHABLE,
    }

    /**
     * TCP 探活：内部切 `Dispatchers.IO`。
     *
     * 与旧的布尔版不同，**必须区分失败原因**：
     * - [ProbeResult.REFUSED]：主机存在（对不存在的 IP 只会走到超时，不会走到 RST），是一次「在线」证据；
     * - [ProbeResult.UNREACHABLE]：这次探测白等了整个 timeout，说明该 IP 不在线，**后续无需再扫它的其它端口**。
     */
    suspend fun probeTcp(host: String, port: Int, timeoutMs: Int = 300): ProbeResult =
        withContext(Dispatchers.IO) {
            if (port <= 0 || port > 65535) {
                return@withContext ProbeResult.UNREACHABLE
            }
            runCatching {
                Socket().use { socket ->
                    socket.connect(InetSocketAddress(host, port), timeoutMs)
                }
                ProbeResult.OPEN
            }.getOrElse { error ->
                // ⚠️ runCatching 会吞 CancellationException，必须显式放行，
                //    否则协程取消信号被吃掉，结构化并发失效（本项目已踩过这个坑）
                if (error is CancellationException) throw error
                classifyFailure(error)
            }
        }

    /**
     * 探测失败原因 → [ProbeResult]。抽成纯函数以便单测（无需真开 socket）。
     */
    internal fun classifyFailure(error: Throwable): ProbeResult {
        // 「连接被拒」= 对方 TCP 栈给了 RST：端口没开，但主机在线且响应很快
        return if (error is ConnectException) ProbeResult.REFUSED else ProbeResult.UNREACHABLE
    }

    /**
     * 创建组播锁。**返回未 acquire 的锁**，由调用方在扫描期间 acquire / release。
     *
     * 说明：Android 默认会过滤组播包，不持锁多数机型收不到 mDNS 响应（需求 B1）。
     * 这里 setReferenceCounted(false)，acquire/release 不计数，避免多次调用互相抵消。
     */
    fun multicastWifiLock(context: Context): WifiManager.MulticastLock {
        val appContext = context.applicationContext
        val wifiManager = appContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            ?: throw IllegalStateException("无法获取 WifiManager，设备可能不支持 Wi-Fi")
        return wifiManager.createMulticastLock(MULTICAST_LOCK_TAG).apply {
            setReferenceCounted(false)
        }
    }
}
