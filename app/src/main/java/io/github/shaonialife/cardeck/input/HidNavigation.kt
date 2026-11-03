package io.github.shaonialife.cardeck.input

import android.view.KeyEvent

/**
 * 车机「导航类」HID（iDrive 旋钮 / 四向拨杆 / 确认键）→ Android 方向键。
 *
 * **字节域已移交 Rust**（A6 下沉——fw::airplay::hid：设备分类/报告解析；
 * fw: APHIDClientHandleHIDReport @0x2276b7e4c 报告格式由 hidDescriptor
 * 定义）。本类只剩**边沿状态 + 键码映射**（Android 语义非协议）：
 *  - 报告字节 → [NativeBridge.nativeAirplayHidDispatch]（判定树在 Rust）；
 *  - kind=nav 的 raw 载荷（mask/delta/hat）→ [handleRaw] 边沿检测；
 *  - 输出 = Android DPAD 键码序列（注入虚拟屏，由焦点系统处理）。
 *
 * 与 ConsumerKeys 的分工不变：Consumer 页（0x0C）= 媒体键走
 * dispatchMediaKeyEvent（全局）；Generic Desktop 的 Wheel/Hat = 导航走
 * DPAD 注入。
 */
object HidNavigation {
    /** 原始报文日志开关（设置 → 显示 →「HID 原始日志」）。 */
    @Volatile
    var rawLogEnabled: Boolean = false

    /** 单次报告最多走几步（旋钮快拧时避免一次注入几十个按键把 UI 打死）。 */
    private const val MAX_STEPS = 5

    private val lastButtonMask = HashMap<String, Int>()
    private val lastHat = HashMap<String, Int>()

    /**
     * 处理一帧导航原语（Rust dispatch kind=nav 的载荷），返回需要注入的
     * 按键序列（0 个或多个）。
     *
     * @param uuid HID 设备 uuid（同一 uuid 的边沿状态单独维护）
     * @param mask 按钮位图：bit0=确认、1=上、2=下、3=左、4=右、5=返回（按下边沿触发）
     * @param delta 旋钮步进（有符号，顺时针为正；null=无步进域）
     * @param hat 8 向帽值 0..7（null=无帽/非帽报告）
     */
    fun handleRaw(
        uuid: String,
        mask: Int,
        delta: Int?,
        hat: Int?,
    ): List<Int> {
        val keys = ArrayList<Int>(3)
        if (hat != null) {
            if (hat != lastHat[uuid]) {
                lastHat[uuid] = hat
                keys.addAll(hatKeys(hat))
            }
            return keys
        }
        val pressed = mask and lastButtonMask[uuid].orZero().inv()
        lastButtonMask[uuid] = mask
        if (pressed != 0) {
            if ((pressed and 0x01) != 0) keys += KeyEvent.KEYCODE_DPAD_CENTER
            if ((pressed and 0x02) != 0) keys += KeyEvent.KEYCODE_DPAD_UP
            if ((pressed and 0x04) != 0) keys += KeyEvent.KEYCODE_DPAD_DOWN
            if ((pressed and 0x08) != 0) keys += KeyEvent.KEYCODE_DPAD_LEFT
            if ((pressed and 0x10) != 0) keys += KeyEvent.KEYCODE_DPAD_RIGHT
            if ((pressed and 0x20) != 0) keys += KeyEvent.KEYCODE_BACK
        }
        if (delta != null && delta != 0) {
            val key = if (delta > 0) KeyEvent.KEYCODE_DPAD_DOWN else KeyEvent.KEYCODE_DPAD_UP
            repeat(kotlin.math.abs(delta).coerceAtMost(MAX_STEPS)) { keys += key }
        }
        return keys
    }

    private fun Int?.orZero(): Int = this ?: 0

    /** 8 向帽 → 键序列：0=上 1=右上 2=右 3=右下 4=下 5=左下 6=左 7=左上。 */
    private fun hatKeys(hat: Int): List<Int> =
        when (hat) {
            0 -> listOf(KeyEvent.KEYCODE_DPAD_UP)
            1 -> listOf(KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_RIGHT)
            2 -> listOf(KeyEvent.KEYCODE_DPAD_RIGHT)
            3 -> listOf(KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_DPAD_RIGHT)
            4 -> listOf(KeyEvent.KEYCODE_DPAD_DOWN)
            5 -> listOf(KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_DPAD_LEFT)
            6 -> listOf(KeyEvent.KEYCODE_DPAD_LEFT)
            7 -> listOf(KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_LEFT)
            else -> emptyList()
        }

    /** 会话拆除时清边沿状态（同一物理设备重连从干净态开始）。 */
    fun reset() {
        lastButtonMask.clear()
        lastHat.clear()
    }

    /** 键盘键的可读名（日志用）。 */
    fun nameOf(keyCode: Int): String =
        when (keyCode) {
            KeyEvent.KEYCODE_DPAD_UP -> "上"
            KeyEvent.KEYCODE_DPAD_DOWN -> "下"
            KeyEvent.KEYCODE_DPAD_LEFT -> "左"
            KeyEvent.KEYCODE_DPAD_RIGHT -> "右"
            KeyEvent.KEYCODE_DPAD_CENTER -> "确认"
            KeyEvent.KEYCODE_BACK -> "返回"
            else -> "$keyCode"
        }
}
