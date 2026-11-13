package com.blvxyz

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import java.net.URLEncoder

class BLVXYZProvider : MainAPI() {
    override var mainUrl = "https://blvietsub.xyz"
    private val apiUrl = "https://api.blvietsub.xyz"
    override var name = "BLVXYZ"
    override val hasMainPage = true
    override var lang = "vi"
    override val hasQuickSearch = true
    override val supportedTypes = setOf(
        TvType.TvSeries,
        TvType.Movie,
        TvType.AsianDrama
    )

    override val mainPage = mainPageOf(
        "tim-kiem?sort=updatedAt" to "Phim Mới Cập Nhật"
    )

    private val userAgent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36"

    private fun cleanTitle(str: String): String {
        return str
            .replace(Regex("""^\[+|\]+$"""), "")
            .replace("&#39;", "'")
            .replace("&amp;", "&")
            .replace("&quot;", "\"")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .trim()
    }

    private fun isUsableImageUrl(url: String?): Boolean {
        if (url.isNullOrBlank()) return false
        val lower = url.lowercase().trim()
        return !lower.startsWith("data:image") &&
            !lower.contains("localhost:") &&
            !lower.contains("127.0.0.1") &&
            !lower.contains("blank.gif") &&
            !lower.contains("placeholder")
    }

    private fun enhancePoster(url: String?): String {
        if (url.isNullOrBlank()) return ""
        var poster = url.trim().replace("&amp;", "&")
        if (poster.startsWith("http://localhost:8000")) {
            poster = poster.replace("http://localhost:8000", apiUrl)
        }
        if (poster.startsWith("/storage/")) {
            poster = "$apiUrl$poster"
        } else if (poster.startsWith("/")) {
            poster = "$mainUrl$poster"
        }
        if (poster.contains("googleusercontent.com") || poster.contains("bp.blogspot.com")) {
            poster = poster.replace(Regex("""=w\d+(-h\d+)?(-p-k-no-nu)?"""), "=s0")
            poster = poster.replace(Regex("""=s\d+(-c)?"""), "=s0")
            poster = poster.replace(Regex("""/[whs]\d+(-[a-zA-Z0-9_-]+)?/"""), "/s0/")
        }
        if (poster.contains("image.tmdb.org/t/p/") && !poster.contains("/t/p/original/")) {
            poster = poster.replace(Regex("""/t/p/w\d+[^/]*/"""), "/t/p/original/")
        }
        return poster
    }

    private fun resolveBestPoster(obj: JSONObject): String {
        val candidates = mutableListOf<String>()

        val poster = obj.optString("poster")
        val thumb = obj.optString("thumbnail")
        val banner = obj.optString("banner")

        // Ưu tiên poster dọc chuẩn cho Cloudstream trước, sau đó đến medias TMDB, cuối cùng đến thumbnail
        if (isUsableImageUrl(poster)) candidates.add(poster)
        if (isUsableImageUrl(thumb)) candidates.add(thumb)

        val medias = obj.optJSONArray("medias")
        if (medias != null) {
            for (i in 0 until medias.length()) {
                val m = medias.optJSONObject(i) ?: continue
                val mType = m.optString("type")
                val mUrl = m.optString("url")
                if (mType == "poster" && isUsableImageUrl(mUrl)) {
                    candidates.add(mUrl)
                    break
                }
            }
            for (i in 0 until medias.length()) {
                val m = medias.optJSONObject(i) ?: continue
                val mUrl = m.optString("url")
                if (isUsableImageUrl(mUrl)) {
                    candidates.add(mUrl)
                    break
                }
            }
        }

        if (isUsableImageUrl(banner)) candidates.add(banner)

        val best = candidates.firstOrNull() ?: poster.ifEmpty { thumb }
        return enhancePoster(best)
    }

