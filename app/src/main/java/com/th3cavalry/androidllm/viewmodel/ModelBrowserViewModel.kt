package com.th3cavalry.androidllm.viewmodel

import android.app.Application
import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import android.os.Environment
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.th3cavalry.androidllm.Prefs
import com.th3cavalry.androidllm.data.HfModelDto
import com.th3cavalry.androidllm.data.HfSiblingDto
import com.th3cavalry.androidllm.network.RetrofitClient
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File

/** UI state for the model list screen. */
sealed class ModelListState {
    object Loading : ModelListState()
    data class Success(val models: List<HfModelDto>) : ModelListState()
    data class Error(val message: String) : ModelListState()
}

/** UI state for a single model's file list. */
sealed class ModelDetailState {
    object Idle : ModelDetailState()
    object Loading : ModelDetailState()
    data class Success(val model: HfModelDto, val files: List<HfSiblingDto>) : ModelDetailState()
    data class Error(val message: String) : ModelDetailState()
}

/** State of an active download. */
data class DownloadState(
    val filename: String,
    val downloadId: Long,
    val targetPath: String
)

/**
 * ViewModel for [com.th3cavalry.androidllm.ModelBrowserActivity].
 *
 * Fetches model lists and per-model file details from the Hugging Face Hub API, and
 * triggers downloads via [DownloadManager].
 *
 * The [backendId] determines which file extensions are surfaced:
 * - [Prefs.BACKEND_MEDIAPIPE]   → `.task` files
 * - [Prefs.BACKEND_LITERT_LM]   → `.litertlm` files
 */
class ModelBrowserViewModel(application: Application) : AndroidViewModel(application) {

    private val ctx: Context get() = getApplication()

    private val _listState = MutableStateFlow<ModelListState>(ModelListState.Loading)
    val listState: StateFlow<ModelListState> = _listState

    private val _detailState = MutableStateFlow<ModelDetailState>(ModelDetailState.Idle)
    val detailState: StateFlow<ModelDetailState> = _detailState

    private val _activeDownload = MutableStateFlow<DownloadState?>(null)
    val activeDownload: StateFlow<DownloadState?> = _activeDownload

    /**
     * Download progress as a percentage (0–100), or -1 when no download is active.
     * Updated by [startProgressPolling].
     */
    private val _downloadProgress = MutableStateFlow(-1)
    val downloadProgress: StateFlow<Int> = _downloadProgress

    /** Set by the Activity before calling [loadModels]. */
    var backendId: String = Prefs.BACKEND_LITERT_LM

    private val hfToken: String get() = Prefs.getSecret(ctx, Prefs.KEY_HF_TOKEN)

    // ────────────────────────────────────────────────────────────
    // Model list
    // ────────────────────────────────────────────────────────────

    fun loadModels() {
        _listState.value = ModelListState.Loading
        viewModelScope.launch {
            try {
                val api = RetrofitClient.buildHfApi(hfToken)
                val models = api.listModels(
                    author      = "litert-community",
                    pipelineTag = "text-generation",
                    limit       = 50
                )
                // Filter to models that have the right file type for this backend
                val filtered = models.filter { model ->
                    model.siblings == null || // detail not loaded yet — assume compatible
                    hasFilesForBackend(model.siblings)
                }
                _listState.value = ModelListState.Success(
                    if (filtered.isEmpty()) models else filtered
                )
            } catch (e: Exception) {
                _listState.value = ModelListState.Error(e.message ?: "Unknown error")
            }
        }
    }

    // ────────────────────────────────────────────────────────────
    // Model detail (file list)
    // ────────────────────────────────────────────────────────────

    fun loadModelDetail(modelId: String) {
        _detailState.value = ModelDetailState.Loading
        viewModelScope.launch {
            try {
                val api    = RetrofitClient.buildHfApi(hfToken)
                val detail = api.getModelDetail(modelId, blobs = true)
                val files  = (detail.siblings ?: emptyList())
                    .filter { sibling -> isCompatibleFile(sibling) }
                    .sortedByDescending { it.size ?: 0L }
                _detailState.value = ModelDetailState.Success(detail, files)
            } catch (e: Exception) {
                _detailState.value = ModelDetailState.Error(e.message ?: "Unknown error")
            }
        }
    }

    fun clearDetail() {
        _detailState.value = ModelDetailState.Idle
    }

    // ────────────────────────────────────────────────────────────
    // Download
    // ────────────────────────────────────────────────────────────

