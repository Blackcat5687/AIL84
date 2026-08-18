package com.notekeep.local.graph

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.util.AttributeSet
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.GridLayout
import android.widget.LinearLayout
import android.widget.TextView
import com.google.android.material.button.MaterialButton

/**
 * Composite color picker for a group rule: 8 preset swatches + a "+" custom trigger that reveals
 * an HSV box + hue slider + hex preview + confirm/cancel, ported from the HTML's
 * renderColorPickerHtml/attachColorPickerHandlers. Presets apply immediately (like the original);
 * the custom picker only applies on "confirm". The caller reads [selectedColor] when the rule is
 * saved - this view does not push changes upward on every drag, matching the original's
 * hidden-input-until-save design.
 */
class GraphColorPickerView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : LinearLayout(context, attrs) {

    var selectedColor: Int = GRAPH_PRESET_COLORS[0]
        private set

    /** Optional: notified with the newly applied color every time one is actually committed -
     * a preset tap, or "confirm" in the custom HSV area. Used by callers (like the display-
     * section hub color pickers) that want to apply the color immediately rather than reading
     * [selectedColor] later from an outer "save" button. Rule-editor callers that do have their
     * own save step simply leave this unset. */
    private var onColorApplied: ((Int) -> Unit)? = null
    fun setOnColorAppliedListener(listener: (Int) -> Unit) {
        onColorApplied = listener
    }

    private val swatchViews = ArrayList<Pair<Int, View>>()
    private lateinit var customArea: LinearLayout
    private lateinit var svBox: GraphSvBoxView
    private lateinit var hueSlider: GraphHueSliderView
    private lateinit var previewSwatch: View
    private lateinit var previewHex: TextView

    private val pickerHsv = FloatArray(3)

    init {
        orientation = VERTICAL
        val pad = dp(12)
        setPadding(pad, pad, pad, pad)
        background = roundedDrawable(Color.parseColor("#050708"), 10f, Color.parseColor("#1AFFFFFF"))

        addView(buildSwatchGrid())
        customArea = buildCustomArea()
        customArea.visibility = View.GONE
        addView(customArea)

        setSelectedColor(GRAPH_PRESET_COLORS[0], refreshSwatches = true)
    }

    fun setSelectedColor(color: Int, refreshSwatches: Boolean = true) {
        selectedColor = color
        Color.colorToHSV(color, pickerHsv)
        if (refreshSwatches) updateSwatchActiveState()
        if (::svBox.isInitialized) {
            svBox.hue = pickerHsv[0]; svBox.saturation = pickerHsv[1]; svBox.value = pickerHsv[2]
            hueSlider.hue = pickerHsv[0]
            refreshCustomPreview()
        }
    }

    // -------------------- Preset swatch grid --------------------
    private fun buildSwatchGrid(): GridLayout {
        val grid = GridLayout(context).apply {
            columnCount = 4
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).also { it.bottomMargin = dp(10) }
        }
        val size = dp(56)
        val margin = dp(4)

        for (color in GRAPH_PRESET_COLORS) {
            val swatch = View(context).apply {
                background = roundedDrawable(color, 9f, Color.parseColor("#33FFFFFF"))
                setOnClickListener {
                    setSelectedColor(color, refreshSwatches = true)
                    customArea.visibility = View.GONE
                    onColorApplied?.invoke(color)
                }
            }
            // Fixed pixel size, no weight spec - GridLayout weights only make sense when the
            // cell itself is meant to stretch; mixing a fixed width/height with a weighted spec
            // made the grid measure inconsistently (uneven gaps/overlap). A plain spec with a
            // fixed size lays out a clean, predictable 4-column grid.
            val lp = GridLayout.LayoutParams(GridLayout.spec(GridLayout.UNDEFINED), GridLayout.spec(GridLayout.UNDEFINED))
            lp.width = size; lp.height = size
            lp.setMargins(margin, margin, margin, margin)
            swatch.layoutParams = lp
            grid.addView(swatch)
            swatchViews.add(color to swatch)
        }

        val customTrigger = TextView(context).apply {
            text = "+"
            gravity = Gravity.CENTER
            textSize = 20f
            setTextColor(Color.WHITE)
            background = conicLikeDrawable()
            setOnClickListener {
                customArea.visibility = if (customArea.visibility == View.VISIBLE) View.GONE else View.VISIBLE
            }
        }
        val lp = GridLayout.LayoutParams(GridLayout.spec(GridLayout.UNDEFINED), GridLayout.spec(GridLayout.UNDEFINED))
        lp.width = size; lp.height = size
        lp.setMargins(margin, margin, margin, margin)
        customTrigger.layoutParams = lp
        grid.addView(customTrigger)