    private fun extractLatinOriginName(rawOrigin: String): String {
        val cleaned = cleanTitle(rawOrigin)
            .replace(Regex("""[^\x20-\x7E]"""), " ")
            .replace(Regex("""\s+"""), " ")
            .trim(' ', '-', '|', ':', '/', ',')
        return if (cleaned.length >= 2) cleaned else ""
    }

    private fun movieJsonToSearchResponse(obj: JSONObject, includeEnglishTitle: Boolean = false): SearchResponse? {
        val slug = obj.optString("slug").trim()
        val rawName = obj.optString("name").trim()
        if (slug.isEmpty() || rawName.isEmpty()) return null

        val viTitle = cleanTitle(rawName)
        val enTitle = if (includeEnglishTitle) extractLatinOriginName(obj.optString("origin_name", "")) else ""
        val title = if (enTitle.isNotEmpty() && !viTitle.contains(enTitle, ignoreCase = true)) {
            "$viTitle ($enTitle)"
        } else {
            viTitle
        }

        val movieUrl = "$mainUrl/phim/$slug"
        val posterUrl = resolveBestPoster(obj)
        val type = obj.optString("type").lowercase()

        val tvType = if (type == "movie") TvType.Movie else TvType.AsianDrama
        return newTvSeriesSearchResponse(title, movieUrl, tvType) {
            this.posterUrl = posterUrl
        }
    }

