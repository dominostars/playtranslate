package com.playtranslate.translation

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import com.playtranslate.cloud.PtCloudService

/**
 * The canonical mutation recipes for online service instances. Every UI
 * path (config-page save, services-page toggle/reorder/delete, picker
 * quick-add) goes through here so the four moving parts stay in sync:
 *
 *   store write → registry membership → setOrder ([applyOnlineOrder]) →
 *   the shared translation cache's reconcile ([GroupTranslator.reconcileShared],
 *   after a clear, [GroupTranslator.clearShared], when the change can alter
 *   what a given input translates to).
 *
 * Every user mutation of one instance also forgets its translation errors
 * ([TranslationErrors.forget]): saving its config, toggling it, picking a
 * model, or deleting it is the user saying "try this again", so a failure
 * after it shows its pill even if it is the one shown before. A provider's
 * thinking report ([applyReportedThinking]) is no such gesture and forgets
 * nothing.
 *
 * Every recipe that changes an instance ends by asking its provider for a
 * thinking report where it needs one ([ReportedThinkingRefresher.refreshIfNeeded]).
 *
 * All main-thread, like the registry's other mutators.
 */
object OnlineServiceMutations {

    /**
     * Create-or-update from a config-page save. Rebuilds the backend
     * (remove + add) rather than patching it: displayName and cooldown
     * participation are baked at construction and both follow the preset,
     * which only a config save can change. Key/model/URL changes alter
     * translation output → cache clear.
     */
    fun saveConfig(context: Context, instance: OnlineServiceInstance, key: String) {
        OnlineServiceStore.writeKey(instance.id, key)
        if (OnlineServiceStore.byId(instance.id) == null) {
            OnlineServiceStore.add(instance)
        } else {
            OnlineServiceStore.update(instance)
        }
        TranslationBackendRegistry.removeOnlineBackend(instance.id) // no-op on create
        TranslationBackendRegistry.addOnlineBackend(
            OnlineBackendFactory.build(context, sharedPrefs(context), instance)
        )
        applyOnlineOrder()
        TranslationErrors.forget(instance.id)
        GroupTranslator.clearShared()
        GroupTranslator.reconcileShared(context)
        // Through the backend just registered, where a new provider, model or
        // the service turned on leaves it without a report.
        ReportedThinkingRefresher.refreshIfNeeded(instance.id)
    }

    /** Instant add with no config page (Lingva from the picker). */
    fun addInstance(context: Context, instance: OnlineServiceInstance) {
        OnlineServiceStore.add(instance)
        TranslationBackendRegistry.addOnlineBackend(
            OnlineBackendFactory.build(context, sharedPrefs(context), instance)
        )
        applyOnlineOrder()
        GroupTranslator.reconcileShared(context)
        ReportedThinkingRefresher.refreshIfNeeded(instance.id)
    }

    /** Switch toggle on the services page. Backend closures read the
     *  store per call, so no registry churn — just the cache-identity
     *  reconcile. The flip also drops the instance's cooldown, in either
     *  direction: off-then-on is the user's "try it again now" gesture,
     *  and a cooldown on a disabled instance protects nothing. Reset
     *  before the reconcile so the preferred-backend identity is computed
     *  against the cleared state. */
    fun setEnabled(context: Context, id: String, enabled: Boolean) {
        OnlineServiceStore.setEnabled(id, enabled)
        TranslationBackendRegistry.resetCooldown(id)
        TranslationErrors.forget(id)
        GroupTranslator.reconcileShared(context)
        // The launch check skips a service that is off, so one turned on
        // later may have no report yet.
        ReportedThinkingRefresher.refreshIfNeeded(id)
    }

    /** Model picked for an LLM instance. Output changes → cache clear. The
     *  record's thinking report is about the old model and goes inert; the
     *  provider is asked about the new one. */
    fun setModel(context: Context, id: String, model: String) {
        val instance = OnlineServiceStore.byId(id) ?: return
        OnlineServiceStore.update(instance.copy(model = model))
        TranslationErrors.forget(id)
        GroupTranslator.clearShared()
        GroupTranslator.reconcileShared(context)
        ReportedThinkingRefresher.refreshIfNeeded(id)
    }

    /**
     * A provider's report about [report]'s model arrived
     * ([ReportedThinkingRefresher]). Stored only if [id] still has that model
     * ([OnlineServiceStore.setReportedThinking]). When it changes what the
     * instance's requests send (a first report, a provider that dropped a
     * level the user picked), every translation the shared cache holds was
     * made under the old request and is dropped, as a model change drops
     * them; a translation in flight across the clear is shown but not
     * cached ([com.playtranslate.TranslationCache]). The one writer of a
     * report.
     */
    fun applyReportedThinking(id: String, report: ReportedThinking) {
        val before = OnlineServiceStore.byId(id) ?: return
        OnlineServiceStore.setReportedThinking(id, report)
        val after = OnlineServiceStore.byId(id) ?: return
        if (OnlineBackendFactory.reasoningFieldsOf(after) != OnlineBackendFactory.reasoningFieldsOf(before)) {
            GroupTranslator.clearShared()
        }
    }

    /**
     * Delete an instance: store record + encrypted key slot (inside
     * [OnlineServiceStore.remove]), registry membership, and the id's
     * persisted cooldown + usage-meter state — so a later re-add of the
     * same id (only possible for the legacy-named instances) starts
     * clean instead of inheriting a weeks-old quota cooldown.
     */
    fun delete(context: Context, id: String) {
        OnlineServiceStore.remove(id)
        TranslationBackendRegistry.removeOnlineBackend(id)
        // A fresh CooldownState is right HERE (and only here): the live
        // backend was just deregistered, so the prefs mirror is the only
        // state left to clean.
        CooldownState(context, id).resetCooldown()
        sharedPrefs(context).edit {
            remove("usage_${id}_day")
            remove("usage_${id}_tokens")
        }
        TranslationErrors.forget(id)
        applyOnlineOrder()
        GroupTranslator.reconcileShared(context)
    }

    /** Persist the edit-mode drag order. List order = waterfall priority. */
    fun reorder(context: Context, orderedIds: List<String>) {
        OnlineServiceStore.reorder(orderedIds)
        applyOnlineOrder()
        GroupTranslator.reconcileShared(context)
    }

    /**
     * The waterfall's online order, the one writer of
     * [TranslationBackendRegistry.setOrder]: PlayTranslate Cloud pinned
     * first among the online services (2026-10-17), then the store's
     * instances in the user's list order; offline tiers follow by priority.
     * The cloud's place is its id at the head of the override, not its
     * priority: [TranslationBackendRegistry.orderedBackends] puts every
     * override id before any priority, and every store instance is in the
     * override, so no priority could put the cloud ahead of them. The id
     * stays in the list while the cloud is off or has no key; the waterfall
     * skips a backend that is not usable. Called by every mutation here that
     * changes membership or order (a save, an add, a delete, a reorder) and
     * once from [com.playtranslate.PlayTranslateApplication.onCreate].
     */
    fun applyOnlineOrder() =
        TranslationBackendRegistry.setOrder(listOf(PtCloudService.ID) + OnlineServiceStore.all().map { it.id })

    private fun sharedPrefs(context: Context): SharedPreferences =
        context.applicationContext.getSharedPreferences("playtranslate_prefs", Context.MODE_PRIVATE)
}
