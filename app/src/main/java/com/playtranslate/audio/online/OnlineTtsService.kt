package com.playtranslate.audio.online

import android.content.Context
import androidx.annotation.VisibleForTesting
import com.playtranslate.Prefs
import com.playtranslate.PtJson
import com.playtranslate.audio.AudioCache
import com.playtranslate.audio.sources.OnlineTtsAudioSource
import com.playtranslate.language.SourceLangId
import com.playtranslate.translation.BackendFailure
import com.playtranslate.translation.BackendFailureKind
import com.playtranslate.tts.VoiceChoice

/**
 * The connected voice service: the one writer of its stored config and key,
 * the one reader of them, and the one place its lists are fetched.
 *
 * Writing goes through [save] and [remove] so three rules cannot be skipped.
 * A config is never stored beside a key that failed to store. Every
 * language's service voice is forgotten when the service becomes another
 * ([OnlineTtsConfig.serviceIdentity]) or is removed: a voice id means
 * nothing to a different service. And a language's service voice that the
 * stored model is known to refuse is not left chosen ([voicesNotTaken]).
 *
 * The service is one record: [save] and [remove] each store all of their
 * change in one write ([Prefs.storeOnlineTts], [Prefs.clearOnlineTts]), and
 * a request is made from a [Connection], the config and the key read
 * together. So no request pairs one service's URL with another's key,
 * whatever is saved while it is being made.
 */
object OnlineTtsService {

    /** The [Prefs] the app reads this service from. A seam for JVM tests,
     *  whose [Prefs] needs a fake codec to hold a key. */
    @Volatile
    @VisibleForTesting
    internal var prefsFor: (Context) -> Prefs = { Prefs(it) }

    /** The client every request of this service goes through. A seam for
     *  JVM tests. */
    @Volatile
    @VisibleForTesting
    internal var client: OnlineTtsClient = OnlineTtsClient()

    fun prefs(ctx: Context): Prefs = prefsFor(ctx.applicationContext)

    /** The connected service, or null when none is. For a reader that
     *  sends nothing; a request is made from a [connection]. */
    fun config(prefs: Prefs): OnlineTtsConfig? = prefs.onlineTtsConfigJson?.let(::decode)

    fun config(ctx: Context): OnlineTtsConfig? = config(prefs(ctx))

    /** The connected service with the key that goes with it. */
    class Connection(val config: OnlineTtsConfig, val key: String)

    /**
     * The connected service and its key as they stand together, or null
     * when none is connected. Everything that sends the key reads it here,
     * once, and makes its request from what it got: a config read now and a
     * key read later can be two services' when a Save lands between them.
     */
    fun connection(prefs: Prefs): Connection? {
        val stored = prefs.onlineTtsStored() ?: return null
        return Connection(decode(stored.configJson) ?: return null, stored.apiKey)
    }

    private fun decode(json: String): OnlineTtsConfig? =
        runCatching { PtJson.lenient.decodeFromString(OnlineTtsConfig.serializer(), json) }.getOrNull()

    /**
     * Stores [config] and [key] as the connected service, with the service
     * voices that do not carry over to it forgotten: every language's when
     * it is another service, and otherwise those its model is known to
     * refuse ([voicesNotTaken]). One write, so nothing reads the new key
     * beside the old config or the old voices. False, with nothing changed,
     * when the key did not hold: [Prefs] writes no secret while encryption
     * is unavailable.
     */
    fun save(prefs: Prefs, config: OnlineTtsConfig, key: String): Boolean {
        val stored = config.normalized()
        val previous = config(prefs)
        val forgotten = if (previous != null && previous.serviceIdentity != stored.serviceIdentity) {
            SourceLangId.entries
        } else {
            voicesNotTaken(prefs, stored).keys
        }
        return prefs.storeOnlineTts(
            configJson = PtJson.lenient.encodeToString(OnlineTtsConfig.serializer(), stored),
            apiKey = key.trim(),
            forget = forgotten,
        )
    }

    /**
     * The game languages whose service voice [config] is known to refuse
     * ([OnlineTtsConfig.doesNotTake]), each with that voice: OpenAI's older
     * models take fewer of its voices than its newest one. Storing [config]
     * sends each of them back to the device's voice ([save]), since the
     * voice would fail on every request; the service's page asks this
     * first, so the user is told before it happens.
     *
     * It is asked of the stored voices against [config], not of what
     * changed between two configs, so a voice left chosen under a model
     * that refuses it is found on the next Save, whatever that Save
     * changes. Empty when [config] is another service than the connected
     * one: every service voice is forgotten then, whatever it is.
     */
    fun voicesNotTaken(prefs: Prefs, config: OnlineTtsConfig): Map<SourceLangId, OnlineTtsChoice> {
        val next = config.normalized()
        if (config(prefs)?.serviceIdentity != next.serviceIdentity) return emptyMap()
        return SourceLangId.entries.mapNotNull { lang ->
            (prefs.voiceChoice(lang) as? VoiceChoice.Online)?.voice
                ?.takeIf { next.doesNotTake(it.id) }
                ?.let { lang to it }
        }.toMap()
    }

