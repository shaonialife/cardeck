package io.github.shaonialife.cardeck.airplay

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** /info 与 changeModes 的 modes 结构解析（8015 置灰案的资源仲裁冻结依据）。 */
class ModesStateParseTest {
    private fun modes(
        appStates: List<Map<String, Any?>>? = null,
        resources: List<Map<String, Any?>>? = null,
    ): Map<String, Any?> =
        buildMap {
            appStates?.let { put("appStates", it) }
            resources?.let { put("resources", it) }
        }

    @Test
    fun audi8015CallBorrowFormIsBusy() {
        // 2026-10-24 实测 /info：车机通话中、语音播报中、主屏被 phoneCall 借走
        val state =
            AirPlayClient.parseModesState(
                modes(
                    appStates =
                        listOf(
                            mapOf("appStateID" to 2L, "state" to true),
                            mapOf("speechMode" to 1L, "appStateID" to 1L),
                            mapOf("appStateID" to 3L, "state" to false),
                        ),
                    resources =
                        listOf(
                            mapOf(
                                "borrowID" to "phoneCall",
                                "transferPriority" to 500L,
                                "resourceID" to 1L,
                                "transferType" to 3L,
                            ),
                        ),
                ),
                base = AirPlayClient.ModesState(false, false, false),
            )
        assertTrue(state.phoneCall)
        assertTrue(state.speechActive)
        assertTrue(state.screenBorrowed)
        assertTrue(state.busy)
    }

    @Test
    fun cleanFormIsNotBusy() {
        // 3315/8015 第二轮形态：无通话、无语音、transferType=1（Take，无借用）
        val state =
            AirPlayClient.parseModesState(
                modes(
                    appStates =
                        listOf(
                            mapOf("appStateID" to 2L, "state" to false),
                            mapOf("speechMode" to -1L, "appStateID" to 1L),
                            mapOf("appStateID" to 3L, "state" to false),
                        ),
                    resources =
                        listOf(
                            mapOf("transferPriority" to 100L, "takeConstraint" to 100L, "resourceID" to 1L, "transferType" to 1L),
                        ),
                ),
                base = AirPlayClient.ModesState(false, false, false),
            )
        assertFalse(state.busy)
    }

    @Test
    fun changeModesUsesEntitySemantics() {
        // changeModes 事件用 entity（0=None）而非 state（bool）；缺失的清单沿用 base
        val busy = AirPlayClient.ModesState(true, true, true)
        val state =
            AirPlayClient.parseModesState(
                modes(
                    appStates =
                        listOf(
                            mapOf("appStateID" to 2L, "entity" to 0L),
                            mapOf("appStateID" to 1L, "entity" to 0L, "speechMode" to -1L),
                        ),
                    resources =
                        listOf(
                            mapOf("resourceID" to 1L, "entity" to 1L, "permanentEntity" to 1L),
                        ),
                ),
                base = busy,
            )
        assertFalse(state.phoneCall)
        assertFalse(state.speechActive)
        assertFalse(state.screenBorrowed)
    }

    @Test
    fun absentListsKeepBase() {
        // changeModes 事件可能只带 resources（或只带 appStates）——缺失侧不覆盖
        val base = AirPlayClient.ModesState(phoneCall = true, speechActive = false, screenBorrowed = false)
        val state =
            AirPlayClient.parseModesState(
                modes(resources = listOf(mapOf("resourceID" to 1L, "transferType" to 4L))),
                base = base,
            )
        assertTrue("缺失的 appStates 不得清掉 base 的 phoneCall", state.phoneCall)
        assertFalse("Unborrow(4) 清借用", state.screenBorrowed)
    }
}
