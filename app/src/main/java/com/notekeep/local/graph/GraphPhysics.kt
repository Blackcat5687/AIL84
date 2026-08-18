package com.notekeep.local.graph

import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * Force-directed physics simulation, ported 1:1 from assets/graph/graph.html (applyForces,
 * integrate, computeComponents, applyClusterDistanceLimit). Every constant below has the exact
 * same value as its JS counterpart so the graph settles into the same layout with the same feel.
 */
class GraphPhysics {

    companion object {
        const val HUB_DEGREE_THRESHOLD = 5

        private const val REPULSION_K = 0.5f
        private const val REPULSION_NEAR_K = 1.6f
        private const val MAX_PAIR_FORCE = 34f
        private const val MAX_SPRING_FORCE = 30f
        private const val CENTER_K = 0.02f
        private const val DEGREE_SHRINK_CAP = 0.5f
        private const val VELOCITY_DECAY = 0.88f
        private const val MAX_VELOCITY = 30f
        const val ALPHA_MIN = 0.001f
        val ALPHA_DECAY = (1.0 - Math.pow(ALPHA_MIN.toDouble(), 1.0 / 400.0)).toFloat() // ~400 ticks to settle
        const val MIN_SCALE = 0.12f
        const val MAX_SCALE = 6f
        private const val CLUSTER_PULL_K = 0.012f

        fun clamp(v: Float, lo: Float, hi: Float): Float = min(hi, max(lo, v))
    }

    var alpha: Float = 1f
        private set

    /** Boosts alpha back up (e.g. after a drag or new data), same as reheat(a) in the HTML. */
    fun reheat(a: Float) {
        alpha = max(alpha, a)
    }

    fun settle() {
        alpha = ALPHA_MIN
    }

    /** Connected-components id per node (islands not reachable from one another via any edge),
     * used only to keep separate clusters from drifting arbitrarily far apart. Recomputed once
     * per data load, same as computeComponents() being called once in buildGraphFromData. */
    fun computeComponents(nodes: List<GraphNode>, edges: List<GraphEdge>): Int {
        val adjacency = HashMap<String, MutableList<String>>()
        for (n in nodes) adjacency[n.id] = ArrayList()
        for (e in edges) {
            adjacency[e.sourceId]?.add(e.targetId)
            adjacency[e.targetId]?.add(e.sourceId)
        }
        val nodesById = nodes.associateBy { it.id }
        val visited = HashSet<String>()
        var compId = 0
        for (n in nodes) {
            if (n.id in visited) continue
            val stack = ArrayDeque<String>()
            stack.addLast(n.id)
            visited.add(n.id)
            val members = ArrayList<String>()
            while (stack.isNotEmpty()) {
                val cur = stack.removeLast()
                members.add(cur)
                for (nb in adjacency[cur].orEmpty()) {
                    if (nb !in visited) {
                        visited.add(nb)
                        stack.addLast(nb)
                    }
                }
            }
            for (id in members) nodesById[id]?.component = compId
            compId++
        }
        return compId
    }

