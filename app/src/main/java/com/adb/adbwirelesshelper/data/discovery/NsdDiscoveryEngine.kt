package com.adb.adbwirelesshelper.data.discovery

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import com.adb.adbwirelesshelper.domain.model.Capability
import com.adb.adbwirelesshelper.domain.model.DiscoveredService
import com.adb.adbwirelesshelper.domain.model.Source
import com.adb.adbwirelesshelper.util.Logx
import com.adb.adbwirelesshelper.util.NetUtils
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.Collections

/**
 * 基于系统 [NsdManager] 的 mDNS/DNS-SD 设备发现引擎。
 *
 * 职责：
 * 1. 同时发现 `_adb-tls-connect._tcp.`（连接端口）与 `_adb-tls-pairing._tcp.`（配对端口）两类服务；
 * 2. 全程持有 [WifiManager.MulticastLock]，否则多数机型收不到组播响应；
 * 3. 所有 discover / resolve 请求串行化，并对 `IllegalArgumentException`
 *    （Android 上表现为 "listener already in use"）做指数退避重试；
 * 4. 结果按 `host + serviceType + port` 去重后发射。
 *
 * 说明：NsdManager 的回调依赖 Looper，因此本引擎的上游统一运行在 `Dispatchers.Main`，
 * 避免在无 Looper 的 IO 线程上注册监听器导致崩溃。
 */
class NsdDiscoveryEngine(private val context: Context) {

    companion object {
        private const val TAG = "NsdDiscovery"

        /** 无线调试「连接端口」服务类型（已开启且就绪）。 */
        const val SERVICE_TYPE_CONNECT: String = "_adb-tls-connect._tcp."

        /** 无线调试「配对端口」服务类型（被控端正处于配对模式）。 */
        const val SERVICE_TYPE_PAIRING: String = "_adb-tls-pairing._tcp."

        /** 需要发现的全部服务类型。 */
        val SERVICE_TYPES: List<String> = listOf(SERVICE_TYPE_CONNECT, SERVICE_TYPE_PAIRING)

        private const val MAX_RETRY: Int = 5
        private const val BASE_BACKOFF_MS: Long = 200L
        private const val MAX_BACKOFF_MS: Long = 4_000L
    }

    private val appContext: Context = context.applicationContext
    private val nsdManager: NsdManager? =
        appContext.getSystemService(Context.NSD_SERVICE) as? NsdManager

    /** 串行化 discover / resolve，规避 "listener already in use"。 */
    private val requestMutex: Mutex = Mutex()

    /** 用于执行 stop()（必须在有 Looper 的线程上注销监听）。 */
    private val stopScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    /** 每个活跃 Flow 注册的「停止动作」，供 stop() 调用。 */
    private val activeAborts: MutableList<() -> Unit> =
        Collections.synchronizedList(mutableListOf<() -> Unit>())

    /** 去重集合：key = host + serviceType + port。 */
    private val resolvedKeys: MutableSet<String> =
        Collections.synchronizedSet(mutableSetOf<String>())

    @Volatile
    private var multicastLock: WifiManager.MulticastLock? = null

