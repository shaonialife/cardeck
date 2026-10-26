package io.github.shaonialife.cardeck.iap2

import io.github.shaonialife.cardeck.transport.Iap2LinkChannel
import io.github.shaonialife.cardeck.transport.InMemoryStreamPair
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * 发送端会话对 mock 配件端的端到端回环测试：
 * 内存双工流两端各跑一个链路通道，mock 按 DiPlay/xcertplay 的配件侧时序应答，
 * 验证 Phase 1 全序列（识别→MFi 跳过→HID→WiFi 配置→CarPlay 启动→通知）。
 */
class Iap2SenderSessionTest {
    @Test
    fun fullBringUpAgainstMockAccessory() {
        val pair = InMemoryStreamPair()
        val phoneChannel = Iap2LinkChannel(pair.sideA, name = "phone")
        val accessoryChannel = Iap2LinkChannel(pair.sideB, name = "accessory")
        val accessory = MockAccessory(accessoryChannel)

        val accessoryThread = Thread({ accessory.run() }, "mock-accessory")
        accessoryThread.isDaemon = true
        accessoryThread.start()

        val endpoint =
            Iap2SenderSession(
                phoneChannel,
                log = { line -> println("PHONE $line") },
                bluetoothMac = "34:86:DA:86:BB:96",
                deviceName = "test-device",
            ).run(timeoutMillis = 10_000L)

        assertEquals("MockAP", endpoint.ssid)
        assertEquals("secret-passphrase", endpoint.passphrase)
        assertEquals(36, endpoint.channel)
        assertEquals(2, endpoint.securityType)
        assertEquals(49152, endpoint.airPlayPort)
        assertEquals("mock-device-id", endpoint.deviceIdentifier)
        assertNotNull(endpoint.publicKey)
        assertEquals(listOf("192.168.49.1", "fd00::1"), endpoint.ipAddresses)

        phoneChannel.close()
        accessoryChannel.close()
    }

    /**
     * 奥迪 MMI 9310 形态：识别帧只声明发 0x5703/0xea03（不发 0x4301），Phase 1
     * 因此不等待；车机在保活期间（Phase 1 之后）才补发 0x4301。
     * 旧实现把这条消息当 keep-alive 只记长度，配对永远不会重启——本用例锁住
     * 「晚到 0x4301 必须解析成端点并回调」这一行为。
     */
    @Test
    fun lateStartSessionAfterPhaseOneTriggersEndpointUpdate() {
        val pair = InMemoryStreamPair()
        val phoneChannel = Iap2LinkChannel(pair.sideA, name = "phone")
        val accessoryChannel = Iap2LinkChannel(pair.sideB, name = "accessory")
        val accessory = MockAccessory(accessoryChannel, shape = MockAccessory.Shape.AUDI_9310, lateStartSession = true)

        val accessoryThread = Thread({ accessory.run() }, "mock-accessory")
        accessoryThread.isDaemon = true
        accessoryThread.start()

        val updates = AtomicReference<Iap2SenderSession.CarPlayEndpoint?>()
        val updateLatch = CountDownLatch(1)
        val session =
            Iap2SenderSession(
                phoneChannel,
                log = { line -> println("PHONE $line") },
                bluetoothMac = "34:86:DA:86:BB:96",
                deviceName = "test-device",
                onEndpointUpdate = {
                    updates.set(it)
                    updateLatch.countDown()
                },
            )

        val phaseOne = session.run(timeoutMillis = 10_000L)
        // 车机声明不发 0x4301：Phase 1 必须立即返回空端点，而不是白等 3s 窗口
        assertTrue(phaseOne.ipAddresses.isEmpty())

        val pump = Thread({ session.pumpIncomingForever() }, "phone-pump")
        pump.isDaemon = true
        pump.start()

        assertTrue("晚到 0x4301 未触发端点回调", updateLatch.await(5, TimeUnit.SECONDS))
        val late = updates.get()
        assertNotNull(late)
        assertEquals("MockAP", late!!.ssid)
        assertEquals(49152, late.airPlayPort)
        assertEquals(listOf("192.168.49.1", "fd00::1"), late.ipAddresses)
        assertEquals("mock-device-id", late.deviceIdentifier)

        phoneChannel.close()
        accessoryChannel.close()
        pump.join(2_000)
    }

