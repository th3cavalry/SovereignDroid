package com.th3cavalry.androidllm

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.ArrayAdapter
import android.widget.RadioGroup
import android.widget.SeekBar
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.snackbar.Snackbar
import com.th3cavalry.androidllm.databinding.ActivitySettingsBinding
import com.th3cavalry.androidllm.network.RetrofitClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

class SettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySettingsBinding

    companion object {
        /** File extensions accepted as on-device model files. */
        private val SUPPORTED_MODEL_EXTENSIONS = setOf("task", "bin", "gguf", "ggml", "litertlm")
    }

    /** File picker for MediaPipe .task model files. */
    private val pickMediaPipeModelLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri == null) return@registerForActivityResult
        val name = resolveFileName(uri) ?: ""
        val ext = name.substringAfterLast('.', "").lowercase()
        if (ext !in SUPPORTED_MODEL_EXTENSIONS) {
            Snackbar.make(
                binding.root,
                "Unsupported file type \".$ext\". Please choose a .task or .bin model file.",
                Snackbar.LENGTH_LONG
            ).show()
            return@registerForActivityResult
        }
        copyModelFile(uri, destPrefKey = Prefs.KEY_ON_DEVICE_MODEL_PATH) { path ->
            binding.etModelPath.setText(path)
        }
    }

    /** File picker for LiteRT-LM .litertlm model files. */
    private val pickLiteRtLmModelLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri == null) return@registerForActivityResult
        val name = resolveFileName(uri) ?: ""
        val ext = name.substringAfterLast('.', "").lowercase()
        if (ext !in SUPPORTED_MODEL_EXTENSIONS) {
            Snackbar.make(
                binding.root,
                "Unsupported file type \".$ext\". Please choose a .litertlm model file.",
                Snackbar.LENGTH_LONG
            ).show()
            return@registerForActivityResult
        }
        copyModelFile(uri, destPrefKey = Prefs.KEY_LITERT_LM_MODEL_PATH) { path ->
            binding.etLiteRtLmModelPath.setText(path)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        loadPrefs()
        setupTemperatureSlider()
        setupBackendSelector()
        setupBrowseButtons()
        setupFetchModels()
    }

    private fun loadPrefs() {
        // Inference backend selector
        val backend = Prefs.getString(this, Prefs.KEY_INFERENCE_BACKEND, Prefs.BACKEND_REMOTE)
        val radioId = when (backend) {
            Prefs.BACKEND_MEDIAPIPE    -> R.id.rbMediaPipe
            Prefs.BACKEND_LITERT_LM   -> R.id.rbLiteRtLm
            Prefs.BACKEND_OLLAMA_LOCAL -> R.id.rbOllamaLocal
            Prefs.BACKEND_GEMINI_NANO  -> R.id.rbGeminiNano
            else                       -> R.id.rbRemote
        }
        binding.rgBackend.check(radioId)
        updateBackendSections(backend)

        // Model paths for each local backend
        binding.etModelPath.setText(Prefs.getString(this, Prefs.KEY_ON_DEVICE_MODEL_PATH))
        binding.etLiteRtLmModelPath.setText(Prefs.getString(this, Prefs.KEY_LITERT_LM_MODEL_PATH))

        // HF token
        binding.etHfToken.setText(Prefs.getString(this, Prefs.KEY_HF_TOKEN))

        // Remote API
        binding.etLlmEndpoint.setText(
            Prefs.getString(this, Prefs.KEY_LLM_ENDPOINT, Prefs.DEFAULT_ENDPOINT)
        )
        binding.etLlmApiKey.setText(Prefs.getString(this, Prefs.KEY_LLM_API_KEY))
        binding.etLlmModel.setText(
            Prefs.getString(this, Prefs.KEY_LLM_MODEL, Prefs.DEFAULT_MODEL)
        )
        binding.etMaxTokens.setText(
            Prefs.getInt(this, Prefs.KEY_LLM_MAX_TOKENS, Prefs.DEFAULT_MAX_TOKENS).toString()
        )
        val temp = Prefs.getFloat(this, Prefs.KEY_LLM_TEMPERATURE, Prefs.DEFAULT_TEMPERATURE)
        binding.seekTemperature.progress = (temp * 10).toInt()
        binding.tvTemperature.text = "Temperature: ${"%.1f".format(temp)}"

        // Search provider
        val provider = Prefs.getString(this, Prefs.KEY_SEARCH_PROVIDER, "duckduckgo")
        val providerIndex = when (provider) {
            "brave" -> 1
            "serpapi" -> 2
            else -> 0
        }
        binding.spinnerSearchProvider.setSelection(providerIndex)
        binding.etSearchApiKey.setText(Prefs.getString(this, Prefs.KEY_SEARCH_API_KEY))

        // GitHub
        binding.etGithubToken.setText(Prefs.getString(this, Prefs.KEY_GITHUB_TOKEN))

        // SSH
        binding.etSshDefaultHost.setText(Prefs.getString(this, Prefs.KEY_SSH_DEFAULT_HOST))
        binding.etSshDefaultUser.setText(Prefs.getString(this, Prefs.KEY_SSH_DEFAULT_USER))
        binding.etSshPrivateKey.setText(Prefs.getString(this, Prefs.KEY_SSH_DEFAULT_KEY))

        // Chat appearance toggles
        binding.switchHideToolMessages.isChecked = Prefs.getBoolean(
            this, Prefs.KEY_HIDE_TOOL_MESSAGES, false
        )
        binding.switchShowResponseInfo.isChecked = Prefs.getBoolean(
            this, Prefs.KEY_SHOW_RESPONSE_INFO, false
        )

        // Restore previously fetched remote model list
        restoreRemoteModelsSpinner(Prefs.getRemoteModelIds(this))
    }

    private fun setupBackendSelector() {
        binding.rgBackend.setOnCheckedChangeListener { _: RadioGroup, checkedId: Int ->
            val backend = when (checkedId) {
                R.id.rbMediaPipe    -> Prefs.BACKEND_MEDIAPIPE
                R.id.rbLiteRtLm    -> Prefs.BACKEND_LITERT_LM
                R.id.rbOllamaLocal -> Prefs.BACKEND_OLLAMA_LOCAL
                R.id.rbGeminiNano  -> Prefs.BACKEND_GEMINI_NANO
                else               -> Prefs.BACKEND_REMOTE
            }
            updateBackendSections(backend)
            // Pre-fill the endpoint field with the Ollama default if the field is blank
            // or still contains a different value. Only auto-fill when switching to Ollama.
            if (backend == Prefs.BACKEND_OLLAMA_LOCAL &&
                binding.etLlmEndpoint.text.toString().trim() != "http://localhost:11434/v1"
            ) {
                binding.etLlmEndpoint.setText("http://localhost:11434/v1")
            }
        }
    }

    /** Shows/hides the per-backend configuration sections. */
    private fun updateBackendSections(backend: String) {
        binding.layoutMediaPipeModel.visibility =
            if (backend == Prefs.BACKEND_MEDIAPIPE) View.VISIBLE else View.GONE
        binding.layoutLiteRtLmModel.visibility =
            if (backend == Prefs.BACKEND_LITERT_LM) View.VISIBLE else View.GONE
        binding.cardOllamaInfo.visibility =
            if (backend == Prefs.BACKEND_OLLAMA_LOCAL) View.VISIBLE else View.GONE
        binding.cardGeminiNanoInfo.visibility =
            if (backend == Prefs.BACKEND_GEMINI_NANO) View.VISIBLE else View.GONE
    }

    private fun setupBrowseButtons() {
        binding.btnBrowseModel.setOnClickListener {
            pickMediaPipeModelLauncher.launch(arrayOf("*/*"))
        }
        binding.btnBrowseModelCatalog.setOnClickListener {
            startActivity(
                Intent(this, ModelBrowserActivity::class.java)
                    .putExtra(ModelBrowserActivity.EXTRA_BACKEND_ID, Prefs.BACKEND_MEDIAPIPE)
            )
        }
        binding.btnBrowseLiteRtLmModel.setOnClickListener {
            pickLiteRtLmModelLauncher.launch(arrayOf("*/*"))
        }
        binding.btnBrowseLiteRtLmCatalog.setOnClickListener {
            startActivity(
                Intent(this, ModelBrowserActivity::class.java)
                    .putExtra(ModelBrowserActivity.EXTRA_BACKEND_ID, Prefs.BACKEND_LITERT_LM)
            )
        }
    }

    /** Wires the "Fetch models" button to call GET /v1/models on the configured endpoint. */
    private fun setupFetchModels() {
        binding.btnFetchModels.setOnClickListener {
            val endpoint = binding.etLlmEndpoint.text.toString().trim().ifBlank { Prefs.DEFAULT_ENDPOINT }
            val apiKey = binding.etLlmApiKey.text.toString().trim()
            val auth = if (apiKey.isBlank()) "Bearer none" else "Bearer $apiKey"

            binding.btnFetchModels.isEnabled = false
            val snack = Snackbar.make(binding.root, getString(R.string.fetching_models), Snackbar.LENGTH_INDEFINITE)
            snack.show()

            lifecycleScope.launch {
                val result = withContext(Dispatchers.IO) {
                    runCatching {
                        RetrofitClient.buildLLMApi(endpoint).listModels(auth)
                    }
                }
                snack.dismiss()
                binding.btnFetchModels.isEnabled = true

                val response = result.getOrElse { e ->
                    Snackbar.make(
                        binding.root,
                        getString(R.string.fetch_models_failed, e.message ?: "unknown error"),
                        Snackbar.LENGTH_LONG
                    ).show()
                    return@launch
                }

                if (!response.isSuccessful) {
                    Snackbar.make(
                        binding.root,
                        getString(R.string.fetch_models_failed, "HTTP ${response.code()}"),
                        Snackbar.LENGTH_LONG
                    ).show()
                    return@launch
                }

                val ids = response.body()?.data?.map { it.id }?.sorted() ?: emptyList()
                if (ids.isEmpty()) {
                    Snackbar.make(binding.root, getString(R.string.no_models_available), Snackbar.LENGTH_SHORT).show()
                } else {
                    Prefs.saveRemoteModelIds(this@SettingsActivity, ids)
                    restoreRemoteModelsSpinner(ids)
                    Snackbar.make(binding.root, "${ids.size} model(s) found", Snackbar.LENGTH_SHORT).show()
                }
            }
        }
    }

    /** Populates (or refreshes) the remote-models spinner and wires selection to the model field. */
    private fun restoreRemoteModelsSpinner(ids: List<String>) {
        if (ids.isEmpty()) {
            binding.spinnerRemoteModels.visibility = View.GONE
            return
        }
        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, ids).also {
            it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        }
        binding.spinnerRemoteModels.adapter = adapter
        binding.spinnerRemoteModels.visibility = View.VISIBLE

        // Pre-select the currently configured model
        val currentModel = binding.etLlmModel.text.toString().trim()
        val idx = ids.indexOf(currentModel)
        if (idx >= 0) binding.spinnerRemoteModels.setSelection(idx)

        binding.spinnerRemoteModels.onItemSelectedListener =
            object : android.widget.AdapterView.OnItemSelectedListener {
                override fun onItemSelected(
                    parent: android.widget.AdapterView<*>, view: View?, pos: Int, id: Long
                ) {
                    binding.etLlmModel.setText(ids[pos])
                }
                override fun onNothingSelected(parent: android.widget.AdapterView<*>) {}
            }
    }

    /**
     * Copies the model file chosen by the user to internal storage so the inference
     * backends can access it by file path. Shows progress via Snackbar.
     *
     * @param destPrefKey  The [Prefs] key to write the resolved path to.
     * @param onSuccess    Called on the main thread with the absolute destination path.
     */
    private fun copyModelFile(uri: Uri, destPrefKey: String, onSuccess: (String) -> Unit) {
        val snack = Snackbar.make(binding.root, getString(R.string.model_copying), Snackbar.LENGTH_INDEFINITE)
        snack.show()

        lifecycleScope.launch {
            val destPath = withContext(Dispatchers.IO) {
                runCatching {
                    val modelsDir = File(filesDir, "models").apply { mkdirs() }
                    val fileName = resolveFileName(uri) ?: "model.task"
                    val destFile = File(modelsDir, fileName)
                    contentResolver.openInputStream(uri)?.use { input ->
                        FileOutputStream(destFile).use { output ->
                            input.copyTo(output)
                        }
                    }
                    destFile.absolutePath
                }.getOrNull()
            }

            snack.dismiss()
            if (destPath != null) {
                Prefs.putString(this@SettingsActivity, destPrefKey, destPath)
                onSuccess(destPath)
                Snackbar.make(binding.root, getString(R.string.model_ready), Snackbar.LENGTH_SHORT).show()
            } else {
                Snackbar.make(binding.root, getString(R.string.model_copy_failed), Snackbar.LENGTH_SHORT).show()
            }
        }
    }

    /** Resolves a human-readable file name from a content URI. */
    private fun resolveFileName(uri: Uri): String? {
        if (uri.scheme == "file") return File(uri.path ?: return null).name
        return contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val nameIndex = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
            if (cursor.moveToFirst() && nameIndex >= 0) cursor.getString(nameIndex) else null
        }
    }

    private fun setupTemperatureSlider() {
        binding.seekTemperature.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                val temp = progress / 10f
                binding.tvTemperature.text = "Temperature: ${"%.1f".format(temp)}"
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })
    }

    private fun savePrefs() {
        // Inference backend
        val backend = when (binding.rgBackend.checkedRadioButtonId) {
            R.id.rbMediaPipe    -> Prefs.BACKEND_MEDIAPIPE
            R.id.rbLiteRtLm    -> Prefs.BACKEND_LITERT_LM
            R.id.rbOllamaLocal -> Prefs.BACKEND_OLLAMA_LOCAL
            R.id.rbGeminiNano  -> Prefs.BACKEND_GEMINI_NANO
            else               -> Prefs.BACKEND_REMOTE
        }
        Prefs.putString(this, Prefs.KEY_INFERENCE_BACKEND, backend)

        // Model paths (each backend remembers its own path independently)
        Prefs.putString(
            this, Prefs.KEY_ON_DEVICE_MODEL_PATH,
            binding.etModelPath.text.toString().trim()
        )
        Prefs.putString(
            this, Prefs.KEY_LITERT_LM_MODEL_PATH,
            binding.etLiteRtLmModelPath.text.toString().trim()
        )

        // When Ollama on-device is selected, ensure the endpoint is localhost.
        // The UI field is already pre-filled by setupBackendSelector; this is a safety net.
        val endpointToSave = if (backend == Prefs.BACKEND_OLLAMA_LOCAL) {
            "http://localhost:11434/v1"
        } else {
            binding.etLlmEndpoint.text.toString().trim().ifBlank { Prefs.DEFAULT_ENDPOINT }
        }

        // Remote API
        Prefs.putString(this, Prefs.KEY_LLM_ENDPOINT, endpointToSave)
        Prefs.putString(this, Prefs.KEY_LLM_API_KEY, binding.etLlmApiKey.text.toString().trim())
        Prefs.putString(
            this, Prefs.KEY_LLM_MODEL,
            binding.etLlmModel.text.toString().trim().ifBlank { Prefs.DEFAULT_MODEL }
        )
        val maxTokens = binding.etMaxTokens.text.toString().toIntOrNull() ?: Prefs.DEFAULT_MAX_TOKENS
        Prefs.putInt(this, Prefs.KEY_LLM_MAX_TOKENS, maxTokens)
        Prefs.putFloat(this, Prefs.KEY_LLM_TEMPERATURE, binding.seekTemperature.progress / 10f)

        val provider = when (binding.spinnerSearchProvider.selectedItemPosition) {
            1 -> "brave"
            2 -> "serpapi"
            else -> "duckduckgo"
        }
        Prefs.putString(this, Prefs.KEY_SEARCH_PROVIDER, provider)
        Prefs.putString(this, Prefs.KEY_SEARCH_API_KEY, binding.etSearchApiKey.text.toString().trim())
        Prefs.putString(this, Prefs.KEY_GITHUB_TOKEN, binding.etGithubToken.text.toString().trim())
        Prefs.putString(this, Prefs.KEY_HF_TOKEN, binding.etHfToken.text.toString().trim())
        Prefs.putString(this, Prefs.KEY_SSH_DEFAULT_HOST, binding.etSshDefaultHost.text.toString().trim())
        Prefs.putString(this, Prefs.KEY_SSH_DEFAULT_USER, binding.etSshDefaultUser.text.toString().trim())
        Prefs.putString(this, Prefs.KEY_SSH_DEFAULT_KEY, binding.etSshPrivateKey.text.toString().trim())

        // Chat appearance
        Prefs.putBoolean(this, Prefs.KEY_HIDE_TOOL_MESSAGES, binding.switchHideToolMessages.isChecked)
        Prefs.putBoolean(this, Prefs.KEY_SHOW_RESPONSE_INFO, binding.switchShowResponseInfo.isChecked)
    }

    override fun onResume() {
        super.onResume()
        // Refresh model paths — the Model Browser may have updated them while we were away
        binding.etModelPath.setText(Prefs.getString(this, Prefs.KEY_ON_DEVICE_MODEL_PATH))
        binding.etLiteRtLmModelPath.setText(Prefs.getString(this, Prefs.KEY_LITERT_LM_MODEL_PATH))
    }

    override fun onPause() {
        super.onPause()
        savePrefs()
    }

    override fun onSupportNavigateUp(): Boolean {
        onBackPressedDispatcher.onBackPressed()
        return true
    }
}
