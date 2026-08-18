package com.notekeep.local.data

import android.content.Context

/**
 * Small app-wide settings store, separate from GraphSettingsStore (which is graph-view-specific
 * display/physics state). Currently holds a single flag: whether archived notes should still show
 * up in the graph view and the labels list, toggled from the top-left of the archive screen.
 */
object AppPrefs {
    private const val PREFS_NAME = "app_prefs"
    private const val KEY_SHOW_ARCHIVED_ELSEWHERE = "show_archived_elsewhere"

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** True by default: archived notes appear in the graph and in labels, same as before this
     * setting existed. Turning it off hides archived notes from both places (they still exist
     * and still show inside the archive screen itself). */
    fun showArchivedElsewhere(context: Context): Boolean =
        prefs(context).getBoolean(KEY_SHOW_ARCHIVED_ELSEWHERE, true)

    fun setShowArchivedElsewhere(context: Context, show: Boolean) {
        prefs(context).edit().putBoolean(KEY_SHOW_ARCHIVED_ELSEWHERE, show).apply()
    }
}
