package com.notekeep.local.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.text.Editable
import android.text.Spannable
import android.text.TextWatcher
import android.text.style.ReplacementSpan
import android.view.MotionEvent
import android.widget.EditText
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.notekeep.local.R
import com.notekeep.local.data.AppDatabase
import com.notekeep.local.data.Note
import kotlinx.coroutines.launch

/**
 * Drives the live "[[Note Title]]" auto-link behavior inside the note editor, per the user's
 * spec: while the caret is inside/touching a [[...]] span it stays raw plain text (fully
 * editable, brackets visible); the moment the caret moves away, that occurrence collapses -
 * brackets hidden, text tinted (sky-blue if a note with that exact title exists, a paler dim
 * sky-blue otherwise) - and becomes tappable: tapping a resolved link opens that note, tapping an
 * unresolved one creates a new note with that title and opens it.
 *
 * This intentionally never mutates the underlying text (no bracket deletion) - only Spannable
 * *spans* are added/removed each pass, so the raw "[[Title]]" is always what's actually stored
 * and saved; the bracket-hiding is purely a rendering effect via CollapsedLinkSpan.
 */
class WikiLinkController(
    private val activity: NoteEditActivity,
    private val editText: EditText,
    private val onSpansChanged: (() -> Unit)? = null
) {
    private val wikiLinkRegex = Regex("\\[\\[([^\\[\\]]+)]]")

    /** title(lowercased, trimmed) -> whether a note with that exact title currently exists.
     * Refreshed opportunistically; a miss just means the link renders unresolved until the next
     * pass, never a crash or a blocking lookup on the UI thread. */
    private val resolutionCache = HashMap<String, Boolean>()

    private var isRefreshing = false

    fun attach() {
        editText.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                if (s == null) return
                refreshSpans(s)
            }
        })

        // Moving the caret with taps/arrow keys/selection changes without any text change must
        // also re-collapse/expand the relevant [[links]] - callers chain this after their own
        // click handling (e.g. the keyboard-follow scroll) via onSelectionMightHaveChanged().
    }

    /** Call after the caret's position may have changed via a non-tap, non-typing path (e.g. the
     * external "scroll cursor above keyboard" logic moving selection) so spans stay in sync. */
    fun onSelectionMightHaveChanged() {
        editText.text?.let { refreshSpans(it) }
    }

    private fun refreshSpans(s: Editable) {
        if (isRefreshing) return // guard against span add/remove re-entering the text watcher
        isRefreshing = true
        try {
            val existing = s.getSpans(0, s.length, CollapsedLinkSpan::class.java)
            existing.forEach { s.removeSpan(it) }

            // An EditText that has never had focus yet (e.g. right after setText() when loading
            // a saved note) reports selectionStart/selectionEnd as 0 by default - NOT -1. Without
            // guarding on hasFocus(), that stray 0 would falsely match "caret touching" for any
            // link starting at the very beginning of the text, permanently leaving it as raw
            // "[[Title]]" text instead of collapsing it, since a never-focused field never fires
            // a real selection change to fix it later.
            val hasFocus = editText.hasFocus()
            val selStart = if (hasFocus) editText.selectionStart else -1
            val selEnd = if (hasFocus) editText.selectionEnd else -1
            val titlesToResolve = LinkedHashSet<String>()

            wikiLinkRegex.findAll(s).forEach { match ->
                val fullRange = match.range
                val caretTouching = hasFocus && (
                    selStart in fullRange.first..(fullRange.last + 1) ||
                        selEnd in fullRange.first..(fullRange.last + 1)
                    )
                if (caretTouching) return@forEach // leave raw/editable exactly as typed

                val title = match.groupValues[1].trim()
                if (title.isEmpty()) return@forEach
                val key = title.lowercase()
                titlesToResolve.add(key)
                val resolved = resolutionCache[key] == true

                s.setSpan(
                    CollapsedLinkSpan(activity, title, resolved),
                    fullRange.first,
                    fullRange.last + 1,
                    Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
                )
            }

            if (titlesToResolve.isNotEmpty()) resolveTitles(titlesToResolve)
        } finally {
            isRefreshing = false
        }
        onSpansChanged?.invoke()
    }

    /** Looks up each not-yet-cached title in the background; if any answer actually changes the
     * cache, re-runs the collapse pass once so a link that was unresolved (dim) upgrades to
     * resolved (bright) the moment its target note gets created elsewhere, without user input. */
    private fun resolveTitles(titles: Set<String>) {
        val unresolved = titles.filter { it !in resolutionCache }
        if (unresolved.isEmpty()) return
        activity.lifecycleScope.launch {
            val dao = AppDatabase.getInstance(activity.applicationContext).noteDao()
            var changed = false
            for (key in unresolved) {
                val found = dao.getByTitleExact(key) != null
                if (resolutionCache[key] != found) changed = true
                resolutionCache[key] = found
            }
            if (changed) editText.text?.let { refreshSpans(it) }
        }
    }

    /** Called on ACTION_UP of a touch event on the editor, to check whether the tap landed on a
     * collapsed [[link]] span and, if so, open/create the target note. Callers should still
     * return false afterwards so the EditText also gets to place the caret normally. */
    fun handleTapForLinkActivation(event: MotionEvent) {
        val text = editText.text ?: return
        val layout = editText.layout ?: return
        val x = (event.x - editText.totalPaddingLeft + editText.scrollX)
        val y = (event.y - editText.totalPaddingTop + editText.scrollY)
        if (y < 0 || x < 0) return
        val line = layout.getLineForVertical(y.toInt())
        val offset = layout.getOffsetForHorizontal(line, x)
        val spans = text.getSpans(offset, offset, CollapsedLinkSpan::class.java)
        val span = spans.firstOrNull() ?: return
        val start = text.getSpanStart(span)
        val end = text.getSpanEnd(span)
        // Only activate if the tap x actually landed within the span's own rendered bounds, not
        // just "somewhere on the line" (getOffsetForHorizontal can return an edge offset for taps
        // past the end of the line's text).
        if (offset < start || offset > end) return
        openOrCreate(span.title)
    }

    private fun openOrCreate(title: String) {
        activity.lifecycleScope.launch {
            val dao = AppDatabase.getInstance(activity.applicationContext).noteDao()
            var note = dao.getByTitleExact(title)
            if (note == null) {
                val newId = dao.insert(Note(title = title))
                note = dao.getById(newId)
            }
            val target = note ?: return@launch
            resolutionCache[title.lowercase()] = true
            activity.openNoteById(target.id)
        }
    }

    /**
     * Draws only the title text (no brackets) in the resolved/unresolved tint, replacing the
     * full "[[Title]]" span's rendered width. The underlying Editable text is untouched - this is
     * a pure rendering span, which is what lets the brackets "come back" instantly and losslessly
     * the moment the caret re-enters the span (the span is simply removed, nothing to restore).
     */
    private class CollapsedLinkSpan(
        context: Context,
        val title: String,
        resolved: Boolean
    ) : ReplacementSpan() {
        private val color = ContextCompat.getColor(
            context,
            if (resolved) R.color.wikilink_resolved else R.color.wikilink_unresolved
        )

        override fun getSize(paint: Paint, text: CharSequence, start: Int, end: Int, fm: Paint.FontMetricsInt?): Int {
            return paint.measureText(title).toInt()
        }

        override fun draw(canvas: Canvas, text: CharSequence, start: Int, end: Int, x: Float, top: Int, y: Int, bottom: Int, paint: Paint) {
            val original = paint.color
            paint.color = color
            canvas.drawText(title, x, y.toFloat(), paint)
            paint.color = original
        }
    }
}
