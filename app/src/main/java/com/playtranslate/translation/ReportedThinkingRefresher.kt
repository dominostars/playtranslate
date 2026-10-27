package com.playtranslate.translation

import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

/**
 * Keeps each instance's [ReportedThinking] current: asks the provider's
 * catalog ([ThinkingReporter]) about the instance's model, and hands the
 * answer to [OnlineServiceMutations.applyReportedThinking], which stores it
 * and drops the translations made under the request it replaces.
 * [ReasoningControls] then reads it for the config page's row and every
 * request. Only DeepSeek, OpenRouter and Claude publish one
 * ([ThinkingCatalog.forPreset]).
 *
 * One rule decides whether to ask ([needsReport]): the instance is on, its
 * provider publishes a catalog, and its report is missing, about another
 * model, or a day old. It runs at launch for every instance
 * ([refreshAllIfNeeded]) and after every [OnlineServiceMutations] recipe that
 * changes an instance ([refreshIfNeeded]), so a model picked, a service
 * turned on or a page saved each ask when the answer can be missing, and a
 * new path that changes an instance asks by going through those recipes.
 * Never while translating; the launch check is the Yomitan auto-updater's
 * cadence (at launch, debounced, never polled). A failed ask keeps the
 * report the instance had.
 */
object ReportedThinkingRefresher {

    private const val TAG = "ReportedThinking"

    /** How old a report may get before it is asked for again. */
    private val STALE_MS = TimeUnit.HOURS.toMillis(24)

    /** The app's background scope ([com.playtranslate.PlayTranslateApplication.appScope]);
     *  null until [init], when nothing is asked. */
    @Volatile private var scope: CoroutineScope? = null

    /** Wall clock; a test seam. */
    @Volatile internal var now: () -> Long = { System.currentTimeMillis() }

    fun init(scope: CoroutineScope) {
        this.scope = scope
    }

    /** Asks about [instanceId]'s model in the background, if it
     *  [needsReport]. */
    fun refreshIfNeeded(instanceId: String) {
        val instance = OnlineServiceStore.byId(instanceId) ?: return
        if (needsReport(instance)) scope?.launch { refreshNow(instanceId) }
    }

    /** At launch: every instance that [needsReport]. */
    fun refreshAllIfNeeded() {
        OnlineServiceStore.all().forEach { refreshIfNeeded(it.id) }
    }

    /** The one rule for asking: [instance] is on, its provider publishes a
     *  catalog, and its report is missing, about another model, or a day
     *  old. */
    internal fun needsReport(instance: OnlineServiceInstance): Boolean {
        if (!instance.enabled || instance.type != ServiceType.OPENAI) return false
        if (ThinkingCatalog.forPreset(instance.preset) == null) return false
        val reported = instance.reportedThinking ?: return true
        return reported.model != OnlineBackendFactory.modelOf(instance) ||
            now() - reported.fetchedAtMs >= STALE_MS
    }

    /** The ask itself, for the instance's model as it stands now. The
     *  answer is applied on the main thread, as every
     *  [OnlineServiceMutations] recipe is, and only if the instance still
     *  has that model when it lands. */
    internal suspend fun refreshNow(instanceId: String) {
        val instance = OnlineServiceStore.byId(instanceId) ?: return
        val reporter = TranslationBackendRegistry.byId(instanceId) as? ThinkingReporter ?: return
        val model = OnlineBackendFactory.modelOf(instance)
        val facts = try {
            reporter.reportedThinking(model)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "report for ${instance.preset} failed, keeping the last: ${e.message}")
            return
        } ?: return
        val report = ReportedThinking(model, facts, now())
        withContext(Dispatchers.Main.immediate) { OnlineServiceMutations.applyReportedThinking(instanceId, report) }
    }
}
