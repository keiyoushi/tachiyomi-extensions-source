package eu.kanade.tachiyomi.extension.de.mangatube.dio

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class Page(
    val url: String,
    @SerialName("page") val index: Int,
    @SerialName("alt_source") val altSource: String,
)
