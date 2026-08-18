package com.notekeep.local.graph

import android.content.Intent
import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.notekeep.local.data.AppDatabase
import com.notekeep.local.databinding.ActivityGraphBinding
import com.notekeep.local.ui.NoteEditActivity
import kotlinx.coroutines.launch

/**
 * Hosts the native Kotlin graph (GraphCanvasView + GraphSettingsPanelView) - no WebView, no JS,
 * no bridge. This Activity's jobs are the same as before: load real note data in, forward taps
 * to NoteEditActivity, and persist graph state durably via GraphSettingsStore. Everything that
 * used to live in assets/graph/graph.html (rendering, physics, touch, the settings UI) now lives
 * in GraphCanvasView/GraphSettingsPanelView, in the same language as the rest of the app.
 */
class GraphActivity : AppCompatActivity(), GraphCanvasView.Callbacks {

    private lateinit var binding: ActivityGraphBinding

    /** True right after onCreate's own initial load, so the very next onResume (which Android
     * always fires immediately after onCreate on a fresh launch) doesn't reload data a
     * split-second after it was already sent. */
    private var justCreated = true

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityGraphBinding.inflate(layoutInflater)
        setContentView(binding.root)
        // No native Toolbar here on purpose: the settings gear sits at top-left like before, and
        // a native Toolbar overlapping it would intercept taps meant for that button. Exiting
        // this screen uses the system back gesture/button, handled by AppCompatActivity already.

        binding.graphCanvasView.callbacks = this
        binding.graphSettingsPanel.graphView = binding.graphCanvasView
        binding.graphSettingsPanel.onCloseRequested = { binding.graphSettingsPanel.visibility = View.GONE }
        binding.settingsToggleBtn.setOnClickListener {
            binding.graphSettingsPanel.visibility =
                if (binding.graphSettingsPanel.visibility == View.VISIBLE) View.GONE else View.VISIBLE
        }

        val savedState = GraphSettingsStore.loadState(applicationContext)
        binding.graphCanvasView.applyState(savedState ?: GraphState(), hasSavedView = savedState != null)
        binding.graphSettingsPanel.refresh()

        loadGraphData(savedState?.positions.orEmpty())
    }

    override fun onResume() {
        super.onResume()
        if (justCreated) {
            justCreated = false
        } else {
            // A real return to this screen (e.g. after editing a note) - notes may have changed,
            // so refresh from the DB. GraphCanvasView.loadData preserves node positions/zoom/pan
            // for notes it already knows, so this doesn't jolt the layout.
            loadGraphData(emptyMap())
        }
    }

    private fun loadGraphData(savedPositions: Map<String, Pair<Float, Float>>) {
        lifecycleScope.launch {
            val db = AppDatabase.getInstance(applicationContext)
            // Archived notes appear as graph nodes only while "show archived" is on (top-left
            // toggle on the archive screen) - soft-deleted/trashed notes are always excluded
            // regardless, since those are conceptually gone.
            val notes = if (com.notekeep.local.data.AppPrefs.showArchivedElsewhere(applicationContext)) {
                db.noteDao().getAllExcludingTrashOnce()
            } else {
                db.noteDao().getAllOnce()
            }
            val labels = db.labelDao().getAllOnce()
            val crossRefs = db.labelDao().getAllCrossRefsOnce()
            val noteLabelPairs = crossRefs.map { it.noteId to it.labelId }

            val payload = GraphDataBuilder.buildPayload(notes, labels, noteLabelPairs)
            binding.emptyView.visibility = if (notes.isEmpty()) View.VISIBLE else View.GONE
            binding.graphCanvasView.loadData(payload, savedPositions)
        }
    }

    override fun onNoteTapped(noteId: Long) {
        val intent = Intent(this, NoteEditActivity::class.java)
        intent.putExtra(NoteEditActivity.EXTRA_NOTE_ID, noteId)
        startActivity(intent)
    }

    override fun onStateChanged(state: GraphState) {
        GraphSettingsStore.saveState(applicationContext, state)
    }
}
