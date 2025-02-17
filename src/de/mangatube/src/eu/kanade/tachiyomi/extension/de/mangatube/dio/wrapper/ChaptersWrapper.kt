package eu.kanade.tachiyomi.extension.de.mangatube.dio.wrapper

import eu.kanade.tachiyomi.extension.de.mangatube.dio.Chapter
import kotlinx.serialization.Serializable

@Serializable
data class ChaptersWrapper(
    val chapters: List<Chapter>,
)
