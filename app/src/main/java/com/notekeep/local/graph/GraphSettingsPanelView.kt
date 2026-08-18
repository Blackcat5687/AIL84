package com.notekeep.local.graph

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.util.AttributeSet
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.widget.addTextChangedListener
import com.google.android.material.slider.Slider
import com.google.android.material.switchmaterial.SwitchMaterial
import com.notekeep.local.R
import kotlin.math.roundToInt

/**
 * Full native settings panel: filter / groups / forces / display, each an independently
 * collapsible accordion section - ported from graph.html's renderPanel()/renderAccordionHtml()
 * and everything under it. Structural changes (opening/closing a section, entering/leaving the
 * rule editor, adding/deleting a rule) rebuild this view's contents, same as the HTML's
 * innerHTML re-render; continuous controls (sliders/toggles/text) update the graph and their own
 * label in place without rebuilding everything, same as the HTML's per-control listeners.
 */
class GraphSettingsPanelView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : LinearLayout(context, attrs) {

    var graphView: GraphCanvasView? = null
    var onCloseRequested: (() -> Unit)? = null

    private var openFilter = false
    private var openGroups = false
    private var openDisplay = false
    private var openForces = false
    private var editingRuleId: String? = null
    private var ruleIdCounter = 3
    private var editorSelectedColor: Int = GRAPH_PRESET_COLORS[0]

    private val bodyContainer: LinearLayout

    private val colorText = Color.parseColor("#E2E8F0")
    private val colorMuted = Color.parseColor("#94A3B8")
    private val colorHint = Color.parseColor("#64748B")
    private val colorAccentLabel = Color.parseColor("#7FA0D6")
    private val colorGreen = Color.parseColor("#4ADE80")
    private val colorArrow = Color.parseColor("#6B7787")

