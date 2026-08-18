package com.notekeep.local.ui

import android.text.Editable
import android.text.TextWatcher
import android.widget.EditText

/**
 * ============================================================================================
 * WHAT THIS REPLACES AND WHY
 * ============================================================================================
 * This replaces the previous TrailingSpaceWrapController, which tried to fix the same bug by
 * inflating one space's *measured* width (via a ReplacementSpan) to nudge Android's native line
 * breaker into wrapping "by accident" at the right point. That approach had three real problems:
 *   1. It only ever examined the LAST paragraph of the whole note (text.lastIndexOf('\n')), so
 *      typing spaces in the middle of a multi-paragraph note was never covered.
 *   2. It re-scanned the ENTIRE note's spans and called text.toString() on every single
 *      keystroke - not just space keystrokes - which is unnecessary O(document length) work on
 *      every character typed, wasteful for longer notes.
 *   3. Most importantly, it assumed a specific (never device-verified) theory about *why*
 *      Android's breaker fails - that a bigger reported width for one character would coax the
 *      native breaker into the right decision. That is a guess about an opaque native code path
 *      (libminikin/ICU), which can vary across Android versions and OEM skins.
 *
 * This version does not try to out-guess Android's native line breaker at all. Instead it relies
 * on one thing that IS a documented, stable TextView contract: TextView.setText() always throws
 * away whatever Layout it had and builds a brand new one from scratch, from the width and the
 * full current text - never an incremental patch. So instead of guessing how to influence the
 * buggy path, this controller detects when the buggy path has produced a wrong result and simply
 * asks TextView to redo that specific layout the way it already reliably does at initial
 * measure/rotation time.
 *
 * ============================================================================================
 * THE ROOT CAUSE (confirmed against the reported symptom)
 * ============================================================================================
 * TextView only rebuilds its whole internal Layout from scratch (TextView.makeNewLayout(), a
 * fresh StaticLayout/DynamicLayout construction from the complete current text) when the
 * measured WIDTH actually changes - e.g. on first layout, or on rotation/resize. When only the
 * TEXT changes and the width stays the same (i.e. on every normal keystroke), DynamicLayout
 * instead patches only the touched region incrementally (reflow()), reusing everything else it
 * already computed. For most content that incremental patch is correct and is exactly what makes
 * typing fast. The reported symptom - letters/digits/symbols wrap normally, but a run of spaces
 * at the end of an RTL (Arabic) line pushes the paragraph off the right edge instead of wrapping
 * - is specific to that incremental path failing to (re)detect that the line has crossed the
 * available width, while a full non-incremental rebuild of the same text at the same width does
 * not have this problem (this matches WrapSafeEditText's own finding: forcing a fresh Layout
 * build at measure time avoids the miscalculation; the gap this class closes is that the same
 * fresh build never used to happen again after that first measure, for the rest of typing).
 *
 * ============================================================================================
 * THE MECHANISM
 * ============================================================================================
 * On every text change that just inserted at least one space character (deletions can only
 * shrink a line, never cause new overflow, so they are skipped for zero extra cost):
 *   1. Find the visual line the caret just landed on, via editText.layout - the very Layout
 *      DynamicLayout's incremental path just (possibly wrongly) produced.
 *   2. Independently re-measure that line's actual content width ourselves, in the text's
 *      original logical character order via Paint.measureText(CharSequence, start, end) - a
 *      measurement that does not depend on or trust any bidi reordering/line-break decision
 *      Android's Layout has already made, so it cannot inherit the same mistake.
 *   3. Compare that ground-truth width against the field's real available width. If our own
 *      measurement says the line has overflowed but the Layout apparently still thinks this is
 *      one line (i.e. it let it overflow instead of wrapping), force a clean rebuild.
 *   4. The rebuild is exactly what already works correctly and is fully supported: save the
 *      caret position, call editText.setText() with the SAME text content (not one character
 *      added/removed), then restore the caret. TextView is now forced through makeNewLayout()
 *      for the whole field. Since the text content is byte-for-byte identical before and after,
 *      this cannot change what gets saved - only how it is currently laid out on screen.
 *
 * This only ever fires in the rare case where a space was just typed AND that specific line has
 * actually overflowed - not on every keystroke, and not speculatively - so the O(text length)
 * cost of setText() is paid only when a real fix is actually needed, never on ordinary typing.
 *
 * ============================================================================================
 * WHY THIS COVERS THE WHOLE NOTE, NOT JUST THE LAST PARAGRAPH
 * ============================================================================================
 * The overflow check is based on wherever the caret's current line is (via editText.layout),
 * not on "is this the last paragraph of the whole text". Typing spaces in the middle of an
 * existing multi-paragraph note is covered exactly the same way as typing at the very end.
 *
 * ============================================================================================
 * WHAT THIS DELIBERATELY DOES NOT DO
 * ============================================================================================
 *  - Does not touch, trim, or reorder a single character of the note's actual text content -
 *    setText() is called with the unmodified string, so what gets saved is unaffected.
 *  - Does not run on every keystroke - only on insertions that contain a space, and even then
 *    only when the independent width check actually finds an overflow.
 *  - Does not add any span, so there is no risk of a stretched/invisible-width character
 *    affecting caret placement or selection-highlight rendering (a real, self-documented risk of
 *    the previous approach).
 *
 * ============================================================================================
 * HONEST VERIFICATION STATUS
 * ============================================================================================
 * This was written and reasoned about by reading TextView/DynamicLayout's documented and
 * long-standing behavior (not by running it) - there is no Android SDK, emulator, or device in
 * this environment. The mechanism this relies on (setText() forces a full non-incremental
 * Layout rebuild) is core, stable, documented TextView contract rather than a guess about an
 * opaque native code path, which is why this should be materially more reliable than the
 * previous attempt - but "materially more reliable" is not the same as "verified", and this
 * still needs a real on-device pass. Specifically check:
 *   1. The original bug scenario: type Arabic text until a line is nearly full, then hold space -
 *      the line should now wrap normally instead of overflowing.
 *   2. Typing spaces in the MIDDLE of an existing multi-paragraph note (not just at the very
 *      end) - this is the scenario the previous fix could never cover.
 *   3. Pasting a block of text containing many spaces at once.
 *   4. That undo/redo does not gain a spurious extra step from a correction (it should not - see
 *      NoteEditActivity's wiring of beginProgrammaticEdit/endProgrammaticEdit).
 *   5. That a correction happening while an IME suggestion/composing state is active does not
 *      look glitchy - setText() ends any in-progress composing span, which in practice should be
 *      unnoticeable since space is itself normally a word-committing character for most
 *      keyboards, but this is worth a specific look on-device with a couple of different IMEs.
 */
