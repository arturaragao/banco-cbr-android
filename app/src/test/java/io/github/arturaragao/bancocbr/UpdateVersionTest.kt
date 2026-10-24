package io.github.arturaragao.bancocbr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateVersionTest {
    @Test fun leNumeroDaTag() {
        assertEquals(7, UpdateVersion.codeFromTag("v1.1.7"))
        assertEquals(12, UpdateVersion.codeFromTag("v1.2.12"))
        assertNull(UpdateVersion.codeFromTag("latest"))
    }

    @Test fun soAtualizaSeForMaior() {
        assertTrue(UpdateVersion.isNewer("v1.1.8", 7))
        assertFalse(UpdateVersion.isNewer("v1.1.7", 7))
        assertFalse(UpdateVersion.isNewer("v1.1.6", 7))
        assertFalse(UpdateVersion.isNewer("latest", 7))
    }
}
