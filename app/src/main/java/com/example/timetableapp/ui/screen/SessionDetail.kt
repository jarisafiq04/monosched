package com.example.timetableapp.ui.screen

import android.content.Context
import android.content.Intent
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Chat
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Group
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Phone
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.example.timetableapp.data.model.Course
import com.example.timetableapp.data.model.Session

val DAY_NAMES = arrayOf("", "Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday", "Sunday")

fun fmtTime(h: Int, m: Int) = "%02d:%02d".format(h, m)

/** wa.me links: phone numbers as international digits, usernames as-is. */
fun whatsappUrl(raw: String): String? {
    val digits = raw.filter { it.isDigit() }
    if (digits.length >= 9 && raw.none { it.isLetter() }) {
        val intl = when {
            digits.startsWith("60") -> digits
            digits.startsWith("0") -> "60" + digits.drop(1)
            else -> digits
        }
        return "https://wa.me/$intl"
    }
    // ponytail: WhatsApp username (no number needed); wa.me resolves those too
    val name = raw.trim().removePrefix("@").replace(" ", "")
    if (name.matches(Regex("^[A-Za-z][A-Za-z0-9._]{2,29}$"))) return "https://wa.me/$name"
    return null
}

fun openWhatsApp(context: Context, rawPhone: String) {
    val url = whatsappUrl(rawPhone) ?: return
    try {
        context.startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse(url)))
    } catch (_: Exception) {
        // ponytail: no WhatsApp/browser installed; silently ignore
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SessionSheet(
    session: Session,
    course: Course?,
    onDismiss: () -> Unit,
    onEditSession: () -> Unit,
    onEditCourse: () -> Unit,
    onDelete: () -> Unit,
    onWhatsApp: () -> Unit
) {
    var confirmDelete by remember { mutableStateOf(false) }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(), shape = RectangleShape) {
        Column(modifier = Modifier.padding(horizontal = 24.dp).padding(bottom = 32.dp)) {
            Text(
                ((course?.code?.takeIf { it.isNotBlank() }) ?: course?.name ?: "Class") + (course?.name?.let { " · $it" } ?: ""),
                style = MaterialTheme.typography.titleLarge
            )
            Spacer(Modifier.height(16.dp))
            DetailRow(Icons.Default.Schedule, "${DAY_NAMES[session.dayOfWeek]}, ${fmtTime(session.startHour, session.startMinute)} – ${fmtTime(session.endHour, session.endMinute)}")
            if (session.room.isNotBlank()) DetailRow(Icons.Default.Place, "Venue · ${session.room}")
            if (session.group.isNotBlank()) DetailRow(Icons.Default.Group, "Group · ${session.group}")
            if (session.type.isNotBlank()) DetailRow(Icons.Default.Edit, "Type · ${session.type}")
            if (course?.lecturer?.isNotBlank() == true) DetailRow(Icons.Default.Person, course.lecturer)
            if (course?.lecturerPhone?.isNotBlank() == true) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                    DetailRow(Icons.Default.Phone, course.lecturerPhone, modifier = Modifier.weight(1f))
                    if (whatsappUrl(course.lecturerPhone) != null) {
                        FilledTonalButton(onClick = onWhatsApp, shape = RectangleShape) {
                        Icon(Icons.Default.Chat, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text("WhatsApp")
                    }
                    }
                }
            }
            Spacer(Modifier.height(20.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedButton(onClick = onEditSession, modifier = Modifier.weight(1f).height(48.dp), shape = RectangleShape) { Text("Edit class") }
                OutlinedButton(onClick = onEditCourse, modifier = Modifier.weight(1f).height(48.dp), shape = RectangleShape) { Text("Edit Info") }
                Box(
                    modifier = Modifier
                        .width(48.dp)
                        .height(48.dp)
                        .border(1.dp, MaterialTheme.colorScheme.error, RectangleShape)
                        .clickable(onClick = { confirmDelete = true }),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(Icons.Default.Delete, contentDescription = "Delete", tint = MaterialTheme.colorScheme.error)
                }
            }
            if (confirmDelete) {
                AlertDialog(
                    onDismissRequest = { confirmDelete = false },
                    shape = RectangleShape,
                    title = { Text("Delete this class?") },
                    text = { Text("${DAY_NAMES[session.dayOfWeek]}, ${fmtTime(session.startHour, session.startMinute)} \u2013 ${fmtTime(session.endHour, session.endMinute)}") },
                    confirmButton = {
                        Button(
                            shape = RectangleShape,
                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                            onClick = { confirmDelete = false; onDelete() }
                        ) { Text("Delete") }
                    },
                    dismissButton = { TextButton(onClick = { confirmDelete = false }, shape = RectangleShape) { Text("Cancel") } }
                )
            }
        }
    }
}

@Composable
private fun DetailRow(icon: ImageVector, text: String, modifier: Modifier = Modifier) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = modifier.padding(vertical = 6.dp)) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.width(16.dp))
        Text(text, style = MaterialTheme.typography.bodyLarge)
    }
}