    /**
     * Disconnects the service: its config, its key and every language's
     * service voice, in one write, and then the clips it spoke. A clip is
     * kept under the request that made it, with no key in it
     * ([OnlineTtsRequest.identity]), so one left behind would be played
     * again, with nothing asked, by a service connected later at the same
     * address. Removing is the user's way to start a service's audio over.
     */
    fun remove(ctx: Context) {
        prefs(ctx).clearOnlineTts()
        AudioCache(ctx).dropClips(OnlineTtsAudioSource.ID)
    }

    /**
     * The voices [config] offers: the service's own list when it gives one
     * with voices in it, and otherwise the config's hand-listed voices. The
     * list call's failure is thrown only when there are no hand-listed
     * voices to offer in its place.
     *
     * "Otherwise" is every way of not getting a list, on purpose: a status,
     * a reply that is no list, an empty one. A service with built-in voices
     * and no call that lists them (OpenAI) has to offer them whatever it
     * answers a list request it was never meant to serve, and how it
     * answers is not documented. The first version of this rule took only a
     * 404 for "no such list", and the first real service tried answered
     * something else, so its voices could not be picked at all. What the
     * list call cannot say here, a voice says when it is tapped: its sample
     * is the check of the key and the request, with the service's own
     * words.
     */
    suspend fun voices(config: OnlineTtsConfig, key: String): List<OnlineTtsChoice> {
        val handListed = config.handListedVoices.map { OnlineTtsChoice(it) }
        val listed = try {
            list(config, config.preset.spec.voices, key)
        } catch (e: OnlineTtsException) {
            if (handListed.isEmpty()) throw e
            emptyList()
        }
        return listed.ifEmpty { handListed }
    }

    /** The connected service's voices; see the overload. Throws when no
     *  service is connected. */
    suspend fun voices(ctx: Context): List<OnlineTtsChoice> {
        val connected = connection(prefs(ctx)) ?: throw notConnected()
        return voices(connected.config, connected.key)
    }

    /** The models [config]'s service lists, asked with [key]. Throws
     *  [OnlineTtsException]. */
    suspend fun models(config: OnlineTtsConfig, key: String): List<OnlineTtsChoice> =
        list(config, config.preset.spec.models, key)

    /**
     * What asking a service for its models says about the URL and the key,
     * for the page's Save: the one request that needs no voice, costs
     * nothing, and that both known services refuse to a wrong key.
     */
    sealed interface ModelsAnswer {
        /** The service listed its models: the URL and the key are right. */
        class Listed(val models: List<OnlineTtsChoice>) : ModelsAnswer

        /**
         * The service answered, and not with a list of models: it has no
         * such call (some self-hosted servers), or answers it in a shape
         * the app cannot read, or lists nothing. The URL reaches something;
         * the key is neither proven nor refused. [said] is what came back,
         * for the user who has to decide whether to save without the check.
         */
        class NoList(val said: OnlineTtsException?) : ModelsAnswer
    }

    /**
     * Asks [config]'s service for its models with [key] and says which of
     * the two answers it gave. Everything that is neither is thrown, and is
     * a Save that does not happen: the key refused (401, 403), the service
     * not reached or not answering in time, its rate spent, its own error.
     *
     * Only what is certain decides. A refusal of the key is certain. Any
     * other answer that is not a list is [ModelsAnswer.NoList], whatever
     * its status: which status a service gives a call it does not have is
     * not something to guess (ElevenLabs answers this very call 404 when
     * sent no key at all).
     */
    suspend fun modelsAnswer(config: OnlineTtsConfig, key: String): ModelsAnswer = try {
        val listed = models(config, key)
        if (listed.isEmpty()) ModelsAnswer.NoList(null) else ModelsAnswer.Listed(listed)
    } catch (e: OnlineTtsException) {
        val answeredWithoutAList = when (e.failure.kind) {
            BackendFailureKind.BAD_RESPONSE -> true
            BackendFailureKind.REJECTED -> e.failure.httpCode != null
            BackendFailureKind.SERVER_ERROR -> e.failure.httpCode == 501
            else -> false
        }
        if (answeredWithoutAList) ModelsAnswer.NoList(e) else throw e
    }

    /**
     * One of [config]'s lists, asked with [key].
     *
     * A 401 or a 403 is the service refusing the key only when the key was
     * sent. A request that keeps its key in the body has no way to send it
     * with a list request ([OnlineTtsRequest.listSendsKey]), so for a key
     * that was entered and could not be carried, such an answer is the
     * request refused, with its status, and not the key: calling it a
     * refused key would tell the user something false about a key that
     * may be right, and Save, which takes a refused key as certain, would
     * never store the service.
     */
    private suspend fun list(
        config: OnlineTtsConfig,
        spec: OnlineTtsListSpec,
        key: String,
    ): List<OnlineTtsChoice> {
        val sent = key.trim()
        val request = OnlineTtsRequest.renderList(config, spec, sent) ?: throw notConnected()
        return try {
            client.list(request, spec)
        } catch (e: OnlineTtsException) {
            val keyNotSent = sent.isNotEmpty() && !OnlineTtsRequest.listSendsKey(config)
            if (e.failure.kind == BackendFailureKind.AUTH && keyNotSent) {
                throw OnlineTtsException(BackendFailure(BackendFailureKind.REJECTED, e.failure.httpCode), e.detail)
            }
            throw e
        }
    }

    /** No request could be made: there is no service, or its URL is not
     *  one the app will call. */
    internal fun notConnected() = OnlineTtsException(BackendFailure(BackendFailureKind.REJECTED))
}
