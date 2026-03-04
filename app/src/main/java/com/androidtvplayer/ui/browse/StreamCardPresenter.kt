package com.androidtvplayer.ui.browse

import android.graphics.Color
import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.leanback.widget.Presenter
import com.androidtvplayer.R
import com.androidtvplayer.data.StreamItem
import com.bumptech.glide.Glide

class StreamCardPresenter : Presenter() {

    override fun onCreateViewHolder(parent: ViewGroup): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.card_stream, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(viewHolder: ViewHolder, item: Any) {
        val stream = item as StreamItem
        val view = viewHolder.view

        view.findViewById<TextView>(R.id.stream_title).text = stream.title
        view.findViewById<TextView>(R.id.stream_type).text = stream.type.name
        view.findViewById<TextView>(R.id.stream_url).text = stream.url

        val thumbnail = view.findViewById<ImageView>(R.id.stream_thumbnail)
        if (stream.thumbnailUrl.isNotEmpty()) {
            Glide.with(view.context)
                .load(stream.thumbnailUrl)
                .centerCrop()
                .placeholder(R.drawable.ic_stream_placeholder)
                .into(thumbnail)
        } else {
            thumbnail.setImageResource(R.drawable.ic_stream_placeholder)
        }
    }

    override fun onUnbindViewHolder(viewHolder: ViewHolder) {}
}