    /**
     * PR #19（凯迪拉克 CT5）：新协议车机声明会发 0x4301 但窗口内未到——
     * **不拆链**，返回空端点进入保活；保活泵把晚到的 0x4301 解析成完整端点
     * 经 onEndpointUpdate 回调（Audi MMI 9310 晚到 2 分 35 秒的同族形态）。
     */
    @Test
    fun newProtocolCarWithoutCredentialsKeepsLinkForLateStartSession() {
        val pair = InMemoryStreamPair()
        val phoneChannel = Iap2LinkChannel(pair.sideA, name = "phone")
        val accessoryChannel = Iap2LinkChannel(pair.sideB, name = "accessory")
        val accessory =
            MockAccessory(
                accessoryChannel,
                shape = MockAccessory.Shape.CT5_NEW_PROTOCOL,
                lateStartSession = true,
            )

        val accessoryThread = Thread({ accessory.run() }, "mock-accessory")
        accessoryThread.isDaemon = true
        accessoryThread.start()

        val updates = AtomicReference<Iap2SenderSession.CarPlayEndpoint?>()
        val updateLatch = CountDownLatch(1)
        val session =
            Iap2SenderSession(
                phoneChannel,
                log = { line -> println("PHONE $line") },
                bluetoothMac = "34:86:DA:86:BB:96",
                deviceName = "test-device",
                onEndpointUpdate = {
                    updates.set(it)
                    updateLatch.countDown()
                },
            )

        val endpoint = session.run(timeoutMillis = 10_000L)
        assertEquals("", endpoint.ssid)

        val pumpThread = Thread({ session.pumpIncomingForever() }, "phone-pump")
        pumpThread.isDaemon = true
        pumpThread.start()
        assertTrue("晚到 0x4301 未在 10s 内经 onEndpointUpdate 回调", updateLatch.await(10, TimeUnit.SECONDS))
        val late = updates.get()
        assertNotNull(late)
        assertEquals("MockAP", late!!.ssid)
        assertEquals("secret-passphrase", late.passphrase)
        assertEquals(49152, late.airPlayPort)

        phoneChannel.close()
        accessoryChannel.close()
    }

    /**
     * **奥迪 MMI 3315 真实形态必须发 0x5702**（2026-10-22 回归的钉子）。
     *
     * 它的可收清单只有 5 项且不含 0x5702，旧实现据此判「跳过旧协议」→ 整轮一次
     * 0x5702 都没发 → 车机 AirPlay 服务**始终没起来**（45 次连接全部 ECONNREFUSED，
     * `_airplay._tcp` 也没广播）。而同一台车在旧版本（无脑发 0x5702）下 122~368ms
     * 就回 0x5703，入网 30ms 后端口即可连通。
     *
     * 判据是 `ReceiverProfile.wifiGen`：稀疏清单 → UNKNOWN → 保守走旧协议。
     */
    @Test
    fun sparseDeclarationsStillSendWifiRequest() {
        val pair = InMemoryStreamPair()
        val phoneChannel = Iap2LinkChannel(pair.sideA, name = "phone")
        val accessoryChannel = Iap2LinkChannel(pair.sideB, name = "accessory")
        val accessory = MockAccessory(accessoryChannel, shape = MockAccessory.Shape.AUDI_3315)

        val accessoryThread = Thread({ accessory.run() }, "mock-accessory")
        accessoryThread.isDaemon = true
        accessoryThread.start()

        val endpoint =
            Iap2SenderSession(
                phoneChannel,
                log = { line -> println("PHONE $line") },
                bluetoothMac = "34:86:DA:86:BB:96",
                deviceName = "test-device",
            ).run(timeoutMillis = 10_000L)

        assertTrue("奥迪 3315 形态必须发 0x5702（跳过会让车机不开 AirPlay 服务）", accessory.sawWifiRequest)
        // 且必须采用 0x5703 给的凭据
        assertEquals("MockAP", endpoint.ssid)
        assertEquals("secret-passphrase", endpoint.passphrase)
        assertEquals(36, endpoint.channel)

        phoneChannel.close()
        accessoryChannel.close()
    }

