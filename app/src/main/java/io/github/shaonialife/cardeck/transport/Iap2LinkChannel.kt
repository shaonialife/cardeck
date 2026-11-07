package io.github.shaonialife.cardeck.transport

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
 * sendControl 与 awaitReady。
 *
 * Kotlin 侧只剩传输（RFCOMM 读写）与节拍——帧解析、协商、seq/ack、
 * 重传、会话消息分发全部在 Rust（日志推断的 Iap2LinkEngine 已下线）。
 *
 * **镜像面已退役（A7 下沉 2026-11-04）**：会话消息镜像不再入队过宿主
 * （旧 consumers = Iap2SenderSession 的纯日志解析，零行为消费）——
 * EventSink 只保留 "LINK msg" 诊断日志 + 编排事件分流。
 */
class Iap2LinkChannel(
    private val stream: DuplexStream,
    name: String = "iap2-link",
    private val log: (String) -> Unit = {},
) : Closeable {
    val isAlive: Boolean get() = !terminated

    /** 编排事件：WIFI=-1、ENDPOINT=-2、PHASE1_DONE=-3（Rust session_flow 发）。 */
    data class FlowEvent(
        val id: Int,
        val bytes: ByteArray,
    )

    /** 启动原生 Phase-1 编排线程（识别→MFi→问候→WiFi→端点，全固件真身）。
     *  平台值直传（JNI 注入面接线——0x4E 通知波身份域，Rust 侧不落缺省）。 */
    fun startFlow(
        deviceName: ByteArray,
        bluetoothMac: ByteArray,
        language: ByteArray,
        tzOffsetMinutes: Int,
        dst: Int,
    ) {
        check(handle != 0L) { "flow start on dead channel" }
        NativeBridge.nativeSessionFlowStart(handle, deviceName, bluetoothMac, language, tzOffsetMinutes, dst)
    }

    /** 取下一个编排事件；超时返回 null。 */
    fun pollFlowEvent(timeoutMillis: Long): FlowEvent? =
        try {
            flowEvents.poll(timeoutMillis, TimeUnit.MILLISECONDS)
        } catch (_: InterruptedException) {
            null
        }

    private val lock = Object()
    private val flowEvents = LinkedBlockingQueue<FlowEvent>()
    private val readyLatch = CountDownLatch(1)

    @Volatile
    private var terminated = false
    private var readySignalled = false
    private var lastEventPollMs = 0L

    /** 原生会话句柄（0 = 未创建）。 */
    @Volatile
    private var handle: Long = 0L

    /** 原始字节转储预算（每方向每连接），真车字节级对照用。 */
    private var rxDumpBudget = RAW_DUMP_LIMIT
    private var txDumpBudget = RAW_DUMP_LIMIT_TX

    init {
        if (!NativeBridge.ensureLoaded()) {
            throw IOException("cardeck_protocol 未加载——原生链路不可用")
        }
        handle = NativeBridge.nativeSessionCreate(0x2000L)
        if (handle == 0L) {
            throw IOException("原生会话创建失败")
        }
        // 出帧：Rust 协议层 → RFCOMM 写
        NativeBridge.nativeSetTransport(
            handle,
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
        // 事件汇：Rust 编排事件（WIFI/ENDPOINT/PHASE1_DONE = 负 id）直通
        // flowEvents；协议帧镜像只打诊断日志（id+len——连接排查的眼睛），
        // 不再入队过宿主（A7：解析面已退役，Rust 内部消化）
        NativeBridge.nativeSessionSetEventSink(
            handle,
            object : NativeBridge.EventSink {
                override fun onEvent(
                    msgId: Int,
                    bytes: ByteArray,
                ) {
                    if (terminated) return
                    log("LINK msg: id=0x${"%04x".format(msgId)} len=${bytes.size}")
                    if (msgId < 0) flowEvents.offer(FlowEvent(msgId, bytes))
                }
            },
        )
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
        // timeoutMillis == 0 → 无限期（fw-engine：无 Kotlin 超时；哨兵值不做
        // 纳秒算术——Long.MAX_VALUE * 1e6 会溢出成负 deadline 立即假失败）
        val deadline =
            if (timeoutMillis == 0L) {
                Long.MAX_VALUE
            } else {
                System.nanoTime() + timeoutMillis * 1_000_000L
            }
        while (true) {
            if (terminated) return false
            if (readySignalled || NativeBridge.nativeSessionLinkReady(handle) == 1) {
                readySignalled = true
                readyLatch.countDown()
                return true
            }
            if (System.nanoTime() >= deadline) {
                return NativeBridge.nativeSessionLinkReady(handle) == 1
            }
            try {
                Thread.sleep(10)
            } catch (_: InterruptedException) {
                return false
            }
        }
    }

    /** 对端协商的最大控制载荷（已扣除帧头与校验和）。 */
    fun peerMaxControlPayload(): Int? = if (handle == 0L) null else NativeBridge.nativeSessionPeerMaxControl(handle).takeIf { it > 0 }

    override fun close() {
        finish(null)
    }

    // ---- 内部 ----

    private fun runReader() {
        try {
            // 传输已连（构造即意味着 RFCOMM 就绪）——固件 attach 流：
            // LinkStart → Attached → FSM ev2（SYN 出站由原生链完成）
            NativeBridge.nativeSessionBtAttached(handle)
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
                NativeBridge.nativeSessionFeed(handle, buffer.copyOf(count))
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
            if (handle == 0L) break
            val sleep = NativeBridge.nativeSessionTick(handle).coerceIn(1, 20).toLong()
            val now = System.currentTimeMillis()
            if (now - lastEventPollMs >= 1000) {
                lastEventPollMs = now
                for (kind in 0..4) {
                    val ev = NativeBridge.nativeSessionTakeEvents(handle, kind)
                    if (!ev.isNullOrEmpty() && !ev.contains("\"events\":[]")) {
                        log("IAP2-EVT k=$kind " + ev.take(300))
                    }
                }
            }
            if (!readySignalled && NativeBridge.nativeSessionLinkReady(handle) == 1) {
                readySignalled = true
                readyLatch.countDown()
            }
            try {
                Thread.sleep(sleep)
            } catch (_: InterruptedException) {
                return
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
        readyLatch.countDown()
        lock.notifyAll()
        if (handle != 0L) {
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
    }
}
