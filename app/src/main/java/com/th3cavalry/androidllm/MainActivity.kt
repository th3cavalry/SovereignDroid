package com.th3cavalry.androidllm

import android.content.Intent
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.inputmethod.EditorInfo
import androidx.activity.viewModels
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)

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
            binding.btnSend.isEnabled = !loading
            binding.etInput.isEnabled = !loading
        }

        viewModel.error.observe(this) { error ->
            if (error != null) {
                Snackbar.make(binding.root, error, Snackbar.LENGTH_LONG).show()
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
            else -> super.onOptionsItemSelected(item)
        }
    }

    /** Shows the saved chat history in a dialog; lets the user load or delete a session. */
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
                viewModel.loadSession(sessions[idx])
            }
            .setNeutralButton(getString(R.string.cancel), null)
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
