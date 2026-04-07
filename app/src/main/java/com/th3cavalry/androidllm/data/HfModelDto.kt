package com.th3cavalry.androidllm.data

import com.google.gson.annotations.SerializedName

/** A single model entry returned by the Hugging Face Hub models API. */
data class HfModelDto(
    @SerializedName("id")           val id: String,
    @SerializedName("downloads")    val downloads: Int = 0,
    @SerializedName("likes")        val likes: Int = 0,
    @SerializedName("tags")         val tags: List<String> = emptyList(),
    @SerializedName("library_name") val libraryName: String? = null,
    @SerializedName("pipeline_tag") val pipelineTag: String? = null,
    /** Populated only when the detail endpoint is called with `?blobs=true`. */
    @SerializedName("siblings")     val siblings: List<HfSiblingDto>? = null
) {
    /** Short display name derived from the model ID (part after the `/`). */
    val displayName: String get() = id.substringAfterLast('/')

    /** Human-readable download count, e.g. "51.2k". */
    val downloadsFormatted: String get() = when {
        downloads >= 1_000_000 -> "%.1fM".format(downloads / 1_000_000.0)
        downloads >= 1_000     -> "%.1fk".format(downloads / 1_000.0)
        else                   -> downloads.toString()
    }
}

/** A single file (sibling) within a Hugging Face model repository. */
data class HfSiblingDto(
    @SerializedName("rfilename") val rfilename: String,
    /** File size in bytes; null if not reported by the API. */
    @SerializedName("size")      val size: Long? = null
) {
    /** Human-readable file size. */
    val sizeFormatted: String get() = when {
        size == null         -> ""
        size >= 1_073_741_824L -> "%.1f GB".format(size / 1_073_741_824.0)
        size >= 1_048_576L   -> "%.0f MB".format(size / 1_048_576.0)
        else                 -> "< 1 MB"
    }

    /**
     * The quantization label extracted from the filename, e.g. "q4_ekv4096".
     * Strips the model-name prefix (uppercase or short alphabetic segments) and
     * returns everything after it, joined by underscores.
     */
    val quantLabel: String get() {
        val base = rfilename.substringBeforeLast('.')
        val segments = base.split("_")
        val prefixEnd = segments.indexOfFirst { segment ->
            !segment.all(Char::isUpperCase) && !(segment.all(Char::isLetter) && segment.length <= 3)
        }
        return if (prefixEnd <= 0) base
        else segments.drop(prefixEnd).joinToString("_")
    }

    /** True for `.litertlm` files (LiteRT-LM backend). */
    val isLiteRtLm: Boolean get() = rfilename.endsWith(".litertlm")

    /** True for `.task` files (MediaPipe backend). */
    val isMediaPipe: Boolean get() = rfilename.endsWith(".task")
}
