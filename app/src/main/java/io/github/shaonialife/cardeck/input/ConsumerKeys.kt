package io.github.shaonialife.cardeck.input

import android.view.KeyEvent

/**
 * 车机 Consumer Control HID 报告 → Android 媒体键。
 *
 * **字节域已移交 Rust**（A6 下沉——fw::airplay::hid：描述符 Usage 数组
 * 解析 + Array/位图双报告语义；fw: APHIDClientHandleHIDReport
 * @0x2276b7e4c 报告格式由 hidDescriptor 定义）。本类只剩**键码映射**
 * （Android 语义非协议）：dispatch JSON kind=media 的 usage → Android
 * 媒体键。
 *
 * 报文语义备忘（历史实测，解析本体见 Rust 测试钉）：
 *  - 报文字节是 Usage 数组的**下标**不是 Usage ID（2026-10-17 奥迪实测：
 *    「下一首」收 0x05 → usages[5]=0xB5；早期直读成 Usage ID 即
 *    「收到消息但不切歌」的根因）；
 *  - 位图设备（本田 HFT `81 02`）：每个 usage 占 1 bit。
 */
object ConsumerKeys {
    /** Usage ID → Android 媒体键；无对应键返回 0（如 Repeat）。 */
    fun keyCodeFor(usage: Int): Int =
        when (usage) {
            0xB0 -> KeyEvent.KEYCODE_MEDIA_PLAY
            0xB1 -> KeyEvent.KEYCODE_MEDIA_PAUSE
            0xB3 -> KeyEvent.KEYCODE_MEDIA_FAST_FORWARD
            0xB4 -> KeyEvent.KEYCODE_MEDIA_REWIND
            0xB5 -> KeyEvent.KEYCODE_MEDIA_NEXT
            0xB6 -> KeyEvent.KEYCODE_MEDIA_PREVIOUS
            0xB9 -> KeyEvent.KEYCODE_MEDIA_STOP
            else -> 0
        }

    /** 可读名称（日志用）。 */
    fun nameOf(usage: Int): String =
        when (usage) {
            0xB0 -> "播放"
            0xB1 -> "暂停"
            0xB3 -> "快进"
            0xB4 -> "快退"
            0xB5 -> "下一首"
            0xB6 -> "上一首"
            0xB9 -> "停止"
            0xBC -> "循环"
            else -> "未知(0x%02x)".format(usage)
        }
}
