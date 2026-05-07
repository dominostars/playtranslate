package com.arm.aichat

import com.arm.aichat.InferenceEngine.State
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/**
 * Interface defining the core LLM inference operations.
 */
interface InferenceEngine {
    /**
     * Current state of the inference engine
     */
    val state: StateFlow<State>

    /**
     * Load a model from the given path.
     *
     * @throws UnsupportedArchitectureException if model architecture not supported
     */
    suspend fun loadModel(pathToModel: String)

    /**
     * Sends a system prompt to the loaded model
     */
    suspend fun setSystemPrompt(systemPrompt: String)

    /**
     * PlayTranslate: trim chat history + KV cache back to "just after the system prompt"
     * without re-decoding the system prompt. Use between independent prompts when the
     * system prompt is unchanged, to skip the per-call system-prompt decode cost.
     *
     * Safe to call on a [State.ModelReady] engine. If no system prompt has been set,
     * this is equivalent to a full state reset.
     */
    suspend fun resetForNextPrompt()

    /**
     * PlayTranslate prefix-mode: tokenize and decode a raw text prefix without going
     * through the chat-template formatter, and record its end position so subsequent
     * [resetForNextPrompt] calls can rewind to here.
     *
     * The caller is responsible for emitting any role markers (e.g. <start_of_turn>user)
     * in the prefix string. BOS is added automatically.
     *
     * Use case: models like Gemma 3 whose chat templates merge system content into the
     * first user turn, defeating the system-prompt boundary that [setSystemPrompt] +
     * [resetForNextPrompt] depends on. By managing the prefix manually we get a stable
     * cache point inside the user turn.
     */
    suspend fun processRawPrefix(prefix: String)

    /**
     * PlayTranslate prefix-mode: decode a raw text suffix and stream generated tokens.
     * Pairs with [processRawPrefix]. The suffix should include any closing role
     * markers (e.g. <end_of_turn>\n<start_of_turn>model\n for Gemma 3).
     */
    fun sendRawSuffix(suffix: String, predictLength: Int = DEFAULT_PREDICT_LENGTH): Flow<String>

    /**
     * Sends a user prompt to the loaded model and returns a Flow of generated tokens.
     */
    fun sendUserPrompt(message: String, predictLength: Int = DEFAULT_PREDICT_LENGTH): Flow<String>

    /**
     * Runs a benchmark with the specified parameters.
     */
    suspend fun bench(pp: Int, tg: Int, pl: Int, nr: Int = 1): String

    /**
     * Unloads the currently loaded model.
     */
    fun cleanUp()

    /**
     * Cleans up resources when the engine is no longer needed.
     */
    fun destroy()

    /**
     * States of the inference engine
     */
    sealed class State {
        object Uninitialized : State()
        object Initializing : State()
        object Initialized : State()

        object LoadingModel : State()
        object UnloadingModel : State()
        object ModelReady : State()

        object Benchmarking : State()
        object ProcessingSystemPrompt : State()
        object ProcessingUserPrompt : State()

        object Generating : State()

        data class Error(val exception: Exception) : State()
    }

    companion object {
        const val DEFAULT_PREDICT_LENGTH = 1024
    }
}

val State.isUninterruptible
    get() = this is State.Initializing ||
        this is State.LoadingModel ||
        this is State.UnloadingModel ||
        this is State.Benchmarking ||
        this is State.ProcessingSystemPrompt ||
        this is State.ProcessingUserPrompt

val State.isModelLoaded: Boolean
    get() = this is State.ModelReady ||
        this is State.Benchmarking ||
        this is State.ProcessingSystemPrompt ||
        this is State.ProcessingUserPrompt ||
        this is State.Generating

class UnsupportedArchitectureException : Exception()
