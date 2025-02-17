package eu.kanade.tachiyomi.extension.de.mangatube.dio

import kotlinx.serialization.Serializable

@Serializable
data class Chapter(
    val id: Int,
    val number: Int,
    val volume: Int,
    val name: String,
    val publishedAt: String,
    val readerURL: String,
    val pages: List<Page> = emptyList(),
)
