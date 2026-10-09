package com.example.timetableapp.data.repository

import android.content.Context
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import com.example.timetableapp.data.model.Course
import com.example.timetableapp.data.model.Session
import com.example.timetableapp.data.parser.GridTimetableParser
import com.example.timetableapp.data.parser.PdfParser
import com.example.timetableapp.data.parser.TimetableParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import android.net.Uri
import com.example.timetableapp.widget.TimetableWidgets

// ponytail: JSON file store instead of Room; survives restarts with zero annotation processing
class TimetableRepository private constructor(private val appContext: Context) {
    private val _courses = MutableLiveData<List<Course>>(emptyList())
    private val _sessions = MutableLiveData<List<Session>>(emptyList())

    // ponytail: synchronous in-memory truth under one lock — LiveData is view-only.
    // Writers must never read _courses/_sessions.value off the main thread: postValue
    // delivery is async, so a second write (or persist) can still see the stale list
    // and silently drop the first write.
    private val fileMutex = Mutex()
    private var coursesCache = listOf<Course>()
    private var sessionsCache = listOf<Session>()

    companion object {
        @Volatile private var INSTANCE: TimetableRepository? = null
        fun getInstance(context: Context): TimetableRepository {
            return INSTANCE ?: synchronized(this) {
                val instance = TimetableRepository(context.applicationContext)
                INSTANCE = instance
                instance
            }
        }

        private const val FILE = "timetable.json"

        // Google-Calendar-style hues, muted for dark cards
        val PALETTE = listOf(
            0xFF7A4A2B.toInt(), // cocoa (BAM3032-ish brown)
            0xFF0B6E4F.toInt(), // basil green
            0xFF1A73E8.toInt(), // calendar blue
            0xFF9334E6.toInt(), // grape
            0xFFB06000.toInt(), // tangerine
            0xFF8E24AA.toInt(), // amethyst
            0xFF00838F.toInt(), // peacock
            0xFFC2185B.toInt()  // raspberry
        )
        // ponytail: one parser for the UI store and the home-screen widgets —
        // widgets read the same file without touching LiveData or coroutines
        fun parseTimetableJson(text: String): Pair<List<Course>, List<Session>> {
            val root = JSONObject(text)
            val courses = mutableListOf<Course>()
            val sessions = mutableListOf<Session>()
            root.optJSONArray("courses")?.let { arr ->
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    courses.add(Course(
                        id = o.optInt("id"),
                        code = o.optString("code"),
                        name = o.optString("name"),
                        color = o.optInt("color", PALETTE[0]),
                        lecturer = o.optString("lecturer"),
                        lecturerPhone = o.optString("lecturerPhone")
                    ))
                }
            }
            root.optJSONArray("sessions")?.let { arr ->
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    sessions.add(Session(
                        id = o.optInt("id"),
                        courseId = o.optInt("courseId"),
                        dayOfWeek = o.optInt("dayOfWeek", 1),
                        startHour = o.optInt("startHour"),
                        startMinute = o.optInt("startMinute"),
                        endHour = o.optInt("endHour"),
                        endMinute = o.optInt("endMinute"),
                        room = o.optString("room"),
                        group = o.optString("group"),
                        type = o.optString("type")
                    ))
                }
            }
            return courses to sessions
        }
    }

    fun getAllCourses(): LiveData<List<Course>> = _courses
    fun getAllSessions(): LiveData<List<Session>> = _sessions

    // ponytail: sync snapshots for the boot receiver (LiveData can't cross that hop)
    fun snapshotCourses(): List<Course> = coursesCache.toList()
    fun snapshotSessions(): List<Session> = sessionsCache.toList()

    suspend fun load() = withContext(Dispatchers.IO) {
        // ponytail: whole read-parse-publish under one lock so no writer can slip between
        fileMutex.withLock {
            try {
                val f = java.io.File(appContext.filesDir, FILE)
                if (!f.exists()) return@withLock
                val (courses, sessions) = parseTimetableJson(f.readText())
                _courses.postValue(courses)
                _sessions.postValue(sessions)
                coursesCache = courses
                sessionsCache = sessions
            } catch (t: Throwable) {
                android.util.Log.e("TimetableApp", "load failed", t)
            }
        }
    }

    private suspend fun persistContent(
        courses: List<Course>,
        sessions: List<Session>
    ) = withContext(Dispatchers.IO) { persistContentNow(courses, sessions) }

    // ponytail: write the exact data we just parsed, NOT a read-back of the
    // LiveData value — postValue() from a background thread is async, so reading
    // _sessions.value right after postValue could still be the stale/empty list.
    private fun persistContentNow(courses: List<Course>, sessions: List<Session>) {
        try {
            val root = JSONObject()
            root.put("courses", JSONArray().apply {
                courses.forEach { c ->
                    put(JSONObject()
                        .put("id", c.id).put("code", c.code).put("name", c.name)
                        .put("color", c.color).put("lecturer", c.lecturer)
                        .put("lecturerPhone", c.lecturerPhone))
                }
            })
            root.put("sessions", JSONArray().apply {
                sessions.forEach { s ->
                    put(JSONObject()
                        .put("id", s.id).put("courseId", s.courseId)
                        .put("dayOfWeek", s.dayOfWeek).put("startHour", s.startHour)
                        .put("startMinute", s.startMinute)
                        .put("endHour", s.endHour).put("endMinute", s.endMinute)
                        .put("room", s.room).put("group", s.group).put("type", s.type))
                }
            })
            java.io.File(appContext.filesDir, FILE).writeText(root.toString())
            TimetableWidgets.refresh(appContext)
        } catch (t: Throwable) {
            android.util.Log.e("TimetableApp", "persist failed", t)
        }
    }

    /** Serialize current in-memory state (used by edits/deletes). Call only with fileMutex held. */
    private fun persistLocked() {
        persistContentNow(coursesCache, sessionsCache)
    }

    private fun nextCourseIdLocked() = (coursesCache.maxOfOrNull { it.id } ?: 0) + 1
    private fun nextSessionIdLocked() = (sessionsCache.maxOfOrNull { it.id } ?: 0) + 1

    suspend fun importFromPdf(context: Context, uri: Uri): ImportResult {
        return withContext(Dispatchers.IO) {
            try {
                // Grid timetables (days as columns) need positioned words; fall back to line text.
                val grid = try {
                    PdfParser.parseGrid(context, uri)
                } catch (t: Throwable) {
                    null
                }
                val parsed: List<TimetableParser.CourseWithSessions> = if (grid != null && grid.sessions.isNotEmpty()) {
                    gridToCourses(grid)
                } else {
                    val text = PdfParser.extractText(context, uri)
                    TimetableParser.parse(text)
                }

                if (parsed.isEmpty()) {
                    return@withContext ImportResult.Error("No timetable data found. If the preview text is empty, the PDF is likely a scanned image.")
                }

                var colorIdx = 0
                var nextCourseId = 1
                var nextSessionId = 1
                val courses = mutableListOf<Course>()
                val sessions = mutableListOf<Session>()
                for (item in parsed) {
                    // ponytail: parsers don't pick colors; deal palette order here
                    val course = item.course.copy(
                        id = nextCourseId++,
                        color = PALETTE[colorIdx++ % PALETTE.size]
                    )
                    courses.add(course)
                    for (s in item.sessions) {
                        sessions.add(s.copy(id = nextSessionId++, courseId = course.id))
                    }
                }
                _courses.postValue(courses)
                _sessions.postValue(sessions.sortedWith(compareBy({ it.dayOfWeek }, { it.startHour }, { it.startMinute })))
                // ponytail: publish + persist the exact imported lists under the lock —
                // never a read-back of LiveData (postValue delivery is async).
                fileMutex.withLock {
                    coursesCache = courses
                    sessionsCache = sessions
                    persistContentNow(courses, sessions)
                }

                ImportResult.Success(courses.size)
            } catch (t: Throwable) {
                android.util.Log.e("TimetableApp", "import failed", t)
                ImportResult.Error("Failed to import: ${t.javaClass.simpleName}: ${t.message}")
            }
        }
    }

    suspend fun upsertCourse(course: Course) = withContext(Dispatchers.IO) {
        fileMutex.withLock {
            val list = coursesCache.toMutableList()
            if (course.id == 0) {
                list.add(course.copy(id = nextCourseIdLocked()))
            } else {
                val i = list.indexOfFirst { it.id == course.id }
                if (i >= 0) list[i] = course else list.add(course)
            }
            coursesCache = list
            _courses.postValue(list)
            persistLocked()
        }
    }

    suspend fun upsertSession(session: Session) = withContext(Dispatchers.IO) {
        fileMutex.withLock {
            val list = sessionsCache.toMutableList()
            if (session.id == 0) {
                list.add(session.copy(id = nextSessionIdLocked()))
            } else {
                val i = list.indexOfFirst { it.id == session.id }
                if (i >= 0) list[i] = session else list.add(session)
            }
            sessionsCache = list.sortedWith(compareBy({ it.dayOfWeek }, { it.startHour }, { it.startMinute }))
            _sessions.postValue(sessionsCache)
            persistLocked()
        }
    }

    suspend fun deleteSession(sessionId: Int) = withContext(Dispatchers.IO) {
        fileMutex.withLock {
            sessionsCache = sessionsCache.filterNot { it.id == sessionId }
            _sessions.postValue(sessionsCache)
            persistLocked()
        }
    }

    /** Merge grid sessions (one record per course+group row) into courses keyed by code. */
    private fun gridToCourses(grid: GridTimetableParser.GridResult): List<TimetableParser.CourseWithSessions> {
        val byCode = linkedMapOf<String, TimetableParser.CourseWithSessions>()
        for (s in grid.sessions) {
            val info = grid.info[s.code]
            val item = byCode[s.code] ?: TimetableParser.CourseWithSessions(
                course = Course(
                    code = s.code,
                    name = info?.name ?: s.code,
                    lecturer = info?.lecturer ?: "",
                    lecturerPhone = info?.phone ?: ""
                ),
                sessions = mutableListOf()
            )
            item.sessions.add(Session(
                courseId = 0,
                dayOfWeek = s.day,
                startHour = s.startH,
                startMinute = s.startM,
                endHour = s.endH,
                endMinute = s.endM,
                room = s.room,
                group = s.group,
                type = ""
            ))
            byCode[s.code] = item
        }
        return byCode.values.toList()
    }
}

sealed interface ImportResult {
    data class Success(val courseCount: Int) : ImportResult
    data class Error(val message: String) : ImportResult
}
