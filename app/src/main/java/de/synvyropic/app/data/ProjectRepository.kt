package de.synvyropic.app.data

import android.content.Context
import de.synvyropic.model.Project
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

/**
 * Speichert Projekte als JSON unter files/projects/<id>/project.json.
 * Importierte Medien liegen in files/projects/<id>/media/.
 */
class ProjectRepository private constructor(private val root: File) {

    private val _projects = MutableStateFlow<List<Project>>(emptyList())
    val projects: StateFlow<List<Project>> = _projects.asStateFlow()

    fun projectDir(id: String) = File(root, id).apply { mkdirs() }
    fun mediaDir(id: String) = File(projectDir(id), "media").apply { mkdirs() }

    suspend fun refresh() = withContext(Dispatchers.IO) {
        val list = root.listFiles()?.mapNotNull { dir ->
            runCatching { Project.fromJson(File(dir, FILE).readText()) }.getOrNull()
        }.orEmpty().sortedByDescending { it.updatedAt }
        _projects.value = list
    }

    suspend fun load(id: String): Project? = withContext(Dispatchers.IO) {
        runCatching { Project.fromJson(File(File(root, id), FILE).readText()) }.getOrNull()
    }

    suspend fun save(project: Project) = withContext(Dispatchers.IO) {
        val dir = projectDir(project.id)
        val tmp = File(dir, "$FILE.tmp")
        tmp.writeText(project.toJson())
        tmp.renameTo(File(dir, FILE))
        refresh()
    }

    suspend fun delete(id: String) = withContext(Dispatchers.IO) {
        File(root, id).deleteRecursively()
        refresh()
    }

    fun newProject(name: String): Project {
        val now = System.currentTimeMillis()
        return Project(id = UUID.randomUUID().toString(), name = name, createdAt = now)
    }

    companion object {
        private const val FILE = "project.json"
        @Volatile private var instance: ProjectRepository? = null

        fun get(context: Context): ProjectRepository = instance ?: synchronized(this) {
            instance ?: ProjectRepository(File(context.applicationContext.filesDir, "projects").apply { mkdirs() })
                .also { instance = it }
        }
    }
}
