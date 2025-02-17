import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonNames

@Serializable
data class Manga(
    val id: Int,
    val title: String,
    val url: String,
    val slug: String,
    val cover: String,
    @JsonNames("status", "statusScanlation")val status: Int = -1,
    val description: String = "",
    val genre: List<Int> = emptyList(),
    val artist: List<Person> = emptyList(),
    val author: List<Person> = emptyList(),
)
