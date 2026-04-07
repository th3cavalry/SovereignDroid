package com.th3cavalry.androidllm.ui

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.th3cavalry.androidllm.data.HfModelDto
import com.th3cavalry.androidllm.databinding.ItemModelCatalogBinding

/**
 * Adapter for the model list in [com.th3cavalry.androidllm.ModelBrowserActivity].
 *
 * Each item shows the model's display name, download count, and a "Select" button.
 */
class ModelCatalogAdapter(
    private val onSelect: (HfModelDto) -> Unit
) : ListAdapter<HfModelDto, ModelCatalogAdapter.ViewHolder>(DIFF) {

    inner class ViewHolder(private val b: ItemModelCatalogBinding) :
        RecyclerView.ViewHolder(b.root) {

        fun bind(item: HfModelDto) {
            b.tvModelName.text      = item.displayName
            b.tvModelId.text        = item.id
            b.tvDownloads.text      = "⬇ ${item.downloadsFormatted}"
            b.tvLikes.text          = "❤ ${item.likes}"
            b.btnSelectModel.setOnClickListener { onSelect(item) }
            b.root.setOnClickListener { onSelect(item) }
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder =
        ViewHolder(
            ItemModelCatalogBinding.inflate(
                LayoutInflater.from(parent.context), parent, false
            )
        )

    override fun onBindViewHolder(holder: ViewHolder, position: Int) =
        holder.bind(getItem(position))

    private companion object {
        val DIFF = object : DiffUtil.ItemCallback<HfModelDto>() {
            override fun areItemsTheSame(a: HfModelDto, b: HfModelDto) = a.id == b.id
            override fun areContentsTheSame(a: HfModelDto, b: HfModelDto) = a == b
        }
    }
}
