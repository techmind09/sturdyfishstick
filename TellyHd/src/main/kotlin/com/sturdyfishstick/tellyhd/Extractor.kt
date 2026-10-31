package com.sturdyfishstick.tellyhd

import com.lagradost.cloudstream3.extractors.VidHidePro
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.INFER_TYPE
import com.lagradost.cloudstream3.utils.getAndUnpack

class Ds2play : VidHidePro() {
    override var name = "Ds2play"
    override var mainUrl = "https://ds2play.com"
}

class Vidsp : VidHidePro() {
    override var name = "Vidsp"
    override var mainUrl = "https://vidsp.net"
}

class VidHideplus : VidHidePro() {
    override var name = "VidHidePlus"
    override var mainUrl = "https://vidhideplus.com"
}

class VidHidedht : VidHidePro() {
    override var name = "VidHideDht"
    override var mainUrl = "https://vidhidedht.com"
}

class Vidhidehub : VidHidePro() {
    override var name = "VidHideHub"
    override var mainUrl = "https://vidhidehub.com"
}

class Bigwarp : VidHidePro() {
    override var name = "Bigwarp"
    override var mainUrl = "https://bigwarp.3e5.co"
}

class Vidhidetoul : VidHidePro() {
    override var name = "VidHideToul"
    override var mainUrl = "https://vidhidetoul.com"
}

class Luluvid : VidHidePro() {
    override var name = "Luluvid"
    override var mainUrl = "https://luluvid.com"
}

class Xtapes : VidHidePro() {
    override var name = "Xtapes"
    override var mainUrl = "https://xtapes.to"
}
