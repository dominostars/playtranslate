package com.playtranslate.mnn.internal

import android.content.Context
import android.util.Log
import com.playtranslate.mnn.InferenceEngine
import com.playtranslate.mnn.UnsupportedArchitectureException
import dalvik.annotation.optimization.FastNative
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

/**
 * MNN-backed [InferenceEngine] implementation. Mirrors `:llama`'s
 * `InferenceEngineImpl` shape so the per-engine translators stay structurally
 * parallel; differences are documented inline where the underlying APIs
 * diverge (notably the blocking `Llm::response` vs. llama.cpp's per-token
 * `generateNextToken`).
 *
 * Process-wide singleton — the native side (mnn_chat.cpp) holds the loaded
 * `Llm` in a global pointer, and all JNI calls are serialized through
 * [mnnDispatcher] (single-threaded, parallelism=1).
 */
internal class MnnChatImpl private constructor(
    private val nativeLibDir: String
) : InferenceEngine {

    companion object {
        private val TAG = MnnChatImpl::class.java.simpleName

        @Volatile
        private var instance: InferenceEngine? = null

        internal fun getInstance(context: Context): InferenceEngine =
            instance ?: synchronized(this) {
                val nativeLibDir = context.applicationInfo.nativeLibraryDir
                require(nativeLibDir.isNotBlank()) { "Expected a valid native library path!" }
                try {
                    Log.i(TAG, "Instantiating MnnChatImpl...")
                    MnnChatImpl(nativeLibDir).also { instance = it }
                } catch (e: UnsatisfiedLinkError) {
                    Log.e(TAG, "Failed to load native library from $nativeLibDir", e)
                    throw e
                }
            }
    }

    // JNI surface — see mnn_chat.cpp. All methods serialize through
    // [mnnDispatcher]; `@FastNative` skips JNI's parameter-copy fast-path
    // safety net since we're confident in the contract.
    @FastNative private external fun init(nativeLibDir: String)
    @FastNative private external fun load(modelDir: String): Int
    @FastNative private external fun prepare(): Int
    @FastNative private external fun systemInfo(): String
    @FastNative private external fun processSystemPrompt(systemPrompt: String): Int
    @FastNative private external fun nativeResetForNextPrompt(): Int
    @FastNative private external fun nativeProcessRawPrefix(prefix: String): Int
    @FastNative private external fun processUserPromptBlocking(userPrompt: String, predictLength: Int): String
    @FastNative private external fun nativeProcessRawSuffixBlocking(suffix: String, predictLength: Int): String
    @FastNative private external fun unload()
    @FastNative private external fun shutdown()

    private val _state =
        MutableStateFlow<InferenceEngine.State>(InferenceEngine.State.Uninitialized)
    override val state: StateFlow<InferenceEngine.State> = _state.asStateFlow()

    @Volatile
    private var _cancelGeneration = false

    @OptIn(ExperimentalCoroutinesApi::class)
    private val mnnDispatcher = Dispatchers.IO.limitedParallelism(1)
    private val mnnScope = CoroutineScope(mnnDispatcher + SupervisorJob())

    init {
        mnnScope.launch {
            try {
                check(_state.value is InferenceEngine.State.Uninitialized) {
                    "Cannot load native library in ${_state.value.javaClass.simpleName}!"
                }
                _state.value = InferenceEngine.State.Initializing
                Log.i(TAG, "Loading native library `mnn-chat`...")
                // MNN's libraries are pulled in as DT_NEEDED dependencies of
                // libmnn-chat.so, so a single loadLibrary call here brings
                // libMNN.so + libllm.so + libMNN_Express.so along via the
                // dynamic linker. (libMNN_CL.so is opened lazily by MNN's
                // OpenCL backend on first GPU use.)
                System.loadLibrary("mnn-chat")
                init(nativeLibDir)
                _state.value = InferenceEngine.State.Initialized
                Log.i(TAG, "MNN native library loaded. ${systemInfo()}")
            } catch (t: Throwable) {
                Log.e(TAG, "Failed to load native library", t)
                val ex = (t as? Exception) ?: RuntimeException(t)
                _state.value = InferenceEngine.State.Error(ex)
            }
        }
    }

    override suspend fun loadModel(pathToModelDir: String) =
        withContext(mnnDispatcher) {
            check(_state.value is InferenceEngine.State.Initialized) {
                "Cannot load model in ${_state.value.javaClass.simpleName}!"
            }
            try {
                Log.i(TAG, "Checking model dir: $pathToModelDir")
                File(pathToModelDir).let {
                    require(it.exists()) { "Model dir not found" }
                    require(it.isDirectory) { "Not a directory (MNN expects a directory containing config.json)" }
                    require(it.canRead()) { "Cannot read model dir" }
                    require(File(it, "config.json").exists()) {
                        "Missing config.json inside model dir"
                    }
                }

                Log.i(TAG, "Loading MNN model from $pathToModelDir")
                _state.value = InferenceEngine.State.LoadingModel
                val configPath = File(pathToModelDir, "config.json").absolutePath
                load(configPath).let {
                    if (it != 0) throw UnsupportedArchitectureException()
                }
                prepare().let {
                    if (it != 0) throw IOException("Failed to prepare MNN runtime (code $it)")
                }
                Log.i(TAG, "Model loaded.")
                _cancelGeneration = false
                _state.value = InferenceEngine.State.ModelReady
            } catch (e: Exception) {
                Log.e(TAG, "Error loading model: ${e.message}", e)
                _state.value = InferenceEngine.State.Error(e)
                throw e
            }
        }

    override suspend fun setSystemPrompt(systemPrompt: String) =
        withContext(mnnDispatcher) {
            require(systemPrompt.isNotBlank()) { "Cannot process empty system prompt!" }
            check(_state.value is InferenceEngine.State.ModelReady) {
                "Cannot process system prompt in ${_state.value.javaClass.simpleName}!"
            }
            Log.i(TAG, "Prefilling system prompt...")
            _state.value = InferenceEngine.State.ProcessingSystemPrompt
            processSystemPrompt(systemPrompt).let { result ->
                if (result != 0) {
                    RuntimeException("Failed to process system prompt: $result").also {
                        _state.value = InferenceEngine.State.Error(it)
                        throw it
                    }
                }
            }
            Log.i(TAG, "System prompt processed.")
            _state.value = InferenceEngine.State.ModelReady
        }

    override suspend fun resetForNextPrompt() =
        withContext(mnnDispatcher) {
            check(_state.value is InferenceEngine.State.ModelReady) {
                "Cannot reset in ${_state.value.javaClass.simpleName}!"
            }
            nativeResetForNextPrompt().let { result ->
                if (result != 0) {
                    RuntimeException("Failed to reset for next prompt: $result").also {
                        _state.value = InferenceEngine.State.Error(it)
                        throw it
                    }
                }
            }
        }

    override suspend fun processRawPrefix(prefix: String) =
        withContext(mnnDispatcher) {
            require(prefix.isNotBlank()) { "Cannot process empty raw prefix!" }
            check(_state.value is InferenceEngine.State.ModelReady) {
                "Cannot process raw prefix in ${_state.value.javaClass.simpleName}!"
            }
            _state.value = InferenceEngine.State.ProcessingSystemPrompt
            nativeProcessRawPrefix(prefix).let { result ->
                if (result != 0) {
                    RuntimeException("Failed to process raw prefix: $result").also {
                        _state.value = InferenceEngine.State.Error(it)
                        throw it
                    }
                }
            }
            _state.value = InferenceEngine.State.ModelReady
        }

    override fun sendRawSuffix(suffix: String, predictLength: Int): Flow<String> = flow {
        require(suffix.isNotEmpty()) { "Raw suffix discarded due to being empty!" }
        check(_state.value is InferenceEngine.State.ModelReady) {
            "Raw suffix discarded due to: ${_state.value.javaClass.simpleName}"
        }
        try {
            _state.value = InferenceEngine.State.ProcessingUserPrompt
            val result = nativeProcessRawSuffixBlocking(suffix, predictLength)
            _state.value = InferenceEngine.State.Generating
            // MNN runs prefill + generation synchronously in the native call
            // above; the entire result is emitted as one Flow item to keep
            // the surface parallel to :llama's per-token Flow.
            if (result.isNotEmpty() && !_cancelGeneration) emit(result)
            _state.value = InferenceEngine.State.ModelReady
        } catch (e: CancellationException) {
            _state.value = InferenceEngine.State.ModelReady
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Error during generation!", e)
            _state.value = InferenceEngine.State.Error(e)
            throw e
        }
    }.flowOn(mnnDispatcher)

    override fun sendUserPrompt(message: String, predictLength: Int): Flow<String> = flow {
        require(message.isNotEmpty()) { "User prompt discarded due to being empty!" }
        check(_state.value is InferenceEngine.State.ModelReady) {
            "User prompt discarded due to: ${_state.value.javaClass.simpleName}"
        }
        try {
            _state.value = InferenceEngine.State.ProcessingUserPrompt
            val result = processUserPromptBlocking(message, predictLength)
            _state.value = InferenceEngine.State.Generating
            if (result.isNotEmpty() && !_cancelGeneration) emit(result)
            _state.value = InferenceEngine.State.ModelReady
        } catch (e: CancellationException) {
            _state.value = InferenceEngine.State.ModelReady
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Error during generation!", e)
            _state.value = InferenceEngine.State.Error(e)
            throw e
        }
    }.flowOn(mnnDispatcher)

    override fun cleanUp() {
        _cancelGeneration = true
        runBlocking(mnnDispatcher) {
            when (val state = _state.value) {
                is InferenceEngine.State.ModelReady -> {
                    Log.i(TAG, "Unloading MNN model...")
                    _state.value = InferenceEngine.State.UnloadingModel
                    unload()
                    _state.value = InferenceEngine.State.Initialized
                    Log.i(TAG, "Model unloaded.")
                    Unit
                }
                is InferenceEngine.State.Error -> {
                    Log.i(TAG, "Resetting error state (releasing any leftover native resources)...")
                    _state.value = InferenceEngine.State.UnloadingModel
                    unload()
                    _state.value = InferenceEngine.State.Initialized
                    Unit
                }
                else -> throw IllegalStateException("Cannot unload model in ${state.javaClass.simpleName}")
            }
        }
    }

    override fun destroy() {
        _cancelGeneration = true
        runBlocking(mnnDispatcher) {
            when (_state.value) {
                is InferenceEngine.State.Uninitialized -> {}
                is InferenceEngine.State.Initialized -> shutdown()
                else -> { unload(); shutdown() }
            }
        }
        mnnScope.cancel()
    }
}
