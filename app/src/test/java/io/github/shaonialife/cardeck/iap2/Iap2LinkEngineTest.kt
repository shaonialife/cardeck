package io.github.shaonialife.cardeck.iap2

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 两个链路引擎通过直接交换输出字节完成握手与数据往返（无线程、无 I/O）。 */
class Iap2LinkEngineTest {
    @Test
    fun handshakeReachesNormalOnBothSides() {
        val phone = Iap2LinkEngine()
        val accessory = Iap2LinkEngine()

        phone.start(0)
        accessory.start(0)

        exchange(phone, accessory)
        exchange(phone, accessory)
        exchange(phone, accessory)

        assertEquals(Iap2LinkEngine.State.NORMAL, phone.state)
        assertEquals(Iap2LinkEngine.State.NORMAL, accessory.state)
        assertEquals(4, accessory.peerSynchronization?.maxOutgoing)
        assertTrue(phone.peerSynchronization?.sessions?.any { it.id == Iap2LinkEngine.SESSION_CONTROL } == true)
    }

    /**
     * 事件通道形态：车机只反复发 marker、一条同步包都不发（Honda HFT 2026-10-20 实测
     * 60 秒 60 个 marker），说明它卡在探测态、在等**我们**的会话表。
     * 已处于 NEGOTIATING 时 [Iap2LinkEngine.initiate] 必须仍能发出「marker + FF5A SYN」，
     * 双方随即收敛到 NORMAL——这是切歌按键通路的入口。
     */
    @Test
    fun initiateFromNegotiatingSendsMarkerAndSynThenReachesNormal() {
        val phone = Iap2LinkEngine()
        val accessory = Iap2LinkEngine()
        // 双方都是静默待命（只发 marker 不发起协商）
        phone.enterNegotiating()
        accessory.enterNegotiating()

        phone.initiate(0)
        val out = phone.takeOutput()
        assertArrayEquals("首帧必须是 marker", Iap2LinkEngine.MARKER, out.copyOfRange(0, 6))
        val frames = parseFrames(out)
        assertEquals("marker 之后必须紧跟一条同步包", 1, frames.size)
        assertTrue("同步包 control 必须带 SYN 位（0x80）", frames[0].control and 0x80 != 0)

        // 车机侧收到「marker + 本表 SYN」→ 回 SYN|ACK（这就是日志里应当出现的推进）
        accessory.feed(out, tick())
        assertEquals("车机侧收到我们的会话表后必须进入 NORMAL", Iap2LinkEngine.State.NORMAL, accessory.state)
        phone.feed(accessory.takeOutput(), tick())
        assertEquals("实际 phone=${phone.state}", Iap2LinkEngine.State.NORMAL, phone.state)
        assertTrue(
            "双方都必须采纳对方的会话表",
            phone.peerSynchronization != null && accessory.peerSynchronization != null,
        )
    }

    /**
     * 轮次用尽后必须能停手：控制通道上无限 500ms 打点会把车机 CarPlay 栈刷懵，
     * 所以上层按轮次重开、轮次用尽后调 [Iap2LinkEngine.stopNegotiationRetries]。
     * 停手后不得再产出任何出站字节，状态保持 NEGOTIATING（等车机自己发 CSM）。
     */
    @Test
    fun stopNegotiationRetriesSilencesResends() {
        val phone = Iap2LinkEngine()
        phone.enterNegotiating()
        phone.initiate(0)
        phone.takeOutput()

        phone.advanceTime(1_000L)
        assertTrue("未停手时必须仍在重发 marker/同步包", phone.takeOutput().isNotEmpty())

        phone.stopNegotiationRetries()
        phone.advanceTime(60_000L)
        assertTrue("停手后不得再有任何出站字节", phone.takeOutput().isEmpty())
        assertEquals(Iap2LinkEngine.State.NEGOTIATING, phone.state)
    }

    @Test
    fun controlPayloadRoundTripBothDirections() {
        val phone = Iap2LinkEngine()
        val accessory = Iap2LinkEngine()
        phone.start(0)
        accessory.start(0)
        exchangeUntilNormal(phone, accessory)

        val phoneToAccessory = Iap2Csm.encode(0x1D00)
        assertTrue(phone.sendControl(phoneToAccessory, 1))
        exchange(phone, accessory)
        val receivedAtAccessory = pollControls(accessory)
        assertEquals(1, receivedAtAccessory.size)
        assertArrayEquals(phoneToAccessory, receivedAtAccessory[0])

        val accessoryToPhone = Iap2Csm.encode(0x1D01, Iap2Csm.string(0, "CarPlay HU"))
        assertTrue(accessory.sendControl(accessoryToPhone, 2))
        exchange(phone, accessory)
        val receivedAtPhone = pollControls(phone)
        assertEquals(1, receivedAtPhone.size)
        assertArrayEquals(accessoryToPhone, receivedAtPhone[0])
    }

