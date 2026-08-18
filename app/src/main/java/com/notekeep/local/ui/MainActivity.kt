package com.notekeep.local.ui

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SearchView
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.StaggeredGridLayoutManager
import com.notekeep.local.R
import com.notekeep.local.data.AppDatabase
import com.notekeep.local.data.Note
import com.notekeep.local.databinding.ActivityMainBinding
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var adapter: NoteAdapter
    private var allNotes: List<Note> = emptyList()
    /** noteId -> lowercased names of the categories/labels assigned to it (set from the note's
     * three-dot menu, never from "#" inside the text) - kept separate from tags so a `section:`
     * search can never accidentally match a `tag:`, and vice versa. Refreshed alongside notes. */
    private var labelNamesByNoteId: Map<Long, List<String>> = emptyMap()

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayShowTitleEnabled(true)
        binding.toolbar.setNavigationOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }

        adapter = NoteAdapter(
            onClick = { note ->
                val intent = Intent(this, NoteEditActivity::class.java)
                intent.putExtra(NoteEditActivity.EXTRA_NOTE_ID, note.id)
                startActivity(intent)
            },
            onTogglePin = { note -> togglePin(note) }
        )

        binding.recyclerNotes.layoutManager =
            StaggeredGridLayoutManager(2, StaggeredGridLayoutManager.VERTICAL)
        binding.recyclerNotes.adapter = adapter

        binding.fabAdd.setOnClickListener {
            startActivity(Intent(this, NoteEditActivity::class.java))
        }

        val dao = AppDatabase.getInstance(applicationContext).noteDao()
        val labelDao = AppDatabase.getInstance(applicationContext).labelDao()
        lifecycleScope.launch {
            dao.observeAll().collectLatest { notes ->
                allNotes = notes
                refreshLabelLookup(notes)
                submit(applyActiveQuery(notes))
            }
        }
        // Kept as its own collector (rather than folded into a combine{}) so a label being
        // renamed/created/deleted refreshes section: search results right away, without needing
        // the note list itself to change first.
        lifecycleScope.launch {
            labelDao.observeAll().collectLatest {
                refreshLabelLookup(allNotes)
                submit(applyActiveQuery(allNotes))
            }
        }
    }

    private suspend fun refreshLabelLookup(notes: List<Note>) {
        if (notes.isEmpty()) {
            labelNamesByNoteId = emptyMap()
            return
        }
        val labelDao = AppDatabase.getInstance(applicationContext).labelDao()
        val labelsById = labelDao.getAllOnce().associateBy { it.id }
        val crossRefs = labelDao.getAllCrossRefsOnce()
        labelNamesByNoteId = crossRefs
            .groupBy({ it.noteId }, { it.labelId })
            .mapValues { (_, labelIds) ->
                labelIds.mapNotNull { labelsById[it]?.name?.trim()?.lowercase() }
            }
    }

    private fun togglePin(note: Note) {
        lifecycleScope.launch {
            AppDatabase.getInstance(applicationContext).noteDao().setPinned(note.id, !note.pinned)
        }
    }

    private fun submit(notes: List<Note>) {
        adapter.submitList(buildListItems(notes))
        binding.emptyView.visibility =
            if (notes.isEmpty()) android.view.View.VISIBLE else android.view.View.GONE
    }

    private fun buildListItems(notes: List<Note>): List<NoteListItem> {
        val pinned = notes.filter { it.pinned }
        val others = notes.filter { !it.pinned }
        val items = mutableListOf<NoteListItem>()
        if (pinned.isNotEmpty()) {
            items.add(NoteListItem.Header(getString(R.string.section_pinned)))
            items.addAll(pinned.map { NoteListItem.Item(it) })
            if (others.isNotEmpty()) {
                items.add(NoteListItem.Header(getString(R.string.section_others)))
            }
        }
        items.addAll(others.map { NoteListItem.Item(it) })
        return items
    }

    override fun onCreateOptionsMenu(menu: android.view.Menu): Boolean {
        menuInflater.inflate(R.menu.menu_main, menu)

        val searchItem = menu.findItem(R.id.action_search)
        val searchView = searchItem.actionView as SearchView
        searchView.queryHint = getString(R.string.hint_search)
        searchView.setOnQueryTextListener(object : SearchView.OnQueryTextListener {
            override fun onQueryTextSubmit(query: String?) = false
            override fun onQueryTextChange(newText: String?): Boolean {
                activeQuery = newText.orEmpty()
                submit(applyActiveQuery(allNotes))
                return true
            }
        })
        return true
    }

    private var activeQuery: String = ""

    /**
     * Four independent search modes, matched strictly against their own field so they never
     * bleed into one another:
     *  - a plain word (no prefix): notes whose TITLE contains it
     *  - `tag:name` : notes carrying that exact #hashtag (set from inside the note's own text
     *    with "#") - never a category
     *  - `section:name` : notes assigned that exact category/label (set from the note's
     *    three-dot menu → "التصنيفات") - never a tag
     *  - `line:word` : notes whose BODY CONTENT contains that word
     */
    private val typedQueryRegex = Regex("^(tag|section|line):(.*)$", RegexOption.IGNORE_CASE)

    private fun applyActiveQuery(notes: List<Note>): List<Note> {
        val raw = activeQuery.trim()
        if (raw.isEmpty()) return notes

        val match = typedQueryRegex.find(raw)
        if (match == null) {
            // Plain word: title only, substring match (kept case-insensitive/substring, unlike
            // the graph's whole-word rule, to match this screen's existing quick-filter feel).
            return notes.filter { it.title.contains(raw, ignoreCase = true) }
        }

        val type = match.groupValues[1].lowercase()
        val value = match.groupValues[2].trim()
        if (value.isEmpty()) return notes

        return when (type) {
            "tag" -> {
                val needle = value.lowercase()
                notes.filter { note ->
                    note.extractTags().any { it.removePrefix("#").trim().lowercase() == needle }
                }
            }
            "section" -> {
                val needle = value.lowercase()
                notes.filter { note ->
                    labelNamesByNoteId[note.id].orEmpty().any { it == needle }
                }
            }
            "line" -> notes.filter { it.content.contains(value, ignoreCase = true) }
            else -> notes
        }
    }
}
