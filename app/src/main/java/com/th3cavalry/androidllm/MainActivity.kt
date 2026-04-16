package com.th3cavalry.androidllm

import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import androidx.activity.viewModels
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.snackbar.Snackbar
import com.th3cavalry.androidllm.databinding.ActivityMainBinding
import com.th3cavalry.androidllm.ui.ChatAdapter
import com.th3cavalry.androidllm.viewmodel.ChatViewModel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val viewModel: ChatViewModel by viewModels()
    private val chatAdapter = ChatAdapter()

    /** Theme index that was active when this Activity was (last) created. */
    private var appliedThemeIndex: Int = 0

    companion object {
        private const val MAX_DIALOG_TITLE_LENGTH = 60
        private const val PERMISSIONS_REQUEST_CODE = 1001

        /**
         * Returns only the permissions that are relevant for the current API level.
         *
         * Models downloaded via the Model Browser are saved to app-private external storage
         * (getExternalFilesDir), which the app can always read without runtime permissions.
         * The permissions below are therefore only needed for models that were previously
         * downloaded to the public Downloads folder (pre-0.1.0) and referenced by an absolute
         * path stored in Prefs — reading such legacy paths still requires the storage permission
         * on API 29–32.
         *
         * - API 33+ (Tiramisu): no storage permission needed.
         * - API 29–32: READ_EXTERNAL_STORAGE for legacy model paths in public storage.
         * - API < 29: Also need WRITE_EXTERNAL_STORAGE for DownloadManager fallback.
         *
         * READ_MEDIA_IMAGES / READ_MEDIA_VIDEO are intentionally excluded — they apply only
         * to photos and videos, not to model files (.litertlm / .task).
         */
        private fun getRequiredPermissions(): Array<String> = when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU -> emptyArray()
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q ->
                arrayOf(android.Manifest.permission.READ_EXTERNAL_STORAGE)
            else -> arrayOf(
                android.Manifest.permission.READ_EXTERNAL_STORAGE,
                android.Manifest.permission.WRITE_EXTERNAL_STORAGE
            )
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        appliedThemeIndex = ThemeHelper.applyTheme(this)
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)

        // Check and request permissions on first run
        if (!hasAllPermissions()) {
            requestPermissions()
        }

        setupRecyclerView()
        setupInput()
        observeViewModel()
        chatAdapter.showResponseInfo = Prefs.getBoolean(this, Prefs.KEY_SHOW_RESPONSE_INFO, false)
    }

    override fun onResume() {
        super.onResume()
        // Refresh the adapter's response-info toggle in case Settings changed
        chatAdapter.showResponseInfo = Prefs.getBoolean(this, Prefs.KEY_SHOW_RESPONSE_INFO, false)
        // Re-submit the current list so existing messages are rebound with the updated flag
        viewModel.messages.value?.let { chatAdapter.submitList(it.toList()) }
        // Recreate this activity if the user changed the color theme in Settings
        ThemeHelper.recreateIfNeeded(this, appliedThemeIndex)
    }

    private fun setupRecyclerView() {
        val layoutManager = LinearLayoutManager(this).apply {
            stackFromEnd = true
        }
        binding.recyclerView.apply {
            this.layoutManager = layoutManager
            adapter = chatAdapter
        }
    }

    private fun setupInput() {
        binding.btnSend.setOnClickListener { sendMessage() }
        binding.btnStop.setOnClickListener { viewModel.stopGeneration() }

        binding.etInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEND) {
                sendMessage()
                true
            } else false
        }
    }

    private fun sendMessage() {
        val text = binding.etInput.text.toString().trim()
        if (text.isEmpty()) return
        binding.etInput.text?.clear()
        viewModel.sendMessage(text)
    }

    private fun observeViewModel() {
        viewModel.messages.observe(this) { messages ->
            chatAdapter.submitList(messages.toList()) {
                if (messages.isNotEmpty()) {
                    binding.recyclerView.scrollToPosition(messages.size - 1)
                }
            }
        }

        viewModel.isLoading.observe(this) { loading ->
            binding.progressBar.visibility = if (loading) View.VISIBLE else View.GONE
            binding.btnSend.visibility = if (loading) View.GONE else View.VISIBLE
            binding.btnStop.visibility = if (loading) View.VISIBLE else View.GONE
            binding.etInput.isEnabled = !loading
        }

        viewModel.error.observe(this) { error ->
            if (error != null) {
                Snackbar.make(binding.root, error, Snackbar.LENGTH_LONG).show()
            }
        }
    }

    // ────────────────────────────────────────────────────────────
    // Permission handling
    // ────────────────────────────────────────────────────────────

    private fun hasAllPermissions(): Boolean {
        val required = getRequiredPermissions()
        if (required.isEmpty()) return true
        return required.all {
            ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
        }
    }

    private fun requestPermissions() {
        val required = getRequiredPermissions()
        if (required.isEmpty()) return
        if (shouldShowRationale(required)) {
            // Show rationale dialog
            AlertDialog.Builder(this)
                .setTitle(getString(R.string.permissions_required))
                .setMessage(getString(R.string.permissions_explanation))
                .setPositiveButton(android.R.string.ok) { _, _ ->
                    requestPermissions(required, PERMISSIONS_REQUEST_CODE)
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        } else {
            requestPermissions(required, PERMISSIONS_REQUEST_CODE)
        }
    }

    private fun shouldShowRationale(permissions: Array<String>): Boolean {
        return permissions.any { shouldShowRequestPermissionRationale(it) }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == PERMISSIONS_REQUEST_CODE) {
            val allGranted = grantResults.all { it == PackageManager.PERMISSION_GRANTED }
            if (!allGranted) {
                // Permissions denied - show warning but don't block
                Snackbar.make(
                    binding.root,
                    getString(R.string.permissions_denied_warning),
                    Snackbar.LENGTH_LONG
                ).show()
            }
        }
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.menu_main, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_settings -> {
                startActivity(Intent(this, SettingsActivity::class.java))
                true
            }
            R.id.action_terminal -> {
                startActivity(Intent(this, TerminalActivity::class.java))
                true
            }
            R.id.action_mcp -> {
                startActivity(Intent(this, MCPManagerActivity::class.java))
                true
            }
            R.id.action_save_chat -> {
                viewModel.saveCurrentSession()
                Snackbar.make(binding.root, getString(R.string.chat_saved), Snackbar.LENGTH_SHORT).show()
                true
            }
            R.id.action_history -> {
                showChatHistoryDialog()
                true
            }
            R.id.action_clear -> {
                showModelPickerThenClear()
                true
            }
            R.id.action_about -> {
                startActivity(Intent(this, AboutActivity::class.java))
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    /** Shows the saved chat history in a dialog; lets the user open, rename, or delete a session. */
    private fun showChatHistoryDialog() {
        val sessions = Prefs.getSavedSessions(this)
        if (sessions.isEmpty()) {
            Snackbar.make(binding.root, getString(R.string.no_saved_chats), Snackbar.LENGTH_SHORT).show()
            return
        }
        val fmt = SimpleDateFormat("MMM d, HH:mm", Locale.getDefault())
        val titles = sessions.map { s ->
            "${s.title.take(50)}\n${fmt.format(Date(s.timestamp))}"
        }.toTypedArray()

        AlertDialog.Builder(this)
            .setTitle(getString(R.string.chat_history_title))
            .setItems(titles) { _, idx ->
                showSessionOptionsDialog(sessions[idx].id, sessions[idx].title)
            }
            .setNeutralButton(getString(R.string.cancel), null)
            .show()
    }

    /** Shows Open / Rename / Delete options for a single saved session. */
    private fun showSessionOptionsDialog(sessionId: Long, sessionTitle: String) {
        val options = arrayOf(
            getString(R.string.session_open),
            getString(R.string.session_rename),
            getString(R.string.delete)
        )
        AlertDialog.Builder(this)
            .setTitle(sessionTitle.take(MAX_DIALOG_TITLE_LENGTH))
            .setItems(options) { _, which ->
                when (which) {
                    0 -> {
                        val session = Prefs.getSavedSessions(this).firstOrNull { it.id == sessionId }
                        if (session != null) viewModel.loadSession(session)
                    }
                    1 -> showRenameSessionDialog(sessionId, sessionTitle)
                    2 -> {
                        AlertDialog.Builder(this)
                            .setMessage(getString(R.string.delete_chat_confirm))
                            .setPositiveButton(getString(R.string.delete)) { _, _ ->
                                Prefs.deleteSession(this, sessionId)
                            }
                            .setNegativeButton(getString(R.string.cancel), null)
                            .show()
                    }
                }
            }
            .setNegativeButton(getString(R.string.cancel), null)
            .show()
    }

    /** Shows an EditText dialog that lets the user rename a saved session. */
    private fun showRenameSessionDialog(sessionId: Long, currentTitle: String) {
        val input = EditText(this).apply {
            setText(currentTitle)
            selectAll()
            setSingleLine()
        }
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.session_rename))
            .setView(input)
            .setPositiveButton(getString(R.string.rename)) { _, _ ->
                val newTitle = input.text.toString().trim()
                if (newTitle.isNotEmpty()) {
                    Prefs.renameSession(this, sessionId, newTitle)
                }
            }
            .setNegativeButton(getString(R.string.cancel), null)
            .show()
    }

    /**
     * When clearing history, optionally let the user pick a model first (issue #5).
     * If the remote model list has been fetched before, shows it as a selection dialog.
     */
    private fun showModelPickerThenClear() {
        val backendKey = Prefs.getString(this, Prefs.KEY_INFERENCE_BACKEND, Prefs.BACKEND_REMOTE)
        val currentModel = Prefs.getString(this, Prefs.KEY_LLM_MODEL, Prefs.DEFAULT_MODEL)

        // Only show the model-picker for backends that use a named model
        val usesModelName = backendKey == Prefs.BACKEND_REMOTE ||
            backendKey == Prefs.BACKEND_OLLAMA_LOCAL

        if (!usesModelName) {
            viewModel.clearHistory()
            return
        }

        val savedModels = Prefs.getRemoteModelIds(this)
        if (savedModels.isEmpty()) {
            // No cached models — just confirm and clear
            AlertDialog.Builder(this)
                .setTitle(getString(R.string.action_clear_chat))
                .setMessage(getString(R.string.select_model_hint))
                .setPositiveButton(getString(R.string.clear)) { _, _ -> viewModel.clearHistory() }
                .setNegativeButton(getString(R.string.cancel), null)
                .show()
            return
        }

        val options = savedModels.toTypedArray()
        val currentIdx = options.indexOf(currentModel).takeIf { it >= 0 } ?: -1

        AlertDialog.Builder(this)
            .setTitle(getString(R.string.action_clear_chat))
            .setSingleChoiceItems(options, currentIdx, null)
            .setPositiveButton(getString(R.string.clear)) { dialog, _ ->
                val d = dialog as AlertDialog
                val pos = d.listView.checkedItemPosition
                if (pos >= 0 && pos < options.size) {
                    Prefs.putString(this, Prefs.KEY_LLM_MODEL, options[pos])
                }
                viewModel.clearHistory()
            }
            .setNegativeButton(getString(R.string.cancel), null)
            .show()
    }
}
