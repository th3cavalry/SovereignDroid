package com.th3cavalry.androidllm

import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.snackbar.Snackbar
import com.th3cavalry.androidllm.data.ChatSession
import com.th3cavalry.androidllm.databinding.ActivityMainBinding
import com.th3cavalry.androidllm.service.DocumentLoader
import com.th3cavalry.androidllm.ui.ChatAdapter
import com.th3cavalry.androidllm.viewmodel.ChatViewModel
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val viewModel: ChatViewModel by viewModels()
    private val chatAdapter by lazy {
        ChatAdapter(onRetryError = { errorMessage ->
            viewModel.retryMessage(errorMessage)
        })
    }

    /** Theme index that was active when this Activity was (last) created. */
    private var appliedThemeIndex: Int = 0

    /** Currently attached image URI for multimodal messages. */
    private var pendingImageUri: Uri? = null

    private val pickImageLauncher = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        if (uri != null) {
            pendingImageUri = uri
            binding.ivAttachedImage.setImageURI(uri)
            binding.imagePreviewContainer.visibility = View.VISIBLE
        }
    }

    private val pickDocumentLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri != null) {
            try {
                val doc = DocumentLoader(this).load(uri)
                viewModel.setDocumentContext(doc.content)
                Snackbar.make(
                    binding.root,
                    getString(R.string.document_loaded, doc.charCount),
                    Snackbar.LENGTH_LONG
                ).setAction(getString(R.string.clear)) {
                    viewModel.setDocumentContext(null)
                }.show()
            } catch (e: Exception) {
                Snackbar.make(
                    binding.root,
                    getString(R.string.document_load_failed, e.message),
                    Snackbar.LENGTH_LONG
                ).show()
            }
        }
    }

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

        // Migrate legacy SharedPreferences sessions to Room on first launch
        viewModel.migrateFromPrefsIfNeeded()

        // If launched from widget, focus the input field
        if (intent?.getBooleanExtra(QuickPromptWidget.EXTRA_FOCUS_INPUT, false) == true) {
            binding.etInput.requestFocus()
        }
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

        // Image attachment
        binding.btnAttach.setOnClickListener {
            pickImageLauncher.launch("image/*")
        }
        binding.btnRemoveImage.setOnClickListener {
            pendingImageUri = null
            binding.imagePreviewContainer.visibility = View.GONE
        }
    }

    private fun sendMessage() {
        val text = binding.etInput.text.toString().trim()
        if (text.isEmpty()) return
        binding.etInput.text?.clear()
        val imageUri = pendingImageUri?.toString()
        pendingImageUri = null
        binding.imagePreviewContainer.visibility = View.GONE
        viewModel.sendMessage(text, imageUri)
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

        viewModel.memoryWarning.observe(this) { warning ->
            if (warning != null) {
                Snackbar.make(binding.root, warning, Snackbar.LENGTH_LONG).show()
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
            R.id.action_load_document -> {
                pickDocumentLauncher.launch(arrayOf("text/*", "application/json", "application/xml"))
                true
            }
            R.id.action_about -> {
                startActivity(Intent(this, AboutActivity::class.java))
                true
            }
            R.id.action_help -> {
                startActivity(Intent(this, HelpActivity::class.java))
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    /** Shows the saved chat history in a dialog; lets the user open, rename, or delete a session. */
    private fun showChatHistoryDialog() {
        val allSessions = viewModel.savedSessions.value ?: emptyList()
        if (allSessions.isEmpty()) {
            Snackbar.make(binding.root, getString(R.string.no_saved_chats), Snackbar.LENGTH_SHORT).show()
            return
        }

        val dialogView = layoutInflater.inflate(R.layout.dialog_session_list, null)
        val etSearch = dialogView.findViewById<EditText>(R.id.etSearchSessions)
        val rvSessions = dialogView.findViewById<RecyclerView>(R.id.rvSessions)

        var filteredSessions = allSessions.toList()
        var dialog: AlertDialog? = null
        val adapter = SessionAdapter(filteredSessions) { session ->
            dialog?.dismiss()
            showSessionOptionsDialog(session.id, session.title)
        }

        rvSessions.layoutManager = LinearLayoutManager(this)
        rvSessions.adapter = adapter

        // Search/filter functionality
        etSearch.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) {
                val query = s.toString().lowercase()
                filteredSessions = if (query.isBlank()) {
                    allSessions
                } else {
                    allSessions.filter { session ->
                        session.title.lowercase().contains(query) ||
                        session.messages.any { it.content?.lowercase()?.contains(query) == true }
                    }
                }
                adapter.updateSessions(filteredSessions)
            }
        })

        dialog = AlertDialog.Builder(this)
            .setTitle(getString(R.string.chat_history_title))
            .setView(dialogView)
            .setNeutralButton(getString(R.string.cancel), null)
            .show()
    }

    /** RecyclerView adapter for displaying session list with search. */
    private inner class SessionAdapter(
        private var sessions: List<ChatSession>,
        private val onSessionClick: (ChatSession) -> Unit
    ) : RecyclerView.Adapter<SessionAdapter.SessionViewHolder>() {

        private val fmt = SimpleDateFormat("MMM d, HH:mm", Locale.getDefault())

        fun updateSessions(newSessions: List<ChatSession>) {
            sessions = newSessions
            notifyDataSetChanged()
        }

        inner class SessionViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            val tvTitle: TextView = view.findViewById(R.id.tvSessionTitle)
            val tvDate: TextView = view.findViewById(R.id.tvSessionDate)
            val tvCount: TextView = view.findViewById(R.id.tvMessageCount)

            init {
                view.setOnClickListener {
                    onSessionClick(sessions[adapterPosition])
                }
            }
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): SessionViewHolder {
            val view = layoutInflater.inflate(R.layout.item_session, parent, false)
            return SessionViewHolder(view)
        }

        override fun onBindViewHolder(holder: SessionViewHolder, position: Int) {
            val session = sessions[position]
            holder.tvTitle.text = session.title
            holder.tvDate.text = fmt.format(Date(session.timestamp))
            holder.tvCount.text = getString(R.string.message_count, session.messages.size)
        }

        override fun getItemCount() = sessions.size
    }

    /** Shows Open / Rename / Delete / Export options for a single saved session. */
    private fun showSessionOptionsDialog(sessionId: Long, sessionTitle: String) {
        val options = arrayOf(
            getString(R.string.session_open),
            getString(R.string.session_rename),
            getString(R.string.export_session),
            getString(R.string.delete)
        )
        AlertDialog.Builder(this)
            .setTitle(sessionTitle.take(MAX_DIALOG_TITLE_LENGTH))
            .setItems(options) { _, which ->
                when (which) {
                    0 -> {
                        viewModel.loadSessionById(sessionId)
                    }
                    1 -> showRenameSessionDialog(sessionId, sessionTitle)
                    2 -> showExportFormatDialog(sessionId)
                    3 -> {
                        AlertDialog.Builder(this)
                            .setMessage(getString(R.string.delete_chat_confirm))
                            .setPositiveButton(getString(R.string.delete)) { _, _ ->
                                viewModel.deleteSession(sessionId)
                            }
                            .setNegativeButton(getString(R.string.cancel), null)
                            .show()
                    }
                }
            }
            .setNegativeButton(getString(R.string.cancel), null)
            .show()
    }

    /** Shows export format selection dialog (JSON or Text). */
    private fun showExportFormatDialog(sessionId: Long) {
        val formats = arrayOf(
            getString(R.string.export_as_json),
            getString(R.string.export_as_text)
        )
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.export_session))
            .setItems(formats) { _, which ->
                androidx.lifecycle.lifecycleScope.launch {
                    val session = viewModel.getSessionForExport(sessionId)
                    if (session != null) {
                        when (which) {
                            0 -> exportSessionAsJson(session)
                            1 -> exportSessionAsText(session)
                        }
                    }
                }
            }
            .setNegativeButton(getString(R.string.cancel), null)
            .show()
    }

    /** Exports a session as JSON to the Downloads folder. */
    private fun exportSessionAsJson(session: ChatSession) {
        try {
            val gson = com.google.gson.GsonBuilder().setPrettyPrinting().create()
            val json = gson.toJson(session)
            val filename = "chat_${session.id}.json"
            val file = saveToDownloads(filename, json)
            Snackbar.make(binding.root, getString(R.string.export_success, file.name), Snackbar.LENGTH_LONG).show()
        } catch (e: Exception) {
            Snackbar.make(binding.root, getString(R.string.export_failed, e.message), Snackbar.LENGTH_LONG).show()
        }
    }

    /** Exports a session as plain text to the Downloads folder. */
    private fun exportSessionAsText(session: ChatSession) {
        try {
            val sb = StringBuilder()
            sb.appendLine("# ${session.title}")
            sb.appendLine("Saved: ${SimpleDateFormat("MMM d, yyyy HH:mm", Locale.getDefault()).format(Date(session.timestamp))}")
            sb.appendLine()

            for (msg in session.messages) {
                when (msg.role) {
                    com.th3cavalry.androidllm.data.MessageRole.USER -> {
                        sb.appendLine("USER:")
                        sb.appendLine(msg.content ?: "")
                        sb.appendLine()
                    }
                    com.th3cavalry.androidllm.data.MessageRole.ASSISTANT -> {
                        sb.appendLine("ASSISTANT:")
                        sb.appendLine(msg.content ?: "")
                        sb.appendLine()
                    }
                    com.th3cavalry.androidllm.data.MessageRole.TOOL -> {
                        sb.appendLine("[TOOL: ${msg.toolName}]")
                        sb.appendLine(msg.content?.take(500) ?: "")
                        sb.appendLine()
                    }
                    else -> {}
                }
            }

            val filename = "chat_${session.id}.txt"
            val file = saveToDownloads(filename, sb.toString())
            Snackbar.make(binding.root, getString(R.string.export_success, file.name), Snackbar.LENGTH_LONG).show()
        } catch (e: Exception) {
            Snackbar.make(binding.root, getString(R.string.export_failed, e.message), Snackbar.LENGTH_LONG).show()
        }
    }

    /** Saves content to the Downloads folder. */
    private fun saveToDownloads(filename: String, content: String): java.io.File {
        val downloadsDir = android.os.Environment.getExternalStoragePublicDirectory(
            android.os.Environment.DIRECTORY_DOWNLOADS
        )
        val file = java.io.File(downloadsDir, filename)
        file.writeText(content)
        return file
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
                    viewModel.renameSession(sessionId, newTitle)
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
