package com.th3cavalry.androidllm

import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.activity.viewModels
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.widget.addTextChangedListener
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.th3cavalry.androidllm.data.HfModelDto
import com.th3cavalry.androidllm.data.HfSiblingDto
import com.th3cavalry.androidllm.databinding.ActivityModelBrowserBinding
import com.th3cavalry.androidllm.databinding.SheetModelFilesBinding
import com.th3cavalry.androidllm.ui.ModelCatalogAdapter
import com.th3cavalry.androidllm.ui.ModelFilesAdapter
import com.th3cavalry.androidllm.viewmodel.ModelBrowserViewModel
import com.th3cavalry.androidllm.viewmodel.ModelDetailState
import com.th3cavalry.androidllm.viewmodel.ModelListState
import kotlinx.coroutines.launch

/**
 * Browsable model catalog backed by the **Hugging Face Hub API**.
 *
 * Shows a list of on-device LLM models from the `litert-community` organisation, filtered by
 * the calling backend (MediaPipe `.task` or LiteRT-LM `.litertlm`). Tapping a model opens a
 * bottom sheet with all downloadable file variants (sorted by size). Downloading uses Android
 * [DownloadManager]; on completion the user is offered to set the file as their model path.
 *
 * ### Intent extras
 * - [EXTRA_BACKEND_ID]: One of [Prefs.BACKEND_MEDIAPIPE] or [Prefs.BACKEND_LITERT_LM].
 *   Controls which file extension is shown and which preference is updated.
 *
 * ### Result
 * No result is set. The model path preference is written directly into [Prefs] so the caller
 * (SettingsActivity) refreshes its field from Prefs in `onResume`.
 */
class ModelBrowserActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_BACKEND_ID = "backend_id"
    }

    private lateinit var binding: ActivityModelBrowserBinding
    private val viewModel: ModelBrowserViewModel by viewModels()

    private lateinit var catalogAdapter: ModelCatalogAdapter
    private var allModels: List<HfModelDto> = emptyList()

    // Tracks the DownloadManager download ID so we know which completion to handle
    private var pendingDownloadId: Long = -1L

    private val downloadReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val id = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1L)
            if (id == pendingDownloadId) onDownloadComplete(id)
        }
    }

    // ────────────────────────────────────────────────────────────
    // Lifecycle
    // ────────────────────────────────────────────────────────────

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityModelBrowserBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val backendId = intent.getStringExtra(EXTRA_BACKEND_ID) ?: Prefs.BACKEND_LITERT_LM
        viewModel.backendId = backendId

        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = when (backendId) {
            Prefs.BACKEND_MEDIAPIPE -> getString(R.string.browse_models_mediapipe)
            else                    -> getString(R.string.browse_models_litert)
        }

        setupRecyclerView()
        setupSearch()
        observeState()

        viewModel.loadModels()
        registerDownloadReceiver()
    }

    override fun onDestroy() {
        super.onDestroy()
        unregisterReceiver(downloadReceiver)
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }

    // ────────────────────────────────────────────────────────────
    // Setup
    // ────────────────────────────────────────────────────────────

    private fun setupRecyclerView() {
        catalogAdapter = ModelCatalogAdapter(onSelect = ::openModelDetail)
        binding.rvModels.apply {
            layoutManager = LinearLayoutManager(this@ModelBrowserActivity)
            adapter        = catalogAdapter
        }
    }

    private fun setupSearch() {
        binding.etSearch.addTextChangedListener { editable ->
            val query = editable?.toString()?.trim() ?: ""
            val filtered = if (query.isBlank()) allModels
            else allModels.filter { it.id.contains(query, ignoreCase = true) }
            catalogAdapter.submitList(filtered)
        }
    }

    private fun registerDownloadReceiver() {
        val filter = IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(downloadReceiver, filter, RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            registerReceiver(downloadReceiver, filter)
        }
    }

    // ────────────────────────────────────────────────────────────
    // State observation
    // ────────────────────────────────────────────────────────────

    private fun observeState() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { viewModel.listState.collect(::renderListState) }
                launch { viewModel.detailState.collect(::renderDetailState) }
            }
        }
    }

    private fun renderListState(state: ModelListState) {
        when (state) {
            is ModelListState.Loading -> {
                binding.progressBar.visibility = View.VISIBLE
                binding.tvError.visibility     = View.GONE
                binding.rvModels.visibility    = View.GONE
            }
            is ModelListState.Success -> {
                binding.progressBar.visibility = View.GONE
                binding.tvError.visibility     = View.GONE
                binding.rvModels.visibility    = View.VISIBLE
                allModels = state.models
                catalogAdapter.submitList(state.models)
                if (state.models.isEmpty()) {
                    binding.tvError.text       = getString(R.string.no_models_found)
                    binding.tvError.visibility = View.VISIBLE
                }
            }
            is ModelListState.Error -> {
                binding.progressBar.visibility = View.GONE
                binding.tvError.visibility     = View.VISIBLE
                binding.rvModels.visibility    = View.GONE
                binding.tvError.text           = state.message
            }
        }
    }

    // Opened as a BottomSheet once detail has loaded
    private fun renderDetailState(state: ModelDetailState) {
        when (state) {
            is ModelDetailState.Loading -> { /* spinner shown in bottom sheet */ }
            is ModelDetailState.Success -> showFilesBottomSheet(state.model, state.files)
            is ModelDetailState.Error   -> Toast.makeText(this, state.message, Toast.LENGTH_LONG).show()
            else -> {}
        }
    }

    // ────────────────────────────────────────────────────────────
    // Model detail bottom sheet
    // ────────────────────────────────────────────────────────────

    private fun openModelDetail(model: HfModelDto) {
        viewModel.loadModelDetail(model.id)
        // Show a progress dialog while loading; the actual sheet opens in renderDetailState
        Toast.makeText(this, getString(R.string.loading_model_files), Toast.LENGTH_SHORT).show()
    }

    private fun showFilesBottomSheet(model: HfModelDto, files: List<HfSiblingDto>) {
        if (files.isEmpty()) {
            Toast.makeText(this, getString(R.string.no_files_for_backend), Toast.LENGTH_LONG).show()
            viewModel.clearDetail()
            return
        }

        val sheetBinding = SheetModelFilesBinding.inflate(layoutInflater)
        val sheet        = BottomSheetDialog(this)
        sheet.setContentView(sheetBinding.root)

        sheetBinding.tvSheetTitle.text = model.displayName
        val filesAdapter = ModelFilesAdapter(onDownload = { sibling ->
            sheet.dismiss()
            confirmAndDownload(model.id, sibling)
        })
        sheetBinding.rvFiles.apply {
            layoutManager = LinearLayoutManager(this@ModelBrowserActivity)
            adapter        = filesAdapter
        }
        filesAdapter.submitList(files)

        sheet.setOnDismissListener { viewModel.clearDetail() }
        sheet.show()
    }

    // ────────────────────────────────────────────────────────────
    // Download
    // ────────────────────────────────────────────────────────────

    private fun confirmAndDownload(modelId: String, sibling: HfSiblingDto) {
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.download_model_title))
            .setMessage(
                getString(
                    R.string.download_model_message,
                    sibling.rfilename,
                    sibling.sizeFormatted
                )
            )
            .setPositiveButton(android.R.string.ok) { _, _ ->
                pendingDownloadId = viewModel.enqueueDownload(modelId, sibling)
                Toast.makeText(
                    this,
                    getString(R.string.download_started, sibling.rfilename),
                    Toast.LENGTH_LONG
                ).show()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun onDownloadComplete(downloadId: Long) {
        viewModel.onDownloadComplete(downloadId)
        val download = viewModel.activeDownload.value ?: return
        val backendId = viewModel.backendId

        AlertDialog.Builder(this)
            .setTitle(getString(R.string.download_complete_title))
            .setMessage(getString(R.string.download_complete_message, download.filename))
            .setPositiveButton(getString(R.string.set_as_model)) { _, _ ->
                val prefKey = when (backendId) {
                    Prefs.BACKEND_MEDIAPIPE -> Prefs.KEY_ON_DEVICE_MODEL_PATH
                    else                    -> Prefs.KEY_LITERT_LM_MODEL_PATH
                }
                Prefs.putString(this, prefKey, download.targetPath)
                Toast.makeText(this, getString(R.string.model_path_updated), Toast.LENGTH_SHORT).show()
                viewModel.clearDownload()
            }
            .setNegativeButton(getString(R.string.dismiss)) { _, _ ->
                viewModel.clearDownload()
            }
            .show()
    }
}
