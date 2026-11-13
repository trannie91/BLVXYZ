package com.blvxyz

import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink
import java.net.URL

class OkRuExtractor : ExtractorApi() {
    override val name = "OK.ru"
    override val mainUrl = "https://ok.ru"
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
        val cleanUrl = url.replace("&amp;", "&")
        val videoId = Regex("""(?:videoembed|/video/|/ok/)/?(\d+)""").find(cleanUrl)?.groupValues?.get(1)
        val targetUrl = if (!videoId.isNullOrEmpty()) {
            "https://ok.ru/videoembed/$videoId"
        } else if (cleanUrl.contains("videoembed")) {
            cleanUrl.split("?")[0]
        } else {
            cleanUrl
        }

        var html = try {
            app.get(
                targetUrl,
                headers = mapOf(
                    "User-Agent" to userAgent,
                    "Referer" to "https://ok.ru/"
                )
            ).text
        } catch (_: Throwable) {
            ""
        }

        if (html.isEmpty()) {
            html = try {
                app.get(
                    targetUrl,
                    headers = mapOf(
                        "User-Agent" to userAgent,
                        "Referer" to (referer ?: "https://blvietsub.xyz/")
                    )
                ).text
            } catch (_: Throwable) {
                ""
            }
        }

        if (html.isEmpty()) return

        var fullUrl: String? = null
        var quadUrl: String? = null
        var ultraUrl: String? = null
        var hlsUrl: String? = null

        val optMatch = Regex("""data-options="([^"]+)"""").find(html)?.groupValues?.get(1)
            ?: Regex("""data-options='([^']+)'""").find(html)?.groupValues?.get(1)

        if (!optMatch.isNullOrEmpty()) {
            val decoded = optMatch
                .replace("&quot;", "\"")
                .replace("&#34;", "\"")
                .replace("&amp;", "&")

            // CHỈ lấy từ "full" (1080p), "quad" (1440p), "ultra" (2160p) - TUYỆT ĐỐI bỏ qua hd (720p), sd (480p), low (360p), lowest (240p), mobile (144p)
            ultraUrl = (Regex(""""name"\s*:\s*"ultra"\s*,\s*"url"\s*:\s*"([^"]+)"""").find(decoded)
                ?: Regex(""""url"\s*:\s*"([^"]+)"\s*,\s*"name"\s*:\s*"ultra"""").find(decoded))?.groupValues?.get(1)

            quadUrl = (Regex(""""name"\s*:\s*"quad"\s*,\s*"url"\s*:\s*"([^"]+)"""").find(decoded)
                ?: Regex(""""url"\s*:\s*"([^"]+)"\s*,\s*"name"\s*:\s*"quad"""").find(decoded))?.groupValues?.get(1)

            fullUrl = (Regex(""""name"\s*:\s*"full"\s*,\s*"url"\s*:\s*"([^"]+)"""").find(decoded)
                ?: Regex(""""url"\s*:\s*"([^"]+)"\s*,\s*"name"\s*:\s*"full"""").find(decoded))?.groupValues?.get(1)

            hlsUrl = Regex(""""hlsManifestUrl"\s*:\s*"([^"]+)"""").find(decoded)?.groupValues?.get(1)
        }

        if (fullUrl.isNullOrEmpty()) {
            fullUrl = Regex("""(?:"|&quot;)name(?:"|&quot;)\s*:\s*(?:"|&quot;)full(?:"|&quot;)\s*,\s*(?:"|&quot;)url(?:"|&quot;)\s*:\s*(?:"|&quot;)([^"&]+(?:&(?:quot;|amp;)[^"&]+)*)(?:"|&quot;)""")
                .find(html)?.groupValues?.get(1)
        }

        if (hlsUrl.isNullOrEmpty()) {
            val hlsDirect = Regex("""(?:"|&quot;)hlsManifestUrl(?:"|&quot;)\s*:\s*(?:"|&quot;)([^"&]+(?:&(?:quot;|amp;)[^"&]+)*)(?:"|&quot;)""").find(html)
                ?: Regex("""https?://[^\s'"]*(?:okcdn\.ru|ok\.ru)/video\.m3u8[^\s'"]*""").find(html)
            if (hlsDirect != null) {
                hlsUrl = hlsDirect.groupValues.getOrNull(1) ?: hlsDirect.value
            }
        }

        fun cleanStreamUrl(raw: String): String {
            return raw
                .replace("\\u0026", "&")
                .replace("&amp;", "&")
                .replace("&quot;", "")
                .replace("\\/", "/")
                .trim()
        }

        if (!ultraUrl.isNullOrEmpty()) {
            callback.invoke(
                newExtractorLink(
                    source = name,
                    name = "Server OK (OK.ru) - 2160p 4K",
                    url = cleanStreamUrl(ultraUrl),
                    type = ExtractorLinkType.VIDEO
                ) {
                    this.quality = Qualities.P2160.value
                    this.referer = "https://ok.ru/"
                    this.headers = mapOf("Referer" to "https://ok.ru/", "User-Agent" to userAgent)
                }
            )
        }

        if (!quadUrl.isNullOrEmpty()) {
            callback.invoke(
                newExtractorLink(
                    source = name,
                    name = "Server OK (OK.ru) - 1440p QHD",
                    url = cleanStreamUrl(quadUrl),
                    type = ExtractorLinkType.VIDEO
                ) {
                    this.quality = Qualities.P1440.value
                    this.referer = "https://ok.ru/"
                    this.headers = mapOf("Referer" to "https://ok.ru/", "User-Agent" to userAgent)
                }
            )
        }

        if (!fullUrl.isNullOrEmpty()) {
            callback.invoke(
                newExtractorLink(
                    source = name,
                    name = "Server OK (OK.ru) - 1080p FHD",
                    url = cleanStreamUrl(fullUrl),
                    type = ExtractorLinkType.VIDEO
                ) {
                    this.quality = Qualities.P1080.value
                    this.referer = "https://ok.ru/"
                    this.headers = mapOf("Referer" to "https://ok.ru/", "User-Agent" to userAgent)
                }
            )
        }

        // Đối với HLS: chỉ phát nếu trích xuất được sub-playlist >= 1080p (tránh HLS chỉ có 720p/480p)
        if (!hlsUrl.isNullOrEmpty()) {
            val cleanedHls = cleanStreamUrl(hlsUrl)
            try {
                val masterText = app.get(
                    cleanedHls,
                    headers = mapOf("Referer" to "https://ok.ru/", "User-Agent" to userAgent)
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
                                bestSubUrl = resolveUrl(cleanedHls, nextUrl)
                            }
                        }
                    }
                    if (!bestSubUrl.isNullOrEmpty()) {
                        callback.invoke(
                            newExtractorLink(
                                source = name,
                                name = "Server OK (OK.ru) - ${bestHeight}p FHD (HLS)",
                                url = bestSubUrl,
                                type = ExtractorLinkType.M3U8
                            ) {
                                this.quality = if (bestHeight >= 2160) Qualities.P2160.value else if (bestHeight >= 1440) Qualities.P1440.value else Qualities.P1080.value
                                this.referer = "https://ok.ru/"
                                this.headers = mapOf("Referer" to "https://ok.ru/", "User-Agent" to userAgent)
                            }
                        )
                    }
                }
            } catch (_: Throwable) {
                // Nếu đã tìm thấy fullUrl MP4 1080p ở trên thì dùng trực tiếp, không đẩy HLS chưa xác minh
            }
        }
    }
}
