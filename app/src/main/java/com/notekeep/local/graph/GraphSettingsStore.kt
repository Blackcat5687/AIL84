package com.notekeep.local.graph

import android.content.Context
import org.json.JSONObject

/**
 * Persists the graph's full state durably: display settings, force settings, filter state,
 * group color rules, node positions, and the last zoom/pan - now typed Kotlin end-to-end since
 * the graph itself is native. Kept byte-compatible with the old WebView-based graph's saved JSON
 * (same keys, see GraphStateJson.kt), so a state saved before this rewrite still loads correctly.
 */
object GraphSettingsStore {
    private const val PREFS_NAME = "graph_settings"
    private const val KEY_STATE_JSON = "graph_state_json"
    private const val KEY_SCHEMA_VERSION = "graph_schema_version"

    const val CURRENT_SCHEMA_VERSION = 2

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** The last saved state, or null if the graph has never been opened yet (or only ever saved
     * an older, incompatible schema) - callers should treat null as "use built-in defaults". */
    fun loadState(context: Context): GraphState? {
        val p = prefs(context)
        if (p.getInt(KEY_SCHEMA_VERSION, 0) != CURRENT_SCHEMA_VERSION) return null
        val raw = p.getString(KEY_STATE_JSON, null) ?: return null
        return try { parseGraphState(JSONObject(raw)) } catch (e: Exception) { null }
    }

    /** Saves the full state in one write, called (debounced) after every meaningful change from
     * GraphCanvasView, so nothing needs an explicit "save" step. */
    fun saveState(context: Context, state: GraphState) {
        prefs(context).edit()
            .putString(KEY_STATE_JSON, state.toJson().toString())
            .putInt(KEY_SCHEMA_VERSION, CURRENT_SCHEMA_VERSION)
            .apply()
    }

    /** Serializes the current state for embedding in a backup file (BackupManager). */
    fun toJson(context: Context): JSONObject {
        val state = loadState(context)
        return JSONObject().apply {
            put("schemaVersion", CURRENT_SCHEMA_VERSION)
            put("state", state?.toJson() ?: JSONObject.NULL)
        }
    }

    /** Restores state from a backup file's embedded JSON, if present and of a schema this build
     * understands. Older/foreign-schema backups leave whatever is already saved on this device
     * untouched - restore never errors out over graph data. */
    fun fromJson(context: Context, obj: JSONObject?) {
        if (obj == null) return
        val schemaVersion = obj.optInt("schemaVersion", -1)
        if (schemaVersion != CURRENT_SCHEMA_VERSION) return
        val stateJson = obj.optJSONObject("state") ?: return
        prefs(context).edit()
            .putString(KEY_STATE_JSON, stateJson.toString())
            .putInt(KEY_SCHEMA_VERSION, CURRENT_SCHEMA_VERSION)
            .apply()
    }
}
