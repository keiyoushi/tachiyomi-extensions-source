package eu.kanade.tachiyomi.extension.de.mangatube.util

import eu.kanade.tachiyomi.source.model.SManga

class MangaTubeHelper {

    companion object {
        fun mangaStatus(status: Int): Int {
            return when (status) {
                0 -> SManga.ONGOING
                1 -> SManga.ON_HIATUS
                2 -> SManga.LICENSED
                3 -> SManga.CANCELLED
                4 -> SManga.COMPLETED
                else -> SManga.UNKNOWN
            }
        }

    }
}
