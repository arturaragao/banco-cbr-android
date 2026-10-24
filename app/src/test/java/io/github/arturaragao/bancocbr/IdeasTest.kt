package io.github.arturaragao.bancocbr

import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDateTime

class IdeasTest {
    private val at = LocalDateTime.of(2026, 10, 24, 9, 5)

    @Test fun lineIsOneUncheckedLineWithDate() {
        assertEquals("- [ ] 2026-10-24 09:05 — app de laudo com voz", Ideas.line("  app de laudo\n com   voz ", at))
    }

    @Test fun cleanFilenameRemovesAccentsSpacesAndPathSeparators() {
        assertEquals("Relatorio-medico-final.PDF", Ideas.cleanFilename("Relatório médico/final.PDF"))
        assertEquals("pasta-exame.png", Ideas.cleanFilename("pasta\\exame.png"))
        assertEquals("arquivo", Ideas.cleanFilename(" /.. "))
        assertEquals("arquivo", Ideas.cleanFilename(""))
    }

    @Test fun cleanFilenameLimitsLengthAndKeepsExtension() {
        val name = Ideas.cleanFilename("á".repeat(100) + ".pdf")
        assertEquals(60, name.length)
        assertEquals("a".repeat(56) + ".pdf", name)
        assertEquals("x".repeat(60), Ideas.cleanFilename("x".repeat(100)))
        assertEquals("imagem.tar.gz", Ideas.cleanFilename("imagem.tar.gz"))
    }

    @Test fun attachmentSuffixKeepsTheIdeaOnOneLine() {
        val names = listOf("20261024-0905-1-exame.pdf", "20261024-0905-2-foto.jpg")
        val line = Ideas.line(" ver\n exame ", at) + Ideas.attachmentSuffix(names)
        assertEquals("- [ ] 2026-10-24 09:05 — ver exame · anexos: anexos/20261024-0905-1-exame.pdf, anexos/20261024-0905-2-foto.jpg", line)
        assertFalse(line.contains('\n'))
        assertEquals(" · anexos: anexos/exame.pdf", Ideas.attachmentSuffix(listOf("exame.pdf")))
    }

    @Test fun noAttachmentsPreservesOriginalLine() {
        assertEquals("", Ideas.attachmentSuffix(emptyList()))
        assertEquals("- [ ] 2026-10-24 09:05 — ideia", Ideas.line("ideia", at) + Ideas.attachmentSuffix(emptyList()))
    }

    @Test fun newFileGetsHeader() {
        val out = Ideas.appendTo(null, listOf("- [ ] a"))
        assertTrue(out.startsWith(Ideas.HEADER))
        assertTrue(out.endsWith("- [ ] a\n"))
    }

    @Test fun appendKeepsExistingLines() {
        assertEquals("# X\n- [x] velha\n- [ ] b\n- [ ] c\n", Ideas.appendTo("# X\n- [x] velha\n\n", listOf("- [ ] b", "- [ ] c")))
    }

    @Test fun joinGluesDictatedPieces() {
        assertEquals("primeira parte segunda", Ideas.join("primeira parte  ", " segunda "))
        assertEquals("só fala", Ideas.join("", "só fala"))
        assertEquals("texto", Ideas.join("texto", "   "))
    }

    @Test fun tokenFromJsLiteral() {
        assertEquals("ghp_abc", Ideas.tokenFromJs("\"ghp_abc\""))
        assertNull(Ideas.tokenFromJs("null"))
        assertNull(Ideas.tokenFromJs("\"\""))
        assertNull(Ideas.tokenFromJs(null))
    }
}
