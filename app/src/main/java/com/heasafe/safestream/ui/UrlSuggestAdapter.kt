package com.heasafe.safestream.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.heasafe.safestream.R

/**
 * 地址栏补全建议：最近打开的页面，一行一条。
 *
 * 不用 ListAdapter/DiffUtil：数据来自 markVisited 的去重前置插入，
 * 每次都是小列表整体换新，直接 submit 即可。
 */
class UrlSuggestAdapter(
    private val onClick: (String) -> Unit,
) : RecyclerView.Adapter<UrlSuggestAdapter.VH>() {

    private val items = mutableListOf<String>()

    fun submit(newItems: List<String>) {
        items.clear()
        items.addAll(newItems)
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context)
            .inflate(android.R.layout.simple_list_item_1, parent, false)
        return VH(v)
    }

    override fun getItemCount(): Int = items.size

    override fun onBindViewHolder(holder: VH, position: Int) {
        holder.text.text = items[position]
        holder.itemView.setOnClickListener { onClick(items[position]) }
    }

    class VH(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val text: TextView = itemView.findViewById(android.R.id.text1)
    }
}
