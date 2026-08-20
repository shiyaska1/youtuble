package com.schoolattendance.app.ui.classes

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.schoolattendance.app.AttendanceApp
import com.schoolattendance.app.data.ClassSection
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class ClassListViewModel(application: Application) : AndroidViewModel(application) {

    private val dao = (application as AttendanceApp).database.classDao()

    val classes: StateFlow<List<ClassSection>> =
        dao.observeAll().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun addClass(name: String) {
        val trimmed = name.trim()
        if (trimmed.isBlank()) return
        viewModelScope.launch { dao.insert(ClassSection(name = trimmed)) }
    }

    fun deleteClass(classSection: ClassSection) {
        viewModelScope.launch { dao.delete(classSection) }
    }
}
