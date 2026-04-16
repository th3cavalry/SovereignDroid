package com.th3cavalry.androidllm

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.widget.EditText
import androidx.appcompat.widget.SwitchCompat
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.DividerItemDecoration
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.google.android.material.snackbar.Snackbar
import com.th3cavalry.androidllm.data.MCPServer
import com.th3cavalry.androidllm.databinding.ActivityMcpManagerBinding
import com.th3cavalry.androidllm.service.MCPClient
import kotlinx.coroutines.launch
import java.util.UUID

class MCPManagerActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMcpManagerBinding
    private val servers: MutableList<MCPServer> = mutableListOf()
    private lateinit var serverAdapter: MCPServerAdapter
    private var appliedThemeIndex: Int = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        appliedThemeIndex = ThemeHelper.applyTheme(this)
        super.onCreate(savedInstanceState)
        binding = ActivityMcpManagerBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        servers.addAll(Prefs.getMCPServers(this))

        serverAdapter = MCPServerAdapter(servers,
            onDelete = { pos -> deleteServer(pos) },
            onToggle = { pos, enabled -> toggleServer(pos, enabled) },
            onTest = { pos -> testServer(pos) }
        )

        binding.rvServers.apply {
            layoutManager = LinearLayoutManager(this@MCPManagerActivity)
            adapter = serverAdapter
            addItemDecoration(DividerItemDecoration(context, DividerItemDecoration.VERTICAL))
        }

        binding.fab.setOnClickListener { showAddServerDialog() }

        updateEmptyState()
    }

    private fun showAddServerDialog() {
        val view = LayoutInflater.from(this).inflate(R.layout.dialog_add_mcp_server, null)
        val etName = view.findViewById<EditText>(R.id.etServerName)
        val etUrl = view.findViewById<EditText>(R.id.etServerUrl)
        val etDesc = view.findViewById<EditText>(R.id.etServerDescription)

        AlertDialog.Builder(this)
            .setTitle(R.string.add_mcp_server)
            .setView(view)
            .setPositiveButton(R.string.add) { _, _ ->
                val name = etName.text.toString().trim()
                val url = etUrl.text.toString().trim()
                val desc = etDesc.text.toString().trim()
                if (name.isNotEmpty() && url.isNotEmpty()) {
                    addServer(MCPServer(
                        id = UUID.randomUUID().toString(),
                        name = name,
                        url = url,
                        description = desc
                    ))
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun addServer(server: MCPServer) {
        servers.add(server)
        serverAdapter.notifyItemInserted(servers.size - 1)
        saveServers()
        updateEmptyState()
    }

    private fun deleteServer(position: Int) {
        if (position < 0 || position >= servers.size) return
        servers.removeAt(position)
        serverAdapter.notifyItemRemoved(position)
        saveServers()
        updateEmptyState()
    }

    private fun toggleServer(position: Int, enabled: Boolean) {
        if (position < 0 || position >= servers.size) return
        servers[position] = servers[position].copy(enabled = enabled)
        saveServers()
    }

    private fun testServer(position: Int) {
        val server = servers.getOrNull(position) ?: return
        lifecycleScope.launch {
            Snackbar.make(binding.root, "Testing ${server.name}…", Snackbar.LENGTH_SHORT).show()
            try {
                val client = MCPClient(server)
                val ok = client.initialize()
                val tools = if (ok) client.listTools() else emptyList()
                val msg = if (ok) {
                    "✅ Connected! Found ${tools.size} tool(s)."
                } else {
                    "❌ Failed to connect to ${server.name}"
                }
                Snackbar.make(binding.root, msg, Snackbar.LENGTH_LONG).show()
            } catch (e: Exception) {
                Snackbar.make(binding.root, "❌ Error: ${e.message}", Snackbar.LENGTH_LONG).show()
            }
        }
    }

    private fun saveServers() = Prefs.saveMCPServers(this, servers)

    private fun updateEmptyState() {
        binding.tvEmpty.visibility = if (servers.isEmpty()) View.VISIBLE else View.GONE
    }

    override fun onSupportNavigateUp(): Boolean {
        onBackPressedDispatcher.onBackPressed()
        return true
    }

    override fun onResume() {
        super.onResume()
        ThemeHelper.recreateIfNeeded(this, appliedThemeIndex)
    }

    // ─── Inner Adapter ────────────────────────────────────────────────────────

    class MCPServerAdapter(
        private val items: List<MCPServer>,
        private val onDelete: (Int) -> Unit,
        private val onToggle: (Int, Boolean) -> Unit,
        private val onTest: (Int) -> Unit
    ) : RecyclerView.Adapter<MCPServerAdapter.VH>() {

        inner class VH(itemView: View) : RecyclerView.ViewHolder(itemView)

        override fun onCreateViewHolder(parent: android.view.ViewGroup, viewType: Int): VH {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_mcp_server, parent, false)
            return VH(view)
        }

        override fun onBindViewHolder(holder: VH, position: Int) {
            val server = items[position]
            val tvName = holder.itemView.findViewById<android.widget.TextView>(R.id.tvServerName)
            val tvUrl = holder.itemView.findViewById<android.widget.TextView>(R.id.tvServerUrl)
            val tvDesc = holder.itemView.findViewById<android.widget.TextView>(R.id.tvServerDesc)
            val switchEnabled = holder.itemView.findViewById<SwitchCompat>(R.id.switchEnabled)
            val btnDelete = holder.itemView.findViewById<android.widget.ImageButton>(R.id.btnDelete)
            val btnTest = holder.itemView.findViewById<android.widget.Button>(R.id.btnTest)

            tvName.text = server.name
            tvUrl.text = server.url
            tvDesc.text = server.description
            tvDesc.visibility = if (server.description.isBlank()) View.GONE else View.VISIBLE

            switchEnabled.isChecked = server.enabled
            switchEnabled.setOnCheckedChangeListener { _, checked ->
                onToggle(holder.adapterPosition, checked)
            }

            btnDelete.setOnClickListener { onDelete(holder.adapterPosition) }
            btnTest.setOnClickListener { onTest(holder.adapterPosition) }
        }

        override fun getItemCount() = items.size
    }
}
