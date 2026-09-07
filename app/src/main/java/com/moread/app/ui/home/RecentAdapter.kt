package com.moread.app.ui.home

import android.content.res.ColorStateList
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.moread.app.R
import com.moread.app.file.RecentDoc
import com.moread.app.theme.ColorPalette
import com.moread.app.theme.ThemeEngine
import java.text.DateFormat
import java.util.Date

class RecentAdapter(
    private val onOpen: (RecentDoc) -> Unit,
    private val onLongClick: (RecentDoc) -> Unit,
) : RecyclerView.Adapter<RecentAdapter.Holder>() {

    private var docs: List<RecentDoc> = emptyList()
    private var palette: ColorPalette = ThemeEngine.palette()

    fun submit(newDocs: List<RecentDoc>) {
        docs = newDocs
        notifyDataSetChanged()
    }

    fun setPalette(newPalette: ColorPalette) {
        palette = newPalette
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_recent, parent, false)
        return Holder(view)
    }

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val doc = docs[position]
        holder.name.text = doc.name
        holder.meta.text = buildString {
            append(DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(doc.lastOpened)))
            if (doc.missing) append("  ·  ").append(holder.itemView.context.getString(R.string.file_not_exists))
        }
        holder.name.setTextColor(palette.textPrimary)
        holder.meta.setTextColor(if (doc.missing) palette.accent else palette.textSecondary)
        holder.icon.imageTintList = ColorStateList.valueOf(if (doc.missing) palette.textSecondary else palette.accent)
        holder.itemView.setOnClickListener { onOpen(doc) }
        holder.itemView.setOnLongClickListener {
            onLongClick(doc)
            true
        }
    }

    override fun getItemCount(): Int = docs.size

    class Holder(view: View) : RecyclerView.ViewHolder(view) {
        val name: TextView = view.findViewById(R.id.recent_name)
        val meta: TextView = view.findViewById(R.id.recent_meta)
        val icon: ImageView = view.findViewById(R.id.recent_icon)
    }
}
