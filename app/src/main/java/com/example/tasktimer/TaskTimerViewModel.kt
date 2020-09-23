package com.example.tasktimer

import android.app.Application
import android.content.ContentValues
import android.content.SharedPreferences
import android.database.ContentObserver
import android.database.Cursor
import android.net.Uri
import android.os.Handler
import android.preference.PreferenceManager
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch

private const val TAG = "TaskTimerViewModel"

class TaskTimerViewModel(application: Application) : AndroidViewModel(application) {

    private val contentObserver = object : ContentObserver(Handler()) {
        override fun onChange(selfChange: Boolean, uri: Uri?) {
            Log.d(TAG, "contentObserver.onChange: called. uri is $uri")
            loadTasks()
        }
    }

    private val settings = PreferenceManager.getDefaultSharedPreferences(application)
    private var ignoreLessThan = settings.getInt(SETTINGS_IGNORE_LESS_THAN, SETTINGS_DEFAULT_IGNORE_LESS_THAN)

    private val settingsListener = SharedPreferences.OnSharedPreferenceChangeListener { sharedPreferences, key -> when (key) {
        SETTINGS_IGNORE_LESS_THAN -> {
            ignoreLessThan = sharedPreferences.getInt(key, SETTINGS_DEFAULT_IGNORE_LESS_THAN)
            Log.d(TAG, "settingsListener: now ignoring timings less than $ignoreLessThan seconds")
        }
    }  }

    private var currentTiming: Timing? = null

    private val databaseCursor = MutableLiveData<Cursor>()
    val cursor: LiveData<Cursor>
    get() = databaseCursor

    private val taskTiming = MutableLiveData<String>()
    val timing: LiveData<String>
        get() = taskTiming

    init {
        Log.d(TAG, "TaskTimerViewModel: created")
        getApplication<Application>().contentResolver.registerContentObserver(TasksContract.CONTENT_URI, true, contentObserver)

        Log.d(TAG, "Ignoring timings less than $ignoreLessThan seconds")
        settings.registerOnSharedPreferenceChangeListener(settingsListener)

        currentTiming = retrieveTiming()
        loadTasks()
    }

    private fun loadTasks() {

        val projection = arrayOf(TasksContract.Columns.ID,
                                 TasksContract.Columns.TASK_NAME,
                                 TasksContract.Columns.TASK_DESCRIPTION,
                                 TasksContract.Columns.SORT_ORDER)
        //  <order.by> Tasks.SortOrder, Tasks.Name
        val sortOrder = "${TasksContract.Columns.SORT_ORDER}, ${TasksContract.Columns.TASK_NAME}"
        GlobalScope.launch {
            val cursor = getApplication<Application>().contentResolver.query(
                TasksContract.CONTENT_URI,
                projection, null, null, sortOrder
            )
            databaseCursor.postValue(cursor)
        }
    }

    fun saveTask(task: Task): Task {
        val values = ContentValues()

        if (task.name.isNotEmpty()) {  // Don't save a task with no name
            values.put(TasksContract.Columns.TASK_NAME, task.name)
            values.put(TasksContract.Columns.TASK_DESCRIPTION, task.description)
            values.put(TasksContract.Columns.SORT_ORDER, task.sortOrder)   // defaults to zero if empty

            if (task.id == 0L) {
                GlobalScope.launch {
                    Log.d(TAG, "saveTask: adding new Task")
                    val uri = getApplication<Application>().contentResolver?.insert(TasksContract.CONTENT_URI, values)
                    if (uri != null) {
                        task.id = TasksContract.getId(uri)
                        Log.d(TAG, "saveTask: new id is ${task.id}")
                    }
                }
            } else {    //task has an id, so we're updating
                GlobalScope.launch {
                    Log.d(TAG, "saveTask: updating task")
                    getApplication<Application>().contentResolver?.update(TasksContract.buildUriFromId(task.id), values, null, null)
                }
            }
        }

        return task
    }

