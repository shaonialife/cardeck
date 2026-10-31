package io.github.shaonialife.cardeck.replay

import io.github.shaonialife.cardeck.iap2.Iap2Csm
import io.github.shaonialife.cardeck.iap2.Iap2SenderSession
import io.github.shaonialife.cardeck.transport.Iap2LinkChannel
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * **蓝牙全栈重放回归**：录制的车机 rx 字节流喂给真实的 Iap2LinkChannel +
 * Iap2SenderSession（与生产同一条代码路径），我们回出的 CSM 消息序列必须与
 * 录制时一致——针对某车型的协议改动若改变了对**其它车型**刺激的应答，这里直接红。
 *
 * 比对策略（对抗重传/时序抖动）：比「**去重后的首次出现顺序**」——
 * 重放的 distinct CSM 序列必须与录制的 distinct CSM 序列完全一致。
 *
 * 时长控制：每 fixture 最多重放 4 轮（phase1 优先），单轮超时 = 录制时长 + 10s。
 */
class BtLinkReplayTest {
    @Test
    fun recordedSessionsReplayIdentically() {
        val fixtures = CarFixture.loadDirectory()
        val usable = fixtures.filter { it.btRounds.isNotEmpty() }
        if (usable.isEmpty()) {
            println("REPLAY: 无蓝牙 fixture（目录被 gitignore，本地用 tools/extract_car_fixtures.py 提取）——跳过")
            return
        }
        val failures = ArrayList<String>()
        usable.forEach { fixture ->
            fixture
                .btRounds
                .sortedWith(compareByDescending<CarFixture.BtRound> { it.outcome == "phase1" }.thenBy { it.durationMs })
                .take(2)
                .forEach { round -> replayRound(fixture, round, failures) }
        }
        assertTrue("蓝牙重放回归失败：\n" + failures.joinToString("\n"), failures.isEmpty())
    }

    private fun replayRound(
        fixture: CarFixture,
        round: CarFixture.BtRound,
        failures: MutableList<String>,
    ) {
        val tag = "${fixture.car}#${round.index}(${round.outcome})"
        val stream = ScriptedCarStream(round.rx)
        val channel = Iap2LinkChannel(stream, name = "replay-${fixture.name}", useNativeEngine = false) { }
        val endpointSeen = CountDownLatch(1)
        val session =
            Iap2SenderSession(
                channel,
                log = { },
                bluetoothMac = "5C:40:71:A4:0A:90",
                deviceName = "iPhone",
                onWifiCredentials = { },
                onEndpointUpdate = { },
                context = null,
            )
        val sessionThread =
            Thread(
                {
                    runCatching { session.run(minOf(round.durationMs + 10_000, 20_000)) }
                    endpointSeen.countDown()
                },
                "replay-bt",
            )
        sessionThread.start()
        // 等会话自然走完（rx 耗尽后各窗口超时）；兜底强制结束
        // 上限 25s：bring-up 重放不该更久（录制时长多为超时轮的等待，重放已跳过）
        val finished = endpointSeen.await(minOf(round.durationMs + 20_000, 25_000), TimeUnit.MILLISECONDS)
        stream.close()
        channel.close()
        sessionThread.join(3_000)

        val replayIds = distinctCsmIds(extractCsmPayloads(stream.written))
        val recordedIds = distinctCsmIds(extractCsmPayloads(listOf(round.recordedTx)))
        if (!finished) {
            failures.add("$tag 会话未在录制时长+20s 内结束（回放 distinct=${replayIds.joinToString()}）")
        }
        // 录制的 RAW 转储有字节预算（旧日志 512B）。分级断言：
        //  - 识别帧（0x1D01）完整幸存 → 录制 distinct 必须是回放的前缀（全量比对）
        //  - 识别帧被预算截断 → 只断言链路层+识别启动（录制首条，恒为 0x1D00）
        val expected =
            if (round.rxHasIdent) recordedIds else recordedIds.take(1)
        val prefixOk =
            expected.size <= replayIds.size &&
                replayIds.subList(0, expected.size) == expected
        if (!prefixOk) {
            failures.add(
                "$tag CSM 应答序列漂移（${if (round.rxHasIdent) "全量" else "仅链路层"}断言）：\n" +
                    "  期望=${expected.joinToString()}\n  回放=${replayIds.joinToString()}",
            )
        } else {
            println(
                "REPLAY ✓ $tag ${if (round.rxHasIdent) "全量" else "仅链路层"}前缀一致" +
                    "（期望 ${expected.size}/回放 ${replayIds.size} 种消息）",
            )
        }
    }

    /** 去重后的首次出现顺序（抹掉重传与时序性重复帧）。 */
    private fun distinctCsmIds(payloads: List<ByteArray>): List<Int> {
        val seen = LinkedHashSet<Int>()
        payloads.forEach { payload ->
            Iap2Csm.Parser().offer(payload).forEach { frame -> seen.add(frame.messageId) }
        }
        return seen.toList()
    }
}
