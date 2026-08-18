package com.notekeep.local.graph

/**
 * Shared query engine used by both filtering and group-color rules. Each query type is matched
 * strictly against its own field, with no bleed between them:
 *  - a plain unprefixed word matches only a real note's own title, as a whole word
 *  - `tag:name` matches only a note's #hashtags (set from inside the note text with "#")
 *  - `section:name` matches only a note's assigned categories/labels (set from the note's
 *    three-dot menu → "Labels"), exactly - never tags, never the title
 *  - `line:word` matches only inside a note's body content, as a whole word
 *  - `path:` is kept as a synonym of `section:` for backward compatibility with any group-color
 *    rules a user already saved under the old scheme
 * This keeps "the word happens to appear somewhere" from being confused with "this note actually
 * carries this tag/category" - the whole point of the type prefix, and keeps tag and
 * section/category strictly separate as two different concepts.
 */
object GraphQueryEngine {

    class ParsedQuery(val type: String, val value: String)

    private val TYPED_QUERY_REGEX = Regex("^([a-zA-Z_]+):(.*)$")
    // Splits a title into whole words for exact (non-substring) matching. Treats Arabic/Latin
    // letters and digits as word characters and everything else (spaces, punctuation, #, [[ ]])
    // as a boundary.
    private val WORD_SPLIT_REGEX = Regex("[^\\p{L}\\p{N}_]+")

    fun parseQuery(raw: String?): ParsedQuery? {
        val q = raw?.trim().orEmpty()
        if (q.isEmpty()) return null
        val m = TYPED_QUERY_REGEX.find(q)
        return if (m != null) {
            ParsedQuery(m.groupValues[1].lowercase(), m.groupValues[2].trim().lowercase())
        } else {
            ParsedQuery("text", q.lowercase())
        }
    }

    /** True if [needle] appears in [text] as a standalone word (not attached to any other
     * letter/digit/underscore) - "as" matches "…this AS well…" but not "task" or "cast". A
     * multi-word [needle] (a typed phrase with a space) falls back to a plain substring check
     * against the whole title, since word-splitting a phrase against single title tokens
     * wouldn't mean anything. */
    private fun wholeWordMatch(text: String, needle: String): Boolean {
        if (needle.isBlank()) return false
        if (needle.contains(' ')) return text.lowercase().contains(needle)
        return text.lowercase().split(WORD_SPLIT_REGEX).any { it == needle }
    }

    fun matches(node: GraphNode, parsed: ParsedQuery?): Boolean {
        if (parsed == null) return true
        val v = parsed.value
        return when (parsed.type) {
            // section: (category/label, assigned via the note's three-dot menu → "Labels").
            // Category names are joined with "، " on the node (see GraphDataBuilder) - match if
            // any single category equals the query exactly, not merely contains it. "path" is
            // kept as a synonym so previously-saved rules using the old prefix keep working.
            "section", "path" -> node.path.isNotEmpty() && node.path.split("، ").any { it.trim().lowercase() == v }
            "file" -> false // this app has no file paths distinct from notes - kept for parity
            // tag: (#hashtag set from inside the note text). Exact match, not substring -
            // tag:as must not match a tag named "task".
            "tag" -> node.tags.any { it.trim().lowercase() == v }
            // line: searches inside the note's own body content, as a whole word - never the
            // title and never a tag/category hub node's name.
            "line" -> node.nodeKind == "note" && wholeWordMatch(node.content, v)
            // Plain word: only ever a real note's own title, as a whole word - never a tag/
            // category hub node's name, and never a substring hit inside a longer word.
            "text" -> node.nodeKind == "note" && wholeWordMatch(node.title, v)
            else -> false // custom [property]:value - no per-node properties in this app yet
        }
    }

    /** Recomputes node.visible for every node against the filter state. Mirrors recomputeFilter(). */
    fun recomputeFilter(nodes: List<GraphNode>, filterState: GraphFilterState) {
        val parsed = parseQuery(filterState.query)
        for (n in nodes) {
            // "الوسوم" toggle: hides only the synthetic tag/label hub nodes themselves - the real
            // notes that were connected to them stay visible, exactly like turning off a filter
            // chip should never hide the notes it was grouping, only the grouping node.
            if (!filterState.showTags && (n.nodeKind == "tag" || n.nodeKind == "label")) {
                n.visible = false
                continue
            }
            var visible = matches(n, parsed)
            if (visible && !filterState.showOrphans && n.isOrphan) visible = false
            // showAttachments / onlyExisting are kept for schema parity with the previous graph
            // but are no-ops here, exactly as they were in the HTML: this app never populates
            // hasAttachment/fileExists on any node, so those checks never actually filter anything.
            n.visible = visible
        }
    }

    /** Recomputes node.colors for every node against the group rules. Mirrors recomputeGroupColors(). */
    fun recomputeGroupColors(nodes: List<GraphNode>, groupRules: List<GraphGroupRule>) {
        for (n in nodes) {
            val matchedColors = ArrayList<Int>()
            for (rule in groupRules) {
                val parsed = parseQuery(rule.query)
                if (matches(n, parsed) && !matchedColors.contains(rule.color)) {
                    matchedColors.add(rule.color)
                }
            }
            n.colors = matchedColors
        }
    }

    fun recomputeDerivedState(nodes: List<GraphNode>, state: GraphState) {
        recomputeFilter(nodes, state.filterState)
        recomputeGroupColors(nodes, state.groupRules)
    }
}
