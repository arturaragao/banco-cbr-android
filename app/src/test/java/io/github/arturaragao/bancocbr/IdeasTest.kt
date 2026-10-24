package io.github.arturaragao.bancocbr

import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDateTime

class IdeasTest {
    private val at = LocalDateTime.of(2026, 10, 24, 9, 5)

    @Test fun lineIsOneUncheckedLineWithDate() {
        assertEquals("- [ ] 2026-10-24 09:05 — app de laudo com voz", Ideas.line("  app de laudo\n com   voz ", at))
    }

    @Test fun newFileGetsHeader() {
        val out = Ideas.appendTo(null, listOf("- [ ] a"))
        assertTrue(out.startsWith(Ideas.HEADER))
        assertTrue(out.endsWith("- [ ] a\n"))
    }

    @Test fun appendKeepsExistingLines() {
        assertEquals("# X\n- [x] velha\n- [ ] b\n- [ ] c\n", Ideas.appendTo("# X\n- [x] velha\n\n", listOf("- [ ] b", "- [ ] c")))
    }

    @Test fun tokenFromJsLiteral() {
        assertEquals("ghp_abc", Ideas.tokenFromJs("\"ghp_abc\""))
        assertNull(Ideas.tokenFromJs("null"))
        assertNull(Ideas.tokenFromJs("\"\""))
        assertNull(Ideas.tokenFromJs(null))
    }
}
