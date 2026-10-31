package io.github.shaonialife.cardeck.transport

import io.github.shaonialife.cardeck.iap2.Iap2LinkEngine
import io.github.shaonialife.cardeck.protocol.NativeBridge
import java.io.Closeable
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * 链路通道：一个读线程 + 一个节奏线程驱动原生会话（cardeck-protocol 固件真身），向会话层暴露
 * 阻塞式的 sendControl/recvControl 与 awaitReady。
 */

/**
 * 链路通道：一个读线程 + 一个节奏线程驱动【原生会话】（cardeck-protocol
 * 固件真身：accessoryd iAP2Link/FSM/分包器/队列全链），向会话层暴露
 * 阻塞式的 sendControl/recvControl 与 awaitReady。
 *
 * Kotlin 侧只剩传输（RFCOMM 读写）与节拍——帧解析、协商、seq/ack、
 * 重传、会话消息分发全部在 Rust（日志推断的 Iap2LinkEngine 已下线）。
 */
class Iap2LinkChannel(
    private val stream: DuplexStream,
    name: String = "iap2-link",
    /** false 仅限 JVM 单测（引擎是日志推断的旧实现，生产一律原生）。 */
    private val useNativeEngine: Boolean = true,
    private val log: (String) -> Unit = {},
) : Closeable {
    val isAlive: Boolean get() = !terminated

    /** 编排事件：WIFI=-1、ENDPOINT=-2、PHASE1_DONE=-3（Rust session_flow 发）。 */
    data class FlowEvent(
        val id: Int,
        val bytes: ByteArray,
    )

    /** 启动原生 Phase-1 编排线程（识别→MFi→问候→WiFi→端点，全固件真身）。 */
    fun startFlow(
        deviceName: String,
        bluetoothMac: String,
    ) {
        check(handle != 0L) { "flow start on dead channel" }
        NativeBridge.nativeSessionFlowStart(handle, deviceName, bluetoothMac)
    }

    /** 取下一个编排事件；超时返回 null。 */
    fun pollFlowEvent(timeoutMillis: Long): FlowEvent? =
        try {
            flowEvents.poll(timeoutMillis, TimeUnit.MILLISECONDS)
        } catch (_: InterruptedException) {
            null
        }

    /** true = 原生会话（识别链 Rust 端到端）；false = JVM 单测引擎路径。 */
    val runsNative: Boolean get() = useNativeEngine
    private val lock = Object()
    private val controls = LinkedBlockingQueue<ByteArray?>()
    private val flowEvents = LinkedBlockingQueue<FlowEvent>()
    private val readyLatch = CountDownLatch(1)

    @Volatile
    private var terminated = false
    private var readySignalled = false

    /** 原生会话句柄（0 = 未创建）。 */
    @Volatile
    private var handle: Long = 0L

    /** 旧引擎路径（仅 useNativeEngine=false：JVM 单测）。 */
    private val engine = if (useNativeEngine) null else Iap2LinkEngine()

    /** 原始字节转储预算（每方向每连接），真车字节级对照用。 */
    private var rxDumpBudget = RAW_DUMP_LIMIT
    private var txDumpBudget = RAW_DUMP_LIMIT_TX

    init {
        if (!useNativeEngine) {
            engine!!.onFrameSent = { _, _, _, _, size ->
                if (txDumpBudget > 0) {
                    txDumpBudget--
                    log("LINK tx: len=$size")
                }
            }
        }
        if (useNativeEngine && !NativeBridge.ensureLoaded()) {
            throw IOException("cardeck_protocol 未加载——原生链路不可用")
        }
        if (useNativeEngine) {
            handle = NativeBridge.nativeSessionCreate(0x2000L)
            if (handle == 0L) {
                throw IOException("原生会话创建失败")
            }
        }
        if (useNativeEngine) {
            // 出帧：Rust 协议层 → RFCOMM 写
            NativeBridge.nativeSetTransport(
                object : NativeBridge.TransportSender {
                    override fun send(bytes: ByteArray) {
                        dumpOutbound(bytes)
                        try {
                            synchronized(lock) {
                                if (!terminated) stream.write(bytes)
                            }
                        } catch (error: IOException) {
                            finish(error.message ?: error.javaClass.simpleName)
                        }
                    }
                },
            )
            // 事件汇：每条完整会话消息镜像（识别 0x1D00/0x1D02 与认证应答由
            // Rust 端到端处理，不进 recvControl 队列；0x1D01 本体仍转发——
            // 会话层要从里面取身份文本/EA 协议表，但不再回 0x1D02）
            NativeBridge.nativeSessionSetEventSink(
                object : NativeBridge.EventSink {
                    override fun onEvent(
                        msgId: Int,
                        bytes: ByteArray,
                    ) {
                        if (terminated) return
                        log(
                            "LINK msg: id=0x${"%04x".format(msgId)} len=${bytes.size}",
                        )
                        when (msgId) {
                            0x1D00, 0x1D02 -> return // Rust 端到端，会话层无感
                            -1, -2, -3 -> flowEvents.offer(FlowEvent(msgId, bytes))
                            else -> controls.offer(bytes)
                        }
                    }
                },
            )
        }
        Thread({ runReader() }, "$name-reader").apply {
            isDaemon = true
            start()
        }
        Thread({ runPacer() }, "$name-pacer").apply {
            isDaemon = true
            start()
        }
    }

    /** 等待链路协商完成（原生 FSM s5 Connected）。 */
    fun awaitReady(timeoutMillis: Long): Boolean {
        if (!useNativeEngine) {
            val ok = readyLatch.await(timeoutMillis, TimeUnit.MILLISECONDS)
            return ok && !terminated
        }
        val deadline = System.nanoTime() + timeoutMillis * 1_000_000L
        while (System.nanoTime() < deadline) {
            if (terminated) return false
            if (readySignalled || NativeBridge.nativeSessionLinkReady(handle) == 1) {
                readySignalled = true
                readyLatch.countDown()
                return true
            }
            try {
                Thread.sleep(10)
            } catch (_: InterruptedException) {
                return false
            }
        }
        return !terminated && NativeBridge.nativeSessionLinkReady(handle) == 1
    }

    /** 对端协商的最大控制载荷（已扣除帧头与校验和）。 */
    fun peerMaxControlPayload(): Int? =
        if (!useNativeEngine) {
            synchronized(lock) { engine!!.peerSynchronization?.maxLength?.minus(10) }
        } else {
            if (handle == 0L) null else NativeBridge.nativeSessionPeerMaxControl(handle).takeIf { it > 0 }
        }

    /** 排入一个完整控制载荷；链路已死返回 false。原生链内部排队 + ACK 驱动重传。 */
    fun sendControl(
        bytes: ByteArray,
        awaitAck: Boolean = true,
    ): Boolean {
        if (!useNativeEngine) {
            synchronized(lock) {
                if (terminated) return false
                val accepted = engine!!.sendControl(bytes, nowMillis(), awaitAck)
                drainEngineOutputLocked()
                engine.pollEvent()
                return accepted
            }
        }
        if (terminated || handle == 0L) return false
        dumpOutbound(bytes)
        return NativeBridge.nativeSessionSendControl(handle, bytes) == 1
    }

    /** 取下一个控制载荷；超时或链路结束返回 null。 */
    fun recvControl(timeoutMillis: Long): ByteArray? {
        if (timeoutMillis == 0L && terminated && controls.isEmpty()) return null
        val polled =
            try {
                controls.poll(timeoutMillis, TimeUnit.MILLISECONDS)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                return null
            }
        return if (polled === null || polled === POISON) null else polled
    }

    override fun close() {
        finish(null)
    }

    // ---- 内部 ----

    private fun runReader() {
        try {
            if (useNativeEngine) {
                // 传输已连（构造即意味着 RFCOMM 就绪）——固件 attach 流：
                // LinkStart → Attached → FSM ev2（SYN 出站由原生链完成）
                NativeBridge.nativeSessionBtAttached(handle)
            } else {
                synchronized(lock) {
                    engine!!.start(nowMillis())
                    drainEngineOutputLocked()
                }
            }
            val buffer = ByteArray(4096)
            while (true) {
                val count = stream.read(buffer)
                if (count < 0) {
                    finish(null)
                    return
                }
                if (count == 0) continue
                if (terminated) return
                dumpInbound(buffer.copyOf(count))
                if (useNativeEngine) {
                    NativeBridge.nativeSessionFeed(handle, buffer.copyOf(count))
                } else {
                    synchronized(lock) {
                        engine!!.feed(buffer.copyOf(count), nowMillis())
                        drainEngineEventsLocked()
                        drainEngineOutputLocked()
                    }
                }
            }
        } catch (error: Throwable) {
            finish(error.message ?: error.javaClass.simpleName)
        }
    }

    /**
     * 原始字节抓包（真车字节级对照的唯一手段）：每方向仅转储前
     * [RAW_DUMP_LIMIT] 字节，防止长期会话日志爆炸。
     */
    private fun dumpInbound(bytes: ByteArray) {
        if (bytes.isEmpty() || rxDumpBudget <= 0) return
        val shown = minOf(bytes.size, rxDumpBudget)
        log("RAW rx: " + bytes.copyOf(shown).joinToString(" ") { "%02x".format(it) })
        rxDumpBudget -= shown
    }

    private fun runPacer() {
        while (!terminated) {
            if (useNativeEngine) {
                if (handle == 0L) break
                val sleep = NativeBridge.nativeSessionTick(handle).coerceIn(1, 20).toLong()
                if (!readySignalled && NativeBridge.nativeSessionLinkReady(handle) == 1) {
                    readySignalled = true
                    readyLatch.countDown()
                }
                try {
                    Thread.sleep(sleep)
                } catch (_: InterruptedException) {
                    return
                }
            } else {
                val deadline: Long?
                synchronized(lock) {
                    if (terminated) return
                    engine!!.advanceTime(nowMillis())
                    drainEngineEventsLocked()
                    drainEngineOutputLocked()
                    if (!readySignalled && engine!!.state == Iap2LinkEngine.State.NORMAL) {
                        readySignalled = true
                        readyLatch.countDown()
                    }
                    deadline = engine!!.nextDeadlineMillis()
                    if (deadline == null) {
                        try {
                            lock.wait(PACER_POLL_MILLIS)
                        } catch (_: InterruptedException) {
                            return
                        }
                    }
                }
                if (deadline != null) {
                    val sleepMs = (deadline - nowMillis()).coerceIn(1, PACER_POLL_MILLIS)
                    try {
                        Thread.sleep(sleepMs)
                    } catch (_: InterruptedException) {
                        return
                    }
                }
            }
        }
    }

    /** 引擎路径（仅 JVM 单测）：出站排空 + 事件入队。 */
    private fun drainEngineOutputLocked() {
        val bytes = engine!!.takeOutput()
        if (bytes.isNotEmpty()) {
            dumpOutbound(bytes)
            try {
                stream.write(bytes)
            } catch (error: IOException) {
                finishLocked(error)
            }
        }
    }

    private fun drainEngineEventsLocked() {
        while (true) {
            when (val event = engine!!.pollEvent() ?: return) {
                is Iap2LinkEngine.Event.Control -> controls.offer(event.bytes)
                is Iap2LinkEngine.Event.Dead -> {
                    finishLocked(event.reason?.let { IOException(it) })
                    return
                }
            }
        }
    }

    private fun dumpOutbound(bytes: ByteArray) {
        if (txDumpBudget <= 0) return
        val shown = minOf(bytes.size, txDumpBudget)
        log("RAW tx: " + bytes.copyOf(shown).joinToString(" ") { "%02x".format(it) })
        txDumpBudget -= shown
    }

    private fun finish(reason: String?) {
        synchronized(lock) { finishLocked(reason?.let { IOException(it) }) }
    }

    private fun finishLocked(failure: IOException?) {
        if (terminated) return
        terminated = true
        controls.offer(POISON) // 哨兵：唤醒阻塞的接收方（LBQ 不接受 null）
        readyLatch.countDown()
        lock.notifyAll()
        if (useNativeEngine && handle != 0L) {
            NativeBridge.nativeSessionDestroy(handle)
            handle = 0L
        }
        runCatching { stream.close() }
        if (failure != null) {
            log("LINK closed: ${failure.message}")
        } else {
            log("LINK closed")
        }
    }

    private fun nowMillis(): Long = System.nanoTime() / 1_000_000L

    private companion object {
        const val PACER_POLL_MILLIS = 20L

        /** 原始字节转储预算：rx 需覆盖车机全部协商帧（Audi 实测约 140B），给 512B 余量。 */
        // 预算按字节计。曾为 512/256：一次完整 bring-up 都盖不住（0x1D01 单帧即 565B），
        // 提取重放 fixture（tools/extract_car_fixtures.py）会缺尾部——提到 4096 覆盖
        // 整个识别/MFi/凭据交换，长会话靠预算封顶防日志爆炸
        const val RAW_DUMP_LIMIT = 4096
        const val RAW_DUMP_LIMIT_TX = 4096

        /** 逐帧摘要日志上限（每方向每连接）。 */
        const val FRAME_LOG_LIMIT = 40

        /** 链路终结哨兵；真实控制载荷至少包含一段 CSM 帧，不会为空。 */
        val POISON = ByteArray(0)
    }
}
