package com.th3cavalry.androidllm.ui

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.th3cavalry.androidllm.data.HfSiblingDto
import com.th3cavalry.androidllm.databinding.ItemModelFileBinding

/**
 * Adapter for the file list bottom sheet in [com.th3cavalry.androidllm.ModelBrowserActivity].
 *
 * Each item represents one downloadable file within a Hugging Face model repo.
 */
class ModelFilesAdapter(
    private val onDownload: (HfSiblingDto) -> Unit
) : ListAdapter<HfSiblingDto, ModelFilesAdapter.ViewHolder>(DIFF) {

    inner class ViewHolder(private val b: ItemModelFileBinding) :
        RecyclerView.ViewHolder(b.root) {

        fun bind(item: HfSiblingDto) {
            b.tvFilename.text   = item.rfilename
            b.tvFileSize.text   = item.sizeFormatted
            b.btnDownload.setOnClickListener { onDownload(item) }
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder =
        ViewHolder(
            ItemModelFileBinding.inflate(
                LayoutInflater.from(parent.context), parent, false
            )
        )

    override fun onBindViewHolder(holder: ViewHolder, position: Int) =
        holder.bind(getItem(position))

    private companion object {
        val DIFF = object : DiffUtil.ItemCallback<HfSiblingDto>() {
            override fun areItemsTheSame(a: HfSiblingDto, b: HfSiblingDto) =
                a.rfilename == b.rfilename
            override fun areContentsTheSame(a: HfSiblingDto, b: HfSiblingDto) = a == b
        }
    }
}
