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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
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

    /** Set by the Activity before calling [loadModels]. */
    var backendId: String = Prefs.BACKEND_LITERT_LM

    private val hfToken: String get() = Prefs.getString(ctx, Prefs.KEY_HF_TOKEN)

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
        val request  = DownloadManager.Request(Uri.parse(url)).apply {
            setTitle(filename)
            setDescription("Downloading model from Hugging Face")
            setNotificationVisibility(
                DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED
            )
            setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, filename)
            if (hfToken.isNotBlank()) {
                addRequestHeader("Authorization", "Bearer $hfToken")
            }
            setAllowedOverMetered(true)
            setAllowedOverRoaming(false)
        }
        val dm         = ctx.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        val downloadId = dm.enqueue(request)
        val targetPath = File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
            filename
        ).absolutePath
        _activeDownload.value = DownloadState(filename, downloadId, targetPath)
        return downloadId
    }

    /** Called when [DownloadManager] reports the download is done. */
    fun onDownloadComplete(downloadId: Long) {
        val current = _activeDownload.value ?: return
        if (current.downloadId == downloadId) {
            // Keep the state so the Activity can inspect the file path
        }
    }

    fun clearDownload() {
        _activeDownload.value = null
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