class RtlSpaceWrapGuard(
    private val editText: EditText,
    /** Called immediately before a corrective setText(), so the caller can suppress its own
     * text-changed side effects (e.g. undo-history bookkeeping) for this programmatic edit,
     * exactly like the existing applyState()/undo-restore path already does. */
    private val beginProgrammaticEdit: () -> Unit = {},
    private val endProgrammaticEdit: () -> Unit = {}
) {
    /** Guards against the corrective setText() call re-entering this same watcher. */
    private var isCorrecting = false

    /** The end offset of the just-inserted text, captured in onTextChanged for use once
     * afterTextChanged runs and the Layout has settled. -1 means "nothing worth checking" -
     * either nothing was inserted, or what was inserted contained no space. */
    private var pendingInsertEnd = -1

    fun attach() {
        editText.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}

            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                // Cheap bounded-cost check over just the newly inserted range - never the whole
                // document. Deletions (count == 0, or a replacement that got shorter) can only
                // reduce a line's width, so they can never be the cause of new overflow and are
                // skipped for zero cost.
                if (isCorrecting || s == null || count <= 0) {
                    pendingInsertEnd = -1
                    return
                }
                var sawSpace = false
                var i = start
                val end = start + count
                while (i < end) {
                    if (s[i] == ' ') {
                        sawSpace = true
                        break
                    }
                    i++
                }
                pendingInsertEnd = if (sawSpace) end else -1
            }

            override fun afterTextChanged(s: Editable?) {
                if (isCorrecting || s == null) return
                val insertEnd = pendingInsertEnd
                pendingInsertEnd = -1
                if (insertEnd < 0 || insertEnd > s.length) return
                checkForOverflow(s, insertEnd)
            }
        })
    }

    /** Independently re-measures the line the just-typed space landed on and, only if that line
     * has genuinely overflowed the field's real available width, forces a clean rebuild. */
    private fun checkForOverflow(s: Editable, caretOffset: Int) {
        val layout = editText.layout ?: return
        val lineIndex = layout.getLineForOffset(caretOffset)
        val lineStart = layout.getLineStart(lineIndex)
        if (caretOffset <= lineStart) return // nothing on this line yet to overflow

        val availableWidth = editText.width - editText.totalPaddingLeft - editText.totalPaddingRight
        if (availableWidth <= 0) return // not laid out yet

        // Ground-truth measurement in the text's original logical order, independent of
        // whatever (possibly wrong) line-break decision the Layout already made.
        val measuredWidth = editText.paint.measureText(s, lineStart, caretOffset)
        if (measuredWidth <= availableWidth) return // genuinely fits - nothing to correct

        forceCleanRelayout(s)
    }

    /** Forces TextView through its full, non-incremental Layout-rebuild path by round-tripping
     * through setText() with byte-for-byte identical content, then restores the caret exactly
     * where it was - so nothing about the saved text or the user's typing position changes,
     * only how the existing text is currently wrapped on screen. */
    private fun forceCleanRelayout(s: Editable) {
        isCorrecting = true
        try {
            val selStart = editText.selectionStart
            val selEnd = editText.selectionEnd
            val content = s.toString()
            beginProgrammaticEdit()
            editText.setText(content)
            val len = content.length
            editText.setSelection(selStart.coerceIn(0, len), selEnd.coerceIn(0, len))
        } finally {
            endProgrammaticEdit()
            isCorrecting = false
        }
    }
}
