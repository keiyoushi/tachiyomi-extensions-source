package keiyoushi.utils

import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.decodeFromStream
import okhttp3.Response
import uy.kohesive.injekt.injectLazy

val jsonInstance: Json by injectLazy()

/**
 * Parses and serializes the String as the type <T>.
 */
inline fun <reified T> String.parseAs(json: Json = jsonInstance): T =
    json.decodeFromString(this)

/**
 * Parse and serialize the response body as the type <T>.
 */
inline fun <reified T> Response.parseAs(json: Json = jsonInstance): T =
    json.decodeFromStream(body.byteStream())
