package com.blvxyz

import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.network.WebViewResolver
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink
import org.json.JSONObject

class RumbleExtractor : ExtractorApi() {
    override val name = "Rumble"
    override val mainUrl = "https://rumble.com"
    override val requiresReferer = false

    private val userAgent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36"

    private fun parseAndEmitFromRumbleJson(jsonText: String, callback: (ExtractorLink) -> Unit): Boolean {
        return try {
            val root = JSONObject(jsonText)
            val ua = root.optJSONObject("ua") ?: return false
            var found = false

            // 1. MP4 resolutions: chỉ lấy 2160, 1440, 1080 - loại bỏ < 1080p
            val mp4Obj = ua.optJSONObject("mp4")
            if (mp4Obj != null) {
                for ((resKey, qVal) in listOf("2160" to Qualities.P2160.value, "1440" to Qualities.P1440.value, "1080" to Qualities.P1080.value)) {
                    val resObj = mp4Obj.optJSONObject(resKey)
                    val mp4Url = resObj?.optString("url")
                    if (!mp4Url.isNullOrEmpty() && mp4Url.startsWith("http")) {
                        callback.invoke(
                            newExtractorLink(
                                source = name,
                                name = "Server Rumble - ${resKey}p FHD",
                                url = mp4Url,
                                type = ExtractorLinkType.VIDEO
                            ) {
                                this.quality = qVal
                                this.referer = "https://rumble.com/"
                                this.headers = mapOf("Referer" to "https://rumble.com/", "User-Agent" to userAgent)
                            }
                        )
                        found = true
                    }
                }
            }

            // 2. HLS: chỉ lấy khi video có bản 1080p trở lên (hoặc qua auto khi ua.h >= 1080)
            val height = root.optInt("h", 0)
            if (height == 0 || height >= 1080) {
                val hlsObj = ua.optJSONObject("hls")
                val hlsUrl = hlsObj?.optJSONObject("auto")?.optString("url")
                if (!hlsUrl.isNullOrEmpty() && hlsUrl.startsWith("http") && (found || height >= 1080)) {
                    callback.invoke(
                        newExtractorLink(
                            source = name,
                            name = "Server Rumble - 1080p FHD (HLS)",
                            url = hlsUrl,
                            type = ExtractorLinkType.M3U8
                        ) {
                            this.quality = Qualities.P1080.value
                            this.referer = "https://rumble.com/"
                            this.headers = mapOf("Referer" to "https://rumble.com/", "User-Agent" to userAgent)
                        }
                    )
                    found = true
                }
            }
            found
        } catch (_: Throwable) {
            false
        }
    }

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val cleanUrl = url.replace("&amp;", "&").trim()
        val videoId = Regex("""rumble\.com/embed/([a-zA-Z0-9]+)""").find(cleanUrl)?.groupValues?.get(1) ?: return
        val apiUrl = "https://rumble.com/embedJS/u3/?request=video&ver=2&v=$videoId"

        try {
            val apiRes = app.get(
                apiUrl,
                headers = mapOf(
                    "User-Agent" to userAgent,
                    "Referer" to cleanUrl
                )
            ).text
            if (apiRes.trim().startsWith("{") && parseAndEmitFromRumbleJson(apiRes, callback)) {
                return
            }
        } catch (_: Throwable) {}

        // Fallback WebViewResolver khi embedJS bị Cloudflare challenge trên TV Box
        try {
            val resolver = WebViewResolver(
                interceptUrl = Regex("""(?:rumble\.com/embedJS|\.(?:mp4|m3u8))"""),
                additionalUrls = listOf(Regex("""https?://[^\s"']+\.(?:mp4|m3u8)[^\s"']*""")),
                userAgent = userAgent,
                timeout = 14_000L
            )
            val intercepted = app.get(
                cleanUrl,
                headers = mapOf("Referer" to (referer ?: "https://blvietsub.xyz/"), "User-Agent" to userAgent),
                interceptor = resolver
            )
            val interceptedUrl = intercepted.url
            if (interceptedUrl.contains("embedJS") && intercepted.text.trim().startsWith("{")) {
                parseAndEmitFromRumbleJson(intercepted.text, callback)
            } else if (interceptedUrl.contains(".m3u8") || (interceptedUrl.contains(".mp4") && !interceptedUrl.contains("360") && !interceptedUrl.contains("480") && !interceptedUrl.contains("720"))) {
                val isM3u8 = interceptedUrl.contains(".m3u8")
                callback.invoke(
                    newExtractorLink(
                        source = name,
                        name = "Server Rumble - 1080p FHD",
                        url = interceptedUrl,
                        type = if (isM3u8) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                    ) {
                        this.quality = Qualities.P1080.value
                        this.referer = "https://rumble.com/"
                        this.headers = mapOf("Referer" to "https://rumble.com/", "User-Agent" to userAgent)
                    }
                )
            }
        } catch (_: Throwable) {}
    }
}
