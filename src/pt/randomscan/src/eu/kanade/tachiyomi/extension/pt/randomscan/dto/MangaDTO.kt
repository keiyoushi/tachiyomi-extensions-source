package eu.kanade.tachiyomi.extension.pt.randomscan.dto

import kotlinx.serialization.Serializable

@Serializable
data class Genero(
    val name: String,
)

@Serializable
data class Capitulo(
    val id: Int,
    val num: Double,
    val data: String,
    val slug: String,
)

@Serializable
data class Manga(
    val id: Int,
    val capa: String,
    val titulo: String,
    val autor: String?,
    val artista: String?,
    val status: String,
    val sinopse: String,
    val tipo: String,
    val generos: List<Genero>,
    val caps: List<Capitulo>,
)

@Serializable
data class Episode(
    val caminhos: List<String>,
)

@Serializable
data class MainPageManga(
    val title: String,
    val capa: String,
    val slug: String,
)

@Serializable
data class MainPage(
    val lancamentos: List<MainPageManga>,
    val top_10: List<MainPageManga>,
)

@Serializable
data class SearchResponseManga(
    val titulo: String,
    val capa: String,
    val slug: String,
)

@Serializable
data class SearchResponse(
    val obras: List<SearchResponseManga>,
)
