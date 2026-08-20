package com.schoolattendance.app.ui.detail

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.schoolattendance.app.data.AttendanceStatus
import com.schoolattendance.app.data.Student
import java.time.format.DateTimeFormatter

private val dateFormatter = DateTimeFormatter.ofPattern("EEE, d MMM yyyy")

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ClassDetailScreen(classId: Long, className: String, onBack: () -> Unit) {
    val application = androidx.compose.ui.platform.LocalContext.current.applicationContext as android.app.Application
    val viewModel: ClassDetailViewModel = viewModel(factory = ClassDetailViewModel.Factory(application, classId))
    val uiState by viewModel.uiState.collectAsState()
    var tab by remember { mutableIntStateOf(0) }
    val tabs = listOf("Attendance", "Students", "Reports")
    var showAddStudent by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(className) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        },
        floatingActionButton = {
            if (tab == 1) {
                FloatingActionButton(onClick = { showAddStudent = true }) {
                    Icon(Icons.Default.Add, contentDescription = "Add student")
                }
            }
        }
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            TabRow(selectedTabIndex = tab) {
                tabs.forEachIndexed { index, title ->
                    Tab(selected = tab == index, onClick = { tab = index }, text = { Text(title) })
                }
            }
            when (tab) {
                0 -> AttendanceTab(uiState, viewModel)
                1 -> StudentsTab(uiState.students, onDelete = viewModel::deleteStudent)
                else -> ReportsTab(uiState.reports)
            }
        }
    }

    if (showAddStudent) {
        var name by remember { mutableStateOf("") }
        var roll by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showAddStudent = false },
            title = { Text("New student") },
            text = {
                Column {
                    OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("Name") }, singleLine = true)
                    OutlinedTextField(value = roll, onValueChange = { roll = it }, label = { Text("Roll number (optional)") }, singleLine = true)
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.addStudent(name, roll)
                    showAddStudent = false
                }) { Text("Add") }
            },
            dismissButton = {
                TextButton(onClick = { showAddStudent = false }) { Text("Cancel") }
            }
        )
    }
}

@Composable
private fun AttendanceTab(uiState: ClassDetailUiState, viewModel: ClassDetailViewModel) {
    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().padding(12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = viewModel::goToPreviousDay) {
                Icon(Icons.Default.ChevronLeft, contentDescription = "Previous day")
            }
            Text(uiState.selectedDate.format(dateFormatter), fontWeight = FontWeight.Medium)
            IconButton(onClick = viewModel::goToNextDay) {
                Icon(Icons.Default.ChevronRight, contentDescription = "Next day")
            }
        }

        if (uiState.students.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("Add students from the Students tab first.")
            }
        } else {
            LazyColumn(Modifier.fillMaxSize()) {
                items(uiState.students, key = { it.id }) { student ->
                    StudentAttendanceRow(
                        student = student,
                        status = uiState.statusByStudentId[student.id],
                        onMark = { status -> viewModel.markStatus(student.id, status) }
                    )
                    HorizontalDivider()
                }
            }
        }
    }
}

@Composable
private fun StudentAttendanceRow(student: Student, status: AttendanceStatus?, onMark: (AttendanceStatus) -> Unit) {
    ListItem(
        headlineContent = { Text(student.name) },
        supportingContent = if (student.rollNumber.isNotBlank()) {
            { Text("Roll: ${student.rollNumber}") }
        } else null,
        trailingContent = {
            Row {
                StatusButton("P", AttendanceStatus.PRESENT, status, onMark)
                StatusButton("A", AttendanceStatus.ABSENT, status, onMark)
                StatusButton("L", AttendanceStatus.LATE, status, onMark)
            }
        }
    )
}

@Composable
private fun StatusButton(label: String, value: AttendanceStatus, current: AttendanceStatus?, onMark: (AttendanceStatus) -> Unit) {
    val selected = current == value
    Button(
        onClick = { onMark(value) },
        colors = if (selected) ButtonDefaults.buttonColors() else ButtonDefaults.outlinedButtonColors(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 10.dp, vertical = 4.dp),
        modifier = Modifier.padding(horizontal = 2.dp)
    ) { Text(label) }
}

@Composable
private fun StudentsTab(students: List<Student>, onDelete: (Student) -> Unit) {
    if (students.isEmpty()) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("No students yet. Tap + to add one.")
        }
        return
    }
    LazyColumn(Modifier.fillMaxSize()) {
        items(students, key = { it.id }) { student ->
            ListItem(
                headlineContent = { Text(student.name) },
                supportingContent = if (student.rollNumber.isNotBlank()) {
                    { Text("Roll: ${student.rollNumber}") }
                } else null,
                trailingContent = {
                    IconButton(onClick = { onDelete(student) }) {
                        Icon(Icons.Default.Delete, contentDescription = "Remove student")
                    }
                }
            )
            HorizontalDivider()
        }
    }
}

@Composable
private fun ReportsTab(reports: List<StudentReport>) {
    if (reports.isEmpty()) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("No students yet.")
        }
        return
    }
    LazyColumn(Modifier.fillMaxSize()) {
        items(reports, key = { it.student.id }) { report ->
            ListItem(
                headlineContent = { Text(report.student.name) },
                supportingContent = {
                    Column {
                        Text("Present ${report.presentDays} · Late ${report.lateDays} · Absent ${report.absentDays} · of ${report.totalDays} day(s)")
                        LinearProgressIndicator(
                            progress = { report.percentPresent / 100f },
                            modifier = Modifier.fillMaxWidth().padding(top = 4.dp)
                        )
                    }
                },
                trailingContent = { Text("${report.percentPresent}%") }
            )
            HorizontalDivider()
        }
    }
}
