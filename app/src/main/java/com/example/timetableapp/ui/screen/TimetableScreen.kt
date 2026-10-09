package com.example.timetableapp.ui.screen

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Today
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.LocalMinimumInteractiveComponentEnforcement
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.Button
import androidx.compose.foundation.BorderStroke
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.ButtonDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.livedata.observeAsState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue

import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import com.example.timetableapp.ui.theme.Monument
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.roundToInt
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.launch
import com.example.timetableapp.data.model.Course
import com.example.timetableapp.data.model.Session
import com.example.timetableapp.ui.viewmodel.TimetableViewModel
import java.util.Calendar

// ponytail: overnight classes (end <= start) run past midnight — live in the
// evening of their day and the morning of the next
internal fun isNowAt(session: Session, today: Int, nowMinutes: Int): Boolean {
    val s = session.startHour * 60 + session.startMinute
    val e = session.endHour * 60 + session.endMinute
    if (e == s) return false
    return if (e > s) {
        session.dayOfWeek == today && nowMinutes in s until e
    } else {
        (session.dayOfWeek == today && nowMinutes >= s) ||
            (session.dayOfWeek % 7 + 1 == today && nowMinutes < e)
    }
}

private fun todayDow(): Int {
    // ponytail: Calendar.SUNDAY=1 → convert to Mon=1..Sun=7
    val c = Calendar.getInstance().get(Calendar.DAY_OF_WEEK)
    return (c + 5) % 7 + 1
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TimetableScreen(initialUri: android.net.Uri? = null) {
    val viewModel: TimetableViewModel = viewModel()
    val context = LocalContext.current
    val courses: List<Course> by viewModel.allCourses.observeAsState(emptyList())
    val sessions: List<Session> by viewModel.allSessions.observeAsState(emptyList())

    val sessionsByDay = remember(sessions) { sessions.groupBy { it.dayOfWeek } }
    val courseMap = remember(courses) { courses.associateBy { it.id } }
    // ponytail: live clock — the running-class border flips the minute a class
    // starts or ends, no reopen needed; aligned to the minute boundary
    var today by remember { mutableStateOf(todayDow()) }
    var nowMinutes by remember {
        mutableStateOf(Calendar.getInstance().let { it.get(Calendar.HOUR_OF_DAY) * 60 + it.get(Calendar.MINUTE) })
    }
    LaunchedEffect(Unit) {
        // ponytail: ask exact-alarm access once on first open so reminders fire on time
        if (android.os.Build.VERSION.SDK_INT >= 31) {
            val am = context.getSystemService(android.app.AlarmManager::class.java)
            val prefs = context.getSharedPreferences("timetable_settings", android.content.Context.MODE_PRIVATE)
            if (am?.canScheduleExactAlarms() == false && !prefs.getBoolean("asked_exact", false)) {
                prefs.edit().putBoolean("asked_exact", true).apply()
                try {
                    context.startActivity(android.content.Intent(
                        android.provider.Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM,
                        android.net.Uri.parse("package:${context.packageName}")
                    ))
                } catch (_: Exception) {
                }
            }
        }
        while (true) {
            kotlinx.coroutines.delay(60_000 - System.currentTimeMillis() % 60_000)
            nowMinutes = Calendar.getInstance().let { it.get(Calendar.HOUR_OF_DAY) * 60 + it.get(Calendar.MINUTE) }
            today = todayDow()
        }
    }

    var selected by remember { mutableStateOf<Session?>(null) }
    var editingSession by remember { mutableStateOf<Session?>(null) }
    var editingCourse by remember { mutableStateOf<Course?>(null) }
    // ponytail: table is the main page; header toggles back to the full grid
    var showList by remember { mutableStateOf(true) }
    var showSettings by remember { mutableStateOf(false) }
    var leadMin by remember {
        mutableStateOf(com.example.timetableapp.notifications.ReminderScheduler.loadLeadMin(context))
    }
    val importStatus by viewModel.importStatus.observeAsState(TimetableViewModel.ImportStatus.Idle)
    var welcomed by remember {
        mutableStateOf(
            context.getSharedPreferences("timetable_settings", android.content.Context.MODE_PRIVATE)
                .getBoolean("welcomed", false)
        )
    }
    // ponytail: a good import ends the first run — the welcome screen never returns
    LaunchedEffect(importStatus) {
        if (importStatus is TimetableViewModel.ImportStatus.Success && !welcomed) {
            welcomed = true
            context.getSharedPreferences("timetable_settings", android.content.Context.MODE_PRIVATE)
                .edit().putBoolean("welcomed", true).apply()
        }
    }
    // ponytail: first run is a bare page — no header, no footer, just the welcome
    val showWelcome = !showSettings && !welcomed && courses.isEmpty()

    val dayNames = arrayOf("", "Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun")
    val startHour = 1
    val endHour = 24
    val hourSpan = endHour - startHour
    val hourHeight = 60.dp
    val gridHeight = hourHeight * hourSpan
    val dayWidth = 110.dp
    val gutterWidth = 40.dp

    // ponytail: one shared vertical state drives gutter + grid (sticky)
    val vState = rememberScrollState()
    // ponytail: hoisted so the today-FAB and the grid share one horizontal state
    val hState = rememberScrollState()
    val listState = rememberLazyListState()
    // ponytail: table jump pill — only while today is off-screen;
    // up arrow when today sits above the viewport, down when below
    val todayIdx = (today - 1).coerceIn(0, 6)
    val jumpVis by remember {
        derivedStateOf {
            // ponytail: firstVisibleItemIndex subscribes this to scrolls;
            // visibleItemsInfo is then read fresh from the latest layout
            if (listState.firstVisibleItemIndex < 0) emptyList()
            else listState.layoutInfo.visibleItemsInfo.map { it.index }
        }
    }
    val showTodayJump = showList && !showSettings &&
        jumpVis.isNotEmpty() && todayIdx !in (jumpVis.minOrNull() ?: 0)..(jumpVis.maxOrNull() ?: 0)
    val jumpUp = (jumpVis.firstOrNull() ?: 0) > todayIdx
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()

    // ponytail: class reminders re-arm on every open, data change, and lead change
    LaunchedEffect(sessions, courses, leadMin) {
        com.example.timetableapp.notifications.ReminderScheduler.refresh(context, sessions, courses)
    }
    androidx.activity.compose.BackHandler(enabled = showSettings) { showSettings = false }

    val pickFile = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: android.net.Uri? ->
        uri?.let { viewModel.importTimetable(it) }
    }
    // ponytail: files shared/opened into the app import straight into the timetable (no Import tab)
    LaunchedEffect(initialUri) {
        initialUri?.let { viewModel.importTimetable(it) }
    }

    Scaffold(
        topBar = {
            if (!showWelcome) {
            // ponytail: slim title bar — Timetable left, view switch right
            Row(
                modifier = Modifier.fillMaxWidth()
                    .background(MaterialTheme.colorScheme.background)
                    .windowInsetsPadding(WindowInsets.statusBars)
                    .padding(horizontal = 12.dp).height(56.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    if (showSettings) "Settings" else "Timetable",
                    style = MaterialTheme.typography.titleLarge.copy(fontFamily = Monument, lineHeight = 26.sp)
                )
                if (!showSettings) {
                    FooterButton(
                        description = if (showList) "Grid view" else "List view",
                        onClick = { showSettings = false; showList = !showList }
                    ) {
                        Icon(
                            Icons.Default.Refresh,
                            contentDescription = if (showList) "Grid view" else "List view",
                            modifier = Modifier.size(22.dp)
                        )
                    }
                }
            }
        
            }},
        // ponytail: footer toolbar — import, add class, calendar/table toggle
        bottomBar = {
            if (!showWelcome) {
            // ponytail: same color as the backdrop, lifted clear of the system nav
            // bar so the icons sit in the optical middle instead of on the edge
            Surface(color = MaterialTheme.colorScheme.background) {
                Row(
                    // ponytail: buttons hug content, 12dp lift clear of the bottom edge
                    modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    CompositionLocalProvider(LocalMinimumInteractiveComponentEnforcement provides false) {
                    FooterButton(
                        description = "Settings",
                        onClick = { showSettings = !showSettings },
                        active = showSettings
                    ) {
                        Icon(Icons.Default.Settings, contentDescription = "Settings", modifier = Modifier.size(22.dp))
                    }
                    FooterButton(description = "Add class", onClick = {
                        showSettings = false
                        if (courses.isEmpty()) {
                            editingCourse = Course()
                        } else {
                            editingSession = Session(courseId = courseMap.keys.first(), dayOfWeek = today, startHour = 9, startMinute = 0, endHour = 10, endMinute = 0)
                        }
                    }) {
                        Icon(Icons.Default.Add, contentDescription = "Add", modifier = Modifier.size(22.dp))
                    }
                    FooterButton(
                        description = "Timetable",
                        onClick = { showSettings = false; showList = true },
                        active = showList && !showSettings
                    ) {
                        Icon(
                            Icons.Default.List,
                            contentDescription = "Timetable",
                            modifier = Modifier.size(22.dp)
                        )
                    }
                    }
                }
            }
        
            }},
        // ponytail: grid jump floats bottom-right (table has its own centered pill)
        floatingActionButton = {
            if (courses.isNotEmpty() && !showList && !showSettings) {
                FloatingActionButton(
                    onClick = {
                        val px = with(density) { ((today - 1) * (dayWidth + 4.dp).toPx()).toInt() }
                        scope.launch { hState.animateScrollTo(px) }
                    },
                    shape = RectangleShape,
                    modifier = Modifier.offset(y = (-8).dp)
                ) {
                    Icon(Icons.Default.Today, contentDescription = "Go to today")
                }
            }
        },
    ) { inner ->
        Column(modifier = Modifier.padding(inner).fillMaxSize()) {
            if (showSettings) {
                // ponytail: same page margins as the timetable, text off the edges
                Box(modifier = Modifier.fillMaxSize().padding(horizontal = 12.dp, vertical = 16.dp)) {
                SettingsScreen(
                    leadMin = leadMin,
                    onLeadPick = {
                        com.example.timetableapp.notifications.ReminderScheduler.saveLeadMin(context, it)
                        leadMin = it
                    },
                    importMsg = importMessage(importStatus),
                    onImport = { pickFile.launch(arrayOf("*/*")) },
                )
                }
            } else if (!welcomed && courses.isEmpty()) {
                WelcomeScreen(
                    onImport = { pickFile.launch(arrayOf("*/*")) },
                    onContinue = {
                        welcomed = true
                        context.getSharedPreferences("timetable_settings", android.content.Context.MODE_PRIVATE)
                            .edit().putBoolean("welcomed", true).apply()
                    }
                )
            } else if (courses.isEmpty()) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        Text("No timetable yet", style = MaterialTheme.typography.titleLarge)
                        Text("Import a file or tap + to start", style = MaterialTheme.typography.bodyMedium)
                    }
                }
            } else {
                // ponytail: shared scroll states pin the day header (vertical)
                // and the time gutter (horizontal); dragging either side moves both
                // ponytail: open on today, not Monday — jump the grid so today's column is first visible
                // ponytail: open on today at 8:00 — grid measures late, retry until both sticks land
                LaunchedEffect(Unit) {
                    val hx = with(density) { ((today - 1) * (dayWidth + 4.dp).toPx()).toInt() }
                    val vx = with(density) { ((8 - startHour) * hourHeight.toPx()).toInt() }
                    repeat(20) {
                        if (hState.value == hx && vState.value == vx) return@LaunchedEffect
                        hState.scrollTo(hx)
                        vState.scrollTo(vx)
                        kotlinx.coroutines.delay(100)
                    }
                }
                Box(modifier = Modifier.fillMaxSize().padding(horizontal = 12.dp, vertical = 16.dp)) {
                    Column(modifier = Modifier.fillMaxSize()) {
                        if (showList) {
                            SessionListView(
                                sessions = sessions,
                                courseMap = courseMap,
                                today = today,
                                nowMinutes = nowMinutes,
                                listState = listState,
                                onSelect = { selected = it }
                            )
                        } else {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Box(modifier = Modifier.width(gutterWidth).height(40.dp))
                            Row(
                                modifier = Modifier
                                    .weight(1f)
                                    .horizontalScroll(hState),
                                horizontalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                            for (day in 1..7) {
                                val isToday = day == today
                                Box(
                                    modifier = Modifier
                                        .width(dayWidth)
                                        .height(40.dp)
                                        .clip(RectangleShape)
                                        .background(
                                            if (isToday) MaterialTheme.colorScheme.primary
                                            else MaterialTheme.colorScheme.primaryContainer
                                        )
                                        .padding(4.dp)
                                ) {
                                    Text(
                                        dayNames[day],
                                        style = MaterialTheme.typography.labelLarge.copy(
                                            fontWeight = if (isToday) FontWeight.Bold else FontWeight.Normal
                                        ),
                                        color = if (isToday) MaterialTheme.colorScheme.onPrimary
                                        else MaterialTheme.colorScheme.onPrimaryContainer,
                                        textAlign = TextAlign.Center,
                                        modifier = Modifier.fillMaxSize().wrapContentSize(Alignment.Center)
                                    )
                                }
                            }
                            }
                        }
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .weight(1f),
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            // time gutter: pinned horizontally, scrolls vertically with the grid.
                            // top spacer keeps the first label (shifted up half its height) unclipped.
                            Column(modifier = Modifier.verticalScroll(vState).padding(top = 8.dp)) {
                                for (hour in startHour..endHour) {
                                    // ponytail: two markers per hour — the :00 label sits at the
                                    // top of its row; the :30 marker at the exact vertical midpoint
                                    // (half an hour down), drawn dark grey so hours stay clearly
                                    // separated from the half-hours.
                                    Box(modifier = Modifier.width(gutterWidth).height(hourHeight).padding(start = 2.dp)) {
                                        Text(
                                            "%02d:00".format(hour),
                                            style = MaterialTheme.typography.bodySmall.copy(
                                                fontWeight = if (hour == nowMinutes / 60) FontWeight.Bold else FontWeight.Normal
                                            ),
                                            color = if (hour == nowMinutes / 60) MaterialTheme.colorScheme.primary
                                            else MaterialTheme.colorScheme.onSurfaceVariant,
                                            modifier = Modifier.align(Alignment.TopStart).offset(y = (-8).dp)
                                        )
                                        // ponytail: last box reads 24:30 — half past midnight slot
                                        Text(
                                            "%02d:30".format(hour),
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.outline,
                                            modifier = Modifier.align(Alignment.TopStart).offset(y = hourHeight / 2 - 8.dp)
                                        )
                                    }
                                }
                            }
                            // ponytail: day grid scrolls both ways in sync with header + gutter
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .verticalScroll(vState)
                                    .horizontalScroll(hState)
                            ) {
                            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            for (day in 1..7) {
                                val daySessions = sessionsByDay[day] ?: emptyList()
                                Box(
                                    modifier = Modifier
                                        .width(dayWidth)
                                        .height(gridHeight + hourHeight + 8.dp)
                                        .background(MaterialTheme.colorScheme.surface)
                                        .padding(top = 8.dp)
                                ) {
                                    Column {
                                        for (hour in startHour..endHour) {
                                            Box(modifier = Modifier.width(dayWidth).height(hourHeight)) {
                                                androidx.compose.material3.HorizontalDivider(
                                                    color = MaterialTheme.colorScheme.outline.copy(alpha = 0.4f)
                                                )
                                            }
                                        }
                                    }
                                    daySessions.forEach { session ->
                                        val startMin = (session.startHour - startHour) * 60 + session.startMinute
                                        val rawEnd = (session.endHour - startHour) * 60 + session.endMinute
                                        val endMin = if (rawEnd <= startMin) hourSpan * 60 else rawEnd
                                        // ponytail: one extra hour below midnight hosts 24:xx ends
                                        val renderMax = hourSpan * 60 + 60
                                        if (endMin <= 0 || startMin >= renderMax) return@forEach
                                        val yOff = startMin.coerceAtLeast(0)
                                        val h = (endMin.coerceAtMost(renderMax) - yOff).coerceAtLeast(30)
                                        Box(
                                            modifier = Modifier
                                                .offset(y = (yOff * hourHeight.value / 60f).dp)
                                                .width(dayWidth)
                                                .height((h * hourHeight.value / 60f).dp)
                                                .padding(2.dp)
                                        ) {
                                            SessionBlock(
                                                course = courseMap[session.courseId],
                                                isNow = isNowAt(session, today, nowMinutes),
                                                onClick = { selected = session }
                                            )
                                        }
                                    }
                                    // ponytail: morning spill of previous day's overnight classes
                                    (sessionsByDay[if (day == 1) 7 else day - 1] ?: emptyList()).forEach { session ->
                                        val s0 = (session.startHour - startHour) * 60 + session.startMinute
                                        val e0 = (session.endHour - startHour) * 60 + session.endMinute
                                        if (e0 <= s0 && e0 > 0) {
                                            Box(
                                                modifier = Modifier
                                                    .width(dayWidth)
                                                    .height((e0.coerceAtLeast(30) * hourHeight.value / 60f).dp)
                                                    .padding(2.dp)
                                            ) {
                                                SessionBlock(
                                                        course = courseMap[session.courseId],
                                                    isNow = isNowAt(session, today, nowMinutes),
                                                    onClick = { selected = session }
                                                )
                                            }
                                        }
                                    }
                                    // ponytail: live now-line across today's column, time pill riding mid-line
                                    if (day == today) {
                                        val relNow = nowMinutes - startHour * 60
                                        if (relNow >= 0) {
                                            Row(
                                                modifier = Modifier
                                                    .offset(y = (relNow * hourHeight.value / 60f).dp - 9.dp)
                                                    .width(dayWidth),
                                                verticalAlignment = Alignment.CenterVertically
                                            ) {
                                                Box(
                                                    modifier = Modifier.weight(1f).height(2.dp)
                                                        .background(MaterialTheme.colorScheme.primary)
                                                )
                                                Box(
                                                    modifier = Modifier.background(MaterialTheme.colorScheme.primary)
                                                        .padding(horizontal = 6.dp, vertical = 1.dp),
                                                    contentAlignment = Alignment.Center
                                                ) {
                                                    Text(
                                                        "%02d:%02d".format(nowMinutes / 60, nowMinutes % 60),
                                                        style = MaterialTheme.typography.labelSmall.copy(
                                                            fontWeight = FontWeight.Bold
                                                        ),
                                                        color = MaterialTheme.colorScheme.onPrimary
                                                    )
                                                }
                                                Box(
                                                    modifier = Modifier.weight(1f).height(2.dp)
                                                        .background(MaterialTheme.colorScheme.primary)
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                            }
                            }
                        }
                        }
                    }
                    // ponytail: table jump pill — bottom-center, only while today is off-screen
                    if (showList && !showSettings && showTodayJump) {
                        Box(
                            modifier = Modifier.fillMaxSize(),
                            contentAlignment = Alignment.BottomCenter
                        ) {
                            SmallFloatingActionButton(
                                onClick = { scope.launch { listState.scrollToItem(todayIdx) } },
                                modifier = Modifier.padding(bottom = 16.dp)
                            ) {
                                Icon(
                                    if (jumpUp) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                                    contentDescription = "Go to today"
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    // ponytail: the sheet only opens for sessions still in data — a delete or
    // re-import between tap and render must not strand a stale sheet
    val sel = selected?.takeIf { s -> sessions.any { it.id == s.id } }
    if (sel != null) {
        val course = courseMap[sel.courseId]
        SessionSheet(
            session = sel,
            course = course,
            onDismiss = { selected = null },
            onEditSession = { editingSession = sel; selected = null },
            onEditCourse = { editingCourse = course ?: Course(); selected = null },
            onDelete = { viewModel.deleteSession(sel.id); selected = null },
            onWhatsApp = { course?.lecturerPhone?.let { openWhatsApp(context, it) } }
        )
    }
    editingSession?.let { s ->
        SessionEditDialog(
            session = s,
            courses = courses,
            onDismiss = { editingSession = null },
            onSave = { viewModel.saveSession(it); editingSession = null }
        )
    }
    editingCourse?.let { c ->
        val existing = c.takeIf { it.id != 0 }
        CourseEditDialog(
            course = existing,
            onDismiss = { editingCourse = null },
            onSave = {
                // ponytail: new courses deal the next palette color; edits keep theirs
                val toSave = if (existing == null) {
                    val palette = com.example.timetableapp.data.repository.TimetableRepository.PALETTE
                    it.copy(color = palette[courses.size % palette.size])
                } else {
                    it.copy(id = c.id, color = c.color)
                }
                viewModel.saveCourse(toSave)
                editingCourse = null
            },
        )
    }
}

// ponytail: single wording for the import row, reused by settings + welcome
private fun importMessage(status: TimetableViewModel.ImportStatus): String = when (status) {
    is TimetableViewModel.ImportStatus.Idle -> "PDF, HTML, TXT, CSV"
    is TimetableViewModel.ImportStatus.Loading -> "Reading file…"
    is TimetableViewModel.ImportStatus.Success -> "Imported ${status.count} courses"
    is TimetableViewModel.ImportStatus.Error -> status.message
}

// ponytail: first-open welcome — import a timetable or start empty
@Composable
private fun WelcomeScreen(
    onImport: () -> Unit,
    onContinue: () -> Unit
) {
    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            "Monosched",
            style = MaterialTheme.typography.headlineMedium.copy(fontFamily = Monument, fontWeight = FontWeight.Bold)
        )
        Text(
            "Your weekly classes, at a glance.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.height(24.dp))
        Button(shape = RectangleShape, modifier = Modifier.width(200.dp), onClick = onImport) { Text("Import timetable") }
        Spacer(modifier = Modifier.height(8.dp))
        OutlinedButton(
            modifier = Modifier.width(200.dp),
            shape = RectangleShape,
            border = BorderStroke(1.dp, Color.White),
            colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White),
            onClick = onContinue
        ) { Text("Start empty") }
    }
}

@Composable
private fun SessionListView(
    sessions: List<Session>,
    courseMap: Map<Int, Course>,
    today: Int,
    nowMinutes: Int,
    listState: LazyListState,
    onSelect: (Session) -> Unit
) {
    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        items((1..7).toList(), key = { it }) { day ->
            // ponytail: docx-table shape — bare day label, full-bleed cards, no boxes
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                // ponytail: today gets a filled tab, other days plain text
                if (day == today) {
                    Box(
                        modifier = Modifier.background(MaterialTheme.colorScheme.primary, RectangleShape)
                            .padding(horizontal = 12.dp, vertical = 4.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            DAY_NAMES[day].uppercase(),
                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                            color = MaterialTheme.colorScheme.onPrimary
                        )
                    }
                } else {
                    Text(
                        DAY_NAMES[day].uppercase(),
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
                val list = sessions.filter { it.dayOfWeek == day }
                if (list.isEmpty()) {
                    // ponytail: empty-day placeholder — same card shape, --:-- time and
                    // venue, grey outline over translucent fill, No Classes as the name
                    Card(
                        modifier = Modifier.fillMaxWidth().height(104.dp),
                        shape = RectangleShape,
                        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
                            contentColor = MaterialTheme.colorScheme.onSurfaceVariant
                        ),
                    ) {
                        Column(
                            modifier = Modifier.padding(6.dp).fillMaxSize(),
                            verticalArrangement = Arrangement.spacedBy(1.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    "--:--",
                                    style = MaterialTheme.typography.bodyMedium,
                                    maxLines = 1, overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    "--:--",
                                    style = MaterialTheme.typography.bodyMedium,
                                    maxLines = 1, overflow = TextOverflow.Ellipsis
                                )
                            }
                            Box(
                                modifier = Modifier.weight(0.7f).fillMaxWidth(),
                                contentAlignment = Alignment.CenterStart
                            ) {
                                Text(
                                    "No Classes",
                                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                                    maxLines = 1, overflow = TextOverflow.Ellipsis
                                )
                            }
                            Box(
                                modifier = Modifier.weight(0.3f).fillMaxWidth(),
                                contentAlignment = Alignment.BottomEnd
                            ) {
                                Text(
                                    "--:--",
                                    style = MaterialTheme.typography.bodyMedium,
                                    maxLines = 1, overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                    }
                }
                list.forEach { session ->
                    Box(modifier = Modifier.fillMaxWidth().height(104.dp)) {
                        SessionBlock(
                            course = courseMap[session.courseId],
                            isNow = isNowAt(session, today, nowMinutes),
                            onClick = { onSelect(session) },
                            timeText = "${fmtTime(session.startHour, session.startMinute)} - ${fmtTime(session.endHour, session.endMinute)}",
                            sideText = session.room.takeIf { it.isNotBlank() },
                            bottomText = session.group.takeIf { it.isNotBlank() }
                        )
                    }
                }
            }
        }
    }
}

// ponytail: settings — reminder lead time, exact-delivery permission, file import
@Composable
private fun SettingsScreen(
    leadMin: Int,
    onLeadPick: (Int) -> Unit,
    importMsg: String,
    onImport: () -> Unit
) {
    val context = LocalContext.current
    Column(modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        Text(
            "Upcoming reminder",
            style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold)
        )
        Text(
            "Notify minutes before each class starts",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        var sliderVal by remember(leadMin) { mutableStateOf(leadMin.toFloat()) }
        // ponytail: free-drag lead line, 1–60 min with live readout; commits on release
        Slider(
            value = sliderVal,
            onValueChange = { sliderVal = it },
            valueRange = 1f..60f,
            onValueChangeFinished = { onLeadPick(sliderVal.roundToInt().coerceIn(1, 60)) }
        )
        Text(
            "${sliderVal.roundToInt()} min before each class",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            "Import timetable",
            style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold)
        )
        Text(
            importMsg,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.height(8.dp))
        OutlinedButton(shape = RectangleShape, onClick = onImport) { Text("Choose file") }
    }
}

// ponytail: 24dp toolbar button — below the 48dp touch floor;
// the active page's button glows primary, the rest stay quiet
@Composable
private fun FooterButton(
    description: String,
    onClick: () -> Unit,
    active: Boolean = false,
    icon: @Composable () -> Unit
) {
    CompositionLocalProvider(
        androidx.compose.material3.LocalContentColor provides
            if (active) MaterialTheme.colorScheme.primary
            else MaterialTheme.colorScheme.onSurfaceVariant
    ) {
    Box(
        modifier = Modifier.size(24.dp).clip(RectangleShape)
            .clickable(onClick = onClick, role = Role.Button, onClickLabel = description),
        contentAlignment = Alignment.Center
    ) {
        icon()
    }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun SessionBlock(
    course: Course?,
    isNow: Boolean = false,
    onClick: () -> Unit,
    timeText: String? = null,
    sideText: String? = null,
    bottomText: String? = null
) {
    val color = course?.color?.let { Color(it) } ?: MaterialTheme.colorScheme.primary
    val luminance = (0.299 * color.red + 0.587 * color.green + 0.114 * color.blue)
    val contentColor = if (luminance > 0.5f) Color.Black else Color.White

    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxSize(),
        shape = RectangleShape,
        border = if (isNow) androidx.compose.foundation.BorderStroke(2.dp, Color.White) else null,
        colors = CardDefaults.cardColors(
            containerColor = color,
            contentColor = contentColor
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(
            modifier = Modifier.padding(6.dp).fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(1.dp)
        ) {
            // ponytail: time left, venue right, subject name below
            if (timeText != null) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        timeText,
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false)
                    )
                    if (sideText != null) {
                        Text(
                            sideText,
                            style = MaterialTheme.typography.bodyMedium,
                            maxLines = 1, overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
            // ponytail: name rides upper-middle (65/35 split); prompter on overflow
            if (course?.name?.isNotBlank() == true) {
                Box(
                    modifier = Modifier.weight(0.7f).fillMaxWidth(),
                    contentAlignment = Alignment.CenterStart
                ) {
                    Text(
                        course.name,
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                        maxLines = 1, overflow = TextOverflow.Visible,
                        modifier = Modifier.basicMarquee()
                    )
                }
                Box(
                    modifier = Modifier.weight(0.3f).fillMaxWidth(),
                    contentAlignment = Alignment.BottomEnd
                ) {
                    if (bottomText != null) {
                        Text(
                            bottomText,
                            style = MaterialTheme.typography.bodyMedium,
                            maxLines = 1, overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
        }
    }
}
