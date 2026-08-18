package com.notekeep.local.graph

/**
 * Native Kotlin data model for the graph view. This replaces the old HTML/JS graph entirely -
 * everything here (fields, ranges, defaults) is a direct 1:1 port of the previous
 * assets/graph/graph.html, kept identical on purpose so the graph's behavior does not change,
 * only its implementation (native Canvas + Kotlin physics instead of a WebView).
 */

/** One node in the graph: a real note, or a synthetic hub/ghost node (tag, label, unresolved
 * [[wiki-link]]). Mutable var fields are simulation state that changes every physics tick. */
class GraphNode(
    val id: String,
    val title: String,
    val tags: List<String>,
    val path: String,
    val content: String,
    val noteId: Long?,
    val isGhost: Boolean,
    val nodeKind: String, // "note" | "tag" | "label" | "ghost"
    var x: Float,
    var y: Float
) {
    var vx: Float = 0f
    var vy: Float = 0f
    var fx: Float = 0f
    var fy: Float = 0f
    var degree: Int = 0
    var isOrphan: Boolean = false
    var dragging: Boolean = false
    var visible: Boolean = true
    var colors: List<Int> = emptyList()
    var component: Int = -1
}

/** direction: "none" | "forward" | "backward" - undirected today (all relations in this app are
 * symmetric), kept as a field for parity with the original schema and possible future use. */
data class GraphEdge(val sourceId: String, val targetId: String, val direction: String = "none")

/** Everything GraphDataBuilder produces for one load: nodes + edges, ready for the physics/view
 * layer. Positions of previously-known nodes are preserved by GraphCanvasView itself (matching
 * buildGraphFromData's behavior), not baked in here. */
data class GraphPayload(
    val nodes: List<GraphNodeSeed>,
    val edges: List<GraphEdge>
)

/** Immutable seed data for one node, before it's placed on the canvas (no x/y/velocity yet -
 * those are assigned by GraphCanvasView.loadData, same as buildGraphFromData did in JS). */
data class GraphNodeSeed(
    val id: String,
    val title: String,
    val tags: List<String>,
    val path: String,
    val content: String,
    val noteId: Long?,
    val isGhost: Boolean,
    val nodeKind: String
)

/** Display + force settings. Names, defaults, and ranges match assets/graph/graph.html's
 * `settings` object exactly. */
data class GraphSettings(
    val nodeSize: Float = 12f,
    val linkWidth: Float = 2f,
    val linkDistance: Float = 100f,
    val linkStrength: Float = 0.7f,
    val repulsionDistance: Float = 80f,
    val centerForce: Float = 0.4f,
    val degreeInfluence: Float = 0.6f,
    val showArrows: Boolean = false,
    val labelFadeLimit: Float = 0.55f,
    val maxClusterDistance: Float = 1500f,
    /** Fill color for tag hub nodes (#وسم), independent from category color. Green by default. */
    val tagColor: Int = DEFAULT_HUB_GREEN,
    /** Fill color for category/label hub nodes (التصنيف), independent from tag color. Green by default. */
    val categoryColor: Int = DEFAULT_HUB_GREEN
)

/** Shared default for both tag and category hub colors until the user customizes them. */
const val DEFAULT_HUB_GREEN = 0xFF4ADE80.toInt()

/** Independent from group-color rules, same as filterState in the HTML. */
data class GraphFilterState(
    val query: String = "",
    val showTags: Boolean = true,
    val showAttachments: Boolean = true,
    val onlyExisting: Boolean = false,
    val showOrphans: Boolean = true
)

/** One group-coloring rule: query string ("tag:x", "path:x", free text...) -> a fill color. */
data class GraphGroupRule(val id: String, val query: String, val color: Int)

data class GraphViewState(val scale: Float = 1f, val offsetX: Float = 0f, val offsetY: Float = 0f)

/** The full persisted state, mirroring what the JS side used to report to Kotlin on every change
 * (settings/filterState/groupRules/positions/view). Now Kotlin owns this end-to-end, so there is
 * no bridge/JSON-string round trip needed at runtime - GraphSettingsStore still serializes this
 * to JSON only for on-disk persistence and backup embedding. */
data class GraphState(
    val settings: GraphSettings = GraphSettings(),
    val filterState: GraphFilterState = GraphFilterState(),
    val groupRules: List<GraphGroupRule> = listOf(
        GraphGroupRule("g1", "tag:programming", 0xFF3B82F6.toInt()),
        GraphGroupRule("g2", "tag:important", 0xFFEF4444.toInt())
    ),
    val positions: Map<String, Pair<Float, Float>> = emptyMap(),
    val view: GraphViewState = GraphViewState()
)

/** Preset swatch colors offered in the group-color picker, matching PRESET_COLORS in the HTML. */
val GRAPH_PRESET_COLORS = intArrayOf(
    0xFFEF4444.toInt(), 0xFF3B82F6.toInt(), 0xFF22C55E.toInt(), 0xFFEAB308.toInt(),
    0xFFA855F7.toInt(), 0xFFEC4899.toInt(), 0xFF111318.toInt(), 0xFFF8FAFC.toInt()
)
