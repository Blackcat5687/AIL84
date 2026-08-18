package com.notekeep.local.graph

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog

/**
 * Popup (Dialog) presentation of GraphColorPickerView, used everywhere a color needs to be
 * picked from a small swatch trigger instead of an always-visible inline grid: تصميم "مربع صغير
 * يعرض اللون الحالي، بالضغط عليه تُفتح نافذة اختيار اللون، وبعد 'حفظ' يظهر اللون الجديد مكان
 * القديم". The dialog closes automatically on حفظ (committing the color) or إلغاء (discarding
 * it) - it never applies a color without an explicit حفظ tap, even though the inner picker's
 * presets/confirm normally apply immediately, because that immediate-apply callback is only
 * wired up when the dialog's own حفظ button is pressed.
 */
object GraphColorPickerDialog {

    fun show(context: Context, title: String, initialColor: Int, onSave: (Int) -> Unit) {
        val dp = { v: Int -> TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), context.resources.displayMetrics).toInt() }

        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(16), dp(18), dp(6))
        }

        val titleView = TextView(context).apply {
            text = title
            setTextColor(Color.parseColor("#E2E8F0"))
            textSize = 14.5f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).also {
                it.bottomMargin = dp(12)
            }
        }
        root.addView(titleView)

        var pendingColor = initialColor
        val picker = GraphColorPickerView(context).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            setSelectedColor(initialColor)
        }
        // Track the picker's current choice locally; the dialog only hands it to the caller
        // when حفظ is tapped below, so a preset tap or a custom "تعيين" inside the picker never
        // leaks out on its own.
        picker.setOnColorAppliedListener { color -> pendingColor = color }
        root.addView(picker)

        val container = FrameLayout(context).apply {
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#12141C"))
                cornerRadius = dp(14).toFloat()
            }
            addView(root)
        }

        val dialog = AlertDialog.Builder(context)
            .setView(container)
            .create()
        dialog.window?.setBackgroundDrawable(GradientDrawable().apply { setColor(Color.TRANSPARENT) })

        val actionsRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).also {
                it.topMargin = dp(6); it.bottomMargin = dp(10)
            }
        }
        val saveBtn = TextView(context).apply {
            text = "حفظ"
            gravity = Gravity.CENTER
            setTextColor(Color.parseColor("#EAFFF2"))
            textSize = 13f
            setPadding(0, dp(10), 0, dp(10))
            background = GradientDrawable().apply { setColor(Color.parseColor("#2F7D4F")); cornerRadius = dp(9).toFloat() }
            isClickable = true
            setOnClickListener {
                onSave(pendingColor)
                dialog.dismiss()
            }
        }
        val cancelBtn = TextView(context).apply {
            text = "إلغاء"
            gravity = Gravity.CENTER
            setTextColor(Color.parseColor("#E2E8F0"))
            textSize = 13f
            setPadding(0, dp(10), 0, dp(10))
            background = GradientDrawable().apply {
                setColor(Color.TRANSPARENT); cornerRadius = dp(9).toFloat()
                setStroke(dp(1), Color.parseColor("#24FFFFFF"))
            }
            isClickable = true
            setOnClickListener { dialog.dismiss() }
        }
        actionsRow.addView(saveBtn, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).also { it.marginEnd = dp(4) })
        actionsRow.addView(cancelBtn, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).also { it.marginStart = dp(4) })
        root.addView(actionsRow)

        dialog.show()
    }
}
