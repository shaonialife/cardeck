package io.github.shaonialife.cardeck.airplay

import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SdpLocalPortsTest {
    @Test
    fun allocateReturnsUsableControlPortAndCloses() {
        val ports = SdpLocalPorts.allocate()
        assertNotNull(ports)
        assertTrue(ports!!.controlPort in 1..65535)
        ports.close()
    }
}
