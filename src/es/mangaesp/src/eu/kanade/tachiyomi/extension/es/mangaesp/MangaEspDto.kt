package eu.kanade.tachiyomi.extension.es.mangaesp

import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.text.SimpleDateFormat

@Serializable
class TopSeriesDto(
    val response: TopSeriesResponseDto,
)

@Serializable
class LastUpdatesDto(
    val response: List<SeriesDto>,
)

@Serializable
class ComicsDto(
    val response: List<SeriesDto>,
)

@Serializable
class TopSeriesResponseDto(
    @SerialName("mensual") val topMonthly: List<List<PayloadSeriesDto>>,
    @SerialName("semanal") val topWeekly: List<List<PayloadSeriesDto>>,
    @SerialName("diario") val topDaily: List<List<PayloadSeriesDto>>,
)

@Serializable
class PayloadSeriesDto(
    @SerialName("project") val data: SeriesDto,
)

@Serializable
class SeriesDto(
    val name: String,
    val slug: String,
    @SerialName("sinopsis") private val synopsis: String? = null,
    @SerialName("urlImg") private val thumbnail: String? = null,
    @SerialName("actualizacionCap") val lastChapterDate: String? = null,
    @SerialName("created_at") val createdAt: String? = null,
    @SerialName("state_id") val status: Int? = 0,
    private val genders: List<GenderDto> = emptyList(),
    @SerialName("lastChapters") val chapters: List<ChapterDto> = emptyList(),
    val trending: TrendingDto? = null,
    @SerialName("autors") private val authors: List<AuthorDto> = emptyList(),
    private val artists: List<ArtistDto> = emptyList(),

) {
    fun toSManga(): SManga {
        return SManga.create().apply {
            title = name
            thumbnail_url = thumbnail
            url = "/ver/$slug"
        }
    }

    fun toSMangaDetails(): SManga {
        return SManga.create().apply {
            title = name
            thumbnail_url = thumbnail
            description = synopsis ?: ""
            genre = genders.joinToString { it.gender.name }
            author = authors.joinToString { it.author.name }
            artist = artists.joinToString { it.artist.name }
        }
    }
}

@Serializable
class TrendingDto(
    @SerialName("visitas") val views: Int? = 0,
)

@Serializable
class GenderDto(
    val gender: DetailDataNameDto,
)

@Serializable
class AuthorDto(
    @SerialName("autor") val author: DetailDataNameDto,
)

@Serializable
class ArtistDto(
    val artist: DetailDataNameDto,
)

@Serializable
class DetailDataNameDto(
    val name: String,
)

@Serializable
class ChapterDto(
    @SerialName("num") private val number: Float,
    private val name: String? = null,
    private val slug: String,
    @SerialName("created_at") private val date: String,
) {
    fun toSChapter(seriesSlug: String, dateFormat: SimpleDateFormat): SChapter {
        return SChapter.create().apply {
            name = if (this@ChapterDto.name.isNullOrBlank()) {
                "Capítulo ${number.toString().removeSuffix(".0")}"
            } else {
                "Capítulo ${number.toString().removeSuffix(".0")} - ${this@ChapterDto.name}"
            }
            date_upload = try {
                dateFormat.parse(date)?.time ?: 0L
            } catch (e: Exception) {
                0L
            }
            url = "/ver/$seriesSlug/$slug"
        }
    }
}
