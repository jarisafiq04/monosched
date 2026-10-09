package com.example.timetableapp.data.parser

import com.example.timetableapp.data.model.Course
import com.example.timetableapp.data.model.Session
import java.util.regex.Pattern

object TimetableParser {
    private val dayMap = mapOf(
        "mon" to 1, "monday" to 1,
        "tue" to 2, "tues" to 2, "tuesday" to 2,
        "wed" to 3, "wednesday" to 3,
        "thu" to 4, "thur" to 4, "thurs" to 4, "thursday" to 4,
        "fri" to 5, "friday" to 5,
        "sat" to 6, "saturday" to 6,
        "sun" to 7, "sunday" to 7
    )

    // Pattern 1: DAY HH:MM-HH:MM CODE NAME ROOM TYPE
    private val pattern1 = Pattern.compile(
        "(?i)(mon|tue|wed|thu|fri|sat|sun)\\s+" +
        "(\\d{1,2}):(\\d{2})\\s*-\\s*(\\d{1,2}):(\\d{2})\\s+" +
        "(\\w{2,8})\\s+" +
        "(.+?)\\s+" +
        "([A-Z]?\\d{1,4}[A-Z]?)\\s*" +
        "(\\w+)?"
    )

    // Pattern 2: CODE | NAME | DAY | START | END | ROOM | TYPE (pipe-separated)
    private val pattern2 = Pattern.compile(
        "(?i)(\\w{2,8})\\s*[|]\\s*" +
        "(.+?)\\s*[|]\\s*" +
        "(mon|tue|wed|thu|fri|sat|sun)\\s*[|]\\s*" +
        "(\\d{1,2}):(\\d{2})\\s*[|]\\s*" +
        "(\\d{1,2}):(\\d{2})\\s*[|]\\s*" +
        "([A-Z]?\\d{1,4}[A-Z]?)\\s*[|]?\\s*" +
        "(\\w+)?"
    )

    // Lenient fallback: day, time range (optional AM/PM), and course code in any order/position.
    // Catches: "Monday 9:00 AM - 11:00 AM CSC2043 Data Structures LT1",
    // "9:00-11:00 Mon CS101 ..." etc.
    private val dayToken = Pattern.compile(
        "(?i)\\b(mon(day)?|tue(s|day)?|wed(nesday)?|thu(r|rs|rsday)?|fri(day)?|sat(urday)?|sun(day)?)\\b"
    )
    private val timeRange = Pattern.compile(
        "(?i)\\b(\\d{1,2}):?(\\d{2})\\s*(am|pm)?\\s*[-\\u2013\\u2014to]+\\s*(\\d{1,2}):?(\\d{2})\\s*(am|pm)?\\b"
    )
    private val codeToken = Pattern.compile("\\b([A-Za-z]{2,5}\\s?\\d{3,4}[A-Za-z]?)\\b")
    private val roomToken = Pattern.compile(
        "(?i)\\b((?:r\\.?m\\.?|room|hall|lab|lt|dk|blk|block|bldg|building|venue|class)[\\s.\\-]*[\\w\\-]+|[A-Z]{1,3}-?\\d{2,4}[A-Z]?)\\b"
    )
    private val typeToken = Pattern.compile("(?i)\\b(lecture|lab(oratory)?|tutorial|practical|seminar|workshop|studio)\\b")

    fun parse(text: String): List<CourseWithSessions> {
        val courses = mutableMapOf<String, CourseWithSessions>()
        val lines = text.lines().filter { it.trim().isNotBlank() }
        var lastDay: Int? = null

        for (line in lines) {
            // Day on its own line ("MONDAY") heads the sessions below it.
            val headerDay = dayHeader(line)
            if (headerDay != null) {
                lastDay = headerDay
                continue
            }
            if (!parseStrictLine(line, courses)) {
                parseLenientLine(line, courses, lastDay)?.let { lastDay = it }
            }
        }
        return courses.values.toList()
    }

    /** Day token present but no time range: a section header, not a session. */
    private fun dayHeader(line: String): Int? {
        if (timeRange.matcher(line).find()) return null
        val m = dayToken.matcher(line)
        if (!m.find()) return null
        return dayMap[m.group(1).lowercase()]
    }

    private fun parseStrictLine(line: String, courses: MutableMap<String, CourseWithSessions>): Boolean {
        var matcher = pattern1.matcher(line)
        if (matcher.find()) {
            extractFromMatcher(matcher, courses, 1)
            return true
        }
        matcher = pattern2.matcher(line)
        if (matcher.find()) {
            extractFromMatcher(matcher, courses, 2)
            return true
        }
        return false
    }

