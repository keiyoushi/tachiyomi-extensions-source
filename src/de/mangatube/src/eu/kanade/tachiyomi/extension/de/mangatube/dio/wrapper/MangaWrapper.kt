package eu.kanade.tachiyomi.extension.de.mangatube.dio.wrapper

import Manga
import kotlinx.serialization.Serializable

@Serializable
data class MangaWrapper(val manga: Manga)
