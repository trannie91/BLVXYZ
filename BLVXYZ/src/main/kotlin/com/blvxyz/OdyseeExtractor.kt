package com.blvxyz

import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink
import org.json.JSONObject

class OdyseeExtractor : ExtractorApi() {
    override val name = "Odysee"
    override val mainUrl = "https://odysee.com"
    override val requiresReferer = false

    private val userAgent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36"

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val cleanUrl = url.replace("&amp;", "&").trim()
        var streamUrl: String? = null
        var videoHeight = 0

        // 1. Lấy từ Odysee LBRY API (trả về streaming_url + video height)
        val embedMatch = Regex("""odysee\.com/\$/embed/([^/?#]+)/([^/?#]+)""").find(cleanUrl)
        if (embedMatch != null) {
            val claimName = embedMatch.groupValues[1]
            val claimId = embedMatch.groupValues[2]
            val lbryUri = "lbry://$claimName#$claimId"

            try {
                // Kiểm tra độ phân giải qua resolve trước
                val resolveBody = """{"jsonrpc":"2.0","method":"resolve","params":{"urls":["$lbryUri"]},"id":1}"""
                val resolveRes = app.post(
                    "https://api.na-backend.odysee.com/api/v1/proxy?m=resolve",
                    headers = mapOf("Content-Type" to "application/json", "User-Agent" to userAgent),
                    json = mapOf(
                        "jsonrpc" to "2.0",
                        "method" to "resolve",
                        "params" to mapOf("urls" to listOf(lbryUri)),
                        "id" to 1
                    )
                ).text
                val resolveJson = JSONObject(resolveRes)
                val claimObj = resolveJson.optJSONObject("result")?.optJSONObject(lbryUri)
                val videoMeta = claimObj?.optJSONObject("value")?.optJSONObject("video")
                videoHeight = videoMeta?.optInt("height", 0) ?: 0
            } catch (_: Throwable) {}

            // Nếu video gốc Odysee có khai báo height rõ ràng < 1080p -> loại bỏ!
            if (videoHeight in 1 until 1080) {
                return
            }

            try {
                val getRes = app.post(
                    "https://api.na-backend.odysee.com/api/v1/proxy?m=get",
                    headers = mapOf("Content-Type" to "application/json", "User-Agent" to userAgent),
                    json = mapOf(
                        "jsonrpc" to "2.0",
                        "method" to "get",
                        "params" to mapOf("uri" to lbryUri),
                        "id" to 2
                    )
                ).text
                val getJson = JSONObject(getRes)
                streamUrl = getJson.optJSONObject("result")?.optString("streaming_url")
            } catch (_: Throwable) {}
        }

        // 2. Fallback: parse trực tiếp contentUrl từ HTML trang embed Odysee
        if (streamUrl.isNullOrEmpty()) {
            try {
                val html = app.get(
                    cleanUrl,
                    headers = mapOf("User-Agent" to userAgent, "Referer" to "https://odysee.com/")
                ).text
                streamUrl = Regex(""""contentUrl"\s*:\s*"(https://player\.odycdn\.com[^"]+)"""").find(html)?.groupValues?.get(1)
                    ?: Regex("""https://player\.odycdn\.com/api/v\d+/streams/[^"'\s\\<>]+""").find(html)?.value
            } catch (_: Throwable) {}
        }

        if (streamUrl.isNullOrEmpty()) return

        val isHls = streamUrl.contains(".m3u8", ignoreCase = true)
        val qVal = when {
            videoHeight >= 2160 -> Qualities.P2160.value
            videoHeight >= 1440 -> Qualities.P1440.value
            else -> Qualities.P1080.value
        }

        callback.invoke(
            newExtractorLink(
                source = name,
                name = "Server Odysee - ${if (videoHeight >= 1080) "${videoHeight}p" else "1080p"} FHD",
                url = streamUrl,
                type = if (isHls) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
            ) {
                this.quality = qVal
                this.referer = "https://odysee.com/"
                this.headers = mapOf(
                    "Referer" to "https://odysee.com/",
                    "Origin" to "https://odysee.com",
                    "User-Agent" to userAgent
                )
            }
        )
    }
}
