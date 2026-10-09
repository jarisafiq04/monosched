package com.example.timetableapp.data.model

data class Course(
    val id: Int = 0,
    val code: String = "",
    val name: String = "",
    val color: Int = 0xFF2196F3.toInt(),
    val lecturer: String = "",
    val lecturerPhone: String = ""
)

data class Session(
    val id: Int = 0,
    val courseId: Int,
    val dayOfWeek: Int, // 1=Mon ... 7=Sun
    val startHour: Int, // 0-23
    val startMinute: Int, // 0-59
    val endHour: Int,
    val endMinute: Int,
    val room: String = "",
    val group: String = "",
    val type: String = "" // Lecture, Lab, Tutorial
)

data class TimetableEntry(
    val course: Course,
    val sessions: List<Session>
)
