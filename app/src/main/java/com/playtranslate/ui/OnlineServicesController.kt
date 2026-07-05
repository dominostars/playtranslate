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
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.materialswitch.MaterialSwitch
import com.playtranslate.R
import com.playtranslate.blendColors
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
 * "Add Online Translation Service" row, the RecyclerView of
 * [OnlineServiceInstance] cells (list order = waterfall priority), and
 * the toolbar-driven edit mode (drag-to-reorder + delete).
 *
 * Cell behavior:
 *  - switch tap → enable/disable the instance directly (no config page)
 *  - row tap → open the instance's config page (no-op for Lingva)
 *  - edit mode → switch swaps to a trash icon (confirm via OverlayAlert),
 *    drag handle appears, the Add row hides; DONE persists the order
 *
 * Status/cooldown rendering carried over from the legacy
 * TranslationServicesBinder verbatim, now bound per instance via
 * [TranslationBackendRegistry.byId]. The host activity calls [rebind]
 * on resume (persist-to-prefs + onResume-refresh navigation idiom).
 */
class OnlineServicesController(
    private val activity: Activity,
    root: View,
    private val lifecycleScope: CoroutineScope,
) {
    private val rowAdd: View = root.findViewById(R.id.rowAddOnlineService)
    private val dividerAdd: View = root.findViewById(R.id.dividerAddOnlineService)
    private val recycler: RecyclerView = root.findViewById(R.id.rvOnlineServices)

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
        rowAdd.setOnClickListener {
            activity.startActivity(Intent(activity, AddOnlineServiceActivity::class.java))
        }
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
        OnlineServiceMutations.reorder(working.map { it.id })
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
            .hideIcon()
            .setTitle(
                activity.getString(
                    R.string.tr_service_remove_title_fmt,
                    OnlineBackendFactory.displayName(activity, instance),
                )
            )
            .setMessage(activity.getString(R.string.tr_service_remove_message))
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

            val key = OnlineServiceStore.readKey(instance.id)
            if (key.isBlank()) {
                holder.keyTail.isGone = true
            } else {
                holder.keyTail.text = activity.getString(
                    R.string.tr_service_key_tail_fmt, key.takeLast(4),
                )
                holder.keyTail.isVisible = true
            }

            if (backend != null) {
                renderBackendStatusLine(holder.itemView, backend.status)
                renderBackendCooldownLine(holder.itemView, backend)
            } else {
                holder.itemView.findViewById<TextView>(R.id.tvRowSubtitle2).isGone = true
                holder.itemView.findViewById<TextView>(R.id.tvRowSubtitle3).isGone = true
                clearRowWarningTint(holder.rowMain)
            }

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
            OnlineServiceMutations.setEnabled(instance.id, checked)
            working[position] = instance.copy(enabled = checked)
            // Rebind shows/hides the model sub-row with the new state.
            notifyItemChanged(position)
        }

        /** The inline "Model" sub-cell: LLM instances only, and only once
         *  enabled (i.e. a key has been saved — the picker needs a real
         *  key to call /models with). */
        private fun bindModelRow(holder: VH, instance: OnlineServiceInstance) {
            val isLlm = instance.type == ServiceType.GEMINI || instance.type == ServiceType.OPENAI
            if (!isLlm || !instance.enabled) {
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
                tv.text = status.text
                applyTone(tv, status.tone)
                applyItalic(tv, status.italic)
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
        val description = cooldownable.unavailableDescription()
            ?: activity.getString(R.string.backend_status_unavailable_default)
        tv.text = formatCooldownLine(description, until)
        applyTone(tv, Tone.Warning)
        applyItalic(tv, false)
        tv.isVisible = true
        applyRowWarningTint(rowMain)
    }

    /** "Rate limited · Retry at 3:42 PM" for short cooldowns;
     *  "Monthly quota used · Retry on Jun 1" for ones more than ~24h
     *  out. Time uses the user's locale TimeFormat; date uses a fixed
     *  "MMM d" so the line stays readable. */
    private fun formatCooldownLine(description: String, retryAt: Long): String {
        val now = System.currentTimeMillis()
        val withinDay = retryAt - now < 24L * 60 * 60 * 1000
        val formatted = if (withinDay) {
            android.text.format.DateFormat.getTimeFormat(activity).format(Date(retryAt))
        } else {
            SimpleDateFormat("MMM d", Locale.getDefault()).format(Date(retryAt))
        }
        val word = if (withinDay) activity.getString(R.string.backend_cooldown_retry_at)
                   else activity.getString(R.string.backend_cooldown_retry_on)
        return activity.getString(R.string.backend_cooldown_status_fmt, description, word, formatted)
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
