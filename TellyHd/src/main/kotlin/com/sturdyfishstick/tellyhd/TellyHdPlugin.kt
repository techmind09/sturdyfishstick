package com.sturdyfishstick.tellyhd

import android.content.Context
import com.lagradost.cloudstream3.extractors.VidHidePro3
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class TellyHdPlugin: Plugin() {
    override fun load(context: Context) {
        registerMainAPI(TellyHd())
        registerExtractorAPI(Ds2play())
        registerExtractorAPI(Vidsp())
        registerExtractorAPI(VidHidePro3())
        registerExtractorAPI(VidHideplus())
        registerExtractorAPI(VidHidedht())
        registerExtractorAPI(Vidhidehub())
        registerExtractorAPI(Bigwarp())
        registerExtractorAPI(Vidhidetoul())
        registerExtractorAPI(Luluvid())
        registerExtractorAPI(Xtapes())
    }
}
