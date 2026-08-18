package com.notekeep.local.graph

import com.notekeep.local.data.Label
import com.notekeep.local.data.Note

/**
 * Builds the graph payload (nodes/edges) fed into GraphCanvasView.loadData from real app data.
 * This is the single source of truth for turning notes into graph nodes/edges - the canvas view
 * only renders and simulates what it's given here, same "Kotlin owns data, rendering owns
 * display" split as before, just with both halves now in Kotlin instead of Kotlin+JS.
 *
 * Node id = the note's real database id (as a string), never its title - titles can change or
 * collide, but the id is stable.
 *
 * A note's #tags and its assigned Labels (categories/groups) each also get their own synthetic
 * hub node (id `tag_<name>` / `label_<id>`), with an edge from every note that carries that tag
 * or label to that hub node - this is what makes notes sharing a tag or category visually
 * cluster together in the graph. Tags are still also listed in each note's own `tags` field so
 * the `tag:`/`path:` filter and group-color query language keeps working exactly as designed.
 */
object GraphDataBuilder {

    fun buildPayload(
        notes: List<Note>,
        labels: List<Label>,
        noteLabelPairs: List<Pair<Long, Long>>
    ): GraphPayload {
        val labelById = labels.associateBy { it.id }
        val labelIdsByNote = noteLabelPairs.groupBy({ it.first }, { it.second })

        // index real notes by resolvable title so [[wiki-links]] can find their target
        // (case-insensitive, trimmed).
        val noteIdByTitle = HashMap<String, Long>()
        for (note in notes) {
            val key = note.title.trim().lowercase()
            if (key.isNotEmpty()) noteIdByTitle[key] = note.id
        }

        val nodes = ArrayList<GraphNodeSeed>()
        val edges = ArrayList<GraphEdge>()
        val seenEdgeKeys = HashSet<String>()
        val ghostIds = HashMap<String, String>() // lowercase title -> synthetic ghost id
        // tag hub id -> display name, only for tags actually used by at least one note
        val tagHubNames = LinkedHashMap<String, String>()

        fun addEdgeOnce(sourceId: String, targetId: String, direction: String) {
            val key = if (sourceId < targetId) "$sourceId|$targetId" else "$targetId|$sourceId"
            if (!seenEdgeKeys.add(key)) return
            edges.add(GraphEdge(sourceId, targetId, direction))
        }

        for (note in notes) {
            val tags = note.extractTags().map { it.removePrefix("#") }.filter { it.isNotBlank() }
            val noteLabelIds = labelIdsByNote[note.id].orEmpty()
            val noteLabelNames = noteLabelIds.mapNotNull { labelById[it]?.name }
            val allTags = tags + noteLabelNames

            nodes.add(
                GraphNodeSeed(
                    id = note.id.toString(),
                    title = note.title.ifBlank { note.content.take(18).ifBlank { "بدون عنوان" } },
                    tags = allTags,
                    // All of a note's label names joined (not just the first) so a path:/section:
                    // rule's exact match still finds any one of several categories a note belongs
                    // to - a note in both "فلسفة" and "كتب" needs section:كتب to actually match.
                    path = noteLabelNames.joinToString("، "),
                    // Full note body, used only by line: (search inside the note's text, not its
                    // title) - kept separate from title/path so each query type stays strict.
                    content = note.content,
                    noteId = note.id,
                    isGhost = false,
                    nodeKind = "note"
                )
            )

            // Connect this note to a hub node per tag, creating the tag id lazily the first time
            // it's seen so hub nodes only exist for tags actually in use. Directed note -> hub,
            // matching the requested "من الملاحظات نحو الوسم/التصنيف الذي هي مرتبطة معه".
            for (tagName in tags) {
                val hubId = "tag_" + tagName.trim().lowercase()
                tagHubNames.putIfAbsent(hubId, tagName)
                addEdgeOnce(note.id.toString(), hubId, "forward")
            }
            // Connect this note to a hub node per assigned label/category, same direction.
            for (labelId in noteLabelIds) {
                if (labelById[labelId] == null) continue
                addEdgeOnce(note.id.toString(), "label_$labelId", "forward")
            }
        }

        // Emit one hub node per tag actually used by at least one note.
        for ((hubId, tagName) in tagHubNames) {
            nodes.add(GraphNodeSeed(hubId, "#$tagName", emptyList(), "", "", null, isGhost = true, nodeKind = "tag"))
        }

        // Emit one hub node per label that has at least one note assigned to it.
        val usedLabelIds = noteLabelPairs.map { it.second }.toHashSet()
        for (label in labels) {
            if (label.id !in usedLabelIds) continue
            nodes.add(GraphNodeSeed("label_${label.id}", label.name, emptyList(), "", "", null, isGhost = true, nodeKind = "label"))
        }

        for (note in notes) {
            val thisId = note.id.toString()
            for (linkTitle in note.extractWikiLinks()) {
                val key = linkTitle.trim().lowercase()
                val targetId = noteIdByTitle[key]
                if (targetId != null) {
                    if (targetId == note.id) continue // ignore self-links
                    // Directed أم -> فرعية: the note containing [[Target]] is the "أم" (parent),
                    // the referenced note is the "فرعية" (child) - arrow points parent -> child.
                    addEdgeOnce(thisId, targetId.toString(), "forward")
                } else {
                    // Unresolved [[wiki-link]] becomes a ghost node so links pointing at
                    // not-yet-created notes stay visible.
                    val ghostId = ghostIds.getOrPut(key) { "ghost_$key" }
                    addEdgeOnce(thisId, ghostId, "forward")
                }
            }
        }

        for ((key, ghostId) in ghostIds) {
            nodes.add(GraphNodeSeed(ghostId, key, emptyList(), "", "", null, isGhost = true, nodeKind = "ghost"))
        }

        return GraphPayload(nodes, edges)
    }
}
