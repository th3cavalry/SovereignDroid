package com.th3cavalry.androidllm.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.th3cavalry.androidllm.R
import com.th3cavalry.androidllm.data.ChatMessage
import com.th3cavalry.androidllm.data.MessageRole
import io.noties.markwon.Markwon
import io.noties.markwon.ext.strikethrough.StrikethroughPlugin
import io.noties.markwon.ext.tables.TablePlugin
import io.noties.markwon.linkify.LinkifyPlugin

class ChatAdapter(
    private val onRetryError: ((ChatMessage) -> Unit)? = null
) : ListAdapter<ChatMessage, ChatAdapter.MessageViewHolder>(DiffCallback()) {

    /** When true, the response-info footer (model · tokens · time) is shown under each reply. */
    var showResponseInfo: Boolean = false
        set(value) {
            if (field == value) return
            field = value
            // Notify all rows that the response info visibility changed.
            if (itemCount > 0) {
                notifyItemRangeChanged(0, itemCount)
            }
        }

    companion object {
        private const val VIEW_USER = 0
        private const val VIEW_ASSISTANT = 1
        private const val VIEW_TOOL_CALL = 2
        private const val VIEW_TOOL_RESULT = 3
        private const val VIEW_ERROR = 4
        private const val VIEW_TOOL_EXECUTING = 5
    }

    override fun getItemViewType(position: Int): Int {
        val msg = getItem(position)
        // Tool executing state takes priority
        if (msg.executingInfo != null) return VIEW_TOOL_EXECUTING
        // Error messages take priority
        if (msg.errorInfo != null) return VIEW_ERROR
        return when (msg.role) {
            MessageRole.USER -> VIEW_USER
            MessageRole.ASSISTANT -> if (msg.toolCalls != null) VIEW_TOOL_CALL else VIEW_ASSISTANT
            MessageRole.TOOL -> VIEW_TOOL_RESULT
            MessageRole.SYSTEM -> VIEW_ASSISTANT
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): MessageViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        val layout = when (viewType) {
            VIEW_USER -> R.layout.item_message_user
            VIEW_TOOL_CALL -> R.layout.item_tool_call
            VIEW_TOOL_RESULT -> R.layout.item_tool_result
            VIEW_ERROR -> R.layout.item_message_error
            VIEW_TOOL_EXECUTING -> R.layout.item_tool_executing
            else -> R.layout.item_message_assistant
        }
        return MessageViewHolder(inflater.inflate(layout, parent, false), viewType)
    }

    override fun onBindViewHolder(holder: MessageViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class MessageViewHolder(itemView: View, private val viewType: Int) :
        RecyclerView.ViewHolder(itemView) {

        private val markwon: Markwon by lazy {
            Markwon.builder(itemView.context)
                .usePlugin(StrikethroughPlugin.create())
                .usePlugin(TablePlugin.create(itemView.context))
                .usePlugin(LinkifyPlugin.create())
                .build()
        }

        fun bind(message: ChatMessage) {
            when (viewType) {
                VIEW_USER -> bindUser(message)
                VIEW_ASSISTANT -> bindAssistant(message)
                VIEW_TOOL_CALL -> bindToolCall(message)
                VIEW_TOOL_RESULT -> bindToolResult(message)
                VIEW_ERROR -> bindError(message)
                VIEW_TOOL_EXECUTING -> bindToolExecuting(message)
            }
        }

        private fun bindUser(message: ChatMessage) {
            val tv = itemView.findViewById<TextView>(R.id.tvContent)
            tv.text = message.content ?: ""
            tv.setOnLongClickListener { copyToClipboard(message.content) }

            // Show attached image if present
            val iv = itemView.findViewById<ImageView>(R.id.ivMessageImage)
            if (message.imageUri != null) {
                iv.visibility = View.VISIBLE
                iv.setImageURI(android.net.Uri.parse(message.imageUri))
            } else {
                iv.visibility = View.GONE
            }
        }

        private fun bindAssistant(message: ChatMessage) {
            val tv = itemView.findViewById<TextView>(R.id.tvContent)
            val content = message.content ?: ""
            // Add typing cursor when streaming
            val displayContent = if (message.isStreaming) {
                "$content▋"  // Add blinking cursor effect
            } else {
                content
            }
            markwon.setMarkdown(tv, displayContent)
            tv.setOnLongClickListener { copyToClipboard(message.content) }

            val tvInfo = itemView.findViewById<TextView?>(R.id.tvResponseInfo)
            val info = message.responseInfo
            if (tvInfo != null && info != null && showResponseInfo) {
                val secs = info.durationMs / 1000.0
                val tokensText = if (info.totalTokens != null) " · ${info.totalTokens} tokens" else ""
                tvInfo.text = "${info.model}$tokensText · ${"%.1f".format(secs)}s"
                tvInfo.visibility = View.VISIBLE
            } else {
                tvInfo?.visibility = View.GONE
            }
        }

        private fun copyToClipboard(text: String?): Boolean {
            if (text.isNullOrBlank()) return false
            val clipboard = itemView.context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(ClipData.newPlainText("message", text))
            Toast.makeText(itemView.context, R.string.copied_to_clipboard, Toast.LENGTH_SHORT).show()
            return true
        }

        private fun bindToolCall(message: ChatMessage) {
            val tvCalls = itemView.findViewById<TextView>(R.id.tvToolCalls)
            val calls = message.toolCalls ?: return
            val text = calls.joinToString("\n") { tc ->
                "🔧 ${tc.function.name}(${tc.function.arguments.take(120)}${if (tc.function.arguments.length > 120) "…" else ""})"
            }
            tvCalls.text = text
        }

        private fun bindToolResult(message: ChatMessage) {
            val tvToolName = itemView.findViewById<TextView>(R.id.tvToolName)
            val tvResult = itemView.findViewById<TextView>(R.id.tvResult)
            tvToolName.text = "✅ ${message.toolName ?: "tool"}"
            val content = message.content ?: ""
            // Truncate very long outputs in the UI
            tvResult.text = if (content.length > 500) {
                content.take(500) + "\n…(truncated, ${content.length} chars total)"
            } else {
                content
            }
        }

        private fun bindError(message: ChatMessage) {
            val errorInfo = message.errorInfo ?: return
            
            val tvErrorMessage = itemView.findViewById<TextView>(R.id.tvErrorMessage)
            val tvErrorDetails = itemView.findViewById<TextView>(R.id.tvErrorDetails)
            val ivExpand = itemView.findViewById<ImageView>(R.id.ivExpand)
            val btnRetry = itemView.findViewById<Button>(R.id.btnRetry)
            
            tvErrorMessage.text = errorInfo.message
            
            // Show details if available
            if (!errorInfo.details.isNullOrBlank()) {
                ivExpand.visibility = View.VISIBLE
                tvErrorDetails.text = errorInfo.details
                
                var isExpanded = false
                ivExpand.setOnClickListener {
                    isExpanded = !isExpanded
                    tvErrorDetails.visibility = if (isExpanded) View.VISIBLE else View.GONE
                    ivExpand.rotation = if (isExpanded) 180f else 0f
                }
            } else {
                ivExpand.visibility = View.GONE
                tvErrorDetails.visibility = View.GONE
            }
            
            // Show retry button if error is retryable
            if (errorInfo.isRetryable && onRetryError != null) {
                btnRetry.visibility = View.VISIBLE
                btnRetry.setOnClickListener {
                    onRetryError.invoke(message)
                }
            } else {
                btnRetry.visibility = View.GONE
            }
        }

        private fun bindToolExecuting(message: ChatMessage) {
            val executingInfo = message.executingInfo ?: return
            
            val tvExecutingTool = itemView.findViewById<TextView>(R.id.tvExecutingTool)
            val tvExecutingStatus = itemView.findViewById<TextView>(R.id.tvExecutingStatus)
            
            // Display tool name with icon
            val icon = when {
                executingInfo.toolName.contains("ssh") -> "🖥️"
                executingInfo.toolName.contains("github") -> "📂"
                executingInfo.toolName.contains("search") -> "🔍"
                executingInfo.toolName.contains("fetch") -> "🌐"
                else -> "⚙️"
            }
            tvExecutingTool.text = "$icon Executing ${executingInfo.toolName}..."
            
            // Show status if available
            if (!executingInfo.status.isNullOrBlank()) {
                tvExecutingStatus.visibility = View.VISIBLE
                tvExecutingStatus.text = executingInfo.status
            } else {
                tvExecutingStatus.visibility = View.GONE
            }
        }
    }

    class DiffCallback : DiffUtil.ItemCallback<ChatMessage>() {
        override fun areItemsTheSame(old: ChatMessage, new: ChatMessage): Boolean =
            old === new

        override fun areContentsTheSame(old: ChatMessage, new: ChatMessage): Boolean =
            old == new
    }
}