    fun deleteTask(taskId: Long) {
        Log.d(TAG, "Deleting task")
        GlobalScope.launch {
            getApplication<Application>().contentResolver?.delete(TasksContract.buildUriFromId(taskId), null, null)
        }
    }

    fun timeTask(task: Task) {
        Log.d(TAG, "timeTask: called")
        // Use local variable, to allow smart casts
        val timingRecord = currentTiming

        if (timingRecord == null) {
            // no task being timed, start timing new task
            currentTiming = Timing(task.id)
            saveTiming(currentTiming!!)  // one way of resolving the error
        } else {
            // We have a task being timed, so save it
            timingRecord.setDuration()
            saveTiming(timingRecord)
            if (task.id == timingRecord.taskId) {
                // the current task was tapped a second time, stop timing
                currentTiming = null
            } else {
                // a new task is being timed
                val newTiming = Timing(task.id)
                saveTiming(newTiming)       // second way of resolving the error
                currentTiming = newTiming   // usually preferable to stick to one way of resolving errors
            }
        }

        // Update the LiveData
        taskTiming.value = if (currentTiming != null) task.name else null
    }

    private fun saveTiming(currentTiming: Timing) {
        Log.d(TAG, "saveTiming: called")

        // Are we updating, or inserting a new row?
        val inserting = (currentTiming.duration == 0L)

        val values = ContentValues().apply {
            if (inserting) {
                put(TimingsContract.Columns.TIMING_TASK_ID, currentTiming.taskId)
                put(TimingsContract.Columns.TIMING_START_TIME, currentTiming.startTime)
            }
            put(TimingsContract.Columns.TIMING_DURATION, currentTiming.duration)
        }

        GlobalScope.launch {
            if (inserting) {
                val uri = getApplication<Application>().contentResolver.insert(TimingsContract.CONTENT_URI, values)
                if (uri != null) {
                    currentTiming.id = TimingsContract.getId(uri)
                }
            } else {
                // Only save if the duration is larger than the values we should ignore
                if (currentTiming.duration >= ignoreLessThan) {
                    Log.d(TAG, "saveTiming: saving timing with duration ${currentTiming.duration}")
                    getApplication<Application>().contentResolver.update(TimingsContract.buildUriFromId(currentTiming.id), values, null, null)
                } else {
                    // Too short to save, delete it instead
                    Log.d(TAG, "saveTiming: deleting row with duration ${currentTiming.duration}")
                    getApplication<Application>().contentResolver.delete(TimingsContract.buildUriFromId(currentTiming.id), null, null)
                }
            }
        }
    }

    private fun retrieveTiming(): Timing? {
        Log.d(TAG, "retrieveTiming starts")
        val timing: Timing?

        val timingCursor: Cursor? = getApplication<Application>().contentResolver.query(
            CurrentTimingContract.CONTENT_URI, null, null, null, null)  // passing null for projection returns all columns

        if (timingCursor != null && timingCursor.moveToFirst()) {
            // We have an un-time record
            val id = timingCursor.getLong(timingCursor.getColumnIndex(CurrentTimingContract.Columns.TIMING_ID))
            val taskId = timingCursor.getLong(timingCursor.getColumnIndex(CurrentTimingContract.Columns.TASK_ID))
            val startTime = timingCursor.getLong(timingCursor.getColumnIndex(CurrentTimingContract.Columns.START_TIME))
            val name = timingCursor.getString(timingCursor.getColumnIndex(CurrentTimingContract.Columns.TASK_NAME))
            timing = Timing(taskId, startTime, id)

            // Update the LiveData
            taskTiming.value = name

        } else {
            // No timing record found with zero duration
            timing = null
        }

        timingCursor?.close()

        Log.d(TAG,"retrieveTiming returning")
        return timing
    }

    override fun onCleared() {
        Log.d(TAG, "onCleared: called")
        getApplication<Application>().contentResolver.unregisterContentObserver(contentObserver)
        settings.unregisterOnSharedPreferenceChangeListener(settingsListener)
    }
}