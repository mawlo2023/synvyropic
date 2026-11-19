package de.synvyropic.app.ui.screens

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import de.synvyropic.app.data.ProjectRepository
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class ProjectListViewModel(app: Application) : AndroidViewModel(app) {
    private val repo = ProjectRepository.get(app)
    val projects = repo.projects

    init { refresh() }

    fun refresh() { viewModelScope.launch { repo.refresh() } }

    /** Legt ein neues Projekt an und liefert dessen ID. */
    fun create(onCreated: (String) -> Unit) {
        val name = "Projekt " + SimpleDateFormat("dd.MM. HH:mm", Locale.GERMANY).format(Date())
        val p = repo.newProject(name)
        viewModelScope.launch { repo.save(p); onCreated(p.id) }
    }

    fun delete(id: String) { viewModelScope.launch { repo.delete(id) } }
}
