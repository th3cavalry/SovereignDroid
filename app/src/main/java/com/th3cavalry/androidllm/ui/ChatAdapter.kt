package com.th3cavalry.androidllm.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
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

class ChatAdapter : ListAdapter<ChatMessage, ChatAdapter.MessageViewHolder>(DiffCallback()) {

    /** When true, the response-info footer (model · tokens · time) is shown under each reply. */
    var showResponseInfo: Boolean = false

    companion object {
        private const val VIEW_USER = 0
        private const val VIEW_ASSISTANT = 1
        private const val VIEW_TOOL_CALL = 2
        private const val VIEW_TOOL_RESULT = 3
    }

    override fun getItemViewType(position: Int): Int {
        val msg = getItem(position)
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
            }
        }

        private fun bindUser(message: ChatMessage) {
            val tv = itemView.findViewById<TextView>(R.id.tvContent)
            tv.text = message.content ?: ""
        }

        private fun bindAssistant(message: ChatMessage) {
            val tv = itemView.findViewById<TextView>(R.id.tvContent)
            markwon.setMarkdown(tv, message.content ?: "")

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
    }

    class DiffCallback : DiffUtil.ItemCallback<ChatMessage>() {
        override fun areItemsTheSame(old: ChatMessage, new: ChatMessage): Boolean =
            old === new

        override fun areContentsTheSame(old: ChatMessage, new: ChatMessage): Boolean =
            old == new
    }
}