    init {
        orientation = VERTICAL
        background = GradientDrawable().apply {
            setColor(Color.parseColor("#0F121A"))
            cornerRadius = dp(14).toFloat()
            setStroke(dp(1), Color.parseColor("#1AFFFFFF"))
        }

        val header = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(14), dp(16), dp(14))
        }
        val title = TextView(context).apply {
            text = context.getString(R.string.action_settings)
            setTextColor(colorText)
            textSize = 14f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            layoutParams = LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f)
        }
        val closeBtn = TextView(context).apply {
            text = "×"
            setTextColor(colorMuted)
            textSize = 16f
            gravity = Gravity.CENTER
            layoutParams = LayoutParams(dp(28), dp(28))
            background = GradientDrawable().apply { setColor(Color.parseColor("#0FFFFFFF")); cornerRadius = dp(8).toFloat() }
            setOnClickListener { onCloseRequested?.invoke() }
        }
        header.addView(title)
        header.addView(closeBtn)
        addView(header)

        val divider = View(context).apply {
            setBackgroundColor(Color.parseColor("#14FFFFFF"))
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, dp(1))
        }
        addView(divider)

        val scroll = ScrollView(context).apply {
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f)
            isFillViewport = false
        }
        bodyContainer = LinearLayout(context).apply {
            orientation = VERTICAL
            setPadding(dp(16), dp(12), dp(16), dp(16))
        }
        scroll.addView(bodyContainer)
        addView(scroll)
    }

    /** Call after [graphView] is assigned (and whenever a fresh saved state is loaded) to sync
     * the panel's displayed values and rebuild its content. */
    fun refresh() {
        val gv = graphView ?: return
        ruleIdCounter = (gv.currentGroupRules().mapNotNull { it.id.removePrefix("g").toIntOrNull() }.maxOrNull() ?: 2) + 1
        rebuildBody()
    }

    private fun rebuildBody() {
        bodyContainer.removeAllViews()
        addAccordionSection(context.getString(R.string.graph_filter), openFilter, { openFilter = !openFilter; rebuildBody() }) { buildFilterContent(it) }
        addAccordionSection(context.getString(R.string.graph_groups), openGroups, { openGroups = !openGroups; if (!openGroups) editingRuleId = null; rebuildBody() }) { buildGroupsContent(it) }
        addAccordionSection(context.getString(R.string.graph_display), openDisplay, { openDisplay = !openDisplay; rebuildBody() }) { buildDisplayContent(it) }
        addAccordionSection(context.getString(R.string.graph_forces), openForces, { openForces = !openForces; rebuildBody() }) { buildForcesContent(it) }
    }

    // ------------------------------------------------------------------
    // Accordion shell
    // ------------------------------------------------------------------
    private fun addAccordionSection(label: String, isOpen: Boolean, onToggle: () -> Unit, content: (LinearLayout) -> Unit) {
        val item = LinearLayout(context).apply {
            orientation = VERTICAL
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).also { it.bottomMargin = dp(6) }
        }
        val headerRow = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(13), dp(12), dp(13))
            isClickable = true
            isFocusable = true
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#08FFFFFF"))
                val r = dp(10).toFloat()
                cornerRadii = if (isOpen) floatArrayOf(r, r, r, r, 0f, 0f, 0f, 0f) else floatArrayOf(r, r, r, r, r, r, r, r)
                setStroke(dp(1), Color.parseColor("#0FFFFFFF"))
            }
            setOnClickListener { onToggle() }
        }
        val labelView = TextView(context).apply {
            text = label
            setTextColor(colorText)
            textSize = 13.5f
            layoutParams = LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f)
        }
        val arrow = TextView(context).apply {
            text = if (isOpen) "⌄" else "‹"
            setTextColor(colorArrow)
            textSize = 13f
        }
        headerRow.addView(labelView)
        headerRow.addView(arrow)
        item.addView(headerRow)

        if (isOpen) {
            val body = LinearLayout(context).apply {
                orientation = VERTICAL
                setPadding(dp(12), dp(14), dp(12), dp(14))
                background = GradientDrawable().apply {
                    setColor(Color.parseColor("#04FFFFFF"))
                    val r = dp(10).toFloat()
                    cornerRadii = floatArrayOf(0f, 0f, 0f, 0f, r, r, r, r)
                    setStroke(dp(1), Color.parseColor("#0FFFFFFF"))
                }
            }
            content(body)
            item.addView(body)
        }
        bodyContainer.addView(item)
    }

    // ------------------------------------------------------------------
    // قسم: تصفية
    // ------------------------------------------------------------------
    private fun buildFilterContent(body: LinearLayout) {
        val gv = graphView ?: return
        val state = gv.currentFilterState()

        body.addView(fieldLabel("استعلام التصفية"))
        val input = EditText(context).apply {
            setText(state.query)
            hint = "مثال: tag:برمجة أو section:مشاريع"
            setHintTextColor(Color.parseColor("#566178"))
            setTextColor(colorText)
            textSize = 13f
            background = fieldBackground()
            setPadding(dp(10), dp(9), dp(10), dp(9))
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).also { it.bottomMargin = dp(10) }
        }
        input.addTextChangedListener {
            graphView?.setFilterState(graphView!!.currentFilterState().copy(query = it?.toString().orEmpty()))
        }
        body.addView(input)
        body.addView(hintText("كلمة مباشرة تبحث في العنوان · tag: للوسوم · section: للتصنيف · line: للبحث داخل النص"))
        body.addView(sectionLabel("إظهار العناصر"))

        addToggleRow(body, "الوسوم", state.showTags) { checked ->
            graphView?.setFilterState(graphView!!.currentFilterState().copy(showTags = checked))
        }
        addToggleRow(body, "المرفقات", state.showAttachments) { checked ->
            graphView?.setFilterState(graphView!!.currentFilterState().copy(showAttachments = checked))
        }
        addToggleRow(body, "الملفات الموجودة فقط", state.onlyExisting) { checked ->
            graphView?.setFilterState(graphView!!.currentFilterState().copy(onlyExisting = checked))
        }
        addToggleRow(body, "الأيتام", state.showOrphans) { checked ->
            graphView?.setFilterState(graphView!!.currentFilterState().copy(showOrphans = checked))
        }
    }

    // ------------------------------------------------------------------
    // قسم: المجموعات
    // ------------------------------------------------------------------
    private fun buildGroupsContent(body: LinearLayout) {
        val gv = graphView ?: return
        val editingId = editingRuleId
        if (editingId != null) {
            buildRuleEditor(body, editingId)
            return
        }

        val rules = gv.currentGroupRules()
        if (rules.isEmpty()) {
            body.addView(hintText("لا توجد قواعد بعد. أضف قاعدة لتلوين العقد المطابقة تلقائيًا."))
        } else {
            for (rule in rules) body.addView(buildRuleCard(rule))
        }

        val addBtn = primaryButton("+ إضافة قاعدة جديدة")
        addBtn.setOnClickListener {
            editingRuleId = "g" + (ruleIdCounter++)
            editorSelectedColor = GRAPH_PRESET_COLORS[0]
            rebuildBody()
        }
        body.addView(addBtn)
    }

    private fun buildRuleCard(rule: GraphGroupRule): View {
        val card = LinearLayout(context).apply {
            orientation = VERTICAL
            setPadding(dp(10), dp(10), dp(10), dp(10))
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#05FFFFFF"))
                cornerRadius = dp(10).toFloat()
                setStroke(dp(1), Color.parseColor("#14FFFFFF"))
            }
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).also { it.bottomMargin = dp(10) }
        }
        val topRow = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).also { it.bottomMargin = dp(8) }
        }
        val swatch = View(context).apply {
            layoutParams = LayoutParams(dp(20), dp(20)).also { it.marginEnd = dp(8) }
            background = GradientDrawable().apply {
                setColor(rule.color); cornerRadius = dp(6).toFloat(); setStroke(dp(1), Color.parseColor("#33FFFFFF"))
            }
        }
        val queryText = TextView(context).apply {
            text = rule.query.ifBlank { "(فارغ)" }
            setTextColor(colorText)
            textSize = 12.5f
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            layoutParams = LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f)
        }
        val delBtn = TextView(context).apply {
            text = "×"
            gravity = Gravity.CENTER
            setTextColor(Color.parseColor("#F87171"))
            textSize = 13f
            layoutParams = LayoutParams(dp(24), dp(24))
            background = GradientDrawable().apply { setColor(Color.parseColor("#1FF87171")); cornerRadius = dp(7).toFloat() }
            setOnClickListener {
                val gv = graphView ?: return@setOnClickListener
                gv.setGroupRules(gv.currentGroupRules().filterNot { it.id == rule.id })
                rebuildBody()
            }
        }
        topRow.addView(swatch)
        topRow.addView(queryText)
        topRow.addView(delBtn)
        card.addView(topRow)

        val editHint = TextView(context).apply {
            text = "تعديل ›"
            setTextColor(colorHint)
            textSize = 10.5f
            setOnClickListener {
                editingRuleId = rule.id
                editorSelectedColor = rule.color
                rebuildBody()
            }
        }
        card.addView(editHint)
        return card
    }

    private lateinit var ruleQueryInput: EditText
    private var ruleEditorColor: Int = GRAPH_PRESET_COLORS[0]

    private fun buildRuleEditor(body: LinearLayout, ruleId: String) {
        val gv = graphView ?: return
        val existing = gv.currentGroupRules().find { it.id == ruleId }

        body.addView(fieldLabel("شرط القاعدة"))
        ruleQueryInput = EditText(context).apply {
            setText(existing?.query.orEmpty())
            hint = "مثال: tag:important"
            setHintTextColor(Color.parseColor("#566178"))
            setTextColor(colorText)
            textSize = 13f
            background = fieldBackground()
            setPadding(dp(10), dp(9), dp(10), dp(9))
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).also { it.bottomMargin = dp(10) }
        }
        body.addView(ruleQueryInput)
        body.addView(hintText("كلمة مباشرة تبحث في العنوان · tag: للوسوم · section: للتصنيف · line: للبحث داخل النص"))

        val errorText = TextView(context).apply {
            setTextColor(Color.parseColor("#F87171"))
            textSize = 11.5f
            visibility = View.GONE
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).also { it.bottomMargin = dp(10) }
        }
        body.addView(errorText)
        ruleQueryInput.addTextChangedListener { errorText.visibility = View.GONE }

        body.addView(fieldLabel("اللون"))

        ruleEditorColor = existing?.color ?: editorSelectedColor
        lateinit var ruleSwatch: View
        ruleSwatch = View(context).apply {
            layoutParams = LayoutParams(dp(36), dp(36)).also { it.bottomMargin = dp(10) }
            background = GradientDrawable().apply {
                setColor(ruleEditorColor); cornerRadius = dp(9).toFloat(); setStroke(dp(1), Color.parseColor("#33FFFFFF"))
            }
            isClickable = true
            setOnClickListener {
                GraphColorPickerDialog.show(context, "اللون", ruleEditorColor) { chosen ->
                    ruleEditorColor = chosen
                    background = GradientDrawable().apply {
                        setColor(chosen); cornerRadius = dp(9).toFloat(); setStroke(dp(1), Color.parseColor("#33FFFFFF"))
                    }
                }
            }
        }
        body.addView(ruleSwatch)

        val actionsRow = LinearLayout(context).apply {
            orientation = HORIZONTAL
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).also { it.topMargin = dp(6) }
        }
        val saveBtn = primaryButton("حفظ")
        saveBtn.setOnClickListener {
            val query = ruleQueryInput.text?.toString()?.trim().orEmpty()
            val color = ruleEditorColor
            val current = graphView?.currentGroupRules().orEmpty()

            // Two different rules can't share the same name/query, even with different colors -
            // only check against blank names being duplicated is skipped since "no name yet" for
            // more than one in-progress rule isn't a meaningful collision.
            val isDuplicate = query.isNotEmpty() && current.any {
                it.id != ruleId && it.query.trim().equals(query, ignoreCase = true)
            }
            if (isDuplicate) {
                errorText.text = "هذا الاسم مستخدم بالفعل في مجموعة أخرى"
                errorText.visibility = View.VISIBLE
                return@setOnClickListener
            }

            val updated = if (current.any { it.id == ruleId }) {
                current.map { if (it.id == ruleId) it.copy(query = query, color = color) else it }
            } else {
                current + GraphGroupRule(ruleId, query, color)
            }
            graphView?.setGroupRules(updated)
            editingRuleId = null
            rebuildBody()
        }
        val cancelBtn = secondaryButton("إلغاء")
        cancelBtn.setOnClickListener { editingRuleId = null; rebuildBody() }
        actionsRow.addView(saveBtn, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f).also { it.marginEnd = dp(4) })
        actionsRow.addView(cancelBtn, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f).also { it.marginStart = dp(4) })
        body.addView(actionsRow)
    }

    // ------------------------------------------------------------------
    // قسم: العرض
    // ------------------------------------------------------------------
    private fun buildDisplayContent(body: LinearLayout) {
        val gv = graphView ?: return
        val s = gv.currentSettings()

        addToggleRow(body, "الأسهم", s.showArrows) { checked ->
            graphView?.setSettings(graphView!!.currentSettings().copy(showArrows = checked), isPhysics = false)
        }
        addSliderRow(body, "حد تلاشي النص", 0.1f, 1.5f, 0.01f, s.labelFadeLimit, decimals = 2, unit = "", isPhysics = false) { cur, v ->
            cur.copy(labelFadeLimit = v)
        }
        addSliderRow(body, "حجم النقاط", 8f, 24f, 1f, s.nodeSize, decimals = 0, unit = "px", isPhysics = false) { cur, v ->
            cur.copy(nodeSize = v)
        }
        addSliderRow(body, "سمك الرابط", 1f, 6f, 0.5f, s.linkWidth, decimals = 1, unit = "px", isPhysics = false) { cur, v ->
            cur.copy(linkWidth = v)
        }
        addSliderRow(body, "أكبر مسافة ممكنة بين العقد", 300f, 5000f, 10f, s.maxClusterDistance, decimals = 0, unit = "px", isPhysics = false) { cur, v ->
            cur.copy(maxClusterDistance = v)
        }

        body.addView(sectionLabel("ألوان العقد"))
        body.addView(hintText("لون الوسوم (#) ولون التصنيفات كل واحد مستقل عن الآخر - افتراضيًا كلاهما أخضر."))

        body.addView(fieldLabel("لون الوسوم (tags)"))
        addHubColorPicker(body, initial = s.tagColor, title = "لون الوسوم (tags)") { color ->
            graphView?.setSettings(graphView!!.currentSettings().copy(tagColor = color), isPhysics = false)
        }

        body.addView(fieldLabel("لون التصنيفات (sections)"))
        addHubColorPicker(body, initial = s.categoryColor, title = "لون التصنيفات (sections)") { color ->
            graphView?.setSettings(graphView!!.currentSettings().copy(categoryColor = color), isPhysics = false)
        }
    }

    /** A small square swatch showing the current color next to its label; tapping it opens
     * [GraphColorPickerDialog], and only a "حفظ" tap inside that dialog swaps the swatch's color
     * and calls [onPicked] - matching the requested "مربع صغير افتراضيًا أخضر ← ضغط يفتح نافذة
     * الاختيار ← بعد حفظ يظهر اللون الجديد مكان القديم" behavior. */
    private fun addHubColorPicker(container: LinearLayout, initial: Int, title: String, onPicked: (Int) -> Unit) {
        var current = initial
        lateinit var swatch: View
        swatch = View(context).apply {
            layoutParams = LayoutParams(dp(36), dp(36)).also { it.bottomMargin = dp(10) }
            background = GradientDrawable().apply {
                setColor(current); cornerRadius = dp(9).toFloat(); setStroke(dp(1), Color.parseColor("#33FFFFFF"))
            }
            isClickable = true
            setOnClickListener {
                GraphColorPickerDialog.show(context, title, current) { chosen ->
                    current = chosen
                    background = GradientDrawable().apply {
                        setColor(current); cornerRadius = dp(9).toFloat(); setStroke(dp(1), Color.parseColor("#33FFFFFF"))
                    }
                    onPicked(chosen)
                }
            }
        }
        container.addView(swatch)
    }

    // ------------------------------------------------------------------
    // قسم: القوى
    // ------------------------------------------------------------------
    private fun buildForcesContent(body: LinearLayout) {
        val gv = graphView ?: return
        val s = gv.currentSettings()

        addSliderRow(body, "طول الرابطة", 16f, 300f, 1f, s.linkDistance, decimals = 0, unit = "px", isPhysics = true) { cur, v ->
            cur.copy(linkDistance = v)
        }
        addSliderRow(body, "قوة الرابطة", 0f, 1f, 0.01f, s.linkStrength, decimals = 2, unit = "", isPhysics = true) { cur, v ->
            cur.copy(linkStrength = v)
        }
        addSliderRow(body, "قوة الصد", 16f, 300f, 1f, s.repulsionDistance, decimals = 0, unit = "px", isPhysics = true) { cur, v ->
            cur.copy(repulsionDistance = v)
        }
        addSliderRow(body, "قوة الوسط", 0f, 1f, 0.01f, s.centerForce, decimals = 2, unit = "", isPhysics = true) { cur, v ->
            cur.copy(centerForce = v)
        }
        addSliderRow(body, "تأثير عدد العلاقات", 0f, 1f, 0.01f, s.degreeInfluence, decimals = 2, unit = "", isPhysics = true) { cur, v ->
            cur.copy(degreeInfluence = v)
        }
    }

    // ------------------------------------------------------------------
    // عناصر مشتركة
    // ------------------------------------------------------------------
    private fun addToggleRow(container: LinearLayout, label: String, initial: Boolean, onChanged: (Boolean) -> Unit) {
        val row = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(10), dp(12), dp(10))
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#08FFFFFF")); cornerRadius = dp(10).toFloat()
                setStroke(dp(1), Color.parseColor("#0FFFFFFF"))
            }
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).also { it.bottomMargin = dp(6) }
        }
        val labelView = TextView(context).apply {
            text = label; setTextColor(colorMuted); textSize = 12.5f
            layoutParams = LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f)
        }
        val switch = SwitchMaterial(context).apply {
            isChecked = initial
            setOnCheckedChangeListener { _, checked -> onChanged(checked) }
        }
        row.addView(labelView)
        row.addView(switch)
        container.addView(row)
    }

    private fun addSliderRow(
        container: LinearLayout,
        label: String,
        min: Float,
        max: Float,
        step: Float,
        initialValue: Float,
        decimals: Int,
        unit: String,
        isPhysics: Boolean,
        update: (GraphSettings, Float) -> GraphSettings
    ) {
        val row = LinearLayout(context).apply {
            orientation = VERTICAL
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).also { it.bottomMargin = dp(14) }
        }
        val labelRow = LinearLayout(context).apply { orientation = HORIZONTAL }
        val nameView = TextView(context).apply {
            text = label; setTextColor(colorMuted); textSize = 12.5f
            layoutParams = LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f)
        }
        val valueView = TextView(context).apply {
            text = formatSliderValue(initialValue, decimals, unit)
            setTextColor(colorGreen)
            textSize = 12.5f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        }
        labelRow.addView(nameView)
        labelRow.addView(valueView)
        row.addView(labelRow)

        val slider = Slider(context).apply {
            valueFrom = min
            valueTo = max
            stepSize = step
            value = initialValue.coerceIn(min, max)
            trackActiveTintList = android.content.res.ColorStateList.valueOf(colorGreen)
            thumbTintList = android.content.res.ColorStateList.valueOf(colorGreen)
            trackInactiveTintList = android.content.res.ColorStateList.valueOf(Color.parseColor("#26FFFFFF"))
            labelBehavior = com.google.android.material.slider.LabelFormatter.LABEL_GONE
            addOnChangeListener { _, v, fromUser ->
                valueView.text = formatSliderValue(v, decimals, unit)
                if (fromUser) {
                    val gv = graphView ?: return@addOnChangeListener
                    gv.setSettings(update(gv.currentSettings(), v), isPhysics)
                }
            }
        }
        row.addView(slider)
        container.addView(row)
    }

    private fun formatSliderValue(v: Float, decimals: Int, unit: String): String {
        val numeric = if (decimals == 0) v.roundToInt().toString() else String.format("%.${decimals}f", v)
        return if (unit.isEmpty()) numeric else "$numeric $unit"
    }

    private fun fieldLabel(text: String): TextView = TextView(context).apply {
        this.text = text
        setTextColor(Color.parseColor("#94A3B8"))
        textSize = 12f
        layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).also { it.bottomMargin = dp(5) }
    }

    private fun sectionLabel(text: String): TextView = TextView(context).apply {
        this.text = text
        setTextColor(colorAccentLabel)
        textSize = 11.5f
        setTypeface(typeface, android.graphics.Typeface.BOLD)
        layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).also {
            it.topMargin = dp(14); it.bottomMargin = dp(8)
        }
    }

    private fun hintText(text: String): TextView = TextView(context).apply {
        this.text = text
        setTextColor(colorHint)
        textSize = 11f
        layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).also { it.bottomMargin = dp(10) }
    }

    private fun primaryButton(label: String): TextView = TextView(context).apply {
        text = label
        gravity = Gravity.CENTER
        setTextColor(Color.parseColor("#EAFFF2"))
        textSize = 13f
        setPadding(0, dp(10), 0, dp(10))
        background = GradientDrawable().apply { setColor(Color.parseColor("#2F7D4F")); cornerRadius = dp(9).toFloat() }
        layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).also { it.topMargin = dp(4) }
        isClickable = true
    }

    private fun secondaryButton(label: String): TextView = TextView(context).apply {
        text = label
        gravity = Gravity.CENTER
        setTextColor(colorText)
        textSize = 12.5f
        setPadding(0, dp(9), 0, dp(9))
        background = GradientDrawable().apply {
            setColor(Color.TRANSPARENT); cornerRadius = dp(9).toFloat()
            setStroke(dp(1), Color.parseColor("#24FFFFFF"))
        }
        isClickable = true
    }

    private fun fieldBackground(): GradientDrawable = GradientDrawable().apply {
        setColor(Color.parseColor("#0AFFFFFF"))
        cornerRadius = dp(8).toFloat()
        setStroke(dp(1), Color.parseColor("#1FFFFFFF"))
    }

    private fun dp(value: Int): Int =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value.toFloat(), resources.displayMetrics).toInt()
}
