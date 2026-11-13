package com.blvxyz

import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.network.WebViewResolver
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink
import java.net.URL

class AbyssExtractor : ExtractorApi() {
    override val name = "Abyss"
    override val mainUrl = "https://player.abyssplayer.com"
    override val requiresReferer = true

    private val userAgent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36"

    private fun resolveUrl(base: String, rel: String): String {
        if (rel.startsWith("http://") || rel.startsWith("https://")) return rel
        return try {
            URL(URL(base), rel).toString()
        } catch (_: Throwable) {
            rel
        }
    }

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val cleanUrl = url.replace("&amp;", "&").trim()
        val ref = referer ?: "https://blvietsub.xyz/"

        // 1. Kiểm tra link .m3u8 trực tiếp nếu có trong HTML
        try {
            val html = app.get(cleanUrl, headers = mapOf("User-Agent" to userAgent, "Referer" to ref)).text
            val m3u8Match = Regex("""https?://[^\s"'<>]+\.m3u8[^\s"'<>]*""").find(html)?.value
            if (!m3u8Match.isNullOrEmpty()) {
                val master = try {
                    app.get(m3u8Match, headers = mapOf("User-Agent" to userAgent, "Referer" to cleanUrl)).text
                } catch (_: Throwable) {
                    ""
                }
                if (master.contains("#EXT-X-STREAM-INF")) {
                    val lines = master.lines()
                    var bestHeight = 0
                    var bestSub: String? = null
                    for (i in lines.indices) {
                        val line = lines[i].trim()
                        if (line.startsWith("#EXT-X-STREAM-INF")) {
                            val nextUrl = lines.getOrNull(i + 1)?.trim() ?: continue
                            if (nextUrl.isEmpty() || nextUrl.startsWith("#")) continue
                            val h = Regex("""RESOLUTION=\d+x(\d+)""").find(line)?.groupValues?.get(1)?.toIntOrNull() ?: 0
                            if (h >= 1080 && h > bestHeight) {
                                bestHeight = h
                                bestSub = resolveUrl(m3u8Match, nextUrl)
                            }
                        }
                    }
                    if (bestSub != null) {
                        callback.invoke(
                            newExtractorLink(
                                source = name,
                                name = "Server HX (Abyss) - ${bestHeight}p FHD",
                                url = bestSub,
                                type = ExtractorLinkType.M3U8
                            ) {
                                this.quality = Qualities.P1080.value
                                this.referer = cleanUrl
                                this.headers = mapOf("Referer" to cleanUrl, "User-Agent" to userAgent)
                            }
                        )
                        return
                    }
                    // Nếu master có STREAM-INF nhưng không có >= 1080p -> loại bỏ
                    return
                }
            }
        } catch (_: Throwable) {}

        // 2. Fallback WebViewResolver giải mã payload iamcdn/SoTrym của AbyssPlayer trên Android/TV Box
        try {
            val resolver = WebViewResolver(
                interceptUrl = Regex("""(?:\.m3u8|\.mp4|googlevideo\.com/videoplayback)"""),
                additionalUrls = listOf(Regex("""https?://[^\s"']+\.(?:m3u8|mp4)[^\s"']*""")),
                userAgent = userAgent,
                timeout = 14_000L
            )
            val interceptedUrl = app.get(
                cleanUrl,
                headers = mapOf("Referer" to ref, "User-Agent" to userAgent),
                interceptor = resolver
            ).url

            if (interceptedUrl != cleanUrl && (interceptedUrl.contains(".m3u8") || interceptedUrl.contains(".mp4") || interceptedUrl.contains("videoplayback"))) {
                // Loại bỏ nếu URL ghi rõ độ phân giải thấp
                if (Regex("""(?:144|240|360|480|720)p""").containsMatchIn(interceptedUrl)) return

                val isM3u8 = interceptedUrl.contains(".m3u8")
                callback.invoke(
                    newExtractorLink(
                        source = name,
                        name = "Server HX (Abyss) - 1080p FHD",
                        url = interceptedUrl,
                        type = if (isM3u8) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                    ) {
                        this.quality = Qualities.P1080.value
                        this.referer = cleanUrl
                        this.headers = mapOf("Referer" to cleanUrl, "User-Agent" to userAgent)
                    }
                )
            }
        } catch (_: Throwable) {}
    }
}