    /**
     * **宝马 iD9 形态不能发 0x5702**（同一处改动的另一半钉子）。
     *
     * 它两份清单都完整（合计 ≥10 项）仍无 0x57xx → 画像判 NEW → 跳过 0x5702，
     * AP 信息完全由 0x4301 自带。若误发 0x5702 并傻等 0x5703，就是 8b64147
     * 当初要修的「每轮死等超时」老毛病。
     */
    @Test
    fun fullDeclarationsWithoutOldProtocolSkipWifiRequest() {
        val pair = InMemoryStreamPair()
        val phoneChannel = Iap2LinkChannel(pair.sideA, name = "phone")
        val accessoryChannel = Iap2LinkChannel(pair.sideB, name = "accessory")
        val accessory = MockAccessory(accessoryChannel, shape = MockAccessory.Shape.BMW_ID9)

        val accessoryThread = Thread({ accessory.run() }, "mock-accessory")
        accessoryThread.isDaemon = true
        accessoryThread.start()

        val endpoint =
            Iap2SenderSession(
                phoneChannel,
                log = { line -> println("PHONE $line") },
                bluetoothMac = "34:86:DA:86:BB:96",
                deviceName = "test-device",
            ).run(timeoutMillis = 10_000L)

        assertTrue("宝马 iD9 形态不应发 0x5702（AP 信息由 0x4301 自带）", !accessory.sawWifiRequest)
        // AP 信息来自 0x4301 的无线组
        assertEquals("MockAP", endpoint.ssid)
        assertEquals(49152, endpoint.airPlayPort)

        phoneChannel.close()
        accessoryChannel.close()
    }

    /**
     * **无新凭据但知道热点 SSID 时不得拆链**（2026-10-22 奥迪回归的第二根钉子）。
     *
     * 关键时序证据：真 iPhone 14:23:02 入网、14:23:23 才收到车机 ctrl 邀请
     * （21 秒）；旧实现「8 秒没 0x4301 就抛异常」每轮自杀，车机侧的等待永远
     * 从 0 重新计时。只要还知道该连哪个热点，就必须让主控按当前网络继续等。
     */
    @Test
    fun knownCarSsidAvoidsCredentiallessAbort() {
        val endpoint = credentiallessEndpoint("Audi_MMI_3315_5GHz")
        assertNotNull("已知热点时必须合成兜底端点，而不是拆链", endpoint)
        assertEquals("Audi_MMI_3315_5GHz", endpoint!!.ssid)
        // 必须是「不自足端点」：主控据此才走「等 ctrl 邀请 → mDNS/网关」路径
        assertEquals(0, endpoint.airPlayPort)
        assertTrue(endpoint.ipAddresses.isEmpty())
    }

    /** 连 SSID 都不知道才允许失败（继续等只会空转）。 */
    @Test
    fun noCredentialAndNoKnownSsidStillFails() {
        assertEquals(null, credentiallessEndpoint(null))
        assertEquals(null, credentiallessEndpoint(""))
        assertEquals(null, credentiallessEndpoint("   "))
    }

