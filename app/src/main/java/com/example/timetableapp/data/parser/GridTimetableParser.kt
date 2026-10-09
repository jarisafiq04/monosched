package com.example.timetableapp.data.parser

import java.util.regex.Pattern

/**
 * Parses column-grid timetables (days as column headers, time cells below them),
 * e.g. USIM iStudent print-outs. Plain line-regex parsing cannot work here
 * because the day lives in a different column, not on the session's line.
 * Returns null when the pages contain no day grid, so callers fall back
 * to [TimetableParser].
 */
object GridTimetableParser {

    data class GridSession(
        val code: String,
        val group: String,
        val day: Int,
        val startH: Int,
        val startM: Int,
        val endH: Int,
        val endM: Int,
        val room: String
    )

    data class CourseInfo(
        val name: String,
        val lecturer: String = "",
        val phone: String = ""
    )

    data class GridResult(
        val sessions: List<GridSession>,
        val info: Map<String, CourseInfo>
    )

    private val days = listOf("MONDAY", "TUESDAY", "WEDNESDAY", "THURSDAY", "FRIDAY", "SATURDAY", "SUNDAY")
    private val codeRe = Pattern.compile("^[A-Z]{2,4}\\d{3,4}$")
    private val groupRe = Pattern.compile("^[A-Z]{1,4}\\d{1,3}$")
    private val roomRe = Pattern.compile("^[A-Za-z]{1,4}-\\S+$")
    private val bilRe = Pattern.compile("^\\d+\\s+([A-Z]{2,4}\\d{3,4})\\b")
    private val timeRe = Pattern.compile("(\\d{1,2}):(\\d{2})\\s*(AM|PM)?\\s*[-\u2013]\\s*(\\d{1,2}):(\\d{2})\\s*(AM|PM)?", Pattern.CASE_INSENSITIVE)
    private val footerRe = Pattern.compile("(?i)(actual\\s+credit|min\\s+credit|max\\s+credit)")
    private val phoneRe = Pattern.compile("^0\\d[\\d-]{5,}$")

    fun parse(pages: List<List<GridStripper.Line>>): GridResult? {
        val sessions = mutableListOf<GridSession>()
        val info = mutableMapOf<String, CourseInfo>()
        var sawGrid = false
        for (page in pages) {
            if (parseNamesPage(page, info)) continue
            if (parseGridPage(page, sessions)) sawGrid = true
        }
        if (!sawGrid) return null
        return GridResult(sessions, info)
    }

    private fun lineText(words: List<GridStripper.Word>) = words.joinToString(" ") { it.text }

    private fun parseGridPage(page: List<GridStripper.Line>, out: MutableList<GridSession>): Boolean {
        val header = page.firstOrNull { line -> line.words.count { it.text in days } >= 3 }
            ?: return false
        val hx = header.words.associate { it.text to it.x0 }
        val locX = hx["LOCATION"] ?: Float.MAX_VALUE
        val cols = days.mapNotNull { d -> hx[d]?.let { d to it } }.sortedBy { it.second }
        if (cols.size < 3) return false
        val ranges = cols.mapIndexed { i, (name, x0) ->
            Triple(name, x0, if (i + 1 < cols.size) cols[i + 1].second else locX)
        }

        var code: String? = null
        var group = ""
        var recWords = mutableListOf<GridStripper.Word>()
        fun flush() {
            val c = code ?: return
            val room = recWords.firstOrNull { roomRe.matcher(it.text).matches() }?.text ?: ""
            val body = recWords.filter { it.text != room }
            val txt = lineText(body)
            val m = timeRe.matcher(txt)
            if (!m.find()) return
            val xc = body.filter { it.text in m.group(0).split("-")[0].split(" ") }
                .map { it.x0 }.average().takeIf { !it.isNaN() } ?: return
            val dayName = ranges.firstOrNull { (_, x0, x1) -> x0 <= xc && xc < x1 }?.first ?: return
            val sh = to24(m.group(1).toIntOrNull() ?: return, m.group(3))
            val sm = m.group(2).toIntOrNull() ?: return
            val eh = to24(m.group(4).toIntOrNull() ?: return, m.group(6) ?: m.group(3))
            val em = m.group(5).toIntOrNull() ?: return
            if (sh > 23 || eh > 23 || sm > 59 || em > 59) return
            if (eh * 60 + em <= sh * 60 + sm) return
            out.add(GridSession(c, group, days.indexOf(dayName) + 1, sh, sm, eh, em, room))
        }

        for (line in page) {
            if (line.y <= header.y) continue
            val ws = line.words
            if (ws.isNotEmpty() && codeRe.matcher(ws[0].text).matches() && ws[0].x0 < 120f) {
                flush()
                code = ws[0].text
                group = ws.drop(1).firstOrNull { groupRe.matcher(it.text).matches() }?.text ?: ""
                recWords = ws.toMutableList()
            } else if (code != null) {
                recWords.addAll(ws)
            }
        }
        flush()
        return true
    }

    private fun parseNamesPage(page: List<GridStripper.Line>, info: MutableMap<String, CourseInfo>): Boolean {
        val header = page.firstOrNull { line ->
            val set = line.words.map { it.text }.toSet()
            set.contains("BIL") && set.contains("CODE") && set.contains("GROUP") && set.contains("COURSE")
        } ?: return false
        val hx = header.words.associate { it.text to it.x0 }
        val courseX = hx["COURSE"] ?: return false
        val groupX = hx["GROUP"] ?: return false
        val lectX = hx["LECTURER"] ?: (groupX + 100f)
        val compX = hx["COMPONENT"] ?: Float.MAX_VALUE
        var code: String? = null
        var acc = mutableListOf<String>()
        var lec = mutableListOf<String>()
        var phone = ""
        var lastY = 0f
        fun inNameRange(x: Float) = courseX - 25f <= x && x < groupX
        fun inLectRange(x: Float) = lectX - 35f <= x && x < compX
        fun commit() {
            val c = code
            if (c != null && acc.isNotEmpty()) {
                info[c] = CourseInfo(acc.joinToString(" "), lec.joinToString(" "), phone)
            }
        }
        fun collect(words: List<GridStripper.Word>) {
            acc.addAll(words.filter { inNameRange(it.x0) }.map { it.text })
            for (w in words.filter { inLectRange(it.x0) }) {
                if (phone.isEmpty() && phoneRe.matcher(w.text).matches()) phone = w.text
                else lec.add(w.text)
            }
        }
        for (line in page) {
            if (line.y <= header.y) continue
            val txt = lineText(line.words)
            if (footerRe.matcher(txt).find()) break
            val bil = bilRe.matcher(txt)
            if (bil.find()) {
                commit()
                code = bil.group(1)
                acc = mutableListOf()
                lec = mutableListOf()
                phone = ""
                collect(line.words)
                lastY = line.y
            } else if (code != null && line.words.isNotEmpty() && line.y - lastY < 14f) {
                val first = line.words.first()
                // ponytail: a leading number at the left margin starts a new entry, not a continuation
                if (!(first.x0 < 100f && first.text.all { it.isDigit() })) {
                    collect(line.words)
                    lastY = line.y
                }
            }
        }
        commit()
        return true
    }

    private fun to24(hour: Int, marker: String?): Int {
        return when (marker?.uppercase()) {
            "PM" -> if (hour < 12) hour + 12 else hour
            "AM" -> if (hour == 12) 0 else hour
            else -> hour
        }
    }
}
