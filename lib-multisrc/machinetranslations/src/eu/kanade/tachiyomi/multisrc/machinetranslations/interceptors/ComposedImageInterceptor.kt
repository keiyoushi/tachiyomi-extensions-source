package eu.kanade.tachiyomi.multisrc.machinetranslations.interceptors

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.text.LineBreaker
import android.os.Build
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import androidx.annotation.RequiresApi
import eu.kanade.tachiyomi.multisrc.machinetranslations.Dialog
import eu.kanade.tachiyomi.multisrc.machinetranslations.Language
import eu.kanade.tachiyomi.multisrc.machinetranslations.MachineTranslations.Companion.PAGE_REGEX
import eu.kanade.tachiyomi.network.GET
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import uy.kohesive.injekt.injectLazy
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream

// The Interceptor joins the dialogues and pages of the manga.
@RequiresApi(Build.VERSION_CODES.O)
class ComposedImageInterceptor(
    baseUrl: String,
    var language: Language,
) : Interceptor {

    private val json: Json by injectLazy()

    private val fontFamily: MutableMap<String, Pair<String, Typeface?>> = mutableMapOf(
        "sub" to Pair<String, Typeface?>("$baseUrl/images/sub.ttf", null),
        "sfx" to Pair<String, Typeface?>("$baseUrl/images/sfx.ttf", null),
        "normal" to Pair<String, Typeface?>("$baseUrl/images/normal.ttf", null),
    )

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val url = request.url.toString()

        if (PAGE_REGEX.containsMatchIn(url).not()) {
            return chain.proceed(request)
        }

        val dialogues = request.url.fragment?.parseAs<List<Dialog>>()
            ?: emptyList()

        val imageRequest = request.newBuilder()
            .url(url)
            .build()

        // Load the fonts before opening the connection to load the image,
        // so there aren't two open connections inside the interceptor.
        if (language.disableSourceSettings.not()) {
            loadAllFont(chain)
        }

        val response = chain.proceed(imageRequest)

        if (response.isSuccessful.not()) {
            return response
        }

        val bitmap = BitmapFactory.decodeStream(response.body.byteStream())!!
            .copy(Bitmap.Config.ARGB_8888, true)

        val canvas = Canvas(bitmap)

        dialogues.forEach { dialog ->
            val textPaint = createTextPaint(selectFontFamily(dialog.type))
            val dialogBox = createDialogBox(dialog, textPaint)
            val y = getYAxis(textPaint, dialog, dialogBox)
            canvas.draw(textPaint, dialogBox, dialog, dialog.x1, y)
        }

        val output = ByteArrayOutputStream()

        val ext = url.substringBefore("#")
            .substringAfterLast(".")
            .lowercase()
        val format = when (ext) {
            "png" -> Bitmap.CompressFormat.PNG
            "jpeg", "jpg" -> Bitmap.CompressFormat.JPEG
            else -> Bitmap.CompressFormat.WEBP
        }

        bitmap.compress(format, 100, output)

        val responseBody = output.toByteArray().toResponseBody(mediaType)

        return response.newBuilder()
            .body(responseBody)
            .build()
    }

    private fun createTextPaint(font: Typeface?): TextPaint {
        val defaultTextSize = language.fontSize.pt
        return TextPaint().apply {
            color = Color.BLACK
            textSize = defaultTextSize
            font?.let {
                typeface = it
            }
            isAntiAlias = true
        }
    }

    private fun selectFontFamily(type: String): Typeface? {
        if (language.disableSourceSettings) {
            return null
        }

        if (type in fontFamily) {
            return fontFamily[type]?.second
        }

        return when (type) {
            "inside", "outside" -> fontFamily["sfx"]?.second
            else -> fontFamily["normal"]?.second
        }
    }

    private fun loadAllFont(chain: Interceptor.Chain) {
        val fallback = loadFont("coming_soon_regular.ttf")
        fontFamily.keys.forEach { key ->
            val font = fontFamily[key] ?: return@forEach
            if (font.second != null) {
                return@forEach
            }
            fontFamily[key] = key to (loadRemoteFont(font.first, chain) ?: fallback)
        }
    }

    /**
     * Loads font from the `assets/fonts` directory within the APK
     *
     * @param fontName The name of the font to load.
     * @return A `Typeface` instance of the loaded font or `null` if an error occurs.
     *
     * Example usage:
     * <pre>{@code
     *   val typeface: TypeFace? = loadFont("filename.ttf")
     * }</pre>
     */
    private fun loadFont(fontName: String): Typeface? {
        return try {
            this::class.java.classLoader!!
                .getResourceAsStream("assets/fonts/$fontName")
                .toTypeface(fontName)
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Loads a remote font and converts it into a usable font object.
     *
     * This function makes an HTTP request to download a font from a specified remote URL.
     * It then converts the response into a usable font object.
     */
    private fun loadRemoteFont(fontUrl: String, chain: Interceptor.Chain): Typeface? {
        return try {
            val request = GET(fontUrl, chain.request().headers)
            val response = chain.proceed(request)

            if (response.isSuccessful.not()) {
                response.close()
                return null
            }

            val fontName = request.url.pathSegments.last()
            response.body.use {
                it.byteStream().toTypeface(fontName)
            }
        } catch (e: Exception) {
            null
        }
    }

    private fun InputStream.toTypeface(fontName: String): Typeface? {
        val fontFile = File.createTempFile(fontName, fontName.substringAfter("."))
        this.copyTo(FileOutputStream(fontFile))
        return Typeface.createFromFile(fontFile)
    }

    /**
     * Adjust the text to the center of the dialog box when feasible.
     */
    private fun getYAxis(textPaint: TextPaint, dialog: Dialog, dialogBox: StaticLayout): Float {
        val fontHeight = textPaint.fontMetrics.let { it.bottom - it.top }

        val dialogBoxLineCount = dialog.height / fontHeight

        /**
         * Centers text in y for dialogues smaller than the dialog box
         */
        return when {
            dialogBox.lineCount < dialogBoxLineCount -> dialog.centerY - dialogBox.lineCount / 2f * fontHeight
            else -> dialog.y1
        }
    }

    private fun createDialogBox(dialog: Dialog, textPaint: TextPaint): StaticLayout {
        var dialogBox = createBoxLayout(dialog, textPaint)

        /**
         * The best way I've found to adjust the text in the dialog box (Especially in long dialogues)
         */
        while (dialogBox.height > dialog.height) {
            textPaint.textSize -= 0.5f
            dialogBox = createBoxLayout(dialog, textPaint)
        }

        textPaint.color = Color.BLACK
        textPaint.bgColor = Color.WHITE

        return dialogBox
    }

    private fun createBoxLayout(dialog: Dialog, textPaint: TextPaint): StaticLayout {
        val text = dialog.getTextBy(language)

        return StaticLayout.Builder.obtain(text, 0, text.length, textPaint, dialog.width.toInt()).apply {
            setAlignment(Layout.Alignment.ALIGN_CENTER)
            setIncludePad(false)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                setBreakStrategy(LineBreaker.BREAK_STRATEGY_BALANCED)
                setHyphenationFrequency(Layout.HYPHENATION_FREQUENCY_FULL)
            }
        }.build()
    }

    private inline fun <reified T> String.parseAs(): T {
        return json.decodeFromString(this)
    }

    private fun Canvas.draw(textPaint: TextPaint, layout: StaticLayout, dialog: Dialog, x: Float, y: Float) {
        save()
        translate(x, y)
        rotate(dialog.angle)
        drawTextOutline(textPaint, layout)
        drawText(textPaint, layout)
        restore()
    }

    private fun Canvas.drawText(textPaint: TextPaint, layout: StaticLayout) {
        textPaint.style = Paint.Style.FILL
        layout.draw(this)
    }

    private fun Canvas.drawTextOutline(textPaint: TextPaint, layout: StaticLayout) {
        val foregroundColor = textPaint.color
        val style = textPaint.style

        textPaint.strokeWidth = 5F
        textPaint.color = textPaint.bgColor
        textPaint.style = Paint.Style.FILL_AND_STROKE

        layout.draw(this)

        textPaint.color = foregroundColor
        textPaint.style = style
    }

    // https://pixelsconverter.com/pt-to-px
    private val Int.pt: Float get() = this / SCALED_DENSITY

    companion object {
        // w3: Absolute Lengths [...](https://www.w3.org/TR/css3-values/#absolute-lengths)
        const val SCALED_DENSITY = 0.75f // 1px = 0.75pt
        val mediaType = "image/png".toMediaType()
    }
}
