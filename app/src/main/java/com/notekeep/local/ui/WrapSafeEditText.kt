package com.notekeep.local.ui

import android.content.Context
import android.util.AttributeSet
import androidx.appcompat.widget.AppCompatEditText

/**
 * A multi-line EditText that never lets its internal text Layout be measured wider than the
 * space its parent actually gave it.
 *
 * This is the FIRST of two independent layers that together work around an Android text-layout
 * quirk with Arabic (RTL) content, where a run of trailing spaces at the end of an open line can
 * push text past the field's right/left edge instead of wrapping (see RtlSpaceWrapGuard for the
 * second layer and the full root-cause writeup - that class handles the bug as it happens live,
 * keystroke by keystroke; this class only handles the *initial* measurement).
 *
 * An AT_MOST width spec (what match_parent inside a wrap_content-height parent normally resolves
 * to here) lets TextView's own "desired width" calculation from the text content decide the
 * final width, comparing it against the AT_MOST bound only afterwards - and that desired-width
 * calculation is exactly where the trailing-space/bidi miscalculation happens. Forcing an
 * EXACTLY width spec before calling into super.onMeasure() removes that discretion entirely:
 * TextView builds its internal Layout to a fixed, correct width from the very first measure,
 * instead of computing one from content and hoping it's right. A final clamp on the reported
 * measured width is kept as a second line of defense.
 *
 * This alone only covers the state of the field at measure time (first layout, rotation,
 * resize) - it says nothing about what happens as the user keeps typing at a fixed width
 * afterwards, which is what RtlSpaceWrapGuard is for.
 */
class WrapSafeEditText @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = android.R.attr.editTextStyle
) : AppCompatEditText(context, attrs, defStyleAttr) {

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        // Force an EXACTLY width constraint before the base implementation builds its internal
        // text Layout. AT_MOST specs (what match_parent inside a wrap_content-height LinearLayout
        // normally resolves to here) let StaticLayout's own width calculation - which is what
        // under-measures a trailing run of Arabic-context spaces - decide the final width instead
        // of the space actually available; EXACTLY removes that discretion entirely, so the
        // internal Layout is always built to fit and any overflowing run is forced to wrap rather
        // than spill past the edge.
        val mode = android.view.View.MeasureSpec.getMode(widthMeasureSpec)
        val size = android.view.View.MeasureSpec.getSize(widthMeasureSpec)
        val exactSpec = if (mode != android.view.View.MeasureSpec.UNSPECIFIED) {
            android.view.View.MeasureSpec.makeMeasureSpec(size, android.view.View.MeasureSpec.EXACTLY)
        } else {
            widthMeasureSpec
        }
        super.onMeasure(exactSpec, heightMeasureSpec)
        // Belt-and-braces: even with an exact spec, clamp the final reported width so this view
        // can never claim more horizontal space than its parent actually gave it.
        if (mode != android.view.View.MeasureSpec.UNSPECIFIED && measuredWidth > size) {
            setMeasuredDimension(size, measuredHeight)
        }
    }
}