    /**
     * 冷流：collect 时启动发现，取消（或超时无结果）时自动停止。
     *
     * @param mdnsTimeoutMs mDNS 阶段时长上限。到达该时长后若**一条结果都没发现**，
     *                      本流自动结束，由上层（[DiscoveryRepository]）切换到端口扫描兜底；
     *                      若已发现结果，则持续发现直到流被取消，以便列表持续更新。
     */
    fun discover(mdnsTimeoutMs: Long = 8_000L): Flow<DiscoveredService> = callbackFlow {
        val manager = nsdManager
        if (manager == null) {
            Logx.w(TAG, "NsdManager 不可用，已跳过 mDNS 发现")
            channel.close()
        } else {
            synchronized(resolvedKeys) { resolvedKeys.clear() }
            acquireMulticastLock()

            val listeners = ArrayList<NsdManager.DiscoveryListener>(SERVICE_TYPES.size)
            val emitted = intArrayOf(0)

            for (type in SERVICE_TYPES) {
                val listener = newDiscoveryListener(type, this) { info ->
                    val service = toDiscoveredService(info, type)
                    if (service != null && resolvedKeys.add(dedupKey(service))) {
                        emitted[0] += 1
                        trySend(service)
                    }
                }
                listeners.add(listener)
                launch { startDiscoveryWithRetry(manager, type, listener) }
            }

            // 超时看门狗：一条都没发现就结束本轮，让上层切换端口扫描
            launch {
                delay(mdnsTimeoutMs.coerceIn(1_000L, 60_000L))
                if (emitted[0] == 0) {
                    Logx.i(TAG, "mDNS ${mdnsTimeoutMs}ms 内无结果，结束本轮发现")
                    channel.close()
                }
            }

            val abort: () -> Unit = {
                for (listener in listeners) stopDiscoveryQuietly(manager, listener)
            }
            activeAborts.add(abort)
            awaitClose {
                activeAborts.remove(abort)
                for (listener in listeners) stopDiscoveryQuietly(manager, listener)
                releaseMulticastLock()
            }
        }
    }.flowOn(Dispatchers.Main.immediate)

    /**
     * 停止全部进行中的发现：注销监听并释放组播锁。
     * 幂等，可重复调用。
     */
    fun stop() {
        stopScope.launch {
            val aborts = synchronized(activeAborts) { ArrayList(activeAborts) }
            for (abort in aborts) {
                try {
                    abort.invoke()
                } catch (t: Throwable) {
                    Logx.w(TAG, "停止发现时异常：${t.message}")
                }
            }
            synchronized(activeAborts) { activeAborts.clear() }
            releaseMulticastLock()
        }
    }

    // ---------------------------------------------------------------- 监听器