    @Test
    fun duplicateFrameIsReAcknowledgedNotRedelivered() {
        val phone = Iap2LinkEngine()
        val accessory = Iap2LinkEngine()
        phone.start(0)
        accessory.start(0)
        exchangeUntilNormal(phone, accessory)

        val payload = Iap2Csm.encode(0x1D02)
        assertTrue(phone.sendControl(payload, 1))
        // 截获 phone 输出的数据帧，重复喂给 accessory 两次
        val dataFrame = phone.takeOutput()
        accessory.feed(dataFrame, 2)
        accessory.feed(dataFrame, 3)

        assertEquals(1, pollControls(accessory).size)
    }

    @Test
    fun windowBacklogFlushesAfterAcknowledgement() {
        val phone = Iap2LinkEngine(maxOutgoing = 2)
        val accessory = Iap2LinkEngine(maxOutgoing = 8)
        phone.start(0)
        accessory.start(0)
        exchangeUntilNormal(phone, accessory)

        val frames = (0 until 5).map { Iap2Csm.encode(0x5000 + it) }
        frames.forEach { assertTrue(phone.sendControl(it, 10)) }
        // 窗口 2：只有前 2 帧立即发出，其余积压；ACK 后由 handleAcknowledgement 冲刷
        var now = 11L
        while (pollControls(accessory).size < 5) {
            exchange(phone, accessory)
            now += 1
            phone.advanceTime(now)
            accessory.advanceTime(now)
            assertTrue(!phone.isDead && !accessory.isDead)
        }
    }

    // ---- 协商细节回归 ----

    /**
     * SYN-ACK 必须使用**新的**发送序号（对端按 `SYN.seq + 1` 校验）。
     *
     * 2026-10-19 BMW 87866 实车日志：我方 SYN 与 SYN-ACK 同为 seq=99，车机判为重复帧
     * 丢弃，14 轮协商全部超时、133 条 SYN 未换回一条 ACK。catplay 用 `take_own_seq()`
     * 消耗新序号，这里锁死该行为。
     */
    @Test
    fun synAckUsesFreshSequenceNumber() {
        val phone = Iap2LinkEngine()
        phone.start(0)
        phone.takeOutput() // marker + 我方 SYN（seq=99）

        phone.feed(synFrame(peerSequence = 161), 1)
        val frames = parseFrames(phone.takeOutput())
        val synAck = frames.single { it.control == 0xC0 }

        assertEquals("ack 必须回显对端 SYN 序号", 161, synAck.ack)
        assertEquals("SYN-ACK 序号必须比 SYN 大 1", 100, synAck.sequence)
    }

    /**
     * 协商期间**每一条** SYN 都要回 SYN|ACK。
     *
     * BMW 每轮重试都用新序号重开协商；旧实现只在首次采纳时回显，其余只回裸 ACK，
     * 车机永远走不完协商。catplay `on_recv_neg_host` 对每条 SYN 都发 SYN+ACK。
     */
    @Test
    fun everySynGetsASynAckReply() {
        val phone = Iap2LinkEngine()
        phone.start(0)
        phone.takeOutput()

        // 车机连续三轮用不同序号重开协商（复刻日志 161 → 191 → 40）
        val sequences = listOf(161, 191, 40)
        val replies = ArrayList<Pair<Int, Int>>() // (synAckSeq, ack)
        sequences.forEachIndexed { index, peerSeq ->
            phone.feed(synFrame(peerSequence = peerSeq), (index + 1).toLong())
            parseFrames(phone.takeOutput())
                .filter { it.control == 0xC0 }
                .forEach { replies += it.sequence to it.ack }
        }

        assertEquals("每条 SYN 都应有 SYN|ACK", sequences.size, replies.size)
        assertEquals(sequences, replies.map { it.second })
        // 序号逐次递增（99 → 100 → 101 → 102）
        assertEquals(listOf(100, 101, 102), replies.map { it.first })
    }

