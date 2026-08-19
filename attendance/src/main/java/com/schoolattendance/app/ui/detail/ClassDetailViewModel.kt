package com.schoolattendance.app.ui.detail

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.schoolattendance.app.AttendanceApp
import com.schoolattendance.app.data.AttendanceRecord
import com.schoolattendance.app.data.AttendanceStatus
import com.schoolattendance.app.data.Student
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.LocalDate

data class StudentReport(
    val student: Student,
    val presentDays: Int,
    val absentDays: Int,
    val lateDays: Int,
    val totalDays: Int
) {
    val percentPresent: Int
        get() = if (totalDays == 0) 0 else ((presentDays + lateDays) * 100) / totalDays
}

data class ClassDetailUiState(
    val students: List<Student> = emptyList(),
    val selectedDate: LocalDate = LocalDate.now(),
    val statusByStudentId: Map<Long, AttendanceStatus> = emptyMap(),
    val reports: List<StudentReport> = emptyList()
)

@OptIn(ExperimentalCoroutinesApi::class)
class ClassDetailViewModel(application: Application, private val classId: Long) : AndroidViewModel(application) {

    private val app = application as AttendanceApp
    private val studentDao = app.database.studentDao()
    private val attendanceDao = app.database.attendanceDao()

    private val selectedDate = MutableStateFlow(LocalDate.now())

    private val studentsFlow = studentDao.observeForClass(classId)
    private val allRecordsFlow = attendanceDao.observeForClass(classId)

    private val recordsForDateFlow = selectedDate.flatMapLatest { date ->
        attendanceDao.observeForClassAndDate(classId, date.toEpochDay())
    }

    val uiState: StateFlow<ClassDetailUiState> =
        combine(studentsFlow, selectedDate, recordsForDateFlow, allRecordsFlow) { students, date, dateRecords, allRecords ->
            val statusByStudent = dateRecords.associate { it.studentId to it.status }
            val recordsByStudent = allRecords.groupBy { it.studentId }
            val reports = students.map { student ->
                val records = recordsByStudent[student.id].orEmpty()
                StudentReport(
                    student = student,
                    presentDays = records.count { it.status == AttendanceStatus.PRESENT },
                    absentDays = records.count { it.status == AttendanceStatus.ABSENT },
                    lateDays = records.count { it.status == AttendanceStatus.LATE },
                    totalDays = records.size
                )
            }
            ClassDetailUiState(students, date, statusByStudent, reports)
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), ClassDetailUiState())

    fun goToPreviousDay() {
        selectedDate.value = selectedDate.value.minusDays(1)
    }

    fun goToNextDay() {
        selectedDate.value = selectedDate.value.plusDays(1)
    }

    fun goToToday() {
        selectedDate.value = LocalDate.now()
    }

    fun markStatus(studentId: Long, status: AttendanceStatus) {
        viewModelScope.launch {
            attendanceDao.upsert(
                AttendanceRecord(
                    studentId = studentId,
                    classId = classId,
                    date = selectedDate.value.toEpochDay(),
                    status = status
                )
            )
        }
    }

    fun addStudent(name: String, rollNumber: String) {
        val trimmed = name.trim()
        if (trimmed.isBlank()) return
        viewModelScope.launch {
            studentDao.insert(Student(classId = classId, name = trimmed, rollNumber = rollNumber.trim()))
        }
    }

    fun deleteStudent(student: Student) {
        viewModelScope.launch { studentDao.delete(student) }
    }

    class Factory(private val application: Application, private val classId: Long) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            ClassDetailViewModel(application, classId) as T
    }
}
