package eu.kanade.tachiyomi.extension.de.mangatube.util

import eu.kanade.tachiyomi.extension.de.mangatube.dio.Pagination
import kotlinx.serialization.Serializable

@Serializable
open class BaseResponse<T>(
    val success: Boolean,
    val data: T,
    val pagination: Pagination? = null,
)
