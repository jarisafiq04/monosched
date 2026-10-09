package com.example.timetableapp.ui.viewmodel

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import com.example.timetableapp.data.model.Course
import com.example.timetableapp.data.model.Session
import com.example.timetableapp.data.repository.TimetableRepository
import com.example.timetableapp.data.repository.ImportResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class TimetableViewModel(application: Application) : AndroidViewModel(application) {
    private val repository = TimetableRepository.getInstance(application)

    val allCourses: LiveData<List<Course>> = repository.getAllCourses()
    val allSessions: LiveData<List<Session>> = repository.getAllSessions()

    private val _importStatus = MutableLiveData<ImportStatus>()
    val importStatus: LiveData<ImportStatus> = _importStatus

    init {
        viewModelScope.launch(Dispatchers.IO) { repository.load() }
    }

    fun importTimetable(uri: Uri) {
        _importStatus.postValue(ImportStatus.Loading)
        viewModelScope.launch {
            val result = repository.importFromPdf(getApplication(), uri)
            _importStatus.postValue(when (result) {
                is ImportResult.Success -> ImportStatus.Success(result.courseCount)
                is ImportResult.Error -> ImportStatus.Error(result.message)
            })
        }
    }

    fun saveCourse(course: Course) = viewModelScope.launch(Dispatchers.IO) {
        repository.upsertCourse(course)
    }

    fun saveSession(session: Session) = viewModelScope.launch(Dispatchers.IO) {
        repository.upsertSession(session)
    }

    fun deleteSession(sessionId: Int) = viewModelScope.launch(Dispatchers.IO) {
        repository.deleteSession(sessionId)
    }

    sealed interface ImportStatus {
        object Idle : ImportStatus
        object Loading : ImportStatus
        data class Success(val count: Int) : ImportStatus
        data class Error(val message: String) : ImportStatus
    }
}
