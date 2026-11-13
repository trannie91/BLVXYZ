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

class VkExtractor : ExtractorApi() {
    override val name = "VK"
    override val mainUrl = "https://vk.com"
    override val requiresReferer = false

    private val userAgent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36"

    private fun cleanVkUrl(raw: String): String {
        return raw
            .replace("\\u0026", "&")
            .replace("&amp;", "&")
            .replace("&quot;", "")
            .replace("\\/", "/")
            .trim()
    }

    private fun resolveUrl(base: String, rel: String): String {
        if (rel.startsWith("http://") || rel.startsWith("https://")) return rel
        return try {
            URL(URL(base), rel).toString()
        } catch (_: Throwable) {
            rel
        }
    }

    private suspend fun checkAndEmitHls1080(hlsUrl: String, refUrl: String, callback: (ExtractorLink) -> Unit): Boolean {
        try {
            val masterText = app.get(
                hlsUrl,
                headers = mapOf("Referer" to refUrl, "User-Agent" to userAgent)
            ).text

            if (masterText.contains("#EXT-X-STREAM-INF")) {
                val lines = masterText.lines()
                var bestHeight = 0
                var bestSubUrl: String? = null
                for (i in lines.indices) {
                    val line = lines[i].trim()
                    if (line.startsWith("#EXT-X-STREAM-INF")) {
                        val nextUrl = lines.getOrNull(i + 1)?.trim() ?: continue
                        if (nextUrl.isEmpty() || nextUrl.startsWith("#")) continue
                        val height = Regex("""RESOLUTION=\d+x(\d+)""").find(line)?.groupValues?.get(1)?.toIntOrNull() ?: 0
                        if (height >= 1080 && height > bestHeight) {
                            bestHeight = height
                            bestSubUrl = resolveUrl(hlsUrl, nextUrl)
                        }
                    }
                }
                if (bestSubUrl == null) return false

                callback.invoke(
                    newExtractorLink(
                        source = name,
                        name = "Server VK - ${bestHeight}p FHD (HLS)",
                        url = bestSubUrl,
                        type = ExtractorLinkType.M3U8
                    ) {
                        this.quality = if (bestHeight >= 2160) Qualities.P2160.value else if (bestHeight >= 1440) Qualities.P1440.value else Qualities.P1080.value
                        this.referer = refUrl
                        this.headers = mapOf("Referer" to refUrl, "User-Agent" to userAgent)
                    }
                )
                return true
            }
        } catch (_: Throwable) {}
        return false
    }

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        var targetUrl = url.replace("&amp;", "&")
        val videoMatch = Regex("""video(-?\d+)_(\d+)""").find(targetUrl)
        if (videoMatch != null) {
            val oid = videoMatch.groupValues[1]
            val id = videoMatch.groupValues[2]
            targetUrl = "https://vk.com/video_ext.php?oid=$oid&id=$id"
        }

        val candidates = listOf(
            targetUrl,
            targetUrl.replace("vkvideo.ru", "vk.com")
        ).distinct()

        var foundAny = false

        for (candidate in candidates) {
            val response = try {
                app.get(
                    candidate,
                    headers = mapOf(
                        "User-Agent" to userAgent,
                        "Referer" to (referer ?: "https://blvietsub.xyz/")
                    )
                ).text
            } catch (_: Throwable) {
                ""
            }

            if (response.isEmpty() || response.contains("Video not found") || response.contains("Access denied")) {
                continue
            }

            val url1080Match = Regex("""["']url1080["']\s*[:=]\s*["']([^"']+)["']""").find(response)?.groupValues?.get(1)
                ?: Regex("""\\?"url1080\\?"\s*:\s*\\?"([^"\\]+)\\?""").find(response)?.groupValues?.get(1)

            if (!url1080Match.isNullOrEmpty()) {
                val videoUrl = cleanVkUrl(url1080Match)
                callback.invoke(
                    newExtractorLink(
                        source = name,
                        name = "Server VK - 1080p FHD",
                        url = videoUrl,
                        type = ExtractorLinkType.VIDEO
                    ) {
                        this.quality = Qualities.P1080.value
                        this.referer = "https://vk.com/"
                        this.headers = mapOf("Referer" to "https://vk.com/", "User-Agent" to userAgent)
                    }
                )
                foundAny = true
            }

            val hlsMatch = Regex("""["']hls["']\s*[:=]\s*["']([^"']+)["']""").find(response)?.groupValues?.get(1)
                ?: Regex("""https?://[^\s'"]+\.m3u8[^\s'"]*""").find(response)?.value

            if (!hlsMatch.isNullOrEmpty()) {
                val hlsUrl = cleanVkUrl(hlsMatch)
                if (checkAndEmitHls1080(hlsUrl, "https://vk.com/", callback)) {
                    foundAny = true
                }
            }

            if (foundAny) return
        }

        // Fallback WebViewResolver cho vkvideo.ru dạng player SPA mới
        if (!foundAny) {
            try {
                val resolver = WebViewResolver(
                    interceptUrl = Regex("""(?:\.m3u8|url1080|type=1080|vkuservideo)"""),
                    additionalUrls = listOf(Regex("""https?://[^\s"']+\.m3u8[^\s"']*""")),
                    userAgent = userAgent,
                    timeout = 12_000L
                )
                val intercepted = app.get(
                    targetUrl,
                    headers = mapOf("Referer" to (referer ?: "https://blvietsub.xyz/"), "User-Agent" to userAgent),
                    interceptor = resolver
                ).url
                if (intercepted.contains(".m3u8")) {
                    checkAndEmitHls1080(intercepted, targetUrl, callback)
                }
            } catch (_: Throwable) {}
        }
    }
}
