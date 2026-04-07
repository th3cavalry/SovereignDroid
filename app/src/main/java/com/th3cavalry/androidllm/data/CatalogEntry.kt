package com.th3cavalry.androidllm.data

/**
 * A single downloadable model file from the Hugging Face Hub.
 * One [CatalogEntry] corresponds to one `.task` file inside a HF model repository.
 *
 * The download URL follows the HF Hub convention:
 *   https://huggingface.co/{modelId}/resolve/main/{filename}
 */
data class CatalogEntry(
    /** Full Hugging Face model ID, e.g. "litert-community/Gemma3-1B-IT-INT4" */
    val modelId: String,
    /** File name to download, e.g. "gemma3_1b_it_int4.task" */
    val filename: String,
    val sizeBytes: Long,
    val downloads: Int
) {
    /** Stable unique key used to track download state. */
    val key: String get() = "$modelId/$filename"

    /** Direct download URL for this file on Hugging Face. */
    val downloadUrl: String
        get() = "https://huggingface.co/$modelId/resolve/main/$filename"

    /** Human-readable file size string. */
    val sizeFormatted: String
        get() = when {
            sizeBytes <= 0L -> ""
            sizeBytes >= 1_073_741_824L -> "%.1f GB".format(sizeBytes / 1_073_741_824.0)
            sizeBytes >= 1_048_576L -> "%.0f MB".format(sizeBytes / 1_048_576.0)
            else -> "< 1 MB"
        }
}
