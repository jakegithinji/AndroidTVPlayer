package com.androidtvplayer.data

import java.util.UUID

enum class StreamType { HLS, DASH }

data class StreamItem(
    val id: String = UUID.randomUUID().toString(),
    val title: String,
    val url: String,
    val type: StreamType = StreamType.HLS,
    val description: String = "",
    val thumbnailUrl: String = ""
)
