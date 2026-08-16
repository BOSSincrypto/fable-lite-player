package com.fable.liteplayer.domain.model

data class Video(
    val id: String,
    val title: String,
    val url: String,
    val duration: Long = 0L,
    val thumbnailUrl: String? = null
)
