package eu.kanade.tachiyomi.extension.de.mangatube.dio

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class Pagination(
    @SerialName("current_page") val currentPage: Int,
    @SerialName("last_page") val lastPage: Int,
) {

    fun lastPage(): Boolean {
        return currentPage == lastPage
    }
}
