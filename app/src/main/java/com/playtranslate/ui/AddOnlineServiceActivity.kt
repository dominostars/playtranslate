package com.playtranslate.ui

import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import com.google.android.material.appbar.MaterialToolbar
import com.playtranslate.R
import com.playtranslate.applyEdgeToEdge
import com.playtranslate.applyTheme
import com.playtranslate.translation.OnlineBackendFactory
import com.playtranslate.translation.OnlineServiceInstance
import com.playtranslate.translation.OnlineServiceMutations
import com.playtranslate.translation.OnlineServiceStore
import com.playtranslate.translation.OpenAiPreset
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
        val card = PtGroupCard(this)
        val rowContainer: LinearLayout = card

        val services = CATALOG.map { type ->
            ServiceRow(
                title = OnlineBackendFactory.typeDisplayName(this, type),
                // OpenAI's row names the providers it reaches on its own
                // line; every other service's row is just its own name.
                providers = if (type == ServiceType.OPENAI) openAiPresetList(this) else null,
                // Same requirement, same words as the service's cell on the
                // services page once it's added — both read ServiceType.account.
                subtitle = getString(type.account.labelRes),
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

    private data class ServiceRow(
        val title: String,
        /** The providers this row reaches beyond its own name (OpenAI's
         *  presets); null for services that only stand for themselves. */
        val providers: String?,
        val subtitle: String,
        val type: ServiceType,
    )

    private fun buildServiceRow(container: ViewGroup, row: ServiceRow): View {
        val view = LayoutInflater.from(this)
            .inflate(R.layout.item_add_online_service, container, false)
        view.findViewById<TextView>(R.id.tvRowTitle).text = row.title
        view.findViewById<TextView>(R.id.tvLlmBadge).isVisible = row.type.isLlm
        view.findViewById<TextView>(R.id.tvRowProviders).apply {
            text = row.providers.orEmpty()
            isVisible = row.providers != null
        }
        view.findViewById<TextView>(R.id.tvRowSubtitle).text = row.subtitle
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

        /** The providers OpenAI reaches beyond itself — "DeepSeek, Mistral,
         *  Groq, OpenRouter, Custom". They have no catalog entry of their
         *  own; they live behind its Provider setting, so without naming
         *  them a user hunting for Mistral would find nothing. Read off
         *  [OpenAiPreset] (minus OPENAI, which is the row's own name), so
         *  adding a preset there surfaces it in the UI with no string to
         *  remember.
         *
         *  This picker gives the list its own line under the OpenAI row's
         *  title; the services page folds it into [catalogTitle]. */
        fun openAiPresetList(context: Context): String =
            OpenAiPreset.entries
                .filter { it != OpenAiPreset.OPENAI }
                .joinToString(", ") { OnlineBackendFactory.presetDisplayName(context, it) }

        /** The name a catalog service goes by in the services page's Add-row
         *  subtitle, which names the whole catalog on one line and so has to
         *  carry OpenAI's providers inside its own entry: "OpenAI (DeepSeek,
         *  Mistral, …), Gemini, DeepL, Lingva". This picker has room to give
         *  that list a line of its own, so its rows title themselves with the
         *  plain [OnlineBackendFactory.typeDisplayName] instead. */
        fun catalogTitle(context: Context, type: ServiceType): String = when (type) {
            ServiceType.OPENAI -> context.getString(
                R.string.add_service_openai_providers_fmt,
                OnlineBackendFactory.typeDisplayName(context, ServiceType.OPENAI),
                openAiPresetList(context),
            )
            else -> OnlineBackendFactory.typeDisplayName(context, type)
        }
    }
}
