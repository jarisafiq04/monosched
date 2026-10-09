package com.example.timetableapp.data.parser

import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.text.PDFTextStripper
import com.tom_roush.pdfbox.text.TextPosition
import kotlin.math.abs

/**
 * Collects positioned words per page so grid timetables (days as columns)
 * can map each time cell back to its day. Plain [PDFTextStripper] text
 * loses the columns, which is why line-regex parsing finds nothing.
 */
class GridStripper : PDFTextStripper() {

    data class Word(val x0: Float, val text: String)
    data class Line(val y: Float, val words: List<Word>)

    val pages = mutableListOf<List<Line>>()
    private data class Glyph(val x: Float, val y: Float, val w: Float, val c: String)
    private val glyphs = mutableListOf<Glyph>()

    override fun processTextPosition(text: TextPosition) {
        val u = text.unicode ?: return
        glyphs.add(Glyph(text.xDirAdj, text.yDirAdj, text.widthDirAdj, u))
    }

    override fun endPage(page: PDPage) {
        super.endPage(page)
        pages.add(buildLines(glyphs))
        glyphs.clear()
    }

    private fun buildLines(glyphs: List<Glyph>): List<Line> {
        if (glyphs.isEmpty()) return emptyList()
        val sorted = glyphs.sortedWith(compareBy({ it.y }, { it.x }))
        val rawLines = mutableListOf<MutableList<Glyph>>()
        for (g in sorted) {
            val line = rawLines.lastOrNull()
            if (line == null || abs(g.y - line.map { it.y }.average().toFloat()) > 3f) {
                rawLines.add(mutableListOf(g))
            } else {
                line.add(g)
            }
        }
        return rawLines.map { line ->
            val ordered = line.sortedBy { it.x }
            val words = mutableListOf<Word>()
            val cur = StringBuilder()
            var wordX = 0f
            var prevX = 0f
            var prevW = 0f
            fun flush() {
                if (cur.isNotEmpty()) {
                    words.add(Word(wordX, cur.toString()))
                    cur.clear()
                }
            }
            for (g in ordered) {
                if (g.c.isBlank()) {
                    flush()
                    continue
                }
                // ponytail: wide gap also splits words for PDFs without space glyphs
                if (cur.isNotEmpty() && g.x - (prevX + prevW) > 8f) flush()
                if (cur.isEmpty()) wordX = g.x
                cur.append(g.c)
                prevX = g.x
                prevW = g.w
            }
            flush()
            val y = line.map { it.y }.average().toFloat()
            Line(y, words)
        }.filter { it.words.isNotEmpty() }
    }
}
