package io.github.shaonialife.cardeck.net

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * `cmd wifi connect-network` 参数拼装的回归保护。
 *
 * 车机热点名/密码来自 0x5703（车机下发），含空格、中文、甚至单引号都可能出现；
 * 拼错一处就是「静默入网失败、退回会弹窗的 specifier」，很难从日志看出原因。
 * 这里把边界钉死，顺带锁住安全类型映射（0=NONE 1=WEP 2=WPA2 3=过渡 4=WPA3）。
 */
class WifiConnectCommandTest {
    @Test
    fun quotesSsidAndPassphraseWithSpaces() {
        assertEquals(
            "cmd wifi connect-network 'DIRECT-BMW 12345' wpa2 'p@ss word'",
            WifiConnectCommand.build("DIRECT-BMW 12345", "p@ss word", 2),
        )
    }

    @Test
    fun escapesSingleQuotes() {
        // POSIX 写法：'pa'\''ss' —— 关闭引号、转义单引号、重开引号
        assertEquals(
            "cmd wifi connect-network 'a b' wpa2 'pa'\\''ss'",
            WifiConnectCommand.build("a b", "pa'ss", 2),
        )
    }

    @Test
    fun openNetworkHasNoPassphrase() {
        assertEquals(
            "cmd wifi connect-network 'DIRECT-BMW' open",
            WifiConnectCommand.build("DIRECT-BMW", "", 0),
        )
    }

    @Test
    fun securityTypeZeroWithPassphraseKeepsCredentials() {
        // 凭据自洽优先（2026-11-12 审查钉）：车机 5703 误报 security=0（open）
        // 而热点实为 WPA2（2026-11-04 K40 病发形态 [0005][0004] 00）——
        // 密码非空一律带上走 wpa2，丢密码 = 关联必败
        assertEquals(
            "cmd wifi connect-network 'DIRECT-BMW' wpa2 'secret123'",
            WifiConnectCommand.build("DIRECT-BMW", "secret123", 0),
        )
    }

    @Test
    fun emptyPassphraseFallsBackToOpen() {
        // 参数自相矛盾（给了 WPA2 却没密码）时不生成半条命令，按开放热点试
        assertEquals(
            "cmd wifi connect-network 'DIRECT-BMW' open",
            WifiConnectCommand.build("DIRECT-BMW", "", 2),
        )
    }

    @Test
    fun wpa3OnlyUsesWpa3Keyword() {
        assertEquals(
            "cmd wifi connect-network 'DIRECT-BMW' wpa3 'secret123'",
            WifiConnectCommand.build("DIRECT-BMW", "secret123", 4),
        )
    }

    @Test
    fun wpa3TransitionAndWepFallBackToWpa2() {
        // 过渡模式 AP 同时接受 WPA2；WEP 没有关键字，同样按 wpa2 试一次
        assertEquals(
            "cmd wifi connect-network 'DIRECT-BMW' wpa2 'secret123'",
            WifiConnectCommand.build("DIRECT-BMW", "secret123", 3),
        )
        assertEquals(
            "cmd wifi connect-network 'DIRECT-BMW' wpa2 'secret123'",
            WifiConnectCommand.build("DIRECT-BMW", "secret123", 1),
        )
    }

    @Test
    fun keepsNonAsciiSsidIntact() {
        assertEquals(
            "cmd wifi connect-network '车机 热点' wpa2 '密码 1234'",
            WifiConnectCommand.build("车机 热点", "密码 1234", 2),
        )
    }
}