    /**
     * Trích xuất toàn bộ object JSON "item":{...} từ payload Next.js RSC một cách chính xác (cân bằng dấu ngoặc nhọn)
     */
    private fun extractRscMovieItems(rscText: String): List<JSONObject> {
        val results = mutableListOf<JSONObject>()
        val marker = "\"item\":{"
        var searchFrom = 0

        while (true) {
            val idx = rscText.indexOf(marker, searchFrom)
            if (idx == -1) break

            val jsonStart = idx + "\"item\":".length
            var depth = 0
            var inString = false
            var escaped = false
            var jsonEnd = -1

            for (i in jsonStart until rscText.length) {
                val ch = rscText[i]
                if (escaped) {
                    escaped = false
                    continue
                }
                if (ch == '\\') {
                    escaped = true
                    continue
                }
                if (ch == '"') {
                    inString = !inString
                    continue
                }
                if (!inString) {
                    if (ch == '{') depth++
                    else if (ch == '}') {
                        depth--
                        if (depth == 0) {
                            jsonEnd = i + 1
                            break
                        }
                    }
                }
            }

            if (jsonEnd > jsonStart) {
                val jsonStr = rscText.substring(jsonStart, jsonEnd)
                try {
                    val obj = JSONObject(jsonStr)
                    if (obj.has("slug") && obj.has("name")) {
                        results.add(obj)
                    }
                } catch (_: Throwable) {}
                searchFrom = jsonEnd
            } else {
                searchFrom = jsonStart + 1
            }
        }
        return results
    }

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        // Chỉ hiển thị đúng 1 catalog "Phim Mới Cập Nhật" sắp xếp theo updatedAt (tập mới lên đầu)
        val (items, totalPages) = fetchCategoryPage(request.data, page)
        return newHomePageResponse(
            listOf(
                HomePageList(
                    name = request.name,
                    list = items,
                    isHorizontalImages = false
                )
            ),
            hasNext = page < totalPages && items.isNotEmpty()
        )
    }

    private suspend fun fetchCategoryPage(path: String, page: Int): Pair<List<SearchResponse>, Int> {
        val separator = if (path.contains("?")) "&" else "?"
        val targetUrl = "$mainUrl/$path${separator}page=$page"
        val items = mutableListOf<SearchResponse>()
        var totalPages = 1

        try {
            val rscText = app.get(
                targetUrl,
                headers = mapOf(
                    "User-Agent" to userAgent,
                    "Referer" to "$mainUrl/",
                    "RSC" to "1"
                )
            ).text

            val tpMatch = Regex(""""totalPages"\s*:\s*(\d+)""").find(rscText)
            if (tpMatch != null) {
                totalPages = tpMatch.groupValues[1].toIntOrNull() ?: 1
            }

            val movieObjs = extractRscMovieItems(rscText)
            for (obj in movieObjs) {
                movieJsonToSearchResponse(obj)?.let { items.add(it) }
            }
        } catch (_: Throwable) {}

        // Fallback: Parse HTML nếu RSC không trả về item JSON
        if (items.isEmpty()) {
            try {
                val doc = app.get(
                    targetUrl,
                    headers = mapOf("User-Agent" to userAgent, "Referer" to "$mainUrl/")
                ).document
                val html = doc.html().replace("\\\"", "\"")
                val tpMatch = Regex(""""totalPages"\s*:\s*(\d+)""").find(html)
                if (tpMatch != null) {
                    totalPages = tpMatch.groupValues[1].toIntOrNull() ?: 1
                }
                val movieObjs = extractRscMovieItems(html)
                for (obj in movieObjs) {
                    movieJsonToSearchResponse(obj)?.let { items.add(it) }
                }

                if (items.isEmpty()) {
                    doc.select(".sw-item").forEach { el ->
                        toHtmlSearchResult(el)?.let { items.add(it) }
                    }
                }
            } catch (_: Throwable) {}
        }

        return items.distinctBy { it.url } to totalPages
    }

    private fun toHtmlSearchResult(element: Element): SearchResponse? {
        val link = element.selectFirst("a[href*='/phim/']") ?: return null
        val href = fixUrl(link.attr("href"))
        val title = cleanTitle(
            element.selectFirst("h4.item-title a, .item-title a")?.text()
                ?: link.attr("title")
        )
        if (title.isEmpty()) return null
        val img = element.selectFirst("img")?.attr("src")?.ifEmpty {
            element.selectFirst("img")?.attr("data-src") ?: ""
        } ?: ""
        return newTvSeriesSearchResponse(title, href, TvType.AsianDrama) {
            this.posterUrl = enhancePoster(img)
        }
    }

    private suspend fun fetchSearchApi(keyword: String): List<SearchResponse> {
        val k = keyword.trim()
        if (k.isEmpty()) return emptyList()

        val encoded = URLEncoder.encode(k, "UTF-8")
        val searchApiUrl = "$apiUrl/api/v1/movies/search?keyword=$encoded"
        val results = mutableListOf<SearchResponse>()

        try {
            val resText = app.get(
                searchApiUrl,
                headers = mapOf(
                    "User-Agent" to userAgent,
                    "Referer" to "$mainUrl/"
                )
            ).text
            val json = JSONObject(resText)
            val arr = json.optJSONArray("result")
            if (arr != null) {
                for (i in 0 until arr.length()) {
                    arr.optJSONObject(i)?.let { movieJsonToSearchResponse(it, includeEnglishTitle = true) }?.let { results.add(it) }
                }
            }
        } catch (_: Throwable) {}

        return results.distinctBy { it.url }
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> {
        return fetchSearchApi(query)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val raw = query.trim().replace(Regex("""\s+"""), " ")
        if (raw.isEmpty()) return emptyList()

        // 1. Tìm trực tiếp bằng nguyên văn từ khóa (API hỗ trợ cả Tiếng Việt có/không dấu & Tiếng Anh origin_name)
        val direct = fetchSearchApi(raw)
        if (direct.isNotEmpty()) return direct

        // 2. Fallback thông minh cho các tên Tiếng Anh/Tiếng Việt có ký tự phụ, năm, hoặc "The Series"
        val candidates = linkedSetOf<String>()

        val strippedNoise = raw
            .replace(Regex("""\(\s*\d{4}\s*\)"""), " ")
            .replace(
                Regex(
                    """\b(?:the\s+series|series|season\s*\d+|ss\s*\d+|part\s*\d+|phần\s*\d+|mùa\s*\d+|vietsub|thuyết\s*minh|lồng\s*tiếng|full|fhd|hd|1080p)\b""",
                    RegexOption.IGNORE_CASE
                ),
                " "
            )
            .replace(Regex("""[:\-–|/!?.,]+"""), " ")
            .replace(Regex("""\s+"""), " ")
            .trim()
        if (strippedNoise.length >= 2 && !strippedNoise.equals(raw, ignoreCase = true)) {
            candidates.add(strippedNoise)
        }

        // Ghép số + chữ nếu viết tách (VD: "4 Minutes" -> "4Minutes")
        val compactNumWord = raw.replace(Regex("""(\d)\s+([a-zA-Z])"""), "$1$2")
        if (compactNumWord != raw) {
            candidates.add(compactNumWord)
        }

        val words = strippedNoise.ifEmpty { raw }.split(" ").filter { it.length >= 2 }
        if (words.size >= 3) {
            candidates.add(words.take(2).joinToString(" "))
        }

        for (candidate in candidates) {
            val found = fetchSearchApi(candidate)
            if (found.isNotEmpty()) return found
        }

        return emptyList()
    }

    override suspend fun load(url: String): LoadResponse? {
        val cleanUrl = url.split("?")[0].trimEnd('/')
        val rawSlug = cleanUrl.substringAfterLast("/")
        val slug = if (cleanUrl.contains("/xem-phim/")) rawSlug.substringBefore(".") else rawSlug
        if (slug.isEmpty()) return null

        val movieApiUrl = "$apiUrl/api/v1/movies/by-slug/$slug"
        val movieRes = app.get(
            movieApiUrl,
            headers = mapOf("User-Agent" to userAgent, "Referer" to "$mainUrl/")
        ).text

        val root = JSONObject(movieRes)
        val movie = root.optJSONObject("movie") ?: return null
        val movieId = movie.optInt("id", 0)
        val title = cleanTitle(movie.optString("name", slug))
        val originName = cleanTitle(movie.optString("origin_name", ""))
        val fullTitle = if (originName.isNotEmpty() && !title.contains(originName, ignoreCase = true)) {
            title
        } else {
            title
        }

        val poster = resolveBestPoster(movie)
        val rawDesc = movie.optString("description", "")
        val description = Jsoup.parse(rawDesc).text().trim().ifEmpty { title }
        val year = movie.optInt("publish_year", 0).takeIf { it > 1900 }
        val movieType = movie.optString("type", "series").lowercase()

        val tags = mutableListOf<String>()
        val cats = movie.optJSONArray("categories")
        if (cats != null) {
            for (i in 0 until cats.length()) {
                val cName = cats.optJSONObject(i)?.optString("name")?.trim()
                if (!cName.isNullOrEmpty()) tags.add(cName)
            }
        }

        // Lấy TOÀN BỘ danh sách tập và server của phim qua /api/v1/episodes/by-idMovie/{id}
        val episodes = mutableListOf<Episode>()
        if (movieId > 0) {
            try {
                val epResText = app.get(
                    "$apiUrl/api/v1/episodes/by-idMovie/$movieId",
                    headers = mapOf("User-Agent" to userAgent, "Referer" to "$mainUrl/")
                ).text

                val epArray = JSONArray(epResText)
                val serverTagNames = setOf("SS", "DL", "OK", "VK", "HX", "GD", "FULL", "RM", "OD")

                // Nhóm các tập theo (season_number, epKey) để gom tất cả server của cùng 1 tập lại
                data class EpServerEntry(val id: Int, val provider: String, val label: String, val priority: Int)
                val grouped = linkedMapOf<String, MutableList<EpServerEntry>>()
                val groupOrder = mutableListOf<Pair<Int, String>>() // (season, epKey)

                for (i in 0 until epArray.length()) {
                    val epObj = epArray.optJSONObject(i) ?: continue
                    val epId = epObj.optInt("id", 0)
                    if (epId <= 0) continue

                    val epNameRaw = epObj.optString("name", "01").trim()
                    val epSlugRaw = epObj.optString("slug", "").trim().lowercase()
                    val season = epObj.optInt("season_number", 1).coerceAtLeast(1)
                    val provider = epObj.optString("server_provider", "other").lowercase().trim()
                    val serverLabel = epObj.optString("server_label", epObj.optString("server", "Server")).trim()
                    val priority = epObj.optInt("server_priority", 99)

                    // Chuẩn hóa key tập: nếu tên là mã server (DL, OK, HX, Full...) hoặc movie type=movie với tên server -> gom vào Full (epKey = "FULL")
                    val upperName = epNameRaw.uppercase()
                    val isServerTag = upperName in serverTagNames || epSlugRaw in listOf("full", "tap-dl", "tap-ok", "tap-hx", "tap-vk", "tap-ss")
                    val normalizedKey = when {
                        isServerTag -> "FULL"
                        else -> {
                            val numMatch = Regex("""^0*(\d+)""").find(epNameRaw)
                            if (numMatch != null) {
                                val suffix = epNameRaw.substring(numMatch.range.last + 1).trim()
                                "${numMatch.groupValues[1]}$suffix"
                            } else {
                                epNameRaw
                            }
                        }
                    }

                    val mapKey = "${season}::${normalizedKey}"
                    if (!grouped.containsKey(mapKey)) {
                        grouped[mapKey] = mutableListOf()
                        groupOrder.add(season to normalizedKey)
                    }
                    grouped[mapKey]?.add(EpServerEntry(epId, provider, serverLabel, priority))
                }

                // Sắp xếp tập theo season tăng dần, số tập tăng dần
                val sortedKeys = groupOrder.sortedWith(
                    compareBy<Pair<Int, String>> { it.first }
                        .thenBy { Regex("""\d+""").find(it.second)?.value?.toIntOrNull() ?: 9999 }
                        .thenBy { it.second }
                )

                for ((season, epKey) in sortedKeys) {
                    val mapKey = "${season}::${epKey}"
                    val serversList = grouped[mapKey] ?: continue
                    val epNum = Regex("""\d+""").find(epKey)?.value?.toIntOrNull() ?: 1

                    val displayEpName = if (epKey == "FULL") {
                        "Full Movie (1080p FHD)"
                    } else {
                        val padded = if (epNum < 10 && epKey == epNum.toString()) "0$epNum" else epKey
                        "Tập $padded"
                    }

                    val serversJsonArr = JSONArray()
                    serversList.sortedBy { it.priority }.forEach { srv ->
                        val sObj = JSONObject()
                        sObj.put("id", srv.id)
                        sObj.put("provider", srv.provider)
                        sObj.put("label", srv.label)
                        serversJsonArr.put(sObj)
                    }

                    val payload = JSONObject().apply {
                        put("slug", slug)
                        put("movie_id", movieId)
                        put("ep_label", epKey)
                        put("servers", serversJsonArr)
                    }.toString()

                    episodes.add(
                        newEpisode(payload) {
                            this.name = displayEpName
                            this.season = season
                            this.episode = epNum
                        }
                    )
                }
            } catch (_: Throwable) {}
        }

        val tvType = if (episodes.size <= 1 && movieType == "movie") TvType.Movie else TvType.AsianDrama
        val finalUrl = "$mainUrl/phim/$slug"

        return newTvSeriesLoadResponse(fullTitle, finalUrl, tvType, episodes) {
            this.posterUrl = poster
            this.plot = description
            this.year = year
            this.tags = tags.distinct()
        }
    }

    private suspend fun resolveEmbedLinkFromWatchPage(slug: String, episodeId: Int): Pair<String, String>? {
        val watchUrl = "$mainUrl/xem-phim/$slug.$episodeId"
        try {
            // Ưu tiên gọi với header RSC: 1 cho payload gọn nhẹ và nhanh
            val rscText = app.get(
                watchUrl,
                headers = mapOf(
                    "User-Agent" to userAgent,
                    "Referer" to "$mainUrl/phim/$slug",
                    "RSC" to "1"
                )
            ).text

            val epBlock = Regex(""""episode"\s*:\s*(\{[^}]+\})""").find(rscText)?.groupValues?.get(1)
            if (!epBlock.isNullOrEmpty()) {
                val epJson = JSONObject(epBlock)
                val type = epJson.optString("type", "embed").lowercase()
                val link = epJson.optString("link", "").replace("\\u0026", "&").replace("&amp;", "&").trim()
                if (type == "m3u8") {
                    return "m3u8" to "$apiUrl/embed/$episodeId"
                }
                if (link.isNotEmpty()) {
                    return type to link
                }
            }
        } catch (_: Throwable) {}

        // Fallback: Parse trực tiếp iframe#embed-player từ HTML đầy đủ
        try {
            val html = app.get(
                watchUrl,
                headers = mapOf("User-Agent" to userAgent, "Referer" to "$mainUrl/phim/$slug")
            ).text
            val decoded = html.replace("\\\"", "\"")
            val epBlock = Regex(""""episode"\s*:\s*(\{[^}]+\})""").find(decoded)?.groupValues?.get(1)
            if (!epBlock.isNullOrEmpty()) {
                val epJson = JSONObject(epBlock)
                val type = epJson.optString("type", "embed").lowercase()
                val link = epJson.optString("link", "").replace("\\u0026", "&").replace("&amp;", "&").trim()
                if (type == "m3u8") return "m3u8" to "$apiUrl/embed/$episodeId"
                if (link.isNotEmpty()) return type to link
            }
            val iframeSrc = Jsoup.parse(html).selectFirst("iframe#embed-player")?.attr("src")?.replace("&amp;", "&")?.trim()
            if (!iframeSrc.isNullOrEmpty()) {
                return "embed" to iframeSrc
            }
        } catch (_: Throwable) {}

        return null
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        var linkFound = false
        var currentServerFound = false

        // BỘ LỌC NGHIÊM NGẶT: CHỈ GIỮ LINK >= 1080p FHD - LOẠI BỎ TOÀN BỘ CHẤT LƯỢNG THẤP (< 1080p)
        val fhdCallback: (ExtractorLink) -> Unit = { link ->
            val q = link.quality
            val isBelow1080 = q in listOf(
                Qualities.P144.value,
                Qualities.P240.value,
                Qualities.P360.value,
                Qualities.P480.value,
                Qualities.P720.value
            ) || Regex("""\b(?:720|480|380|360|240|144)p\b""", RegexOption.IGNORE_CASE).containsMatchIn(link.name)

            if (!isBelow1080) {
                linkFound = true
                currentServerFound = true
                callback.invoke(link)
            }
        }

        val payload = try {
            JSONObject(data)
        } catch (_: Throwable) {
            null
        } ?: return false

        val slug = payload.optString("slug")
        val serversArr = payload.optJSONArray("servers") ?: return false

        data class ServerTarget(val id: Int, val provider: String, val label: String, val order: Int)
        val targets = mutableListOf<ServerTarget>()

        for (i in 0 until serversArr.length()) {
            val s = serversArr.optJSONObject(i) ?: continue
            val epId = s.optInt("id", 0)
            val provider = s.optString("provider", "other").lowercase()
            val label = s.optString("label", "Server")
            if (epId <= 0) continue

            // Thứ tự ưu tiên: SSPlay -> OK.ru -> VK -> Dailymotion -> Odysee -> Rumble -> Abyss -> Khác
            val order = when {
                provider.contains("ssplay") -> 0
                provider.contains("ok") -> 1
                provider.contains("vk") -> 2
                provider.contains("dailymotion") -> 3
                provider.contains("odysee") -> 4
                provider.contains("rumble") -> 5
                provider.contains("abyss") || provider.contains("other") -> 6
                else -> 7
            }
            targets.add(ServerTarget(epId, provider, label, order))
        }

        // LẤY TUẦN TỰ TỪNG SERVER (Sequential Fallback):
        // Chỉ gửi 1 request /xem-phim/<slug>.<epId> cho Server #1 trước;
        // nếu lấy được link >= 1080p FHD hợp lệ thì DỪNG NGAY (không gửi request tới các server còn lại).
        // Chỉ khi Server hiện tại lỗi/die hoặc < 1080p mới tiếp tục thử sang Server kế tiếp.
        for (target in targets.sortedBy { it.order }) {
            currentServerFound = false
            try {
                val resolved = resolveEmbedLinkFromWatchPage(slug, target.id) ?: continue
                val (linkType, rawEmbedUrl) = resolved
                val embedUrl = rawEmbedUrl.replace("\\u0026", "&").replace("&amp;", "&").trim()
                val watchRef = "$mainUrl/xem-phim/$slug.${target.id}"
                val lowerUrl = embedUrl.lowercase()

                when {
                    linkType == "m3u8" || lowerUrl.contains(".m3u8") -> {
                        if (lowerUrl.contains(".m3u8")) {
                            val m3u8Valid = try {
                                val m3u8Text = app.get(
                                    embedUrl,
                                    headers = mapOf("Referer" to watchRef, "User-Agent" to userAgent)
                                ).text
                                if (m3u8Text.contains("#EXT-X-STREAM-INF")) {
                                    Regex("""RESOLUTION=\d+x(\d+)""").findAll(m3u8Text).any {
                                        (it.groupValues[1].toIntOrNull() ?: 0) >= 1080
                                    }
                                } else {
                                    m3u8Text.contains("#EXTM3U")
                                }
                            } catch (_: Throwable) {
                                false
                            }
                            if (m3u8Valid) {
                                fhdCallback.invoke(
                                    newExtractorLink(
                                        source = name,
                                        name = "${target.label} - 1080p FHD (HLS)",
                                        url = embedUrl,
                                        type = ExtractorLinkType.M3U8
                                    ) {
                                        this.quality = Qualities.P1080.value
                                        this.referer = watchRef
                                        this.headers = mapOf("Referer" to watchRef, "User-Agent" to userAgent)
                                    }
                                )
                            }
                        } else {
                            loadExtractor(embedUrl, watchRef, subtitleCallback, fhdCallback)
                        }
                    }
                    lowerUrl.contains("ssplay.net") -> {
                        SSPlayExtractor().getUrl(embedUrl, watchRef, subtitleCallback, fhdCallback)
                    }
                    lowerUrl.contains("dailymotion.com") || lowerUrl.contains("dai.ly") -> {
                        DailymotionExtractor().getUrl(embedUrl, watchRef, subtitleCallback, fhdCallback)
                    }
                    lowerUrl.contains("ok.ru") -> {
                        OkRuExtractor().getUrl(embedUrl, watchRef, subtitleCallback, fhdCallback)
                    }
                    lowerUrl.contains("vk.com") || lowerUrl.contains("vkvideo.ru") -> {
                        VkExtractor().getUrl(embedUrl, watchRef, subtitleCallback, fhdCallback)
                    }
                    lowerUrl.contains("odysee.com") -> {
                        OdyseeExtractor().getUrl(embedUrl, watchRef, subtitleCallback, fhdCallback)
                    }
                    lowerUrl.contains("rumble.com") -> {
                        RumbleExtractor().getUrl(embedUrl, watchRef, subtitleCallback, fhdCallback)
                    }
                    lowerUrl.contains("abyssplayer.com") || lowerUrl.contains("abyss") || lowerUrl.contains("short.ink") || lowerUrl.contains("short.icu") -> {
                        AbyssExtractor().getUrl(embedUrl, watchRef, subtitleCallback, fhdCallback)
                    }
                    else -> {
                        loadExtractor(embedUrl, watchRef, subtitleCallback, fhdCallback)
                    }
                }
            } catch (_: Throwable) {}

            if (currentServerFound) {
                break
            }
        }

        return linkFound
    }
}
