package com.th3cavalry.androidllm.service

import android.content.Context
import android.net.Uri
import java.io.BufferedReader
import java.io.InputStreamReader

/**
 * Loads document content from a URI for use as LLM context.
 * Supports plain text (.txt, .md, .csv, .json, .xml, .html, .log)
 * and falls back to raw text extraction for other types.
 */
class DocumentLoader(private val context: Context) {

    data class LoadedDocument(
        val filename: String,
        val content: String,
        val charCount: Int
    )

    /**
     * Reads a document URI and returns its text content.
     * Truncates at [maxChars] to avoid blowing up the context window.
     */
    fun load(uri: Uri, maxChars: Int = 8000): LoadedDocument {
        val filename = resolveFilename(uri)
        val raw = context.contentResolver.openInputStream(uri)?.use { stream ->
            BufferedReader(InputStreamReader(stream)).readText()
        } ?: throw IllegalArgumentException("Cannot read file: $filename")

        val truncated = if (raw.length > maxChars) {
            raw.take(maxChars) + "\n\n[… truncated at $maxChars characters]"
        } else {
            raw
        }

        return LoadedDocument(
            filename = filename,
            content = truncated,
            charCount = truncated.length
        )
    }

    private fun resolveFilename(uri: Uri): String {
        // Try to get display name from content resolver
        context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val nameIndex = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
            if (nameIndex >= 0 && cursor.moveToFirst()) {
                return cursor.getString(nameIndex)
            }
        }
        return uri.lastPathSegment ?: "document"
    }
}
