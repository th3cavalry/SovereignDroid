package com.th3cavalry.androidllm

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.SeekBar
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.snackbar.Snackbar
import com.th3cavalry.androidllm.databinding.ActivitySettingsBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

class SettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySettingsBinding

    /** File picker that copies the chosen .task model file to internal storage. */
    private val pickModelLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri != null) copyModelFile(uri)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        loadPrefs()
        setupTemperatureSlider()
        setupOnDeviceToggle()
        setupBrowseButton()
    }

    private fun loadPrefs() {
        // On-device
        val onDevice = Prefs.getBoolean(this, Prefs.KEY_ON_DEVICE_ENABLED)
        binding.switchOnDevice.isChecked = onDevice
        binding.layoutOnDeviceModel.visibility = if (onDevice) View.VISIBLE else View.GONE
        binding.etModelPath.setText(Prefs.getString(this, Prefs.KEY_ON_DEVICE_MODEL_PATH))

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
    }

    private fun setupOnDeviceToggle() {
        binding.switchOnDevice.setOnCheckedChangeListener { _, checked ->
            binding.layoutOnDeviceModel.visibility = if (checked) View.VISIBLE else View.GONE
        }
    }

    private fun setupBrowseButton() {
        binding.btnBrowseModel.setOnClickListener {
            // Open the system file picker for any file type (model files have no standard MIME)
            pickModelLauncher.launch(arrayOf("*/*"))
        }
    }

    /**
     * Copies the model file chosen by the user to internal storage so MediaPipe
     * can access it by file path (content:// URIs are not supported by MediaPipe directly).
     * Shows progress via Snackbar.
     */
    private fun copyModelFile(uri: Uri) {
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
                binding.etModelPath.setText(destPath)
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
        // On-device
        Prefs.putBoolean(this, Prefs.KEY_ON_DEVICE_ENABLED, binding.switchOnDevice.isChecked)
        Prefs.putString(
            this, Prefs.KEY_ON_DEVICE_MODEL_PATH,
            binding.etModelPath.text.toString().trim()
        )

        // Remote API
        Prefs.putString(
            this, Prefs.KEY_LLM_ENDPOINT,
            binding.etLlmEndpoint.text.toString().trim().ifBlank { Prefs.DEFAULT_ENDPOINT }
        )
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
        Prefs.putString(this, Prefs.KEY_SSH_DEFAULT_HOST, binding.etSshDefaultHost.text.toString().trim())
        Prefs.putString(this, Prefs.KEY_SSH_DEFAULT_USER, binding.etSshDefaultUser.text.toString().trim())
        Prefs.putString(this, Prefs.KEY_SSH_DEFAULT_KEY, binding.etSshPrivateKey.text.toString().trim())
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

