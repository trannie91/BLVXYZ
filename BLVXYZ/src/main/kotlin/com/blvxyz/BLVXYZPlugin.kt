package com.blvxyz

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import android.content.Context

@CloudstreamPlugin
class BLVXYZPlugin : Plugin() {
    override fun load(context: Context) {
        // Đăng ký Provider chính cào toàn bộ danh mục, phim cũ -> mới & tập từ https://blvietsub.xyz/phimhay
        registerMainAPI(BLVXYZProvider())

        // Đăng ký đầy đủ các Extractor chuyên dụng lọc chuẩn >= 1080p FHD
        registerExtractorAPI(SSPlayExtractor())
        registerExtractorAPI(DailymotionExtractor())
        registerExtractorAPI(OkRuExtractor())
        registerExtractorAPI(VkExtractor())
        registerExtractorAPI(OdyseeExtractor())
        registerExtractorAPI(RumbleExtractor())
        registerExtractorAPI(AbyssExtractor())
    }
}
