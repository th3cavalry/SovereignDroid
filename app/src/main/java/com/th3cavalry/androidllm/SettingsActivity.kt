package com.th3cavalry.androidllm

import android.os.Bundle
import android.widget.SeekBar
import androidx.appcompat.app.AppCompatActivity
import com.th3cavalry.androidllm.databinding.ActivitySettingsBinding

class SettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySettingsBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        loadPrefs()
        setupTemperatureSlider()
    }

    private fun loadPrefs() {
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