    private fun parseLenientLine(
        line: String,
        courses: MutableMap<String, CourseWithSessions>,
        fallbackDay: Int?
    ): Int? {
        val dayMatcher = dayToken.matcher(line)
        // ponytail: dateless lines inherit the last day header; skip if none seen yet
        val explicitDay = if (dayMatcher.find()) dayMap[dayMatcher.group(1).lowercase()] else null
        val day = explicitDay ?: fallbackDay ?: return null

        val timeMatcher = timeRange.matcher(line)
        if (!timeMatcher.find()) return null
        var startH = timeMatcher.group(1).toIntOrNull() ?: return null
        val startM = timeMatcher.group(2).toIntOrNull() ?: return null
        var endH = timeMatcher.group(4).toIntOrNull() ?: return null
        val endM = timeMatcher.group(5).toIntOrNull() ?: return null
        // AM/PM: a marker on one side applies to both when the other side has none.
        val startMarker = timeMatcher.group(3)?.lowercase() ?: timeMatcher.group(6)?.lowercase()
        val endMarker = timeMatcher.group(6)?.lowercase() ?: timeMatcher.group(3)?.lowercase()
        startH = to24h(startH, startMarker)
        endH = to24h(endH, endMarker)
        if (startM > 59 || endM > 59 || startH > 23 || endH > 23) return null
        if (endH * 60 + endM <= startH * 60 + startM) return null

        val codeMatcher = codeToken.matcher(line)
        if (!codeMatcher.find()) return null
        val code = codeMatcher.group(1).replace(" ", "").uppercase()

        val typeMatcher = typeToken.matcher(line)
        val type = if (typeMatcher.find()) typeMatcher.group(1) else ""

        // Name = leftover after removing day, time, code, type tokens; room pulled out if recognizable.
        var leftover = line
        if (explicitDay != null) leftover = dayMatcher.replaceFirst(" ")
        leftover = timeMatcher.replaceFirst(" ")
        leftover = codeMatcher.replaceFirst(" ")
        if (type.isNotBlank()) leftover = leftover.replace(type, " ", ignoreCase = true)
        var room = ""
        val roomMatcher = roomToken.matcher(leftover)
        if (roomMatcher.find()) {
            room = roomMatcher.group(1).trim()
            leftover = roomMatcher.replaceFirst(" ")
        }
        val name = leftover.replace(Regex("\\s{2,}"), " ").trim(' ', '-', '|', '–', '—', ':').ifBlank { code }

        addSession(courses, code, name, Session(
            courseId = 0,
            dayOfWeek = day,
            startHour = startH,
            startMinute = startM,
            endHour = endH,
            endMinute = endM,
            room = room,
            type = type
        ))
        return explicitDay
    }

    private fun to24h(hour: Int, marker: String?): Int {
        return when (marker) {
            "pm" -> if (hour < 12) hour + 12 else hour
            "am" -> if (hour == 12) 0 else hour
            else -> hour
        }
    }

    private fun extractFromMatcher(
        matcher: java.util.regex.Matcher,
        courses: MutableMap<String, CourseWithSessions>,
        patternNum: Int
    ) {
        val dayStr = when (patternNum) {
            1 -> matcher.group(1)
            2 -> matcher.group(3)
            else -> return
        }
        val startH = when (patternNum) {
            1 -> matcher.group(2)
            2 -> matcher.group(4)
            else -> return
        }
        val startM = when (patternNum) {
            1 -> matcher.group(3)
            2 -> matcher.group(5)
            else -> return
        }
        val endH = when (patternNum) {
            1 -> matcher.group(4)
            2 -> matcher.group(6)
            else -> return
        }
        val endM = when (patternNum) {
            1 -> matcher.group(5)
            2 -> matcher.group(7)
            else -> return
        }
        val code = when (patternNum) {
            1 -> matcher.group(6)
            2 -> matcher.group(1)
            else -> return
        }
        val name = when (patternNum) {
            1 -> matcher.group(7)
            2 -> matcher.group(2)
            else -> return
        }
        val room = when (patternNum) {
            1 -> matcher.group(8)
            2 -> matcher.group(8)
            else -> return
        }
        val type = when (patternNum) {
            1 -> matcher.group(9)
            2 -> matcher.group(9)
            else -> return
        }

        val day = dayMap[dayStr.lowercase()] ?: return
        addSession(courses, code.uppercase(), name.trim(), Session(
            courseId = 0,
            dayOfWeek = day,
            startHour = startH.toIntOrNull() ?: return,
            startMinute = startM.toIntOrNull() ?: return,
            endHour = endH.toIntOrNull() ?: return,
            endMinute = endM.toIntOrNull() ?: return,
            room = room ?: "",
            type = type ?: ""
        ))
    }

    private fun addSession(
        courses: MutableMap<String, CourseWithSessions>,
        code: String,
        name: String,
        session: Session
    ) {
        val existing = courses[code] ?: CourseWithSessions(
            course = Course(code = code, name = name),
            sessions = mutableListOf()
        )
        existing.sessions.add(session)
        courses[code] = existing
    }

    data class CourseWithSessions(
        val course: Course,
        val sessions: MutableList<Session> = mutableListOf()
    )
}
