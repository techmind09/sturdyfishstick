@CloudstreamPlugin
class TellyHdPlugin: Plugin() {
    override fun load(context: Context) {
        registerMainAPI(TellyHdProvider()) // <-- TollyPro ki jagah TellyHdProvider() karein
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

