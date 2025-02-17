package eu.kanade.tachiyomi.extension.pt.manhastro

import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json

@Serializable
class MangaResponseDto(
    val data: Array<MangaDto>,
)

@Serializable
class MangaDto(
    val manga_id: Int,
    val titulo: String,
    val descricao: String,
    val imagem: String,
    val generos: String?,
) {
    fun getGeneros(): List<String> {
        return try {
            generos?.let { Json.decodeFromString<List<String>>(it) } ?: emptyList()
        } catch (e: Exception) {
            emptyList()
        }
    }
}

@Serializable
class CapituloDto(
    val capitulos: Array<CapsDto>,
)

@Serializable
class CapsDto(
    val capitulo_id: Int,
    val capitulo_nome: String,
    val capitulo_data: String,
)

@Serializable
class PaginaDto(
    val paginas: Map<String, String>,
)
