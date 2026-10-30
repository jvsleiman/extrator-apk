package com.joao.extratorapk

import android.app.Application
import android.content.Context
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class Progress(
    val index: Int,
    val total: Int,
    val label: String,
    val fraction: Float,
)

data class ExtractionReport(
    val files: List<ExtractedFile>,
    val errors: List<String>,
    val cancelled: Boolean,
)

data class UiState(
    val loading: Boolean = true,
    val apps: List<AppEntry> = emptyList(),
    val query: String = "",
    val showSystem: Boolean = false,
    val selected: Set<String> = emptySet(),
    val folder: Uri? = null,
    val progress: Progress? = null,
    val report: ExtractionReport? = null,
) {
    val visible: List<AppEntry>
        get() {
            val q = query.trim()
            return apps.filter { app ->
                (showSystem || !app.isSystem) &&
                    (q.isEmpty() || app.label.contains(q, true) || app.packageName.contains(q, true))
            }
        }
}

class MainViewModel(app: Application) : AndroidViewModel(app) {

    private val prefs = app.getSharedPreferences("config", Context.MODE_PRIVATE)
    private val _state = MutableStateFlow(UiState(folder = prefs.getString(KEY_FOLDER, null)?.let(Uri::parse)))
    val state: StateFlow<UiState> = _state.asStateFlow()

    private var job: Job? = null

    init {
        reload()
    }

    fun reload() {
        _state.update { it.copy(loading = true) }
        viewModelScope.launch {
            val apps = withContext(Dispatchers.IO) { AppRepository.load(getApplication()) }
            _state.update { s ->
                val keep = s.selected.filterTo(HashSet()) { p -> apps.any { it.packageName == p } }
                s.copy(loading = false, apps = apps, selected = keep)
            }
        }
    }

    fun setQuery(q: String) = _state.update { it.copy(query = q) }
    fun setShowSystem(v: Boolean) = _state.update { it.copy(showSystem = v) }

    fun toggle(pkg: String) = _state.update {
        it.copy(selected = if (pkg in it.selected) it.selected - pkg else it.selected + pkg)
    }

    fun selectAllVisible() = _state.update { s -> s.copy(selected = s.selected + s.visible.map { it.packageName }) }
    fun clearSelection() = _state.update { it.copy(selected = emptySet()) }
    fun dismissReport() = _state.update { it.copy(report = null) }

    fun setFolder(uri: Uri) {
        prefs.edit().putString(KEY_FOLDER, uri.toString()).apply()
        _state.update { it.copy(folder = uri) }
    }

    /** A permissão da pasta ainda vale? (o usuário pode tê-la revogado) */
    fun folderStillValid(): Boolean {
        val f = _state.value.folder ?: return false
        return getApplication<Application>().contentResolver.persistedUriPermissions
            .any { it.uri == f && it.isWritePermission }
    }

    fun extractSelected() {
        val s = _state.value
        val folder = s.folder ?: return
        val targets = s.apps.filter { it.packageName in s.selected }
        if (targets.isEmpty() || job?.isActive == true) return
        job = viewModelScope.launch {
            val files = mutableListOf<ExtractedFile>()
            val errors = mutableListOf<String>()
            var cancelled = false
            try {
                withContext(Dispatchers.IO) {
                    val extractor = try {
                        Extractor(getApplication(), folder)
                    } catch (e: Exception) {
                        errors += e.message ?: "Pasta inválida"
                        null
                    }
                    if (extractor != null) {
                        for ((i, app) in targets.withIndex()) {
                            _state.update { it.copy(progress = Progress(i + 1, targets.size, app.label, 0f)) }
                            try {
                                files += extractor.extract(app) { bytes ->
                                    val frac = if (app.sizeBytes > 0) (bytes.toFloat() / app.sizeBytes).coerceIn(0f, 1f) else 0f
                                    _state.update { st -> st.copy(progress = st.progress?.copy(fraction = frac)) }
                                }
                            } catch (e: CancellationException) {
                                throw e
                            } catch (e: Exception) {
                                errors += "${app.label}: ${e.message ?: e.javaClass.simpleName}"
                            }
                        }
                    }
                }
            } catch (e: CancellationException) {
                cancelled = true
            } finally {
                _state.update {
                    it.copy(
                        progress = null,
                        report = ExtractionReport(files.toList(), errors.toList(), cancelled),
                        selected = if (errors.isEmpty() && !cancelled) emptySet() else it.selected,
                    )
                }
            }
        }
    }

    fun cancel() {
        job?.cancel()
    }

    companion object {
        private const val KEY_FOLDER = "folder"
    }
}