@Composable
fun CourseEditDialog(
    course: Course?,
    onDismiss: () -> Unit,
    onSave: (Course) -> Unit
) {
    var name by remember { mutableStateOf(course?.name ?: "") }
    var lecturer by remember { mutableStateOf(course?.lecturer ?: "") }
    var phone by remember { mutableStateOf(course?.lecturerPhone ?: "") }
    AlertDialog(
        onDismissRequest = onDismiss,
        shape = RectangleShape,
        title = { Text(if (course == null) "New course" else "Edit Info") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(name, { name = it }, label = { Text("Subject name") }, singleLine = true, shape = RectangleShape)
                OutlinedTextField(lecturer, { lecturer = it }, label = { Text("Lecturer") }, singleLine = true, shape = RectangleShape)
                OutlinedTextField(phone, { phone = it }, label = { Text("Number/Username") }, singleLine = true, shape = RectangleShape)
            }
        },
        confirmButton = {
            Button(
                shape = RectangleShape,
                onClick = { onSave((course ?: Course()).copy(name = name.trim(), lecturer = lecturer.trim(), lecturerPhone = phone.trim())) },
                enabled = name.isNotBlank()
            ) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss, shape = RectangleShape) { Text("Cancel") } }
    )
}

private val TIME_RE = Regex("^([01]?\\d|2[0-3]):([0-5]\\d)$|^24:([0-5]\\d)$")

@Composable
fun SessionEditDialog(
    session: Session,
    courses: List<Course>,
    onDismiss: () -> Unit,
    onSave: (Session) -> Unit
) {
    var courseId by remember { mutableStateOf(session.courseId.takeIf { it != 0 } ?: courses.firstOrNull()?.id ?: 0) }
    var day by remember { mutableStateOf(session.dayOfWeek.coerceIn(1, 7)) }
    var start by remember { mutableStateOf(fmtTime(session.startHour, session.startMinute)) }
    var end by remember { mutableStateOf(fmtTime(session.endHour, session.endMinute)) }
    var room by remember { mutableStateOf(session.room) }
    var group by remember { mutableStateOf(session.group) }
    var type by remember { mutableStateOf(session.type) }
    var dayMenu by remember { mutableStateOf(false) }
    var courseMenu by remember { mutableStateOf(false) }

    // ponytail: 24:xx matches with the minutes in group 3
    fun parts(m: MatchResult?): Pair<Int, Int>? {
        if (m == null) return null
        val g = m.groupValues
        return if (g[1].isEmpty()) 24 to g[3].toInt() else g[1].toInt() to g[2].toInt()
    }
    val startP = parts(TIME_RE.matchEntire(start.trim()))
    val endP = parts(TIME_RE.matchEntire(end.trim()))
    val startM = startP != null
    val endM = endP != null
    // ponytail: end <= start runs overnight (duration wraps past midnight)
    val durMin = if (startP != null && endP != null)
        (endP.first * 60 + endP.second - (startP.first * 60 + startP.second) + 1440) % 1440 else 0
    val valid = courseId != 0 && durMin != 0

    AlertDialog(
        onDismissRequest = onDismiss,
        shape = RectangleShape,
        title = { Text(if (session.id == 0) "New class" else "Edit class") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                    Text("Course:", modifier = Modifier.width(64.dp), style = MaterialTheme.typography.bodyMedium)
                    Column {
                        OutlinedButton(onClick = { courseMenu = true }, shape = RectangleShape) {
                            Text(courses.firstOrNull { it.id == courseId }?.let { c -> if (c.code.isNotBlank()) c.code else c.name } ?: "Pick course")
                        }
                        DropdownMenu(courseMenu, onDismissRequest = { courseMenu = false }) {
                            courses.forEach { c ->
                                DropdownMenuItem(
                                    text = { Text(if (c.code.isNotBlank()) "${c.code} · ${c.name}" else c.name) },
                                    onClick = { courseId = c.id; courseMenu = false }
                                )
                            }
                        }
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Day:", modifier = Modifier.width(64.dp), style = MaterialTheme.typography.bodyMedium)
                    Column {
                        OutlinedButton(onClick = { dayMenu = true }, shape = RectangleShape) { Text(DAY_NAMES[day]) }
                        DropdownMenu(dayMenu, onDismissRequest = { dayMenu = false }) {
                            (1..7).forEach { d ->
                                DropdownMenuItem(text = { Text(DAY_NAMES[d]) }, onClick = { day = d; dayMenu = false })
                            }
                        }
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(start, { start = it }, label = { Text("Start") }, placeholder = { Text("09:00") }, singleLine = true, shape = RectangleShape, modifier = Modifier.weight(1f), isError = !startM)
                    OutlinedTextField(end, { end = it }, label = { Text("End") }, placeholder = { Text("10:30") }, singleLine = true, shape = RectangleShape, modifier = Modifier.weight(1f), isError = !endM)
                }
                OutlinedTextField(room, { room = it }, label = { Text("Venue") }, singleLine = true, shape = RectangleShape)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(group, { group = it }, label = { Text("Group") }, singleLine = true, shape = RectangleShape, modifier = Modifier.weight(1f))
                    OutlinedTextField(type, { type = it }, label = { Text("Type") }, singleLine = true, shape = RectangleShape, modifier = Modifier.weight(1f))
                }
            }
        },
        confirmButton = {
            Button(
                shape = RectangleShape,
                onClick = {
                    val sp = startP!!; val ep = endP!!
                    onSave(session.copy(
                        courseId = courseId, dayOfWeek = day,
                        startHour = sp.first, startMinute = sp.second,
                        endHour = ep.first, endMinute = ep.second,
                        room = room.trim(), group = group.trim(), type = type.trim()
                    ))
                },
                enabled = valid
            ) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss, shape = RectangleShape) { Text("Cancel") } }
    )
}
