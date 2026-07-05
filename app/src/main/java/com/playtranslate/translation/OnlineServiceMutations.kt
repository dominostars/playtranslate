package com.playtranslate.translation

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import com.playtranslate.CaptureService

/**
 * The canonical mutation recipes for online service instances. Every UI
 * path (config-page save, services-page toggle/reorder/delete, picker
 * quick-add) goes through here so the four moving parts stay in sync:
 *
 *   store write → registry membership → setOrder → CaptureService
 *   reconcile (and a translation-cache clear when the change can alter
 *   what a given input translates to).
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
        applyStoreOrder()
        CaptureService.instance?.clearTranslationCache()
        CaptureService.instance?.reconcileBackendPreference()
    }

    /** Instant add with no config page (Lingva from the picker). */
    fun addInstance(context: Context, instance: OnlineServiceInstance) {
        OnlineServiceStore.add(instance)
        TranslationBackendRegistry.addOnlineBackend(
            OnlineBackendFactory.build(context, sharedPrefs(context), instance)
        )
        applyStoreOrder()
        CaptureService.instance?.reconcileBackendPreference()
    }

    /** Switch toggle on the services page. Backend closures read the
     *  store per call, so no registry churn — just the cache-identity
     *  reconcile. */
    fun setEnabled(id: String, enabled: Boolean) {
        OnlineServiceStore.setEnabled(id, enabled)
        CaptureService.instance?.reconcileBackendPreference()
    }

    /** Model picked for an LLM instance. Output changes → cache clear. */
    fun setModel(id: String, model: String) {
        val instance = OnlineServiceStore.byId(id) ?: return
        OnlineServiceStore.update(instance.copy(model = model))
        CaptureService.instance?.clearTranslationCache()
        CaptureService.instance?.reconcileBackendPreference()
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
        CooldownState(context, id).recordSuccess(System.currentTimeMillis())
        sharedPrefs(context).edit {
            remove("usage_${id}_day")
            remove("usage_${id}_tokens")
        }
        applyStoreOrder()
        CaptureService.instance?.reconcileBackendPreference()
    }

    /** Persist the edit-mode drag order. List order = waterfall priority. */
    fun reorder(orderedIds: List<String>) {
        OnlineServiceStore.reorder(orderedIds)
        applyStoreOrder()
        CaptureService.instance?.reconcileBackendPreference()
    }

    private fun applyStoreOrder() =
        TranslationBackendRegistry.setOrder(OnlineServiceStore.all().map { it.id })

    private fun sharedPrefs(context: Context): SharedPreferences =
        context.applicationContext.getSharedPreferences("playtranslate_prefs", Context.MODE_PRIVATE)
}
