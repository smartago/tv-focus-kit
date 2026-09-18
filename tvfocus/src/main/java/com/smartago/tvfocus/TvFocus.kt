package com.smartago.tvfocus

/**
 * Kit-wide switches. Nothing here is required: the defaults log through `android.util.Log`
 * and keep the gap detector off.
 */
object TvFocus {

    /** Where the kit's diagnostics go. Replace to route them into an in-app log buffer. */
    interface Logger {
        fun i(tag: String, message: String)
        fun w(tag: String, message: String)
    }

    @Volatile
    var log: Logger = object : Logger {
        override fun i(tag: String, message: String) { android.util.Log.i("TvFocus:$tag", message) }
        override fun w(tag: String, message: String) { android.util.Log.w("TvFocus:$tag", message) }
    }
}
