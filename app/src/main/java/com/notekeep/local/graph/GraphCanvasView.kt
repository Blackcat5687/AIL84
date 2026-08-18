package com.notekeep.local.graph

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.os.Handler
import android.os.Looper
import android.util.AttributeSet
import android.view.Choreographer
import android.view.MotionEvent
import android.view.View
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.random.Random

/**
 * Native replacement for the old WebView + assets/graph/graph.html. Owns rendering, the physics
 * loop, and all touch interaction (drag/pan/pinch-zoom/tap). Kotlin (GraphActivity) still owns
 * loading real note data and persisting state durably, exactly like the old Kotlin/JS split -
 * only the JS side moved into this class, in the same language as the rest of the app.
 */
class GraphCanvasView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    interface Callbacks {
        fun onNoteTapped(noteId: Long)
        /** Fired (debounced) whenever anything worth persisting durably changed: settings,
         * filter, group rules, node positions, or the view's pan/zoom. */
        fun onStateChanged(state: GraphState)
    }

    var callbacks: Callbacks? = null

    // ------------------------------------------------------------------
    // Graph data + settings/filter/groups state
    // ------------------------------------------------------------------
    private var nodes: List<GraphNode> = emptyList()
    private var nodesById: Map<String, GraphNode> = emptyMap()
    private var edges: List<GraphEdge> = emptyList()
    private var maxDegree = 0
    private var componentCount = 0

    private var settings = GraphSettings()
    private var filterState = GraphFilterState()
    private var groupRules: List<GraphGroupRule> = GraphState().groupRules

    private val physics = GraphPhysics()
    private var hasReceivedFirstData = false
    private var pendingResetView = false

    // ------------------------------------------------------------------
    // View transform (pan/zoom)
    // ------------------------------------------------------------------
    private var viewScale = 1f
    private var viewOffsetX = 0f
    private var viewOffsetY = 0f

    // ------------------------------------------------------------------
    // Public: data + saved-state loading (called by GraphActivity)
    // ------------------------------------------------------------------

    /** Applies previously-saved settings/filter/groups/view. Call BEFORE loadData on first
     * setup, same ordering as the old bridge (settings sent before data). */
    fun applyState(state: GraphState, hasSavedView: Boolean) {
        settings = state.settings
        filterState = state.filterState
        groupRules = state.groupRules
        if (hasSavedView) {
            viewScale = state.view.scale
            viewOffsetX = state.view.offsetX
            viewOffsetY = state.view.offsetY
            hasReceivedFirstData = true
        }
        recomputeDerivedState()
        invalidate()
    }

    /** Builds/updates the graph from real note data. Preserves position/velocity of nodes that
     * already existed on screen (by id); uses savedPositions for nodes seen for the first time
     * this session but known from a previous one; scatters brand-new nodes randomly. Mirrors
     * buildGraphFromData exactly. */
    fun loadData(payload: GraphPayload, savedPositions: Map<String, Pair<Float, Float>>) {
        val previousById = nodesById
        val newNodes = ArrayList<GraphNode>(payload.nodes.size)
        val newNodesById = HashMap<String, GraphNode>()

        for (seed in payload.nodes) {
            val prev = previousById[seed.id]
            val saved = savedPositions[seed.id]
            val x: Float
            val y: Float
            when {
                prev != null -> { x = prev.x; y = prev.y }
                saved != null -> { x = saved.first; y = saved.second }
                else -> {
                    val angle = Random.nextFloat() * (2f * PI.toFloat())
                    val radius = 60f + Random.nextFloat() * 260f
                    x = cos(angle) * radius + (Random.nextFloat() - 0.5f) * 40f
                    y = sin(angle) * radius + (Random.nextFloat() - 0.5f) * 40f
                }
            }
            val node = GraphNode(
                seed.id, seed.title, seed.tags, seed.path, seed.content, seed.noteId, seed.isGhost, seed.nodeKind, x, y
            )
            if (prev != null) { node.vx = prev.vx; node.vy = prev.vy }
            newNodes.add(node)
            newNodesById[seed.id] = node
        }

        val newEdges = ArrayList<GraphEdge>()
        for (e in payload.edges) {
            val a = newNodesById[e.sourceId]
            val b = newNodesById[e.targetId]
            if (a != null && b != null) {
                a.degree++; b.degree++
                newEdges.add(e)
            }
        }
        var newMaxDegree = 0
        for (n in newNodes) {
            if (n.degree > newMaxDegree) newMaxDegree = n.degree
            n.isOrphan = n.degree == 0
        }

        nodes = newNodes
        nodesById = newNodesById
        edges = newEdges
        maxDegree = newMaxDegree

        componentCount = physics.computeComponents(nodes, edges)
        recomputeDerivedState()
        physics.reheat(1f)

        if (!hasReceivedFirstData) {
            hasReceivedFirstData = true
            if (savedPositions.isEmpty()) resetView()
        }
        invalidate()
    }

    // ------------------------------------------------------------------
    // Public: setters used by the settings panel
    // ------------------------------------------------------------------

    fun currentSettings(): GraphSettings = settings
    fun currentFilterState(): GraphFilterState = filterState
    fun currentGroupRules(): List<GraphGroupRule> = groupRules

    /** [isPhysics] mirrors bindSlider's isPhysics flag: only forces-section sliders reheat the
     * simulation (linkDistance/linkStrength/repulsionDistance/centerForce/degreeInfluence);
     * display-section sliders (nodeSize/linkWidth/labelFadeLimit/maxClusterDistance) and the
     * arrows toggle only trigger a redraw + persistence, exactly as in the original panel. */
    fun setSettings(newSettings: GraphSettings, isPhysics: Boolean) {
        settings = newSettings
        if (isPhysics) physics.reheat(0.5f)
        scheduleStateSync()
        invalidate()
    }

    fun setFilterState(newState: GraphFilterState) {
        filterState = newState
        GraphQueryEngine.recomputeFilter(nodes, filterState)
        physics.reheat(0.6f)
        scheduleStateSync()
        invalidate()
    }

    fun setGroupRules(newRules: List<GraphGroupRule>) {
        groupRules = newRules
        GraphQueryEngine.recomputeGroupColors(nodes, groupRules)
        scheduleStateSync()
        invalidate()
    }

    private fun recomputeDerivedState() {
        GraphQueryEngine.recomputeFilter(nodes, filterState)
        GraphQueryEngine.recomputeGroupColors(nodes, groupRules)
    }

    // ------------------------------------------------------------------
    // Physics loop (Choreographer replaces requestAnimationFrame)
    // ------------------------------------------------------------------
    private val frameCallback = Choreographer.FrameCallback { tick() }
    private var wasSettling = true
    private val stateSyncHandler = Handler(Looper.getMainLooper())
    private val stateSyncRunnable = Runnable { callbacks?.onStateChanged(buildCurrentState()) }

    private fun tick() {
        if (physics.alpha > GraphPhysics.ALPHA_MIN || physics.anyDragging(nodes)) {
            physics.applyForces(nodes, edges, nodesById, maxDegree, componentCount, settings)
            physics.integrate(nodes)
        }
        invalidate()
        checkSettleAndSync()
        if (isAttachedToWindow) Choreographer.getInstance().postFrameCallback(frameCallback)
    }

    /** Mirrors checkSettleAndSync(): syncs state to Kotlin storage exactly once, right when the
     * simulation transitions from moving to settled - not every frame while it's already still. */
    private fun checkSettleAndSync() {
        val settling = physics.alpha <= GraphPhysics.ALPHA_MIN && !physics.anyDragging(nodes)
        if (settling && !wasSettling) scheduleStateSync()
        wasSettling = settling
    }

    private fun scheduleStateSync() {
        stateSyncHandler.removeCallbacks(stateSyncRunnable)
        stateSyncHandler.postDelayed(stateSyncRunnable, 400)
    }

    private fun buildCurrentState(): GraphState {
        val positions = HashMap<String, Pair<Float, Float>>()
        for (n in nodes) positions[n.id] = n.x to n.y
        return GraphState(settings, filterState, groupRules, positions, GraphViewState(viewScale, viewOffsetX, viewOffsetY))
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        wasSettling = true
        Choreographer.getInstance().postFrameCallback(frameCallback)
    }

    override fun onDetachedFromWindow() {
        Choreographer.getInstance().removeFrameCallback(frameCallback)
        stateSyncHandler.removeCallbacks(stateSyncRunnable)
        super.onDetachedFromWindow()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (pendingResetView && w > 0 && h > 0) {
            pendingResetView = false
            resetView()
        }
    }

    // ------------------------------------------------------------------
    // Coordinate transforms
    // ------------------------------------------------------------------
    private fun screenToWorldX(sx: Float) = (sx - viewOffsetX) / viewScale
    private fun screenToWorldY(sy: Float) = (sy - viewOffsetY) / viewScale

    private fun hitTestNode(worldX: Float, worldY: Float): GraphNode? {
        val padding = max(settings.nodeSize + 6f, 16f)
        for (i in nodes.indices.reversed()) {
            val n = nodes[i]
            if (!n.visible) continue
            val dx = n.x - worldX
            val dy = n.y - worldY
            if (hypot(dx.toDouble(), dy.toDouble()).toFloat() <= padding) return n
        }
        return null
    }

    private fun zoomAt(sx: Float, sy: Float, factor: Float) {
        val newScale = GraphPhysics.clamp(viewScale * factor, GraphPhysics.MIN_SCALE, GraphPhysics.MAX_SCALE)
        val wx = screenToWorldX(sx)
        val wy = screenToWorldY(sy)
        viewOffsetX = sx - wx * newScale
        viewOffsetY = sy - wy * newScale
        viewScale = newScale
    }

    /** Fits all visible nodes on screen, same padding/logic as resetView() in the HTML. */
    fun resetView() {
        if (width == 0 || height == 0) { pendingResetView = true; return }
        var minX = Float.POSITIVE_INFINITY; var maxX = Float.NEGATIVE_INFINITY
        var minY = Float.POSITIVE_INFINITY; var maxY = Float.NEGATIVE_INFINITY
        var any = false
        for (n in nodes) {
            if (!n.visible) continue
            any = true
            if (n.x < minX) minX = n.x
            if (n.x > maxX) maxX = n.x
            if (n.y < minY) minY = n.y
            if (n.y > maxY) maxY = n.y
        }
        if (!any) { minX = -100f; maxX = 100f; minY = -100f; maxY = 100f }
        val graphW = max(maxX - minX, 50f)
        val graphH = max(maxY - minY, 50f)
        val padding = 90f
        val scaleX = (width - padding * 2f) / graphW
        val scaleY = (height - padding * 2f) / graphH
        val newScale = GraphPhysics.clamp(min(scaleX, scaleY), GraphPhysics.MIN_SCALE, GraphPhysics.MAX_SCALE)
        val cx = (minX + maxX) / 2f
        val cy = (minY + maxY) / 2f

        viewScale = newScale
        viewOffsetX = width / 2f - cx * newScale
        viewOffsetY = height / 2f - cy * newScale
        invalidate()
    }

    // ------------------------------------------------------------------
    // Touch handling: tap vs drag vs pan vs pinch-zoom
    // ------------------------------------------------------------------
    private val tapMoveThresholdPx = 18f * resources.displayMetrics.density

    private var pointerDownNode: GraphNode? = null
    private var pointerDownX = 0f
    private var pointerDownY = 0f
    private var pointerMoved = false

    private var draggingNode: GraphNode? = null
    private var isPanning = false
    private var panStartX = 0f
    private var panStartY = 0f
    private var panStartOffsetX = 0f
    private var panStartOffsetY = 0f

    private var pinchStartDist: Float? = null
    private var pinchStartScale: Float? = null

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> handleSingleDown(event.x, event.y)

            MotionEvent.ACTION_POINTER_DOWN -> if (event.pointerCount == 2) {
                draggingNode?.dragging = false
                draggingNode = null
                isPanning = false
                pointerDownNode = null
                pinchStartDist = touchDist(event)
                pinchStartScale = viewScale
            }

            MotionEvent.ACTION_MOVE -> {
                if (event.pointerCount >= 2 && pinchStartDist != null) {
                    val newDist = touchDist(event)
                    val midX = (event.getX(0) + event.getX(1)) / 2f
                    val midY = (event.getY(0) + event.getY(1)) / 2f
                    val targetScale = GraphPhysics.clamp(
                        pinchStartScale!! * (newDist / pinchStartDist!!),
                        GraphPhysics.MIN_SCALE, GraphPhysics.MAX_SCALE
                    )
                    zoomAt(midX, midY, targetScale / viewScale)
                    invalidate()
                } else if (event.pointerCount == 1) {
                    handleSingleMove(event.x, event.y)
                }
            }

            MotionEvent.ACTION_POINTER_UP -> {
                if (event.pointerCount - 1 < 2) { pinchStartDist = null; pinchStartScale = null }
            }

            MotionEvent.ACTION_UP -> {
                handleRelease()
                pinchStartDist = null
                pinchStartScale = null
            }

            MotionEvent.ACTION_CANCEL -> {
                draggingNode?.dragging = false
                draggingNode = null
                pointerDownNode = null
                pointerMoved = false
                isPanning = false
                pinchStartDist = null
                pinchStartScale = null
            }
        }
        return true
    }

    private fun touchDist(event: MotionEvent): Float =
        hypot((event.getX(1) - event.getX(0)).toDouble(), (event.getY(1) - event.getY(0)).toDouble()).toFloat()

    private fun handleSingleDown(sx: Float, sy: Float) {
        val hit = hitTestNode(screenToWorldX(sx), screenToWorldY(sy))
        pointerDownNode = hit
        pointerDownX = sx; pointerDownY = sy
        pointerMoved = false

        if (hit != null) {
            draggingNode = hit
            hit.dragging = true
            physics.reheat(0.5f)
        } else {
            isPanning = true
            panStartX = sx; panStartY = sy
            panStartOffsetX = viewOffsetX; panStartOffsetY = viewOffsetY
        }
    }

    private fun handleSingleMove(sx: Float, sy: Float) {
        if (!pointerMoved && hypot((sx - pointerDownX).toDouble(), (sy - pointerDownY).toDouble()) > tapMoveThresholdPx) {
            pointerMoved = true
        }
        val dragging = draggingNode
        if (dragging != null) {
            dragging.x = screenToWorldX(sx)
            dragging.y = screenToWorldY(sy)
        } else if (isPanning) {
            viewOffsetX = panStartOffsetX + (sx - panStartX)
            viewOffsetY = panStartOffsetY + (sy - panStartY)
            invalidate()
        }
    }

    private fun handleRelease() {
        val dragging = draggingNode
        if (dragging != null) {
            dragging.dragging = false
            draggingNode = null
            physics.reheat(0.3f)
            scheduleStateSync()
        }
        val downNode = pointerDownNode
        if (downNode != null && !pointerMoved) handleNodeActivated(downNode)
        pointerDownNode = null
        isPanning = false
    }

    private fun handleNodeActivated(node: GraphNode) {
        if (node.isGhost) return
        val id = node.noteId ?: return
        callbacks?.onNoteTapped(id)
    }

    // ------------------------------------------------------------------
    // Rendering
    // ------------------------------------------------------------------
    private val bgPaint = Paint().apply { color = Color.parseColor("#0A0E14") }
    private val edgePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#4B5563")
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val arrowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#4B5563")
        style = Paint.Style.FILL
    }
    private val nodeFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val nodeStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(89, 0, 0, 0) // rgba(0,0,0,0.35)
        style = Paint.Style.STROKE
        strokeWidth = 1f
    }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#CBD5E1")
        textSize = 11f
        textAlign = Paint.Align.CENTER
    }
    private val nodeLetterPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textAlign = Paint.Align.CENTER
        isFakeBoldText = true
    }
    private val defaultNodeColor = Color.parseColor("#93A5C9")
    private val nodeOval = RectF()
    private val arrowPath = Path()

    override fun onDraw(canvas: Canvas) {
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), bgPaint)

        canvas.save()
        canvas.translate(viewOffsetX, viewOffsetY)
        canvas.scale(viewScale, viewScale)

        edgePaint.strokeWidth = settings.linkWidth
        for (e in edges) {
            val a = nodesById[e.sourceId] ?: continue
            val b = nodesById[e.targetId] ?: continue
            if (!a.visible || !b.visible) continue
            canvas.drawLine(a.x, a.y, b.x, b.y, edgePaint)
        }

        if (settings.showArrows) {
            for (e in edges) {
                val a = nodesById[e.sourceId] ?: continue
                val b = nodesById[e.targetId] ?: continue
                if (!a.visible || !b.visible) continue
                when (e.direction) {
                    "none" -> {}
                    "backward" -> drawArrowHead(canvas, b.x, b.y, a.x, a.y, settings.nodeSize)
                    else -> drawArrowHead(canvas, a.x, a.y, b.x, b.y, settings.nodeSize)
                }
            }
        }

        val fadeLimit = settings.labelFadeLimit
        val fadeStart = fadeLimit * 1.8f
        val labelAlpha = when {
            viewScale >= fadeStart -> 1f
            viewScale <= fadeLimit -> 0f
            else -> (viewScale - fadeLimit) / (fadeStart - fadeLimit)
        }

        for (n in nodes) {
            if (!n.visible) continue
            // A node's own kind - not how many connections it has - decides whether it's a tag/
            // category hub. A heavily-linked real note must stay the normal note color, and a
            // tag or category with only one note must still get its hub color; connection count
            // never factors into this.
            val kindColor = when (n.nodeKind) {
                "tag" -> settings.tagColor
                "label" -> settings.categoryColor
                else -> defaultNodeColor
            }
            val colors = n.colors

            if (colors.size > 1) {
                val slice = 360f / colors.size
                var startAngle = -90f
                nodeOval.set(n.x - settings.nodeSize, n.y - settings.nodeSize, n.x + settings.nodeSize, n.y + settings.nodeSize)
                for (c in colors) {
                    nodeFillPaint.color = c
                    canvas.drawArc(nodeOval, startAngle, slice, true, nodeFillPaint)
                    startAngle += slice
                }
                canvas.drawCircle(n.x, n.y, settings.nodeSize, nodeStrokePaint)
            } else {
                nodeFillPaint.color = colors.firstOrNull() ?: kindColor
                canvas.drawCircle(n.x, n.y, settings.nodeSize, nodeFillPaint)
                canvas.drawCircle(n.x, n.y, settings.nodeSize, nodeStrokePaint)
            }

            // Type letter, centered in the node: T for a tag hub, C for a category (label) hub.
            // Real notes and unresolved-link ghost nodes get no letter.
            val letter = when (n.nodeKind) {
                "tag" -> "T"
                "label" -> "C"
                else -> null
            }
            if (letter != null) {
                nodeLetterPaint.textSize = settings.nodeSize * 1.05f
                val ty = n.y - (nodeLetterPaint.ascent() + nodeLetterPaint.descent()) / 2f
                canvas.drawText(letter, n.x, ty, nodeLetterPaint)
            }

            if (labelAlpha > 0.01f) {
                labelPaint.alpha = (labelAlpha * 255).toInt()
                val textY = n.y + settings.nodeSize + 4f - labelPaint.ascent()
                canvas.drawText(n.title, n.x, textY, labelPaint)
            }
        }

        canvas.restore()
    }

    private fun drawArrowHead(canvas: Canvas, fromX: Float, fromY: Float, toX: Float, toY: Float, nodeRadius: Float) {
        val angle = atan2((toY - fromY).toDouble(), (toX - fromX).toDouble())
        val tipX = (toX - cos(angle) * (nodeRadius + 2f)).toFloat()
        val tipY = (toY - sin(angle) * (nodeRadius + 2f)).toFloat()
        val size = 6.5
        val a1 = angle + PI * 0.82
        val a2 = angle - PI * 0.82
        arrowPath.reset()
        arrowPath.moveTo(tipX, tipY)
        arrowPath.lineTo(tipX + (cos(a1) * size).toFloat(), tipY + (sin(a1) * size).toFloat())
        arrowPath.lineTo(tipX + (cos(a2) * size).toFloat(), tipY + (sin(a2) * size).toFloat())
        arrowPath.close()
        canvas.drawPath(arrowPath, arrowPaint)
    }
}
