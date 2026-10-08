package com.heasafe.safestream.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.materialswitch.MaterialSwitch
import com.heasafe.safestream.R
import com.heasafe.safestream.core.SecurityLog

/**
 * 防护面板（借鉴 Brave Shields）：本次会话的安全事件明细 + 拦截开关 + 一键清理。
 *
 * 面板代码从 MainActivity 抽出：它是防护的"门面"，与播放无关，
 * 放在 600 行的 Activity 里只会让两边互相干扰。
 */
object ShieldPanel {

    fun show(
        activity: android.app.Activity,
        log: SecurityLog,
        filterEnabled: Boolean,
        onToggleFilter: () -> Unit,
        onClear: () -> Unit,
    ) {
        val panel = BottomSheetDialog(activity)
        panel.setContentView(R.layout.view_shield_sheet)
        panel.findViewById<TextView>(R.id.shieldCount)!!.text =
            activity.getString(R.string.shield_count, log.count())
        panel.findViewById<RecyclerView>(R.id.shieldList)!!.adapter =
            EventsAdapter(log.all())
        panel.findViewById<TextView>(R.id.shieldEmpty)!!.visibility =
            if (log.count() == 0) View.VISIBLE else View.GONE
        val sw = panel.findViewById<MaterialSwitch>(R.id.shieldSwitch)!!
        sw.isChecked = filterEnabled
        sw.setOnCheckedChangeListener { _, checked ->
            if (checked != filterEnabled) onToggleFilter()
        }
        panel.findViewById<Button>(R.id.shieldClear)!!.setOnClickListener {
            panel.dismiss()
            onClear()
        }
        panel.show()
    }

    private class EventsAdapter(private val items: List<String>) :
        RecyclerView.Adapter<EventsAdapter.VH>() {

        class VH(val text: TextView) : RecyclerView.ViewHolder(text)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
            VH(
                LayoutInflater.from(parent.context)
                    .inflate(R.layout.item_security, parent, false) as TextView,
            )

        override fun getItemCount(): Int = items.size

        override fun onBindViewHolder(holder: VH, position: Int) {
            holder.text.text = items[position]
        }
    }
}
