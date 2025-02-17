package eu.kanade.tachiyomi.extension.de.mangatube.util

import okhttp3.Interceptor
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.IOException

class ResponseInterceptor : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()

        val response = try {
            chain.proceed(request)
        } catch (e: IOException) {
            throw e
        }

        val body = response.body.string()

        if (response.code != 200) {
            throw Exception("Unexpected api issue")
        }

        if (body.startsWith("<!DOCTYPE html>")) {
            throw Exception("IP isn't verified. Open webview!")
        }

        if (body.contains(""""success":false""")) {
            throw Exception("Resource not found!")
        }

        return response.newBuilder()
            .body(body.toResponseBody(response.body.contentType()))
            .build()
    }
}
