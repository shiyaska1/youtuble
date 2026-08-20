package com.schoolattendance.app

import android.app.Application
import com.schoolattendance.app.data.AppDatabase

class AttendanceApp : Application() {
    val database: AppDatabase by lazy { AppDatabase.build(this) }
}
