package com.blvxyz

import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink
import java.net.URL

class DailymotionExtractor : ExtractorApi() {
    override val name = "Dailymotion"
    override val mainUrl = "https://www.dailymotion.com"
    override val requiresReferer = false

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
        // Hỗ trợ cả /video/ID, /embed/video/ID và geo.dailymotion.com/player/...html?video=ID
        val videoId = Regex("""(?:video|embed/video)[/=]([a-zA-Z0-9]+)""").find(url)?.groupValues?.get(1) ?: return
        val metadataApiUrl = "https://www.dailymotion.com/player/metadata/video/$videoId?embedder=https%3A%2F%2Fblvietsub.xyz%2F"

        val metadataResponse = try {
            app.get(
                metadataApiUrl,
                headers = mapOf(
                    "User-Agent" to userAgent,
                    "Referer" to "https://geo.dailymotion.com/"
                )
            ).text
        } catch (_: Throwable) {
            return
        }

        try {
            val masterM3u8Url = Regex(""""qualities"\s*:\s*\{[^}]*"auto"\s*:\s*\[\s*\{[^}]*"url"\s*:\s*"([^"]+)"""").find(metadataResponse)?.groupValues?.get(1)
                ?: Regex(""""url"\s*:\s*"([^"]+\.m3u8[^"]*)"""").find(metadataResponse)?.groupValues?.get(1)
                ?: return

            val cleanMasterUrl = masterM3u8Url.replace("\\/", "/").replace("&amp;", "&")

            val m3u8Content = try {
                app.get(
                    cleanMasterUrl,
                    headers = mapOf(
                        "User-Agent" to userAgent,
                        "Referer" to "https://www.dailymotion.com/"
                    )
                ).text
            } catch (_: Throwable) {
                ""
            }

            if (m3u8Content.isEmpty() || !m3u8Content.contains("#EXT-X-STREAM-INF")) return

            val lines = m3u8Content.lines()
            var best1080Url: String? = null
            var bestHeight = 0

            // CHỈ CHỌN LUỒNG CÓ ĐỘ PHÂN GIẢI >= 1080p (1920x1080, 1080p, 1440p, 2160p)
            // Loại bỏ hoàn toàn nếu video Dailymotion chỉ có 720p, 480p, 380p, 240p!
            for (i in lines.indices) {
                val line = lines[i].trim()
                if (line.startsWith("#EXT-X-STREAM-INF")) {
                    val nextUrl = lines.getOrNull(i + 1)?.trim() ?: continue
                    if (nextUrl.isEmpty() || nextUrl.startsWith("#")) continue

                    val resHeight = Regex("""RESOLUTION=\d+x(\d+)""").find(line)?.groupValues?.get(1)?.toIntOrNull()
                        ?: Regex("""NAME=["']?(\d+)["']?""").find(line)?.groupValues?.get(1)?.toIntOrNull()
                        ?: 0

                    if (resHeight >= 1080 && resHeight > bestHeight) {
                        bestHeight = resHeight
                        best1080Url = resolveUrl(cleanMasterUrl, nextUrl)
                    }
                }
            }

            // Không có 1080p trở lên -> bỏ qua không trả về link chất lượng thấp
            val finalUrl = best1080Url ?: return
            val qualityVal = when {
                bestHeight >= 2160 -> Qualities.P2160.value
                bestHeight >= 1440 -> Qualities.P1440.value
                else -> Qualities.P1080.value
            }

            callback.invoke(
                newExtractorLink(
                    source = name,
                    name = "Server DL (Dailymotion) - ${bestHeight}p FHD",
                    url = finalUrl,
                    type = ExtractorLinkType.M3U8
                ) {
                    this.quality = qualityVal
                    this.referer = "https://www.dailymotion.com/"
                    this.headers = mapOf(
                        "Referer" to "https://www.dailymotion.com/",
                        "User-Agent" to userAgent
                    )
                }
            )
        } catch (_: Throwable) {}
    }
}
