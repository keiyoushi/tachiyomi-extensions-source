package eu.kanade.tachiyomi.extension.zh.miaoshang

import eu.kanade.tachiyomi.multisrc.mccms.MCCMS
import eu.kanade.tachiyomi.multisrc.mccms.MCCMSConfig
import eu.kanade.tachiyomi.network.interceptor.rateLimitHost
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.util.asJsoup
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Response
import org.jsoup.Jsoup

class Miaoqu : MCCMS(
    "喵趣漫画",
    "https://www.miaoqumh.com",
    "zh",
    MiaoquMCCMSConfig(),
) {
    override val client = network.cloudflareClient.newBuilder()
        .rateLimitHost(baseUrl.toHttpUrl(), 2)
        .build()     
          
    override val id = 589887691505478724

    private class MiaoquMCCMSConfig : MCCMSConfig(
        textSearchOnlyPageOne = true,
        lazyLoadImageAttr = "data-src",
    ) {
        override fun pageListParse(response: Response): List<Page> {
            val document = response.asJsoup()
            val container = document.select(".rd-article-wr")
            val comments = container.comments()

            return comments.filter { comment ->
                comment.data.contains(lazyLoadImageAttr)
            }.mapIndexed { i, comment ->
                Jsoup.parse(comment.data)
                    .selectFirst("img[$lazyLoadImageAttr]")?.attr(lazyLoadImageAttr).let { imageUrl ->
                        Page(i, imageUrl = imageUrl)
                    }
            }.ifEmpty {
                document.select("img[$lazyLoadImageAttr]").mapIndexed { i, img ->
                    Page(i, imageUrl = img.attr(lazyLoadImageAttr))
                }
            }
        }
    }    
}