    /**
     * Audi MMI 3315 形态：0x4301 的地址字段按定长 NUL 填充（IPv6 后 22 个、IPv4 后 5 个）。
     * 旧实现只去掉**一个**尾部 NUL，得到 `"10.174.189.1\0\0\0\0\0"`——它 contains('.')=true、
     * contains(':')=false，能骗过所有「是不是 IPv4」的判断，但 Android 的 getaddrinfo 解不了，
     * 表现为 `Unable to resolve host "10.174.189.1"`（2026-10-18 实测：配对成功后视频端口
     * 与 keepAlive 全挂、会话 10 秒被拆）。
     */
    @Test
    fun nulPaddedAddressesAreStrippedCompletely() {
        val pair = InMemoryStreamPair()
        val phoneChannel = Iap2LinkChannel(pair.sideA, name = "phone")
        val accessoryChannel = Iap2LinkChannel(pair.sideB, name = "accessory")
        val accessory = MockAccessory(accessoryChannel, padAddresses = true)

        val accessoryThread = Thread({ accessory.run() }, "mock-accessory")
        accessoryThread.isDaemon = true
        accessoryThread.start()

        val endpoint =
            Iap2SenderSession(
                phoneChannel,
                log = { line -> println("PHONE $line") },
                bluetoothMac = "34:86:DA:86:BB:96",
                deviceName = "test-device",
            ).run(timeoutMillis = 10_000L)

        // 每个地址都必须干净：没有 NUL、没有空白
        endpoint.ipAddresses.forEach { address ->
            check(address.none { it == '\u0000' }) { "地址含 NUL：${address.toByteArray().toList()}" }
            check(address.trim() == address) { "地址含首尾空白：'$address'" }
        }
        assertEquals(listOf("192.168.49.1", "fd00::1"), endpoint.ipAddresses)
        // 端口解析不受填充影响（同一帧里的 u32 参数）
        assertEquals(49152, endpoint.airPlayPort)

        phoneChannel.close()
        accessoryChannel.close()
    }

    /**
     * 0x5703 迟迟不答：发送端必须在等待窗口内**重发设备状态问候**、再**重发 0x5702**，
     * 而不是干等到车机拆链（BMW iD8.5 卡在 0x5703 的修复点）。
     */
    @Test
    fun slowWifiConfigurationTriggersResends() {
        val pair = InMemoryStreamPair()
        val phoneChannel = Iap2LinkChannel(pair.sideA, name = "phone")
        val accessoryChannel = Iap2LinkChannel(pair.sideB, name = "accessory")
        val accessory = MockAccessory(accessoryChannel, slowWifi = true)

        val accessoryThread = Thread({ accessory.run() }, "mock-accessory")
        accessoryThread.isDaemon = true
        accessoryThread.start()

        val endpoint =
            Iap2SenderSession(
                phoneChannel,
                log = { line -> println("PHONE $line") },
                bluetoothMac = "34:86:DA:86:BB:96",
                deviceName = "test-device",
            ).run(timeoutMillis = 15_000L)

        assertEquals("MockAP", endpoint.ssid)
        assertEquals(49152, endpoint.airPlayPort)

        phoneChannel.close()
        accessoryChannel.close()
    }

