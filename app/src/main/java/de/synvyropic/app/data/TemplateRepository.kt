package de.synvyropic.app.data

import android.content.Context
import de.synvyropic.model.Project
import de.synvyropic.model.ProjectTemplate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.io.File
import java.util.UUID

/** Vorlagen unter files/templates/<id>/ (template.json + LUT/Logo-Kopien). */
class TemplateRepository private constructor(private val root: File) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; prettyPrint = true }
    private val _templates = MutableStateFlow<List<ProjectTemplate>>(emptyList())
    val templates: StateFlow<List<ProjectTemplate>> = _templates.asStateFlow()

    suspend fun refresh() = withContext(Dispatchers.IO) {
        _templates.value = root.listFiles()?.mapNotNull { d ->
            runCatching { json.decodeFromString(ProjectTemplate.serializer(), File(d, FILE).readText()) }.getOrNull()
        }.orEmpty().sortedByDescending { it.createdAt }
    }

    /** Projekt als Vorlage speichern; Dateien (LUT, Logo) werden mitkopiert. */
    suspend fun save(project: Project, name: String) = withContext(Dispatchers.IO) {
        val id = UUID.randomUUID().toString()
        val dir = File(root, id).apply { mkdirs() }
        val t = ProjectTemplate.from(project, id, name, System.currentTimeMillis()) { path -> copyInto(path, dir) }
        File(dir, FILE).writeText(json.encodeToString(ProjectTemplate.serializer(), t))
        refresh()
    }

    /** Vorlage auf ein Projekt anwenden; Dateien landen im Medienordner des Projekts. */
    suspend fun apply(template: ProjectTemplate, project: Project, mediaDir: File): Project = withContext(Dispatchers.IO) {
        template.applyTo(project) { path -> copyInto(path, mediaDir) }
    }

    suspend fun delete(id: String) = withContext(Dispatchers.IO) {
        File(root, id).deleteRecursively()
        refresh()
    }

    private fun copyInto(path: String, dir: File): String {
        val src = File(path)
        if (!src.exists()) return path
        val dst = File(dir, "tpl_${System.nanoTime()}_${src.name}")
        src.copyTo(dst, overwrite = true)
        return dst.absolutePath
    }

    companion object {
        private const val FILE = "template.json"
        @Volatile private var instance: TemplateRepository? = null
        fun get(context: Context) = instance ?: synchronized(this) {
            instance ?: TemplateRepository(File(context.applicationContext.filesDir, "templates").apply { mkdirs() }).also { instance = it }
        }
    }
}
