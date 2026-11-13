package com.blvxyz

import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink
import java.net.URL

class SSPlayExtractor : ExtractorApi() {
    override val name = "SSPlay"
    override val mainUrl = "https://ssplay.net"
    override val requiresReferer = true

    private val userAgent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36"

    private fun unPackDeanEdwards(code: String): String {
        try {
            val pattern = Regex("""\}\s*\(\s*['"]([\s\S]*?)['"]\s*,\s*(\d+)\s*,\s*(\d+)\s*,\s*['"]([\s\S]*?)['"]\s*\.split\(['"]\|['"]\)""")
            val match = pattern.find(code) ?: return ""
            val p = match.groupValues[1]
            val a = match.groupValues[2].toIntOrNull() ?: return ""
            val k = match.groupValues[4].split("|")

            fun base62Decode(str: String): Int? {
                var num = 0
                for (ch in str) {
                    val digit = when (ch) {
                        in '0'..'9' -> ch - '0'
                        in 'a'..'z' -> ch - 'a' + 10
                        in 'A'..'Z' -> ch - 'A' + 36
                        else -> return null
                    }
                    num = num * a + digit
                }
                return num
            }

            return Regex("""\b\w+\b""").replace(p) { m ->
                val idx = base62Decode(m.value)
                if (idx != null && idx in k.indices && k[idx].isNotEmpty()) {
                    k[idx]
                } else {
                    m.value
                }
            }
        } catch (_: Throwable) {
            return ""
        }
    }

    private fun resolveRelativeUrl(baseUrl: String, relative: String): String {
        if (relative.startsWith("http://") || relative.startsWith("https://")) return relative
        return try {
            URL(URL(baseUrl), relative).toString()
        } catch (_: Throwable) {
            if (relative.startsWith("/")) "https://ssplay.net$relative" else relative
        }
    }

    private suspend fun verifyAndEmit1080pM3u8(
        streamUrl: String,
        labelHint: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        // Loại bỏ ngay nếu label ghi rõ chất lượng thấp dưới 1080p
        val lowerLabel = labelHint.lowercase()
        if (lowerLabel.contains("720") || lowerLabel.contains("480") || lowerLabel.contains("360") || lowerLabel.contains("240")) {
            return false
        }

        var finalUrl = streamUrl
        var detectedQuality = Qualities.P1080.value

        try {
            val playlist = app.get(
                streamUrl,
                headers = mapOf(
                    "Referer" to "https://ssplay.net/",
                    "User-Agent" to userAgent
                )
            ).text

            if (playlist.contains("#EXT-X-STREAM-INF")) {
                val lines = playlist.lines()
                var bestHeight = 0
                var bestUrl: String? = null

                for (i in lines.indices) {
                    val line = lines[i].trim()
                    if (line.startsWith("#EXT-X-STREAM-INF")) {
                        val nextUrl = lines.getOrNull(i + 1)?.trim() ?: continue
                        if (nextUrl.isEmpty() || nextUrl.startsWith("#")) continue

                        val height = Regex("""RESOLUTION=\d+x(\d+)""").find(line)?.groupValues?.get(1)?.toIntOrNull()
                            ?: Regex("""NAME=["']?(\d+)p?["']?""").find(line)?.groupValues?.get(1)?.toIntOrNull()
                            ?: 0

                        if (height >= 1080 && height > bestHeight) {
                            bestHeight = height
                            bestUrl = resolveRelativeUrl(streamUrl, nextUrl)
                        }
                    }
                }

                // Nếu đây là master playlist có khai báo độ phân giải mà KHÔNG có >= 1080p -> loại bỏ
                if (bestUrl == null) {
                    return false
                }
                finalUrl = bestUrl
                detectedQuality = if (bestHeight >= 2160) Qualities.P2160.value else if (bestHeight >= 1440) Qualities.P1440.value else Qualities.P1080.value
            }
        } catch (_: Throwable) {
            // Nếu không tải kiểm tra trước được nhưng label là 1080p (hoặc không thấp), vẫn phát luồng gốc
        }

        callback.invoke(
            newExtractorLink(
                source = name,
                name = "Server SS (SSPlay) - 1080p FHD",
                url = finalUrl,
                type = ExtractorLinkType.M3U8
            ) {
                this.referer = "https://ssplay.net/"
                this.quality = detectedQuality
                this.headers = mapOf(
                    "Referer" to "https://ssplay.net/",
                    "Origin" to "https://ssplay.net",
                    "User-Agent" to userAgent
                )
            }
        )
        return true
    }

