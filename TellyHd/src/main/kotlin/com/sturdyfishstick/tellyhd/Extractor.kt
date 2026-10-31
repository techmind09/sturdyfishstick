package com.sturdyfishstick.tellyhd

import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.extractors.LuluStream
import com.lagradost.cloudstream3.extractors.VidhideExtractor
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.INFER_TYPE
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.getQualityFromName
import com.lagradost.cloudstream3.utils.newExtractorLink

// --- Lulustream Mirrors ---
class Lulustream : LuluStream() {
    override val name = "Lulustream"
    override val mainUrl = "https://lulustream.com"
}

class Luluvid : LuluStream() {
    override val name = "Lulustream"
    override val mainUrl = "https://luluvid.com"
}

class Luluvdo : LuluStream() {
    override val name = "Lulustream"
    override val mainUrl = "https://luluvdo.com"
}

class Lulust : LuluStream() {
    override val name = "Lulustream"
    override val mainUrl = "https://lulust.com"
}

class CdnTelly : LuluStream() {
    override val name = "Lulustream"
    override val mainUrl = "https://cdntelly.sbs"
}

// --- Ultra Stream / Vidhide Mirrors ---
class Ultrastream : VidhideExtractor() {
    override var mainUrl = "https://ultrastream.audio"
    override var name = "Ultra Stream"
}

class Bysezoxexe : VidhideExtractor() {
    override var mainUrl = "https://bysezoxexe.com"
    override var name = "Ultra Stream"
}

class VidaraTo : VidhideExtractor() {
    override var mainUrl = "https://vidara.to"
    override var name = "Ultra Stream"
}

class Vidhideplus : VidhideExtractor() {
    override var mainUrl = "https://vidhideplus.com"
    override var name = "Ultra Stream"
}

class Vidhidehub : VidhideExtractor() {
    override var mainUrl = "https://vidhidehub.com"
    override var name = "Ultra Stream"
}

class TellyXin : ExtractorApi() {
    override var mainUrl = "https://telly.xin"
    override var name = "Ultra Stream"
    override val requiresReferer = false

    override suspend fun getUrl(url: String, referer: String?): List<ExtractorLink>? {
        val doc = app.get(url, referer = referer).document
        
        val script = doc.select("script").html()
        val videoUrl = Regex("https?://[^\\s\"']+\\.(m3u8|mp4)").find(script)?.value 
            ?: doc.select("source").attr("src")
            ?: doc.select("iframe").attr("src")

        if (videoUrl.isEmpty()) return null

        val fixedUrl = if (videoUrl.startsWith("//")) "https:$videoUrl" else videoUrl

        return listOf(
            newExtractorLink(
                source = this.name,
                name = this.name,
                url = fixedUrl,
                INFER_TYPE
            ) {
                this.referer = mainUrl
                this.quality = Qualities.P1080.value
            }
        )
    }
}

// --- Doodstream Mirrors ---
open class Ds2play : ExtractorApi() {
    override var name = "DoodStream"
    override var mainUrl = "https://ds2play.com"
    override val requiresReferer = false

    override fun getExtractorUrl(id: String): String {
        return "https://dood.wf/d/$id"
    }

    override suspend fun getUrl(url: String, referer: String?): List<ExtractorLink>? {
        val response0 = app.get(url).text
        val md5Regex = Regex("/pass_md5/[^'\"<>]+").find(response0)?.value ?: return null
        val md5Url = if (md5Regex.startsWith("http")) md5Regex else mainUrl + md5Regex
        
        val md5Response = app.get(md5Url, referer = url).text
        val trueUrl = md5Response + "zUEJeL3mUN?token=" + md5Url.substringAfterLast("/")
        
        val quality = Regex("\\d{3,4}p").find(response0.substringAfter("<title>").substringBefore("</title>"))?.groupValues?.get(0)
        
        return listOf(
            newExtractorLink(
                source = this.name,
                name = this.name,
                url = trueUrl
            ) {
                this.referer = mainUrl
                this.quality = getQualityFromName(quality)
            }
        )
    }
}

class DoodCom : Ds2play() { override var mainUrl = "https://doodstream.com" }
class DoodTo : Ds2play() { override var mainUrl = "https://dood.to" }
class DoodWatch : Ds2play() { override var mainUrl = "https://dood.watch" }
class DoodLa : Ds2play() { override var mainUrl = "https://dood.la" }
class DoodPm : Ds2play() { override var mainUrl = "https://dood.pm" }
class DoodSh : Ds2play() { override var mainUrl = "https://dood.sh" }
class DoodWs : Ds2play() { override var mainUrl = "https://dood.ws" }
class DoodOne : Ds2play() { override var mainUrl = "https://dood.one" }
class DoodSo : Ds2play() { override var mainUrl = "https://dood.so" }
class DoodWork : Ds2play() { override var mainUrl = "https://dood.work" }

// --- Pixeldrain ---
open class Pixeldrain : ExtractorApi() {
    override val name = "Pixeldrain"
    override val mainUrl = "https://pixeldrain.com"
    override val requiresReferer = false

    override suspend fun getUrl(url: String, referer: String?): List<ExtractorLink>? {
        val fileId = url.substringAfterLast("/")
        val directUrl = "https://pixeldrain.com/api/file/$fileId"
        return listOf(
            newExtractorLink(
                source = name,
                name = name,
                url = directUrl,
                INFER_TYPE
            ) {
                this.referer = "$mainUrl/"
                this.quality = Qualities.P1080.value
            }
        )
    }
}
