package eu.kanade.tachiyomi.extension.ru.hotmanga.dto

import kotlinx.serialization.Serializable

@Serializable
class MangaDto(
    val id: Long,
    val slug: String,
    val titleEn: String?,
    val desc: String?,
    val imageHigh: String,
)