    private fun newDiscoveryListener(
        type: String,
        scope: CoroutineScope,
        onResolved: (NsdServiceInfo) -> Unit,
    ): NsdManager.DiscoveryListener {
        return object : NsdManager.DiscoveryListener {
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                Logx.w(TAG, "启动发现失败：$serviceType，错误码=$errorCode")
            }

            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {
                Logx.w(TAG, "停止发现失败：$serviceType，错误码=$errorCode")
            }

            override fun onDiscoveryStarted(serviceType: String) {
                Logx.d(TAG, "已启动发现：$serviceType")
            }

            override fun onDiscoveryStopped(serviceType: String) {
                Logx.d(TAG, "已停止发现：$serviceType")
            }

            override fun onServiceFound(info: NsdServiceInfo) {
                Logx.d(TAG, "发现服务：$type -> ${info.serviceName}")
                scope.launch { resolveServiceWithRetry(info, onResolved) }
            }

            override fun onServiceLost(info: NsdServiceInfo) {
                Logx.d(TAG, "服务已丢失：${info.serviceName}")
            }
        }
    }

    // ---------------------------------------------------------------- 串行化请求

    /**
     * 注册发现监听，失败时指数退避重试（最多 [MAX_RETRY] 次）。
     */
    private suspend fun startDiscoveryWithRetry(
        manager: NsdManager,
        type: String,
        listener: NsdManager.DiscoveryListener,
    ) {
        var attempt = 0
        var backoffMs = BASE_BACKOFF_MS
        while (attempt < MAX_RETRY) {
            val ok: Boolean = try {
                requestMutex.withLock {
                    manager.discoverServices(type, NsdManager.PROTOCOL_DNS_SD, listener)
                }
                true
            } catch (e: IllegalArgumentException) {
                Logx.w(TAG, "discoverServices 被拒绝（$type，第 ${attempt + 1} 次）：${e.message}")
                false
            } catch (e: SecurityException) {
                Logx.e(TAG, "discoverServices 权限不足（$type）：${e.message}")
                return
            } catch (e: Throwable) {
                Logx.e(TAG, "discoverServices 未知异常（$type）：${e.message}")
                return
            }
            if (ok) {
                Logx.d(TAG, "已注册发现监听：$type")
                return
            }
            attempt += 1
            delay(backoffMs)
            backoffMs = (backoffMs * 2L).coerceAtMost(MAX_BACKOFF_MS)
        }
        Logx.w(TAG, "discoverServices 重试 $MAX_RETRY 次仍失败：$type")
    }

    /**
     * 解析服务（拿到 host / port），失败时指数退避重试（最多 [MAX_RETRY] 次）。
     */
    private suspend fun resolveServiceWithRetry(
        info: NsdServiceInfo,
        onResolved: (NsdServiceInfo) -> Unit,
    ) {
        val manager = nsdManager ?: return
        val listener = object : NsdManager.ResolveListener {
            override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                Logx.w(TAG, "解析失败：${serviceInfo.serviceName}，错误码=$errorCode")
            }

            override fun onServiceResolved(serviceInfo: NsdServiceInfo) {
                onResolved(serviceInfo)
            }
        }

        var attempt = 0
        var backoffMs = BASE_BACKOFF_MS
        while (attempt < MAX_RETRY) {
            val ok: Boolean = try {
                requestMutex.withLock { manager.resolveService(info, listener) }
                true
            } catch (e: IllegalArgumentException) {
                Logx.w(TAG, "resolveService 被拒绝（${info.serviceName}，第 ${attempt + 1} 次）：${e.message}")
                false
            } catch (e: SecurityException) {
                Logx.e(TAG, "resolveService 权限不足（${info.serviceName}）：${e.message}")
                return
            } catch (e: Throwable) {
                Logx.e(TAG, "resolveService 未知异常（${info.serviceName}）：${e.message}")
                return
            }
            if (ok) return
            attempt += 1
            delay(backoffMs)
            backoffMs = (backoffMs * 2L).coerceAtMost(MAX_BACKOFF_MS)
        }
        Logx.w(TAG, "resolveService 重试 $MAX_RETRY 次仍失败：${info.serviceName}")
    }

    private fun stopDiscoveryQuietly(manager: NsdManager, listener: NsdManager.DiscoveryListener) {
        try {
            manager.stopServiceDiscovery(listener)
        } catch (t: Throwable) {
            Logx.d(TAG, "注销监听时忽略异常：${t.message}")
        }
    }

    // ---------------------------------------------------------------- 组播锁

    private fun acquireMulticastLock() {
        if (multicastLock != null) return
        multicastLock = try {
            val lock = NetUtils.multicastWifiLock(appContext)
            lock.acquire()
            Logx.d(TAG, "已获取组播锁")
            lock
        } catch (t: Throwable) {
            Logx.w(TAG, "获取组播锁失败：${t.message}")
            null
        }
    }

    private fun releaseMulticastLock() {
        val lock = multicastLock ?: return
        multicastLock = null
        try {
            if (lock.isHeld) lock.release()
            Logx.d(TAG, "已释放组播锁")
        } catch (t: Throwable) {
            Logx.w(TAG, "释放组播锁失败：${t.message}")
        }
    }

    // ---------------------------------------------------------------- 数据转换

    private fun toDiscoveredService(info: NsdServiceInfo, type: String): DiscoveredService? {
        val host: String = info.host?.hostAddress ?: return null
        val port: Int = info.port
        if (host.isBlank() || port <= 0) return null
        val name: String = info.serviceName?.takeIf { it.isNotBlank() } ?: "adb-$host-$port"
        return DiscoveredService(
            instanceName = name,
            serviceType = type,
            host = host,
            port = port,
            source = Source.MDNS,
            capability = capabilityOf(type),
        )
    }

    private fun capabilityOf(serviceType: String): Capability = when {
        serviceType.contains("pairing", ignoreCase = true) -> Capability.PAIRING_ONLY
        serviceType.contains("connect", ignoreCase = true) -> Capability.CONNECT
        else -> Capability.UNKNOWN
    }

    private fun dedupKey(service: DiscoveredService): String =
        "${service.host}|${service.serviceType}|${service.port}"
}
