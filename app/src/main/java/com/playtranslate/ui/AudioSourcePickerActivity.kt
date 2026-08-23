package com.playtranslate.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.LinearLayout
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.playtranslate.PtJson
import com.playtranslate.R
import com.playtranslate.applyEdgeToEdge
import com.playtranslate.applyTheme
import com.playtranslate.audio.Attribution
import com.playtranslate.audio.AudioSelection
import com.playtranslate.language.SourceLangId

/**
 * Activity shell for the multi-section Anki audio picker — the content is
 * [AudioSourcePickerView], shared with the floating workspace's audio
 * picker page. This shell keeps the Activity-only concerns: theme, toolbar,
 * edge-to-edge insets, the RECORD_AUDIO permission contract, and the
 * intent-extras result transport ([setResult]; parsed by
 * [SentenceAnkiContentView.parsePickerResult]).
 */
class AudioSourcePickerActivity : AppCompatActivity() {

    private var content: AudioSourcePickerView? = null

    /** RECORD_AUDIO for the in-picker game-audio enable switch — the
     *  content's [AudioSourcePickerView.Host.requestRecordAudio] verb. */
    private var pendingPermissionResult: ((Boolean) -> Unit)? = null
    private val requestRecordAudio =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            pendingPermissionResult?.invoke(granted)
            pendingPermissionResult = null
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        applyTheme(this)
        applyEdgeToEdge(this)
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_audio_source_picker)
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(android.R.id.content)) { v, insets ->
            val sys = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout(),
            )
            v.setPadding(sys.left, sys.top, sys.right, sys.bottom)
            WindowInsetsCompat.CONSUMED
        }

        val view = AudioSourcePickerView(
            this, lifecycleScope, argsFrom(intent),
            object : AudioSourcePickerView.Host {
                override fun requestRecordAudio(onResult: (Boolean) -> Unit) {
                    pendingPermissionResult = onResult
                    requestRecordAudio.launch(Manifest.permission.RECORD_AUDIO)
                }
            },
        )
        content = view

        findViewById<MaterialToolbar>(R.id.toolbar).apply {
            title = getString(R.string.audio_source_picker_title)
            setNavigationOnClickListener { finish() }
        }
        findViewById<MaterialButton>(R.id.btnSave).setOnClickListener {
            val explicit = view.pickedSelection() as? AudioSelection.Explicit
            val data = Intent()
                .putExtra(EXTRA_PICKED_SOURCE, explicit?.sourceId)
                .putExtra(EXTRA_PICKED_KEY, explicit?.key)
                .putExtra(EXTRA_PICKED_LOCATOR, explicit?.locator)
                .putExtra(
                    EXTRA_PICKED_ATTRIBUTION,
                    explicit?.attribution?.let {
                        PtJson.lenient.encodeToString(Attribution.serializer(), it)
                    },
                )
            setResult(RESULT_OK, data)
            finish()
        }
        view.buildInto(findViewById<LinearLayout>(R.id.sourceSections))
    }

    override fun onDestroy() {
        content?.release()
        content = null
        super.onDestroy()
    }

    companion object {
        private const val EXTRA_LANG = "lang"
        private const val EXTRA_SURFACE = "surface"
        private const val EXTRA_READING = "reading"
        private const val EXTRA_IS_WORD = "is_word"
        private const val EXTRA_INITIAL_SOURCE = "initial_source"
        private const val EXTRA_INITIAL_KEY = "initial_key"
        private const val EXTRA_INITIAL_LOCATOR = "initial_locator"
        private const val EXTRA_INITIAL_ATTRIBUTION = "initial_attribution"
        const val EXTRA_PICKED_SOURCE = "picked_source"
        const val EXTRA_PICKED_KEY = "picked_key"
        const val EXTRA_PICKED_LOCATOR = "picked_locator"
        const val EXTRA_PICKED_ATTRIBUTION = "picked_attribution"

        fun intent(
            context: Context,
            lang: SourceLangId,
            surface: String,
            reading: String?,
            isWord: Boolean,
            current: AudioSelection,
        ): Intent {
            val explicit = current as? AudioSelection.Explicit
            return Intent(context, AudioSourcePickerActivity::class.java)
                .putExtra(EXTRA_LANG, lang.code)
                .putExtra(EXTRA_SURFACE, surface)
                .putExtra(EXTRA_READING, reading)
                .putExtra(EXTRA_IS_WORD, isWord)
                .putExtra(EXTRA_INITIAL_SOURCE, explicit?.sourceId)
                .putExtra(EXTRA_INITIAL_KEY, explicit?.key)
                .putExtra(EXTRA_INITIAL_LOCATOR, explicit?.locator)
                .putExtra(
                    EXTRA_INITIAL_ATTRIBUTION,
                    explicit?.attribution?.let {
                        PtJson.lenient.encodeToString(Attribution.serializer(), it)
                    },
                )
        }

        /** The launch extras decoded back into the content core's [Args] —
         *  the workspace page path receives the same Intent the activity
         *  flow would (the [WordAnkiReviewBinder.Host.openAudioPicker]
         *  transport) but hosts the content in-window, so the marshalling
         *  round-trips through here instead of an activity launch. */
        fun argsFrom(intent: Intent): AudioSourcePickerView.Args = AudioSourcePickerView.Args(
            lang = SourceLangId.fromCode(intent.getStringExtra(EXTRA_LANG)) ?: SourceLangId.JA,
            surface = intent.getStringExtra(EXTRA_SURFACE).orEmpty(),
            reading = intent.getStringExtra(EXTRA_READING),
            isWord = intent.getBooleanExtra(EXTRA_IS_WORD, true),
            initialSourceId = intent.getStringExtra(EXTRA_INITIAL_SOURCE),
            initialKey = intent.getStringExtra(EXTRA_INITIAL_KEY),
            initialLocator = intent.getStringExtra(EXTRA_INITIAL_LOCATOR),
            initialAttribution = intent.getStringExtra(EXTRA_INITIAL_ATTRIBUTION)
                ?.let {
                    runCatching {
                        PtJson.lenient.decodeFromString(Attribution.serializer(), it)
                    }.getOrNull()
                },
        )
    }
}
