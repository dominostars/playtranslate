package com.playtranslate.ui

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.annotation.StringRes
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.materialswitch.MaterialSwitch
import com.playtranslate.BuildConfig
import com.playtranslate.R
import com.playtranslate.blendColors
import com.playtranslate.cloud.PtCloudService
import com.playtranslate.themeColor
import com.playtranslate.translation.BackendStatus
import com.playtranslate.translation.Cooldownable
import com.playtranslate.translation.OnlineBackendFactory
import com.playtranslate.translation.OnlineServiceInstance
import com.playtranslate.translation.OnlineServiceMutations
import com.playtranslate.translation.OnlineServiceStore
import com.playtranslate.translation.ServiceType
import com.playtranslate.translation.Tone
import com.playtranslate.translation.TranslationBackend
import com.playtranslate.translation.TranslationBackendRegistry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import androidx.core.view.isGone
import androidx.core.view.isVisible

/**
 * Owns the Online card on the Translation services page: the fixed
 * "Add Online Translation Service" row, PlayTranslate Cloud's fixed row
 * ([bindCloudRow]), the RecyclerView of [OnlineServiceInstance] cells
 * (list order = waterfall priority), and the toolbar-driven edit mode
 * (drag-to-reorder + delete).
 *
 * Cell behavior:
 *  - switch tap → enable/disable the instance directly (no config page)
 *  - row tap → open the instance's config page (no-op for Lingva)
 *  - edit mode → switch swaps to a trash icon (confirm via OverlayAlert),
 *    drag handle appears, the Add row hides; DONE persists the order
 *
 * Status/cooldown rendering carried over from the legacy
 * TranslationServicesBinder verbatim, now bound per instance via
 * [TranslationBackendRegistry.byId]. The host activity calls [rebind],
 * then [bindCloudRow], on resume (persist-to-prefs + onResume-refresh
 * navigation idiom).
 */