    /** 按 DiPlay Iap2WirelessControlClient 的时序脚本化应答。 */
    private class MockAccessory(
        private val channel: Iap2LinkChannel,
        /**
         * 车机形态（0x1D01 双清单 + 是否应答 0x5702）。
         *
         * 这四种形态是 WiFi 凭据协议分派的**全部**依据来源，任何一个判错都会
         * 让对应车型连不上，所以逐个钉死：
         *  - [Shape.TEST_BENCH]：DiPlay/xcertplay 测试台，无清单、走 0x5702→0x5703；
         *  - [Shape.AUDI_9310]：发清单 2 项（含 0x5703），走旧协议；
         *  - [Shape.AUDI_3315]：发 2 项（**不含** 0x5703）收 5 项（**不含** 0x5702）
         *    ——稀疏且不完整，但**实际完整支持旧协议**；判成「跳过 0x5702」会让车机
         *    压根不启动 AirPlay 服务（2026-10-22 真车日志：45 次 ECONNREFUSED）；
         *  - [Shape.BMW_ID9]：两清单完整（10+ 项）仍无 0x57xx，AP 信息只在 0x4301。
         */
        private val shape: Shape = Shape.TEST_BENCH,
        /** Phase 1 结束后再补发 0x4301（车机窗口晚开的真实时序）。 */
        private val lateStartSession: Boolean = false,
        /**
         * 地址字段按真实车机的定长填充（Audi MMI 3315 实测：IPv6 后跟 22 个 NUL、
         * IPv4 后跟 5 个）。用于锁定「必须去掉全部尾部 NUL」这一行为。
         */
        private val padAddresses: Boolean = false,
        /** 0x5703 迟迟不答：发送端应重发问候、再重发 0x5702（而不是干等到车机拆链）。 */
        private val slowWifi: Boolean = false,
    ) {
        private val parser = Iap2Csm.Parser()
        private val pending = ArrayDeque<Iap2Csm.Frame>()

        /** 发送端是否发过 0x5702（分派断言用）。 */
        @Volatile
        var sawWifiRequest: Boolean = false
            private set

        /** 车机形态。 */
        enum class Shape {
            TEST_BENCH,
            AUDI_9310,
            AUDI_3315,
            BMW_ID9,

            // 凯迪拉克 CT5（GM/Cinemo）：声明会发 0x4301、无 0x5702/0x5703——
            // AP 信息只能在 0x4301 里，且窗口内常不到（PR #19，2026-10-21 日志）
            CT5_NEW_PROTOCOL,
        }

        /** 老式奥迪/测试台：不声明接收清单，也不发 StartHID。 */
        private val legacyHid: Boolean get() = shape != Shape.TEST_BENCH

        /** 是否应答 0x5702（旧协议在网络侧有凭据交换）。CT5 与 BMW 同为纯新协议。 */
        private val answersWifiRequest: Boolean get() = shape != Shape.BMW_ID9 && shape != Shape.CT5_NEW_PROTOCOL

        fun run() {
            check(channel.awaitReady(10_000)) { "mock link not ready" }

            expect(Iap2MessageIds.START_IDENTIFICATION)
            send(
                Iap2MessageIds.IDENTIFICATION_INFORMATION,
                Iap2Csm.string(0, identityName()),
                Iap2Csm.string(1, "MU-1"),
                Iap2Csm.string(2, "Mock Manufacturer"),
                *declarationParams(),
            )
            expect(Iap2MessageIds.IDENTIFICATION_ACCEPTED)

            expect(Iap2MessageIds.MFI_REQUEST_CERTIFICATE)
            send(Iap2MessageIds.MFI_CERTIFICATE, Iap2Csm.param(0, ByteArray(64) { it.toByte() }))
            expect(Iap2MessageIds.MFI_REQUEST_CHALLENGE)
            send(Iap2MessageIds.MFI_RESPONSE, Iap2Csm.param(0, ByteArray(70) { (it * 7).toByte() }))
            expect(Iap2MessageIds.MFI_AUTHENTICATION_SUCCEEDED)

            // 设备状态问候紧跟 0xAA05（真机次序，早于订阅与 0x5702；PR17 分段等待也靠它）
            expectDeviceStateGreetings()

            // 订阅 + StartHID（奥迪/宝马/CT5 形态不发，发送端应直接跳过等待）
            if (!legacyHid) {
                send(Iap2MessageIds.START_NOW_PLAYING_UPDATES)
                send(Iap2MessageIds.START_POWER_UPDATES)
                send(
                    Iap2MessageIds.START_HID,
                    Iap2Csm.u16(0, 0),
                    Iap2Csm.u16(1, 2),
                    Iap2Csm.u16(2, 1),
                    Iap2Csm.param(4, byteArrayOf(0x05, 0x0c)),
                )
                expect(Iap2MessageIds.DEVICE_HID_REPORT)
            }

            // WiFi 配置：BMW/CT5 形态不发 0x5702（AP 信息由 0x4301 自带）。
            // 若发送端在 BMW 形态下仍发 0x5702，下面第一个 expect(0x4e0d) 会立刻
            // 报「expected 0x4e0d got 0x5702」——分派错误无处可藏。
            if (answersWifiRequest) {
                expect(Iap2MessageIds.REQUEST_ACCESSORY_WIFI_CONFIGURATION)
                sawWifiRequest = true
                if (slowWifi) {
                    // 发送端 1.5s 未等到 0x5703 → 重发问候；再 1.3s → 重发 0x5702
                    // （PR17 分段等待救援剧本，BMW iD8.5 实测）
                    expectDeviceStateGreetings()
                    expect(Iap2MessageIds.REQUEST_ACCESSORY_WIFI_CONFIGURATION)
                }
                // 迟到一条订阅请求（真实车机 bring-up 常见：0x5000 与 0x5703 相邻到达）。
                // 它必须被暂存而不是在 expectOrNull 里被反复重读——旧实现把非预期帧塞回
                // 「自己下一轮优先读取」的队列，同一帧无限循环（2026-10-18 日志刷屏根因）。
                send(Iap2MessageIds.START_NOW_PLAYING_UPDATES)
                send(
                    Iap2MessageIds.ACCESSORY_WIFI_CONFIGURATION,
                    Iap2Csm.string(1, "MockAP"),
                    Iap2Csm.string(2, "secret-passphrase"),
                    Iap2Csm.u8(3, 2),
                    Iap2Csm.u8(4, 36),
                )
            }

            // CarPlay 启动
            expect(Iap2MessageIds.CARPLAY_AVAILABILITY)
            if (!lateStartSession) {
                sendStartSession()
            }

            expect(Iap2MessageIds.DEVICE_TRANSPORT_IDENTIFIER)
            if (lateStartSession) {
                // 车机打开 CarPlay 窗口后补发（Phase 1 早已结束）
                Thread.sleep(150)
                sendStartSession()
            }
        }

        /**
         * 设备状态问候：**4E0D（无线能力声明 u8=1）打头**——6318 定谳（2026-10-26）：
         * 不发它车机不武装 AirPlay 栈（车机自己的可收清单里就声明了 0x4e0d）；
         * 其后按 iPhone 抓包顺序 4E0C → A100 → 4E0B → 4E0A → 4E09 → FFFA → FFF0。
         */
        private fun expectDeviceStateGreetings() {
            expect(Iap2MessageIds.WIRELESS_CARPLAY_UPDATE)
            expect(Iap2MessageIds.DEVICE_UUID_UPDATE)
            expect(0xA100)
            expect(Iap2MessageIds.DEVICE_TIME_UPDATE)
            expect(Iap2MessageIds.DEVICE_LANGUAGE_UPDATE)
            expect(Iap2MessageIds.DEVICE_INFORMATION_UPDATE)
            expect(0xFFFA)
            expect(0xFFF0)
        }

        /** 0x1D01 的 name（奥迪/宝马/CT5 形态用真实车机名，MFi 记忆与画像键才一致）。 */
        private fun identityName(): String =
            when (shape) {
                Shape.AUDI_9310, Shape.AUDI_3315 -> "Audi_MMI_3315"
                Shape.BMW_ID9 -> "BMW 87866"
                Shape.CT5_NEW_PROTOCOL -> "CADILLAC_2179"
                Shape.TEST_BENCH -> "MockCarPlay"
            }

        /**
         * 0x1D01 的参数 6（车机会发）与参数 7（车机可收）。
         *
         * 奥迪 3315 的 5 项接收清单取自真实识别帧（2026-10-22 日志），宝马 iD9 的
         * 两份完整清单取自 ReceiverProfileTest 的同款形态——两者都是「分派判错的现场」。
         */
        private fun declarationParams(): Array<ByteArray> =
            when (shape) {
                // 测试台：完全不声明 → 画像 UNKNOWN → 走旧协议
                Shape.TEST_BENCH -> emptyArray()
                // 奥迪 MMI 9310：只声明发 0x5703/0xea03（无接收清单）
                Shape.AUDI_9310 -> arrayOf(Iap2Csm.u16List(6, intArrayOf(0x5703, 0xea03)))
                // 奥迪 MMI 3315：发 2 项（0x4301/0xea03）、收 5 项（均无 0x57xx）
                Shape.AUDI_3315 ->
                    arrayOf(
                        Iap2Csm.u16List(6, intArrayOf(0x4301, 0xea03)),
                        Iap2Csm.u16List(7, intArrayOf(0x4e0c, 0x4e09, 0x4300, 0xea00, 0xea01)),
                    )
                // 宝马 iD9：两清单各 10 项、合计 20（≥10）仍无 0x57xx → NEW
                Shape.BMW_ID9 ->
                    arrayOf(
                        Iap2Csm.u16List(
                            6,
                            intArrayOf(0xaa01, 0xaa03, 0x5000, 0x5002, 0x5200, 0x5203, 0xae00, 0xae02, 0x4157, 0x4301),
                        ),
                        Iap2Csm.u16List(
                            7,
                            intArrayOf(0xaa00, 0xaa02, 0xaa04, 0xaa05, 0xea00, 0xea01, 0x5001, 0x5201, 0x4300, 0x4e0d),
                        ),
                    )
                // 凯迪拉克 CT5（PR #19）：发 11 项含 0x4301、收 9 项，均无 0x57xx → NEW
                Shape.CT5_NEW_PROTOCOL ->
                    arrayOf(
                        Iap2Csm.u16List(
                            6,
                            intArrayOf(0xaa06, 0x6800, 0x6802, 0x6803, 0x4c00, 0x4c02, 0x4c03, 0x4c05, 0x5000, 0x5002, 0x4301),
                        ),
                        Iap2Csm.u16List(
                            7,
                            intArrayOf(0x6801, 0x4c01, 0x4c04, 0x5001, 0x4e09, 0x4e0a, 0x4e0b, 0x4e0c, 0x4300),
                        ),
                    )
            }

        private fun sendStartSession() {
            send(
                Iap2MessageIds.CARPLAY_START_SESSION,
                Iap2Csm.group(
                    1,
                    Iap2Csm.string(0, "MockAP"),
                    Iap2Csm.string(1, "secret-passphrase"),
                    Iap2Csm.u8(2, 36),
                    addressParam(3, "192.168.49.1", padTo = 17),
                    addressParam(3, "fd00::1", padTo = 47),
                    Iap2Csm.u8(4, 2),
                ),
                Iap2Csm.u32(2, 49152L),
                Iap2Csm.string(3, "mock-device-id"),
                Iap2Csm.string(4, "a".repeat(64) + "publickeyhex"),
                Iap2Csm.string(5, "560.20"),
            )
        }

        /** 地址参数：真实车机按定长 NUL 填充；默认（不填充）与之前行为一致。 */
        private fun addressParam(
            id: Int,
            value: String,
            padTo: Int,
        ): ByteArray {
            val raw = value.encodeToByteArray()
            if (!padAddresses) return Iap2Csm.param(id, raw + byteArrayOf(0))
            // 至少保留一个 NUL 结尾（CSM 字符串语义），其余补到固定宽度
            val width = maxOf(padTo, raw.size + 1)
            val padded = raw + ByteArray(width - raw.size)
            return Iap2Csm.param(id, padded)
        }

        private fun send(
            messageId: Int,
            vararg body: ByteArray,
        ) {
            val joined =
                when (body.size) {
                    0 -> ByteArray(0)
                    1 -> body[0]
                    else -> body.reduce { acc, bytes -> acc + bytes }
                }
            val frame = Iap2Csm.encode(messageId, joined)
            var offset = 0
            val limit = channel.peerMaxControlPayload() ?: 2048
            while (offset < frame.size) {
                val end = minOf(offset + limit, frame.size)
                check(channel.sendControl(frame.copyOfRange(offset, end))) { "mock send failed" }
                offset = end
            }
        }

        private fun expect(messageId: Int) {
            println("MOCK expect 0x${messageId.toString(16)}")
            val frame =
                recvFrame(10_000)
                    ?: error("mock timed out waiting 0x${messageId.toString(16)}")
            check(frame.messageId == messageId) {
                "mock expected 0x${messageId.toString(16)} got 0x${frame.messageId.toString(16)}"
            }
            println("MOCK got 0x${frame.messageId.toString(16)}")
        }

        private fun recvFrame(timeoutMillis: Long): Iap2Csm.Frame? {
            pending.firstOrNull()?.let {
                pending.removeFirst()
                return it
            }
            val bytes = channel.recvControl(timeoutMillis) ?: return null
            val frames = parser.offer(bytes)
            if (frames.isEmpty()) return null
            frames.forEachIndexed { index, frame -> if (index > 0) pending += frame }
            return frames.first()
        }
    }
}
