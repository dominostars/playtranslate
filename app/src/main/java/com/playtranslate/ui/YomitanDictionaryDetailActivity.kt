package com.playtranslate.ui

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import com.google.android.material.appbar.MaterialToolbar
import com.playtranslate.R
import com.playtranslate.yomitan.YomitanDictionaryStore
import kotlinx.coroutines.launch

/**
 * Read-only detail view of an installed Yomitan dictionary's index.json
 * metadata. Opened from [YomitanSettingsActivity] by tapping a dictionary row.
 * Settings-detail styling, but the close affordance is an "X" toolbar action on
 * the right (not a left nav icon).
 */
class YomitanDictionaryDetailActivity : SettingsSubPageActivity() {

    override val layoutResId: Int = R.layout.activity_yomitan_dictionary_detail

    override fun onContentCreated(savedInstanceState: Bundle?) {
        val id = intent.getStringExtra(EXTRA_ID)
        if (id == null) {
            finish()
            return
        }

        // Back arrow lives in the toolbar's navigation slot; the base
        // SettingsSubPageActivity already wires it to finish().
        findViewById<MaterialToolbar>(R.id.toolbar).title =
            intent.getStringExtra(EXTRA_TITLE) ?: getString(R.string.yomitan_metadata_title)

        findViewById<View>(R.id.metadataHeader)
            .findViewById<TextView>(R.id.tvGroupTitle)
            .setText(R.string.yomitan_metadata_header)

        render(id)
    }

    private fun render(id: String) {
        val container = findViewById<LinearLayout>(R.id.metadataRows)
        val inflater = LayoutInflater.from(this)
        lifecycleScope.launch {
            val fields = YomitanDictionaryStore.readIndexJson(this@YomitanDictionaryDetailActivity, id)
            container.removeAllViews()
            if (fields.isNullOrEmpty()) {
                val row = inflater.inflate(R.layout.item_yomitan_metadata_row, container, false)
                row.findViewById<TextView>(R.id.tvMetaLabel).isVisible = false
                row.findViewById<TextView>(R.id.tvMetaValue)
                    .setText(R.string.yomitan_metadata_unavailable)
                row.findViewById<View>(R.id.metaRowDivider).isVisible = false
                container.addView(row)
                return@launch
            }
            fields.forEachIndexed { index, (key, value) ->
                val row = inflater.inflate(R.layout.item_yomitan_metadata_row, container, false)
                row.findViewById<TextView>(R.id.tvMetaLabel).text = key
                row.findViewById<TextView>(R.id.tvMetaValue).text = value
                row.findViewById<View>(R.id.metaRowDivider).isVisible = index < fields.size - 1
                container.addView(row)
            }
        }
    }

    companion object {
        private const val EXTRA_ID = "yomitan_dict_id"
        private const val EXTRA_TITLE = "yomitan_dict_title"

        fun intent(context: Context, id: String, title: String): Intent =
            Intent(context, YomitanDictionaryDetailActivity::class.java)
                .putExtra(EXTRA_ID, id)
                .putExtra(EXTRA_TITLE, title)
    }
}