class OnlineServicesController(
    private val activity: Activity,
    root: View,
    private val lifecycleScope: CoroutineScope,
) {
    private val rowAdd: View = root.findViewById(R.id.rowAddOnlineService)
    private val dividerAdd: View = root.findViewById(R.id.dividerAddOnlineService)
    private val recycler: RecyclerView = root.findViewById(R.id.rvOnlineServices)

    /** PlayTranslate Cloud's row ([bindCloudRow]), null outside debug
     *  builds, where it is hidden once and never bound: the service's switch
     *  reads false there ([com.playtranslate.Prefs.ptCloudTranslateEnabled]) and its
     *  page, like the Settings cell that opens it, exists only in debug
     *  builds. */
    private val cloudRow: View? = root.findViewById<View>(R.id.rowPtCloud).let { row ->
        if (BuildConfig.DEBUG) {
            row
        } else {
            row.isGone = true
            null
        }
    }

    private val working = mutableListOf<OnlineServiceInstance>()
    private val adapter = OnlineServiceAdapter()
    private var itemTouchHelper: ItemTouchHelper? = null

    /** Per-instance in-flight `refreshStatus` job. Single-flighted so a
     *  slow request can't overwrite the result of a faster, newer one. */
    private val backendRefreshJobs = mutableMapOf<String, Job>()

    var isEditing = false
        private set

    init {
        recycler.layoutManager = LinearLayoutManager(activity)
        recycler.adapter = adapter
        rowAdd.findViewById<TextView>(R.id.tvRowSubtitle).text =
            AddOnlineServiceActivity.CATALOG.joinToString(", ") {
                AddOnlineServiceActivity.catalogTitle(activity, it)
            }
        rowAdd.setOnClickListener {
            activity.startActivity(Intent(activity, AddOnlineServiceActivity::class.java))
        }
        cloudRow?.let { row ->
            // What never changes: its name, the LLM chip (tier one is an
            // LLM), no model sub-cell (the server picks the model), no drag
            // handle or trash in edit mode either (it is pinned first and
            // cannot be removed), and a tap opens its own page, in edit
            // mode too, since an edit changes nothing on it.
            row.findViewById<TextView>(R.id.tvRowTitle).setText(R.string.pt_cloud_display_name)
            row.findViewById<View>(R.id.tvLlmBadge).isVisible = true
            row.findViewById<View>(R.id.sectionModel).isGone = true
            row.findViewById<View>(R.id.ivDragHandle).isGone = true
            row.findViewById<View>(R.id.ivDelete).isGone = true
            row.findViewById<View>(R.id.rowMain).setOnClickListener {
                activity.startActivity(Intent(activity, PtCloudSettingsActivity::class.java))
            }
        }
    }

    /**
     * Renders PlayTranslate Cloud's row, the first in the card as the cloud
     * is first in the waterfall
     * ([com.playtranslate.translation.OnlineServiceMutations.applyOnlineOrder]).
     * It is no store instance: its id is fixed, its settings live on its own
     * page, and nothing about it can be dragged or deleted, so edit mode
     * leaves it as it is. The host calls this on every resume, after
     * [rebind] and while editing too, where [rebind] returns early to keep
     * the drag order: a return from the cloud page during an edit shows what
     * changed there.
     *
     * Through the store rows' own renderers: the key's tail from the
     * service's settings ([PtCloudService.config]); the status line ("Account
     * required" with no key) and the cooldown line and tint from the
     * registry's backend, whose cooldown is the service's at the moment of
     * the read. The switch is the service's TRANSLATION switch, as every
     * row's switch on this page means "translate with this" (its OCR switch
     * lives with the OCR engines, on the Capture & overlay page), and writes
     * through its one mutation, [PtCloudService.setTranslateEnabled]; the mutation announces
     * itself ([PtCloudService.changes]) and the host repaints the row from
     * that, so nothing here repaints after it.
     */
    fun bindCloudRow() {
        val row = cloudRow ?: return
        val config = PtCloudService.config
        bindKeyTail(row.findViewById(R.id.tvKeyTail), config.clientKey)
        renderBackendLines(row, TranslationBackendRegistry.byId(PtCloudService.ID))
        // Listener cleared before setChecked, as on the store rows, so a
        // render never fires a toggle.
        val switch = row.findViewById<MaterialSwitch>(R.id.switchRowToggle)
        switch.setOnCheckedChangeListener(null)
        // No key saved: the switch shows off and is not a tap target of its
        // own, so a tap on it falls through to rowMain and opens the cloud
        // page, where the key goes; nothing is written here
        // (2026-10-25). With a key it flips in place.
        val hasKey = config.hasKey
        switch.isClickable = hasKey
        switch.isChecked = hasKey && config.translateEnabled
        switch.setOnCheckedChangeListener { _, checked -> PtCloudService.setTranslateEnabled(checked) }
        // The card's rule for its last row (the adapter's, below): no
        // divider above the footer. This row is the last one only when the
        // store holds no instance.
        row.findViewById<View>(R.id.rowDivider).isGone = working.isEmpty()
    }

    /** Reload the instance list from the store and re-render. Skipped in
     *  edit mode so a resume can't clobber an in-progress drag order. */
    @SuppressLint("NotifyDataSetChanged")
    fun rebind() {
        if (isEditing) return
        working.clear()
        working.addAll(OnlineServiceStore.all())
        adapter.notifyDataSetChanged()
        refreshStatuses()
    }

    /** Flip edit mode; returns the new state so the caller can restyle
     *  its toolbar action (Edit ⇄ Done). */
    fun toggleEditMode(): Boolean {
        if (isEditing) exitEditMode() else enterEditMode()
        return isEditing
    }

    @SuppressLint("NotifyDataSetChanged")
    private fun enterEditMode() {
        isEditing = true
        rowAdd.isGone = true
        dividerAdd.isGone = true
        itemTouchHelper = ItemTouchHelper(dragCallback).also {
            it.attachToRecyclerView(recycler)
        }
        adapter.notifyDataSetChanged()
    }

    /** Exit + persist: one reorder commit for the whole session
     *  (RegionPickerSheet persist-on-exit idiom). */
    @SuppressLint("NotifyDataSetChanged")
    private fun exitEditMode() {
        isEditing = false
        rowAdd.isVisible = true
        dividerAdd.isVisible = true
        itemTouchHelper?.attachToRecyclerView(null)
        itemTouchHelper = null
        OnlineServiceMutations.reorder(activity, working.map { it.id })
        adapter.notifyDataSetChanged()
    }

    /** Kick an async status refresh per instance; each row re-renders
     *  when its fresh status lands. */
    fun refreshStatuses() {
        for (instance in working.toList()) {
            val backend = TranslationBackendRegistry.byId(instance.id) ?: continue
            backendRefreshJobs[instance.id]?.cancel()
            backendRefreshJobs[instance.id] = lifecycleScope.launch {
                backend.refreshStatus()
                notifyInstanceChanged(instance.id)
            }
        }
    }

    private fun notifyInstanceChanged(id: String) {
        val pos = working.indexOfFirst { it.id == id }
        if (pos >= 0) adapter.notifyItemChanged(pos)
    }

    private val dragCallback = object : ItemTouchHelper.SimpleCallback(
        ItemTouchHelper.UP or ItemTouchHelper.DOWN, 0
    ) {
        override fun onMove(
            recyclerView: RecyclerView,
            viewHolder: RecyclerView.ViewHolder,
            target: RecyclerView.ViewHolder,
        ): Boolean {
            val from = viewHolder.bindingAdapterPosition
            val to = target.bindingAdapterPosition
            if (from == RecyclerView.NO_POSITION || to == RecyclerView.NO_POSITION) return false
            val item = working.removeAt(from)
            working.add(to, item)
            adapter.notifyItemMoved(from, to)
            return true
        }

        override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) {}

        override fun isLongPressDragEnabled() = false

        override fun clearView(recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder) {
            super.clearView(recyclerView, viewHolder)
            // Re-pin dividers: the dragged row may have become (or stopped
            // being) the last one. Persistence waits for DONE.
            adapter.notifyItemRangeChanged(0, adapter.itemCount)
        }
    }

    private fun confirmDelete(instance: OnlineServiceInstance) {
        OverlayAlert.Builder(activity)
            .setTitle(
                activity.getString(
                    R.string.tr_service_remove_title_fmt,
                    OnlineBackendFactory.displayName(activity, instance),
                )
            )
            .setMessage(activity.getString(removeConfirmationMessage(instance)))
            .addButton(
                activity.getString(R.string.tr_service_remove_confirm),
                activity.themeColor(R.attr.ptDanger),
                activity.themeColor(R.attr.ptAccentOn),
            ) {
                OnlineServiceMutations.delete(activity, instance.id)
                backendRefreshJobs.remove(instance.id)?.cancel()
                val pos = working.indexOfFirst { it.id == instance.id }
                if (pos >= 0) {
                    working.removeAt(pos)
                    adapter.notifyItemRemoved(pos)
                    adapter.notifyItemRangeChanged(0, adapter.itemCount)
                    // The cloud row may have become the card's last row.
                    bindCloudRow()
                }
            }
            .addCancelButton()
            .show()
    }

    private fun openConfig(instance: OnlineServiceInstance) {
        when (instance.type) {
            ServiceType.GEMINI, ServiceType.OPENAI ->
                activity.startActivity(LlmBackendSettingsActivity.editIntent(activity, instance.id))
            ServiceType.DEEPL ->
                activity.startActivity(DeepLSettingsActivity.newIntent(activity, instance.id))
            ServiceType.LINGVA -> Unit // nothing to configure
        }
    }

    // ── Adapter ───────────────────────────────────────────────────────────

    private inner class OnlineServiceAdapter :
        RecyclerView.Adapter<OnlineServiceAdapter.VH>() {

        inner class VH(view: View) : RecyclerView.ViewHolder(view) {
            val rowMain: View = view.findViewById(R.id.rowMain)
            val dragHandle: ImageView = view.findViewById(R.id.ivDragHandle)
            val title: TextView = view.findViewById(R.id.tvRowTitle)
            val llmBadge: TextView = view.findViewById(R.id.tvLlmBadge)
            val keyTail: TextView = view.findViewById(R.id.tvKeyTail)
            val switch: MaterialSwitch = view.findViewById(R.id.switchRowToggle)
            val delete: ImageView = view.findViewById(R.id.ivDelete)
            val sectionModel: View = view.findViewById(R.id.sectionModel)
            val rowModel: View = view.findViewById(R.id.rowModel)
            val divider: View = view.findViewById(R.id.rowDivider)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
            VH(
                LayoutInflater.from(parent.context)
                    .inflate(R.layout.item_online_service, parent, false)
            )

        override fun getItemCount(): Int = working.size

        @SuppressLint("ClickableViewAccessibility")
        override fun onBindViewHolder(holder: VH, position: Int) {
            val instance = working[position]
            val backend = TranslationBackendRegistry.byId(instance.id)

            holder.title.text = OnlineBackendFactory.displayName(activity, instance)
            holder.llmBadge.isVisible = instance.type.isLlm

            bindKeyTail(holder.keyTail, OnlineServiceStore.readKey(instance.id))
            renderBackendLines(holder.itemView, backend)

            // Switch ⇄ trash swap (edit mode). Listener is cleared before
            // setChecked so a recycled bind can't fire a phantom toggle.
            holder.switch.setOnCheckedChangeListener(null)
            holder.switch.isChecked = instance.enabled
            holder.switch.isGone = isEditing
            holder.switch.setOnCheckedChangeListener { _, checked ->
                onToggle(holder.bindingAdapterPosition, checked)
            }
            holder.delete.isVisible = isEditing
            holder.delete.setOnClickListener {
                val pos = holder.bindingAdapterPosition
                if (pos != RecyclerView.NO_POSITION) confirmDelete(working[pos])
            }

            holder.dragHandle.isVisible = isEditing
            holder.dragHandle.setOnTouchListener { _, event ->
                if (event.actionMasked == MotionEvent.ACTION_DOWN) {
                    itemTouchHelper?.startDrag(holder)
                }
                false
            }

            holder.rowMain.setOnClickListener {
                val pos = holder.bindingAdapterPosition
                if (pos != RecyclerView.NO_POSITION && !isEditing) openConfig(working[pos])
            }

            bindModelRow(holder, instance)

            holder.divider.isGone = position == working.size - 1
        }

        private fun onToggle(position: Int, checked: Boolean) {
            if (position == RecyclerView.NO_POSITION) return
            val instance = working[position]
            OnlineServiceMutations.setEnabled(activity, instance.id, checked)
            working[position] = instance.copy(enabled = checked)
            // Rebind shows/hides the model sub-row with the new state.
            notifyItemChanged(position)
        }

        /** The inline "Model" sub-cell: LLM instances only, and only once
         *  enabled (i.e. a key has been saved where the provider takes one —
         *  the picker needs a real key to call /models with; a keyless
         *  provider's catalog needs none). */
        private fun bindModelRow(holder: VH, instance: OnlineServiceInstance) {
            if (!instance.type.isLlm || !instance.enabled) {
                holder.sectionModel.isGone = true
                return
            }
            val model = instance.model.ifBlank {
                OnlineBackendFactory.defaultModelFor(instance.type, instance.preset)
            }
            applyModelRowChrome(holder.rowModel, model)
            holder.rowModel.setOnClickListener {
                activity.startActivity(LlmModelPickerActivity.newIntent(activity, instance.id))
            }
            holder.sectionModel.isVisible = true
        }
    }

    /** Apply the compact, muted styling to an inline "Model" sub-cell.
     *  The row's title IS the model name; the value column stays blank —
     *  only the chevron carries "tap to change" affordance. Rendered in
     *  the regular sans-serif weight tinted [R.attr.ptTextMuted] so the
     *  cell reads as a secondary annotation rather than a peer of the
     *  service row above it. */
    private fun applyModelRowChrome(row: View, modelName: String) {
        val title = row.findViewById<TextView>(R.id.tvRowTitle)
        title.text = modelName
        title.typeface = Typeface.SANS_SERIF
        title.setTextColor(activity.themeColor(R.attr.ptTextMuted))
        row.findViewById<TextView>(R.id.tvRowValue).text = ""
        val density = activity.resources.displayMetrics.density
        val hPad = activity.resources.getDimensionPixelSize(R.dimen.pt_row_h_padding)
        val vPad = (6 * density).toInt()
        row.setPaddingRelative(hPad, vPad, hPad, vPad)
        row.minimumHeight = (48 * density).toInt()
    }

    // ── Status + cooldown rendering (moved from TranslationServicesBinder) ──

    /** The masked key tail beside the title ("Key ••••4f2a"), the last four
     *  characters of [key]; hidden for a blank key. One rule for the store
     *  rows and the cloud's row. */
    private fun bindKeyTail(tv: TextView, key: String) {
        if (key.isBlank()) {
            tv.isGone = true
            return
        }
        tv.text = activity.getString(R.string.tr_service_key_tail_fmt, key.takeLast(4))
        tv.isVisible = true
    }

    /** The status and cooldown lines of an online service's row, with the
     *  cooldown's tint, from its registered [backend]; with none registered
     *  both lines hide and the tint clears. */
    private fun renderBackendLines(row: View, backend: TranslationBackend?) {
        if (backend != null) {
            renderBackendStatusLine(row, backend.status)
            renderBackendCooldownLine(row, backend)
        } else {
            row.findViewById<TextView>(R.id.tvRowSubtitle2).isGone = true
            row.findViewById<TextView>(R.id.tvRowSubtitle3).isGone = true
            clearRowWarningTint(row.findViewById(R.id.rowMain))
        }
    }

    /** Apply a [BackendStatus] to the row's secondary subtitle TextView,
     *  styling by tone and italic flag. The Loading state has its own
     *  generic text since backends don't supply transient text. */
    private fun renderBackendStatusLine(row: View, status: BackendStatus) {
        val tv = row.findViewById<TextView>(R.id.tvRowSubtitle2) ?: return
        when (status) {
            is BackendStatus.Hidden -> tv.isGone = true
            is BackendStatus.Loading -> {
                tv.text = activity.getString(R.string.tr_service_status_loading)
                applyTone(tv, Tone.Neutral)
                applyItalic(tv, true)
                tv.isVisible = true
            }
            is BackendStatus.Info -> {
                tv.text = status.resolve(activity)
                applyTone(tv, status.tone)
                applyItalic(tv, status.italic)
                tv.isVisible = true
            }
            is BackendStatus.Account -> {
                tv.text = activity.getString(status.requirement.labelRes)
                // Pinned Neutral: needing an account is configuration, not
                // alarm. A freshly-added service shouldn't look broken.
                applyTone(tv, Tone.Neutral)
                applyItalic(tv, false)
                tv.isVisible = true
            }
            is BackendStatus.Quota -> {
                tv.text = formatQuota(status)
                // Danger tone when the user has hit (or exceeded) their
                // limit for the period — translations will start failing
                // through to the next backend.
                val exhausted = status.used >= status.limit
                applyTone(tv, if (exhausted) Tone.Danger else Tone.Neutral)
                applyItalic(tv, false)
                tv.isVisible = true
            }
        }
    }

    /** Render (or hide) the cooldown line below the status line, plus the
     *  warning-tinted row background while a [Cooldownable] backend
     *  reports a future `retryAt`. */
    private fun renderBackendCooldownLine(row: View, backend: TranslationBackend) {
        val tv = row.findViewById<TextView>(R.id.tvRowSubtitle3) ?: return
        val rowMain = row.findViewById<View>(R.id.rowMain) ?: row
        val cooldownable = backend as? Cooldownable
        val until = cooldownable?.unavailableUntil()
        if (until == null) {
            tv.isGone = true
            clearRowWarningTint(rowMain)
            return
        }
        tv.text = cooldownLine(activity, cooldownable.unavailableDescription(), until)
        applyTone(tv, Tone.Warning)
        applyItalic(tv, false)
        tv.isVisible = true
        applyRowWarningTint(rowMain)
    }

    private fun applyRowWarningTint(row: View) {
        val baseCard = activity.themeColor(R.attr.ptCard)
        val warning = activity.themeColor(R.attr.ptWarning)
        val density = activity.resources.displayMetrics.density
        // GradientDrawable here (rather than the MaterialCardView recipe
        // used by applyUpdatePacksWarningTint) because the row is a
        // LinearLayout inside an already-rounded card — applying card
        // properties would target the wrong View. Foreground stays as
        // selectableItemBackground (XML default) so the ripple still
        // works over the tinted fill.
        row.background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            setColor(blendColors(warning, baseCard, 0.20f))
            setStroke((1 * density).toInt(), warning)
        }
    }

    private fun clearRowWarningTint(row: View) {
        row.background = null
    }

    private fun applyTone(tv: TextView, tone: Tone) {
        tv.setTextColor(activity.themeColor(toneAttr(tone)))
    }

    private fun toneAttr(tone: Tone): Int = when (tone) {
        Tone.Neutral -> R.attr.ptTextHint
        Tone.Warning -> R.attr.ptWarning
        Tone.Danger  -> R.attr.ptDanger
        Tone.Accent  -> R.attr.ptAccent
    }

    private fun applyItalic(tv: TextView, italic: Boolean) {
        // Pass null for the family so only the style flag changes;
        // otherwise re-styling a previously-italicised typeface can
        // leave residual italic-ness on platforms where the styled
        // typeface gets cached. This guarantees a clean toggle.
        tv.setTypeface(null, if (italic) Typeface.ITALIC else Typeface.NORMAL)
    }

    private fun formatQuota(q: BackendStatus.Quota): String {
        val used  = String.format(Locale.getDefault(), "%,d", q.used)
        val limit = String.format(Locale.getDefault(), "%,d", q.limit)
        val base  = activity.getString(R.string.tr_service_status_quota_fmt, used, limit)
        return q.resetEpochMs?.let { ms ->
            val date = SimpleDateFormat("MMM d", Locale.getDefault()).format(Date(ms))
            activity.getString(R.string.tr_service_status_quota_with_reset_fmt, base, date)
        } ?: base
    }
}

/** The delete confirmation's body. Only a service that takes a key has one
 *  to delete; Lingva and Index-Translate never stored one, so they are told
 *  only that the service leaves the list. */
@StringRes
internal fun removeConfirmationMessage(instance: OnlineServiceInstance): Int =
    if (instance.account.requiresKey) R.string.tr_service_remove_message
    else R.string.tr_service_remove_message_keyless
