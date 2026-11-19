package de.synvyropic.app.export

import android.content.Context
import android.net.Uri
import de.synvyropic.model.Project
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

sealed interface ExportState {
    data object Idle : ExportState
    data class Running(val projectId: String, val progress: Float) : ExportState
    data class Done(val projectId: String, val uri: Uri) : ExportState
    data class Failed(val projectId: String, val message: String) : ExportState
}

/** App-weiter Export: läuft weiter, auch wenn der Editor verlassen wird. */
object ExportManager {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val _state = MutableStateFlow<ExportState>(ExportState.Idle)
    val state: StateFlow<ExportState> = _state.asStateFlow()
    private var job: Job? = null

    fun start(context: Context, project: Project) {
        if (_state.value is ExportState.Running) return
        val app = context.applicationContext
        _state.value = ExportState.Running(project.id, 0f)
        job = scope.launch {
            _state.value = try {
                val uri = VideoExporter(app).export(project) { p ->
                    _state.value = ExportState.Running(project.id, p)
                }
                ExportState.Done(project.id, uri)
            } catch (e: kotlinx.coroutines.CancellationException) {
                ExportState.Idle
            } catch (e: Throwable) {
                ExportState.Failed(project.id, e.message ?: e.javaClass.simpleName)
            }
        }
    }

    fun cancel() {
        job?.cancel()
        _state.value = ExportState.Idle
    }

    fun dismiss() {
        if (_state.value !is ExportState.Running) _state.value = ExportState.Idle
    }
}
