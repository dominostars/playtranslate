package com.playtranslate.ui

import android.content.Context
import android.content.res.ColorStateList
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.card.MaterialCardView
import com.playtranslate.R
import com.playtranslate.applyEdgeToEdge
import com.playtranslate.applyTheme
import com.playtranslate.themeColor
import com.playtranslate.translation.OnlineBackendFactory
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

        val services = CATALOG.map { type ->
            ServiceRow(
                title = catalogTitle(this, type),
                subtitle = when (type) {
                    ServiceType.DEEPL -> getString(R.string.add_service_sub_requires_key_free)
                    ServiceType.LINGVA -> getString(R.string.add_service_sub_no_key)
                    else -> getString(R.string.add_service_sub_requires_key)
                },
                type = type,
            )
        }
        services.forEachIndexed { idx, row ->
            if (idx > 0) {
                rowContainer.addView(
                    inflater.inflate(R.layout.settings_row_divider, rowContainer, false)
                )
            }
            rowContainer.addView(buildServiceRow(rowContainer, row))
        }
        parent.addView(card)
    }

    private data class ServiceRow(val title: String, val subtitle: String, val type: ServiceType)

    private fun buildServiceRow(container: ViewGroup, row: ServiceRow): View {
        val view = LayoutInflater.from(this)
            .inflate(R.layout.language_list_row, container, false)
        view.findViewById<TextView>(R.id.tvRowTitle).text = row.title
        view.findViewById<TextView>(R.id.tvRowEndonym).apply {
            text = row.subtitle
            isVisible = true
        }
        // Repurpose the row's trailing slot as a passive chevron (the
        // model picker does the same for its check icon).
        val trailing = view.findViewById<FrameLayout>(R.id.btnDelete)
        trailing.isVisible = true
        trailing.isClickable = false
        trailing.isFocusable = false
        trailing.foreground = null
        trailing.contentDescription = null
        view.findViewById<ImageView>(R.id.ivDeleteIcon).apply {
            setImageResource(R.drawable.ic_chevron_right)
            imageTintList = ColorStateList.valueOf(themeColor(R.attr.ptTextMuted))
        }
        view.setOnClickListener { onServicePicked(row.type) }
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

        /** Every service this picker offers, in the order it offers them —
         *  OpenAI leads because its provider preset (DeepSeek / custom
         *  endpoints) makes it the entry most users are here for. Also the
         *  order the services page's Add row names them in
         *  ([OnlineServicesController]), so a new service surfaces in both
         *  places at once. */
        val CATALOG = listOf(
            ServiceType.OPENAI,
            ServiceType.GEMINI,
            ServiceType.DEEPL,
            ServiceType.LINGVA,
        )

        /** The name a catalog service goes by wherever we offer it — this
         *  picker's rows and the services page's Add-row subtitle. OpenAI
         *  advertises its provider preset ("OpenAI (Customizable)"); the
         *  rest are just their brand. */
        fun catalogTitle(context: Context, type: ServiceType): String = when (type) {
            ServiceType.OPENAI -> context.getString(R.string.add_service_openai_customizable)
            else -> OnlineBackendFactory.typeDisplayName(context, type)
        }
    }
}