    /** 同一个 SYN 的字节级重发必须幂等：整对帧原样重放，不推进序号、不产生新序号。 */
    @Test
    fun retransmittedSynKeepsTheSameEcho() {
        val phone = Iap2LinkEngine()
        phone.start(0)
        phone.takeOutput()

        phone.feed(synFrame(peerSequence = 77), 1)
        val first = phone.takeOutput()
        phone.feed(synFrame(peerSequence = 77), 2)
        val again = phone.takeOutput()

        assertArrayEquals("重传同一 SYN 应原样重放", first, again)
        assertEquals(100, parseFrames(first).single { it.control == 0xC0 }.sequence)
    }

    /** 裸 ACK 不能与已发出的 SYN|ACK 撞序号（BMW 会把同序号帧当重复丢弃）。 */
    @Test
    fun ackSequenceNeverDuplicatesTheSynAck() {
        val phone = Iap2LinkEngine()
        phone.start(0)
        phone.takeOutput()

        val emitted = ArrayList<Int>()
        listOf(161, 191, 40).forEachIndexed { index, peerSeq ->
            phone.feed(synFrame(peerSequence = peerSeq), (index + 1).toLong())
            emitted += parseFrames(phone.takeOutput()).map { it.sequence }
        }
        assertEquals("序号必须严格递增且无重复", emitted.size, emitted.distinct().size)
        assertEquals(emitted.sorted(), emitted)
    }

    /**
     * 隧道形态的探测回显（2026-10-26 对照第三方发送端）：echoMarker 开启时协商期
     * 每收到一条 marker 原样回写一条（车机在等「探测被应答」才发 SYN）；
     * NORMAL 后重复探测忽略（不回显）。echoMarker 关闭时维持旧行为（上层应答）。
     */
    @Test
    fun markerEchoedWhileNegotiatingAndIgnoredAfterNormal() {
        val phone = Iap2LinkEngine()
        phone.echoMarker = true
        phone.enterNegotiating()

        phone.feed(Iap2LinkEngine.MARKER, tick())
        assertArrayEquals("协商期收到探测必须原样回显", Iap2LinkEngine.MARKER, phone.takeOutput())

        phone.feed(Iap2LinkEngine.MARKER, tick())
        assertArrayEquals("重复探测每次都回显", Iap2LinkEngine.MARKER, phone.takeOutput())

        // 建链到 NORMAL：喂一条车机 SYN，回显即完成协商（响应方语义）
        phone.feed(synFrame(peerSequence = 55), tick())
        phone.takeOutput()
        assertEquals(Iap2LinkEngine.State.NORMAL, phone.state)

        phone.feed(Iap2LinkEngine.MARKER, tick())
        assertTrue("NORMAL 后的重复探测不得回显", phone.takeOutput().isEmpty())
    }

    /** echoMarker 关闭（蓝牙/事件通道形态）：marker 由上层应答，引擎不得自行回显。 */
    @Test
    fun markerNotEchoedWhenDisabled() {
        val phone = Iap2LinkEngine()
        phone.enterNegotiating()

        phone.feed(Iap2LinkEngine.MARKER, tick())
        assertTrue(phone.takeOutput().isEmpty())
    }

    /**
     * Re-SYN（2026-10-26 对照第三方发送端 L3 的 link_resync）：链路 NORMAL 后对端
     * 用**新序号**重开协商 = 整轮重置——必须重跑协商并回调 onResynchronized；
     * 同序号重发（DiPlay 每 500ms 重发同一 SYN）不算 Re-SYN，走幂等重放。
     */
    @Test
    fun reSynAfterNormalResetsNegotiation() {
        val phone = Iap2LinkEngine()
        phone.echoFirst = true
        phone.start(0)
        phone.takeOutput()

        phone.feed(synFrame(peerSequence = 77), tick())
        phone.takeOutput()
        assertEquals(Iap2LinkEngine.State.NORMAL, phone.state)

        var resynced = 0
        phone.onResynchronized = { resynced++ }

        // 同序号重发：幂等重放，不触发重置
        phone.feed(synFrame(peerSequence = 77), tick())
        val replay = phone.takeOutput()
        assertEquals("同 SYN 重发不触发 Re-SYN", 0, resynced)
        assertTrue("重发必须原样重放 SYN|ACK", parseFrames(replay).any { it.control == 0xC0 })
        assertEquals(Iap2LinkEngine.State.NORMAL, phone.state)

        // 新序号 Re-SYN：重置 → 重新采纳 → 回 SYN|ACK → 回调
        phone.feed(synFrame(peerSequence = 120), tick())
        assertEquals("新序号 SYN 必须触发 Re-SYN 回调", 1, resynced)
        val out = parseFrames(phone.takeOutput())
        assertTrue("重置后必须重新回 SYN|ACK", out.any { it.control == 0xC0 })
        assertEquals("echoFirst 回显即视为协商完成", Iap2LinkEngine.State.NORMAL, phone.state)
    }

