package com.notekeep.local.ui

import android.content.Intent
import android.os.Bundle
import android.widget.EditText
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.notekeep.local.R
import com.notekeep.local.data.AppDatabase
import com.notekeep.local.data.Label
import com.notekeep.local.data.LabelWithCount
import com.notekeep.local.databinding.ActivityLabelsOverviewBinding
import kotlinx.coroutines.launch

/** Shows every label together with how many notes carry it; tapping one opens its notes,
 * shown the same way the archive screen shows archived notes. Each row's overflow menu can
 * also rename or delete the label. */
class LabelsOverviewActivity : AppCompatActivity() {

    private lateinit var binding: ActivityLabelsOverviewBinding
    private lateinit var adapter: LabelOverviewAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityLabelsOverviewBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)
        binding.toolbar.setNavigationOnClickListener { finish() }

        adapter = LabelOverviewAdapter(
            onClick = { label ->
                val intent = Intent(this, LabelNotesActivity::class.java)
                intent.putExtra(LabelNotesActivity.EXTRA_LABEL_ID, label.id)
                intent.putExtra(LabelNotesActivity.EXTRA_LABEL_NAME, label.name)
                startActivity(intent)
            },
            onRename = { label -> showRenameDialog(label) },
            onDelete = { label -> confirmDelete(label) }
        )
        binding.recyclerLabelsOverview.layoutManager = LinearLayoutManager(this)
        binding.recyclerLabelsOverview.adapter = adapter

        reload()
    }

    override fun onResume() {
        super.onResume()
        reload()
    }

    private fun reload() {
        lifecycleScope.launch {
            val includeArchived = com.notekeep.local.data.AppPrefs.showArchivedElsewhere(applicationContext)
            val labels = AppDatabase.getInstance(applicationContext).labelDao().getLabelsWithCounts(includeArchived)
            adapter.submitList(labels)
            binding.emptyView.visibility = if (labels.isEmpty()) android.view.View.VISIBLE else android.view.View.GONE
        }
    }

    private fun showRenameDialog(label: LabelWithCount) {
        val input = EditText(this)
        input.setText(label.name)
        input.setSelection(input.text.length)
        AlertDialog.Builder(this)
            .setTitle(R.string.labels_edit)
            .setView(input)
            .setPositiveButton(R.string.labels_done) { _, _ ->
                val newName = input.text.toString().trim()
                if (newName.isNotEmpty() && newName != label.name) {
                    lifecycleScope.launch {
                        val labelDao = AppDatabase.getInstance(applicationContext).labelDao()
                        labelDao.update(Label(id = label.id, name = newName))
                        reload()
                    }
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun confirmDelete(label: LabelWithCount) {
        AlertDialog.Builder(this)
            .setTitle(R.string.labels_delete_confirm)
            .setPositiveButton(R.string.delete_confirm_positive) { _, _ ->
                lifecycleScope.launch {
                    val labelDao = AppDatabase.getInstance(applicationContext).labelDao()
                    labelDao.clearAssignmentsForLabel(label.id)
                    labelDao.delete(Label(id = label.id, name = label.name))
                    reload()
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }
}
