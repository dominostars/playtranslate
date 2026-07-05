package com.playtranslate.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isGone
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.card.MaterialCardView
import com.playtranslate.R
import com.playtranslate.applyEdgeToEdge
import com.playtranslate.applyTheme
import com.playtranslate.translation.OnlineServiceInstance
import com.playtranslate.translation.OnlineServiceMutations
import com.playtranslate.translation.OnlineServiceStore
import com.playtranslate.translation.ServiceType
import java.util.UUID

/**
 * The add-service picker: every online service a user can add an
 * instance of (the same service any number of times, each with its own
 * key). DeepSeek is deliberately absent — it's reached through OpenAI's
 * provider preset.
 *
 * Dismiss-both-on-save mechanics: tapping a keyed service generates
 * [pendingNewId] up front and launches its config page in CREATE mode.
 * The config page writes the instance under that id only on a
 * successful save, so this screen's [onResume] watches for the id's
 * appearance in [OnlineServiceStore] — present → the save happened,
 * finish() (landing back on the services page, which rebinds in its own
 * onResume); absent → the user backed out, stay open. Watching the
 * specific id (not a list count) keeps back-out and repeat-add robust.
 *
 * Lingva has nothing to configure, so it's added enabled immediately.
 */
class AddOnlineServiceActivity : AppCompatActivity() {

    private var pendingNewId: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        applyTheme(this)
        applyEdgeToEdge(this)
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_add_online_service)
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(android.R.id.content)) { v, insets ->
            val sys = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            v.setPadding(sys.left, sys.top, sys.right, sys.bottom)
            WindowInsetsCompat.CONSUMED
        }

        pendingNewId = savedInstanceState?.getString(KEY_PENDING_ID)

        findViewById<MaterialToolbar>(R.id.toolbar).setNavigationOnClickListener { finish() }

        renderServiceList(findViewById(R.id.serviceListContainer))
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(KEY_PENDING_ID, pendingNewId)
    }

    override fun onResume() {
        super.onResume()
        val pending = pendingNewId ?: return
        if (OnlineServiceStore.byId(pending) != null) finish()
    }

    private fun renderServiceList(parent: LinearLayout) {
        parent.removeAllViews()
        val inflater = LayoutInflater.from(this)
        val card = inflater.inflate(R.layout.language_list_section, parent, false) as MaterialCardView
        val rowContainer = card.findViewById<LinearLayout>(R.id.sectionRows)

        val services = listOf(
            getString(R.string.gemini_display_name) to ServiceType.GEMINI,
            getString(R.string.openai_display_name) to ServiceType.OPENAI,
            getString(R.string.deepl_display_name) to ServiceType.DEEPL,
            getString(R.string.lingva_display_name) to ServiceType.LINGVA,
        )
        services.forEachIndexed { idx, (label, type) ->
            if (idx > 0) {
                rowContainer.addView(
                    inflater.inflate(R.layout.settings_row_divider, rowContainer, false)
                )
            }
            rowContainer.addView(buildServiceRow(rowContainer, label, type))
        }
        parent.addView(card)
    }

    private fun buildServiceRow(container: ViewGroup, label: String, type: ServiceType): View {
        val view = LayoutInflater.from(this)
            .inflate(R.layout.language_list_row, container, false)
        view.findViewById<TextView>(R.id.tvRowTitle).text = label
        view.findViewById<TextView>(R.id.tvRowEndonym).isGone = true
        view.findViewById<FrameLayout>(R.id.btnDelete).isGone = true
        view.setOnClickListener { onServicePicked(type) }
        return view
    }

    private fun onServicePicked(type: ServiceType) {
        when (type) {
            ServiceType.LINGVA -> {
                // No config page: add enabled immediately and dismiss.
                OnlineServiceMutations.addInstance(
                    this,
                    OnlineServiceInstance(
                        id = UUID.randomUUID().toString(),
                        type = ServiceType.LINGVA,
                        enabled = true,
                    ),
                )
                finish()
            }
            ServiceType.DEEPL -> {
                val id = UUID.randomUUID().toString()
                pendingNewId = id
                startActivity(DeepLSettingsActivity.newIntent(this, id))
            }
            ServiceType.GEMINI, ServiceType.OPENAI -> {
                val id = UUID.randomUUID().toString()
                pendingNewId = id
                startActivity(LlmBackendSettingsActivity.createIntent(this, id, type))
            }
        }
    }

    companion object {
        private const val KEY_PENDING_ID = "pending_new_id"
    }
}