    fun applyForces(
        nodes: List<GraphNode>,
        edges: List<GraphEdge>,
        nodesById: Map<String, GraphNode>,
        maxDegree: Int,
        componentCount: Int,
        settings: GraphSettings
    ) {
        for (n in nodes) { n.fx = 0f; n.fy = 0f }

        // 1) Long-range repulsion between every visible pair, plus a near-range push that
        // prevents visual overlap (not a boolean gate - it scales smoothly with distance).
        val r = settings.repulsionDistance
        val nodeR = settings.nodeSize
        val minGap = nodeR * 2f + 6f
        val visible = nodes.filter { it.visible }

        for (i in visible.indices) {
            val a = visible[i]
            for (j in i + 1 until visible.size) {
                val b = visible[j]
                var dx = b.x - a.x
                var dy = b.y - a.y
                var dist = sqrt(dx * dx + dy * dy)
                if (dist < 0.0001f) {
                    dx = (Random.nextFloat() - 0.5f) * 0.02f
                    dy = (Random.nextFloat() - 0.5f) * 0.02f
                    dist = 0.01f
                }
                val minSep = max(2f, dist)

                var forceMag = (r * r * REPULSION_K) / (minSep * minSep)
                if (dist < minGap) forceMag += (minGap - dist) * REPULSION_NEAR_K
                forceMag = min(forceMag, MAX_PAIR_FORCE)

                val fx = (dx / dist) * forceMag * alpha
                val fy = (dy / dist) * forceMag * alpha
                a.fx -= fx; a.fy -= fy
                b.fx += fx; b.fy += fy
            }
        }

        // 2) Link spring force + degree-influence shrink (local to each edge only).
        for (e in edges) {
            val a = nodesById[e.sourceId] ?: continue
            val b = nodesById[e.targetId] ?: continue
            if (!a.visible || !b.visible) continue
            var dx = b.x - a.x
            var dy = b.y - a.y
            var dist = sqrt(dx * dx + dy * dy)
            if (dist < 0.0001f) { dist = 0.01f; dx = 0.01f; dy = 0f }

            val normA = if (maxDegree > 0) a.degree.toFloat() / maxDegree else 0f
            val normB = if (maxDegree > 0) b.degree.toFloat() / maxDegree else 0f
            val avgNorm = (normA + normB) / 2f

            val shrink = settings.degreeInfluence * avgNorm * DEGREE_SHRINK_CAP
            val targetDist = settings.linkDistance * (1f - shrink)

            val diff = dist - targetDist
            var forceMag = diff * settings.linkStrength * alpha
            forceMag = clamp(forceMag, -MAX_SPRING_FORCE, MAX_SPRING_FORCE)

            val fx = (dx / dist) * forceMag
            val fy = (dy / dist) * forceMag
            a.fx += fx; a.fy += fy
            b.fx -= fx; b.fy -= fy
        }

        // 3) Weak center force - keeps the network gathered without collapsing to a point.
        var cx = 0f; var cy = 0f; var visCount = 0
        for (n in visible) { cx += n.x; cy += n.y; visCount++ }
        if (visCount > 0) { cx /= visCount; cy /= visCount }
        for (n in visible) {
            n.fx += -(n.x - cx) * settings.centerForce * CENTER_K * alpha
            n.fy += -(n.y - cy) * settings.centerForce * CENTER_K * alpha
        }

        // 4) Max distance between disconnected islands only (never within one connected component).
        if (componentCount > 1) {
            applyClusterDistanceLimit(nodes, settings.maxClusterDistance)
        }
    }

    private fun applyClusterDistanceLimit(nodes: List<GraphNode>, maxDist: Float) {
        data class Center(var x: Float = 0f, var y: Float = 0f, var count: Int = 0)
        val centers = HashMap<Int, Center>()
        for (n in nodes) {
            if (!n.visible) continue
            val c = centers.getOrPut(n.component) { Center() }
            c.x += n.x; c.y += n.y; c.count++
        }
        for (c in centers.values) { c.x /= c.count; c.y /= c.count }
        val ids = centers.keys.toList()

        for (i in ids.indices) {
            var nearestDist = Float.POSITIVE_INFINITY
            var nearestId: Int? = null
            val ci = centers[ids[i]]!!
            for (j in ids.indices) {
                if (i == j) continue
                val cj = centers[ids[j]]!!
                val d = hypot((cj.x - ci.x).toDouble(), (cj.y - ci.y).toDouble()).toFloat()
                if (d < nearestDist) { nearestDist = d; nearestId = ids[j] }
            }
            if (nearestId == null || nearestDist <= maxDist) continue

            val cj = centers[nearestId]!!
            val dx = cj.x - ci.x
            val dy = cj.y - ci.y
            val dist = max(nearestDist, 0.001f)
            val excess = dist - maxDist
            val pullX = (dx / dist) * excess * CLUSTER_PULL_K * alpha
            val pullY = (dy / dist) * excess * CLUSTER_PULL_K * alpha

            for (n in nodes) {
                if (!n.visible || n.component != ids[i]) continue
                n.fx += pullX
                n.fy += pullY
            }
        }
    }

    fun integrate(nodes: List<GraphNode>) {
        for (n in nodes) {
            if (n.dragging || !n.visible) { n.vx = 0f; n.vy = 0f; continue }
            n.vx = (n.vx + n.fx) * VELOCITY_DECAY
            n.vy = (n.vy + n.fy) * VELOCITY_DECAY

            val speed = hypot(n.vx.toDouble(), n.vy.toDouble()).toFloat()
            if (speed > MAX_VELOCITY) {
                n.vx = (n.vx / speed) * MAX_VELOCITY
                n.vy = (n.vy / speed) * MAX_VELOCITY
            }
            n.x += n.vx
            n.y += n.vy
        }
        alpha += (0f - alpha) * ALPHA_DECAY
        if (alpha < ALPHA_MIN) alpha = ALPHA_MIN
    }

    fun anyDragging(nodes: List<GraphNode>): Boolean = nodes.any { it.dragging }
}