    /**
     * Enqueues a file download via Android [DownloadManager].
     *
     * The file is saved to the public Downloads folder so it survives Activity destruction.
     * Returns the [DownloadManager] download ID, which the Activity uses to register a
     * [android.content.BroadcastReceiver] for completion.
     *
     * @param modelId  HF model ID, e.g. "litert-community/Gemma3-1B-IT"
     * @param sibling  The specific file to download
     */
    fun enqueueDownload(modelId: String, sibling: HfSiblingDto): Long {
        val url      = "https://huggingface.co/$modelId/resolve/main/${sibling.rfilename}"
        val filename = sibling.rfilename

        // Download to app-private external storage (getExternalFilesDir) instead of the
        // public Downloads folder.  This directory is readable by the app on all API levels
        // without any runtime storage permissions, which is essential on API 33+ where
        // READ_EXTERNAL_STORAGE no longer grants access to arbitrary external paths.
        val modelDir = ctx.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
            ?: ctx.filesDir // fallback to internal storage if external is unavailable

        val request  = DownloadManager.Request(Uri.parse(url)).apply {
            setTitle(filename)
            setDescription("Downloading model from Hugging Face")
            setNotificationVisibility(
                DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED
            )
            setDestinationUri(Uri.fromFile(File(modelDir, filename)))
            if (hfToken.isNotBlank()) {
                addRequestHeader("Authorization", "Bearer $hfToken")
            }
            setAllowedOverMetered(true)
            setAllowedOverRoaming(false)
        }
        val dm         = ctx.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        val downloadId = dm.enqueue(request)
        val targetPath = File(modelDir, filename).absolutePath
        _activeDownload.value = DownloadState(filename, downloadId, targetPath)
        startProgressPolling(downloadId)
        return downloadId
    }

    /**
     * Polls the [DownloadManager] every 500 ms while the given download is active and
     * updates [downloadProgress] (0–100). Stops automatically when the download finishes
     * or when the ViewModel is cleared.
     */
    private fun startProgressPolling(downloadId: Long) {
        _downloadProgress.value = 0
        viewModelScope.launch {
            val dm = ctx.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
            while (isActive && _activeDownload.value?.downloadId == downloadId) {
                val query = DownloadManager.Query().setFilterById(downloadId)
                val progress = dm.query(query).use { cursor ->
                    if (!cursor.moveToFirst()) return@use -1
                    val status = cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
                    if (status == DownloadManager.STATUS_RUNNING ||
                        status == DownloadManager.STATUS_PAUSED) {
                        val downloaded = cursor.getLong(
                            cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR)
                        )
                        val total = cursor.getLong(
                            cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES)
                        )
                        if (total > 0) ((downloaded * 100L) / total).toInt() else 0
                    } else {
                        -1 // done or error
                    }
                }
                if (progress < 0) break
                _downloadProgress.value = progress
                delay(500L)
            }
            // Only reset progress if this coroutine's download is still the active one.
            // If a new download was enqueued before we reached this point, leave the new
            // download's progress state untouched.
            if (_activeDownload.value?.downloadId == downloadId) {
                _downloadProgress.value = -1
            }
        }
    }

    /**
     * Called when [DownloadManager] broadcasts [DownloadManager.ACTION_DOWNLOAD_COMPLETE].
     *
     * Queries the manager for the final status and verifies the file exists on disk
     * before keeping the state. Returns `true` only when the download succeeded and
     * the output file is non-empty and has the correct extension for the backend;
     * `false` otherwise.
     */
    fun onDownloadComplete(downloadId: Long): Boolean {
        val current = _activeDownload.value ?: return false
        if (current.downloadId != downloadId) return false

        val dm = ctx.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        val query = DownloadManager.Query().setFilterById(downloadId)
        val cursor = dm.query(query)
        val succeeded = cursor.use { c ->
            if (!c.moveToFirst()) return@use false
            val statusCol = c.getColumnIndex(DownloadManager.COLUMN_STATUS)
            val reasonCol = c.getColumnIndex(DownloadManager.COLUMN_REASON)
            val status = c.getInt(statusCol)
            val reason = if (reasonCol >= 0) c.getInt(reasonCol) else 0
            val fileExists = File(current.targetPath).let { it.exists() && it.length() > 0 }
            
            // Verify the file has the correct extension for this backend
            val hasCorrectExtension = when (backendId) {
                Prefs.BACKEND_LITERT_LM -> current.filename.endsWith(".litertlm", ignoreCase = true)
                Prefs.BACKEND_MEDIAPIPE -> current.filename.endsWith(".task", ignoreCase = true)
                else -> current.filename.endsWith(".litertlm", ignoreCase = true) || current.filename.endsWith(".task", ignoreCase = true)
            }
            
            status == DownloadManager.STATUS_SUCCESSFUL &&
                reason == 0 &&
                fileExists &&
                hasCorrectExtension
        }
        return succeeded
    }

    fun clearDownload() {
        _activeDownload.value = null
        _downloadProgress.value = -1
    }

    // ────────────────────────────────────────────────────────────
    // Helpers
    // ────────────────────────────────────────────────────────────

    private fun isCompatibleFile(sibling: HfSiblingDto): Boolean = when (backendId) {
        Prefs.BACKEND_LITERT_LM -> sibling.isLiteRtLm
        Prefs.BACKEND_MEDIAPIPE -> sibling.isMediaPipe
        else                    -> sibling.isLiteRtLm || sibling.isMediaPipe
    }

    private fun hasFilesForBackend(siblings: List<HfSiblingDto>): Boolean =
        siblings.any { isCompatibleFile(it) }
}
