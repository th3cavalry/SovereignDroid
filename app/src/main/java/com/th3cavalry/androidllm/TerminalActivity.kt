package com.th3cavalry.androidllm

import android.os.Bundle
import android.view.View
import android.view.inputmethod.EditorInfo
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.th3cavalry.androidllm.databinding.ActivityTerminalBinding
import com.th3cavalry.androidllm.service.SSHService
import kotlinx.coroutines.launch

/**
 * Interactive SSH terminal activity.
 * Users enter host/user/password once, then run commands in a scrolling output view.
 */
class TerminalActivity : AppCompatActivity() {

    private lateinit var binding: ActivityTerminalBinding
    private val sshService = SSHService()
    private var appliedThemeIndex: Int = 0

    private var connectedHost: String = ""
    private var connectedUser: String = ""
    private var connectedPassword: String = ""
    private var connectedKey: String = ""
    private var connectedPort: Int = 22

    override fun onCreate(savedInstanceState: Bundle?) {
        appliedThemeIndex = ThemeHelper.applyTheme(this)
        super.onCreate(savedInstanceState)
        binding = ActivityTerminalBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        // Load defaults from settings
        binding.etHost.setText(Prefs.getString(this, Prefs.KEY_SSH_DEFAULT_HOST))
        binding.etUsername.setText(Prefs.getString(this, Prefs.KEY_SSH_DEFAULT_USER))

        setupListeners()
    }

    private fun setupListeners() {
        binding.btnConnect.setOnClickListener { connect() }

        binding.etCommand.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEND) {
                runCommand()
                true
            } else false
        }

        binding.btnRun.setOnClickListener { runCommand() }
        binding.btnClear.setOnClickListener { binding.tvOutput.text = "" }
    }

    private fun connect() {
        val host = binding.etHost.text.toString().trim()
        val user = binding.etUsername.text.toString().trim()
        val password = binding.etPassword.text.toString()
        val key = Prefs.getSecret(this, Prefs.KEY_SSH_DEFAULT_KEY)
        val port = binding.etPort.text.toString().toIntOrNull() ?: 22

        if (host.isEmpty() || user.isEmpty()) {
            appendOutput("Error: host and username are required.\n")
            return
        }

        connectedHost = host
        connectedUser = user
        connectedPassword = password
        connectedKey = key
        connectedPort = port

        appendOutput("Connected to $user@$host:$port\n> ")
        showCommandPanel()
    }

    private fun showCommandPanel() {
        binding.cardConnect.visibility = View.GONE
        binding.cardCommand.visibility = View.VISIBLE
    }

    private fun runCommand() {
        if (connectedHost.isEmpty()) {
            appendOutput("Not connected. Please connect first.\n")
            return
        }

        val command = binding.etCommand.text.toString().trim()
        if (command.isEmpty()) return
        binding.etCommand.text?.clear()

        appendOutput("$ $command\n")

        binding.progressTerminal.visibility = View.VISIBLE
        binding.btnRun.isEnabled = false

        lifecycleScope.launch {
            val result = try {
                sshService.executeCommand(
                    host = connectedHost,
                    port = connectedPort,
                    username = connectedUser,
                    password = connectedPassword.ifBlank { null },
                    privateKey = connectedKey.ifBlank { null },
                    command = command
                )
            } catch (e: Exception) {
                "Error: ${e.message}"
            }

            appendOutput("$result\n\n> ")
            binding.progressTerminal.visibility = View.GONE
            binding.btnRun.isEnabled = true
            scrollToBottom()
        }
    }

    private fun appendOutput(text: String) {
        binding.tvOutput.append(text)
        scrollToBottom()
    }

    private fun scrollToBottom() {
        binding.scrollOutput.post {
            binding.scrollOutput.fullScroll(View.FOCUS_DOWN)
        }
    }

    override fun onResume() {
        super.onResume()
        ThemeHelper.recreateIfNeeded(this, appliedThemeIndex)
    }

    override fun onSupportNavigateUp(): Boolean {
        onBackPressedDispatcher.onBackPressed()
        return true
    }
}