    private suspend fun extractStreamFromHtml(html: String, callback: (ExtractorLink) -> Unit): Boolean {
        // 1. Direct /ST/ hoặc /SU/
        val directSuMatch = Regex("""["'](/S[UT]/[^"']+)["']""").find(html)?.groupValues?.get(1)
            ?: Regex("""/S[UT]/[a-zA-Z0-9_\-./]+""").find(html)?.value

        if (directSuMatch != null) {
            val streamUrl = if (directSuMatch.startsWith("http")) directSuMatch else "https://ssplay.net$directSuMatch"
            if (verifyAndEmit1080pM3u8(streamUrl, "", callback)) return true
        }

        // 2. Direct .m3u8 URL
        val m3u8Match = Regex("""https?://[^\s"']+\.m3u8[^\s"']*""").find(html)?.value
        if (m3u8Match != null) {
            if (verifyAndEmit1080pM3u8(m3u8Match, "", callback)) return true
        }

        // 3. Giải mã Dean Edwards Packer
        val packedMatches = Regex("""eval\(function\(p,a,c,k,e,d\)[\s\S]*?split\(['"]\|['"]\)\)\)""").findAll(html).toList()
        for (match in packedMatches) {
            val unpacked = unPackDeanEdwards(match.value)
            if (unpacked.isEmpty()) continue

            val labelHint = Regex("""["']label["']\s*:\s*["']([^"']+)["']""").find(unpacked)?.groupValues?.get(1) ?: ""

            val suMatch = Regex("""["'](/S[UT]/[^"']+)["']""").find(unpacked)?.groupValues?.get(1)
                ?: Regex("""/S[UT]/[a-zA-Z0-9_\-./]+""").find(unpacked)?.value

            if (suMatch != null) {
                val finalUrl = if (suMatch.startsWith("http")) suMatch else "https://ssplay.net$suMatch"
                if (verifyAndEmit1080pM3u8(finalUrl, labelHint, callback)) return true
            }

            val fileMatch = Regex("""["']file["']\s*:\s*["']([^"']+)["']""").find(unpacked)?.groupValues?.get(1)
                ?: Regex("""file\s*:\s*["']([^"']+)["']""").find(unpacked)?.groupValues?.get(1)

            if (fileMatch != null && (fileMatch.contains(".m3u8") || fileMatch.contains("/SU/") || fileMatch.contains("/ST/"))) {
                val finalUrl = if (fileMatch.startsWith("http")) fileMatch else "https://ssplay.net$fileMatch"
                if (verifyAndEmit1080pM3u8(finalUrl, labelHint, callback)) return true
            }
        }

        return false
    }

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val targetUrl = if (url.startsWith("http")) url else "$mainUrl$url"
        val ref = referer ?: "https://blvietsub.xyz/"
        val headers = mapOf(
            "Referer" to ref,
            "User-Agent" to userAgent
        )

        val response = try {
            app.get(targetUrl, headers = headers).text
        } catch (_: Throwable) {
            return
        }

        if (extractStreamFromHtml(response, callback)) return

        // Theo dõi iframe con (ví dụ: ssplay.net/ok/... -> ssplay.net/v/...)
        val iframeSrc = Regex("""<iframe[^>]+src=["']([^"']+)["']""").find(response)?.groupValues?.get(1)
        if (!iframeSrc.isNullOrEmpty()) {
            val nextUrl = if (iframeSrc.startsWith("http")) iframeSrc else "https://ssplay.net$iframeSrc"
            try {
                val childHtml = app.get(
                    nextUrl,
                    headers = mapOf(
                        "Referer" to targetUrl,
                        "User-Agent" to userAgent
                    )
                ).text
                extractStreamFromHtml(childHtml, callback)
            } catch (_: Throwable) {}
        }
    }
}
