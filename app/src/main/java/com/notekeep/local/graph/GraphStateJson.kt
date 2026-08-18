package com.notekeep.local.graph

import android.graphics.Color
import org.json.JSONArray
import org.json.JSONObject

/**
 * JSON shape kept byte-compatible with the old graph.html's sendStateToAndroid()/applySettings()
 * (same key names for settings/filterState/groupRules/positions/view), so any state a user
 * already had saved from the previous WebView-based graph keeps loading correctly here - nothing
 * about their saved forces/display/filter/group setup or last pan/zoom is lost by this rewrite.
 */

private fun colorToHex(color: Int): String = String.format("#%06x", 0xFFFFFF and color)
private fun hexToColor(hex: String, fallback: Int): Int =
    try { Color.parseColor(hex) } catch (e: Exception) { fallback }

fun GraphState.toJson(): JSONObject = JSONObject().apply {
    put("settings", JSONObject().apply {
        put("nodeSize", settings.nodeSize)
        put("linkWidth", settings.linkWidth)
        put("linkDistance", settings.linkDistance)
        put("linkStrength", settings.linkStrength)
        put("repulsionDistance", settings.repulsionDistance)
        put("centerForce", settings.centerForce)
        put("degreeInfluence", settings.degreeInfluence)
        put("showArrows", settings.showArrows)
        put("labelFadeLimit", settings.labelFadeLimit)
        put("maxClusterDistance", settings.maxClusterDistance)
        put("tagColor", colorToHex(settings.tagColor))
        put("categoryColor", colorToHex(settings.categoryColor))
    })
    put("filterState", JSONObject().apply {
        put("query", filterState.query)
        put("showTags", filterState.showTags)
        put("showAttachments", filterState.showAttachments)
        put("onlyExisting", filterState.onlyExisting)
        put("showOrphans", filterState.showOrphans)
    })
    put("groupRules", JSONArray().apply {
        for (rule in groupRules) {
            put(JSONObject().apply {
                put("id", rule.id)
                put("query", rule.query)
                put("color", colorToHex(rule.color))
            })
        }
    })
    put("positions", JSONObject().apply {
        for ((id, pos) in positions) {
            put(id, JSONObject().apply { put("x", pos.first); put("y", pos.second) })
        }
    })
    put("view", JSONObject().apply {
        put("scale", view.scale)
        put("offsetX", view.offsetX)
        put("offsetY", view.offsetY)
    })
}

fun parseGraphState(json: JSONObject): GraphState {
    val defaults = GraphState()

    val settingsJson = json.optJSONObject("settings")
    val settings = if (settingsJson != null) GraphSettings(
        nodeSize = settingsJson.optDouble("nodeSize", defaults.settings.nodeSize.toDouble()).toFloat(),
        linkWidth = settingsJson.optDouble("linkWidth", defaults.settings.linkWidth.toDouble()).toFloat(),
        linkDistance = settingsJson.optDouble("linkDistance", defaults.settings.linkDistance.toDouble()).toFloat(),
        linkStrength = settingsJson.optDouble("linkStrength", defaults.settings.linkStrength.toDouble()).toFloat(),
        repulsionDistance = settingsJson.optDouble("repulsionDistance", defaults.settings.repulsionDistance.toDouble()).toFloat(),
        centerForce = settingsJson.optDouble("centerForce", defaults.settings.centerForce.toDouble()).toFloat(),
        degreeInfluence = settingsJson.optDouble("degreeInfluence", defaults.settings.degreeInfluence.toDouble()).toFloat(),
        showArrows = settingsJson.optBoolean("showArrows", defaults.settings.showArrows),
        labelFadeLimit = settingsJson.optDouble("labelFadeLimit", defaults.settings.labelFadeLimit.toDouble()).toFloat(),
        maxClusterDistance = settingsJson.optDouble("maxClusterDistance", defaults.settings.maxClusterDistance.toDouble()).toFloat(),
        tagColor = hexToColor(settingsJson.optString("tagColor", "#4ADE80"), defaults.settings.tagColor),
        categoryColor = hexToColor(settingsJson.optString("categoryColor", "#4ADE80"), defaults.settings.categoryColor)
    ) else defaults.settings

    val filterJson = json.optJSONObject("filterState")
    val filterState = if (filterJson != null) GraphFilterState(
        query = filterJson.optString("query", defaults.filterState.query),
        showTags = filterJson.optBoolean("showTags", defaults.filterState.showTags),
        showAttachments = filterJson.optBoolean("showAttachments", defaults.filterState.showAttachments),
        onlyExisting = filterJson.optBoolean("onlyExisting", defaults.filterState.onlyExisting),
        showOrphans = filterJson.optBoolean("showOrphans", defaults.filterState.showOrphans)
    ) else defaults.filterState

    val rulesJson = json.optJSONArray("groupRules")
    val groupRules = if (rulesJson != null) {
        val list = ArrayList<GraphGroupRule>()
        for (i in 0 until rulesJson.length()) {
            val r = rulesJson.optJSONObject(i) ?: continue
            val id = r.optString("id", "g${i + 1}")
            val query = r.optString("query", "")
            val color = hexToColor(r.optString("color", "#3B82F6"), GRAPH_PRESET_COLORS[i % GRAPH_PRESET_COLORS.size])
            list.add(GraphGroupRule(id, query, color))
        }
        list
    } else defaults.groupRules

    val positionsJson = json.optJSONObject("positions")
    val positions = HashMap<String, Pair<Float, Float>>()
    if (positionsJson != null) {
        val keys = positionsJson.keys()
        while (keys.hasNext()) {
            val id = keys.next()
            val p = positionsJson.optJSONObject(id) ?: continue
            positions[id] = p.optDouble("x", 0.0).toFloat() to p.optDouble("y", 0.0).toFloat()
        }
    }

    val viewJson = json.optJSONObject("view")
    val view = if (viewJson != null) GraphViewState(
        scale = viewJson.optDouble("scale", 1.0).toFloat().let { if (it <= 0f) 1f else it },
        offsetX = viewJson.optDouble("offsetX", 0.0).toFloat(),
        offsetY = viewJson.optDouble("offsetY", 0.0).toFloat()
    ) else defaults.view

    return GraphState(settings, filterState, groupRules, positions, view)
}