        return grid
    }

    private fun updateSwatchActiveState() {
        for ((color, view) in swatchViews) {
            val active = color == selectedColor
            view.background = roundedDrawable(
                color, 9f,
                if (active) Color.WHITE else Color.parseColor("#33FFFFFF"),
                if (active) dp(2) else dp(1)
            )
        }
    }

    // -------------------- Custom HSV picker area --------------------
    private fun buildCustomArea(): LinearLayout {
        val area = LinearLayout(context).apply { orientation = VERTICAL }

        svBox = GraphSvBoxView(context).apply {
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, dp(140)).also { it.bottomMargin = dp(10) }
            onChange = { s, v ->
                pickerHsv[1] = s; pickerHsv[2] = v
                refreshCustomPreview()
            }
        }
        area.addView(svBox)

        hueSlider = GraphHueSliderView(context).apply {
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, dp(28)).also { it.bottomMargin = dp(12) }
            onChange = { h ->
                pickerHsv[0] = h
                svBox.hue = h
                refreshCustomPreview()
            }
        }
        area.addView(hueSlider)

        val previewRow = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).also { it.bottomMargin = dp(12) }
        }
        previewSwatch = View(context).apply {
            layoutParams = LinearLayout.LayoutParams(dp(40), dp(40)).also { it.marginEnd = dp(10) }
            background = roundedDrawable(selectedColor, 9f, Color.parseColor("#33FFFFFF"))
        }
        previewHex = TextView(context).apply {
            setTextColor(Color.parseColor("#CBD5E1"))
            textSize = 12.5f
        }
        previewRow.addView(previewSwatch)
        previewRow.addView(previewHex)
        area.addView(previewRow)

        val actionsRow = LinearLayout(context).apply {
            orientation = HORIZONTAL
            weightSum = 2f
        }
        val confirmBtn = flatButton("تعيين", Color.parseColor("#2F7D4F"), Color.parseColor("#EAFFF2")) {
            val hexColor = Color.HSVToColor(pickerHsv)
            setSelectedColor(hexColor, refreshSwatches = true)
            customArea.visibility = View.GONE
            onColorApplied?.invoke(hexColor)
        }
        val cancelBtn = flatButton("إلغاء", Color.TRANSPARENT, Color.parseColor("#CBD5E1")) {
            customArea.visibility = View.GONE
        }
        val lpBtn = LinearLayout.LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f).also { it.marginEnd = dp(4) }
        val lpBtn2 = LinearLayout.LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f).also { it.marginStart = dp(4) }
        actionsRow.addView(confirmBtn, lpBtn)
        actionsRow.addView(cancelBtn, lpBtn2)
        area.addView(actionsRow)

        return area
    }

    private fun refreshCustomPreview() {
        val hexColor = Color.HSVToColor(pickerHsv)
        previewSwatch.background = roundedDrawable(hexColor, 9f, Color.parseColor("#33FFFFFF"))
        previewHex.text = String.format("#%06X", 0xFFFFFF and hexColor)
    }

    private fun flatButton(label: String, bg: Int, textColor: Int, onClick: () -> Unit): MaterialButton {
        return MaterialButton(context, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
            text = label
            setTextColor(textColor)
            cornerRadius = dp(9)
            insetTop = 0; insetBottom = 0
            if (bg != Color.TRANSPARENT) {
                backgroundTintList = android.content.res.ColorStateList.valueOf(bg)
                strokeWidth = 0
            } else {
                strokeColor = android.content.res.ColorStateList.valueOf(Color.parseColor("#24FFFFFF"))
                backgroundTintList = android.content.res.ColorStateList.valueOf(Color.TRANSPARENT)
            }
            setOnClickListener { onClick() }
        }
    }

    private fun conicLikeDrawable(): GradientDrawable = GradientDrawable(
        GradientDrawable.Orientation.TL_BR,
        intArrayOf(Color.RED, Color.YELLOW, Color.GREEN, Color.CYAN, Color.BLUE, Color.MAGENTA)
    ).apply { cornerRadius = dp(9).toFloat() }

    private fun roundedDrawable(fill: Int, radiusDp: Float, strokeColor: Int, strokeWidthPx: Int = dp(1)): GradientDrawable =
        GradientDrawable().apply {
            setColor(fill)
            cornerRadius = dp(radiusDp.toInt()).toFloat()
            setStroke(strokeWidthPx, strokeColor)
        }

    private fun dp(value: Int): Int =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value.toFloat(), resources.displayMetrics).toInt()
}