    /** Re-SYN 超过上限（3 次）判死——对端无限重开协商视为链路不可用。 */
    @Test
    fun reSynBeyondLimitDies() {
        val phone = Iap2LinkEngine()
        phone.echoFirst = true
        phone.start(0)
        phone.takeOutput()
        phone.feed(synFrame(peerSequence = 10), tick())
        phone.takeOutput()
        assertEquals(Iap2LinkEngine.State.NORMAL, phone.state)

        phone.feed(synFrame(peerSequence = 20), tick())
        phone.feed(synFrame(peerSequence = 30), tick())
        phone.feed(synFrame(peerSequence = 40), tick())
        assertEquals(Iap2LinkEngine.State.NORMAL, phone.state)

        phone.feed(synFrame(peerSequence = 50), tick())
        assertEquals("第 4 次 Re-SYN 必须判死", Iap2LinkEngine.State.DEAD, phone.state)
    }

    // ---- 工具 ----

    private class ParsedFrame(
        val control: Int,
        val sequence: Int,
        val ack: Int,
        val frameLength: Int,
    )

    /** 解析一段输出里的所有 FF5A 帧（跳过前置 marker）。 */
    private fun parseFrames(bytes: ByteArray): List<ParsedFrame> {
        val frames = ArrayList<ParsedFrame>()
        var offset = 0
        while (offset + 9 <= bytes.size) {
            if (bytes[offset] != 0xFF.toByte() || bytes[offset + 1] != 0x5A.toByte()) {
                offset++
                continue
            }
            val frame = parseFrame(bytes, offset)
            frames += frame
            offset += frame.frameLength
        }
        return frames
    }

    /** 解析一帧 FF5A（长度含 9 字节头 + 载荷 + 载荷校验和）。 */
    private fun parseFrame(
        bytes: ByteArray,
        offset: Int,
    ): ParsedFrame {
        require(bytes[offset] == 0xFF.toByte() && bytes[offset + 1] == 0x5A.toByte()) {
            "offset=$offset 不是 FF5A 帧头"
        }
        val length = ((bytes[offset + 2].toInt() and 0xFF) shl 8) or (bytes[offset + 3].toInt() and 0xFF)
        return ParsedFrame(
            control = bytes[offset + 4].toInt() and 0xFF,
            sequence = bytes[offset + 5].toInt() and 0xFF,
            ack = bytes[offset + 6].toInt() and 0xFF,
            frameLength = length,
        )
    }

    /**
     * 构造车机侧的 SYN 帧（含 marker）。
     *
     * 帧头是 **9 字节**：`FF5A | u16 长度 | u8 控制 | u8 seq | u8 ack | u8 会话 | u8 头校验和`，
     * 所以 seq 在帧内偏移 5、头校验和在偏移 8（不是 7——那一位是会话 ID）。
     */
    private fun synFrame(peerSequence: Int): ByteArray {
        val engine = Iap2LinkEngine()
        engine.start(0)
        val raw = engine.takeOutput()
        val synOffset = 6 // 前置 marker 6 字节
        raw[synOffset + 5] = peerSequence.toByte()
        // 头校验和 = 前 8 字节之和的补码
        var sum = 0
        for (index in synOffset until synOffset + 8) sum += raw[index].toInt() and 0xFF
        raw[synOffset + 8] = (-sum and 0xFF).toByte()
        return raw
    }

    private fun exchange(
        a: Iap2LinkEngine,
        b: Iap2LinkEngine,
    ) {
        b.feed(a.takeOutput(), tick())
        a.feed(b.takeOutput(), tick())
    }

    private fun exchangeUntilNormal(
        a: Iap2LinkEngine,
        b: Iap2LinkEngine,
    ) {
        var guard = 0
        while ((a.state != Iap2LinkEngine.State.NORMAL || b.state != Iap2LinkEngine.State.NORMAL) &&
            guard++ < 10
        ) {
            exchange(a, b)
        }
    }

    private fun pollControls(engine: Iap2LinkEngine): List<ByteArray> {
        val out = ArrayList<ByteArray>()
        while (true) {
            when (val event = engine.pollEvent() ?: return out) {
                is Iap2LinkEngine.Event.Control -> out += event.bytes
                is Iap2LinkEngine.Event.Dead -> error("engine died: ${event.reason}")
            }
        }
    }

    private var clock = 0L

    private fun tick(): Long = ++clock
}
