package com.playtranslate.ui

import android.app.Dialog
import android.content.DialogInterface
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.os.CountDownTimer
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.widget.TextView
import androidx.fragment.app.DialogFragment
import com.playtranslate.PlayTranslateAccessibilityService
import com.playtranslate.R
import com.playtranslate.comboTakesTypingKey
import androidx.core.graphics.drawable.toDrawable
import androidx.core.view.isVisible
import androidx.core.view.isGone

/**
 * Reusable dialog for capturing a hotkey combo. Shows a timed hold prompt —
 * the user holds down key(s) for [HOLD_DURATION_MS] to confirm the combo.
 *
 * Receives key events via [PlayTranslateAccessibilityService.onKeyEventListener]
 * since controller input is routed to the game display, not the app's dialog
 * window. Accepts exactly what dispatch can fire — see
 * [PlayTranslateAccessibilityService.isHotkeyEligible].
 *
 * Used for every hotkey row on the Hotkeys page (hold/tap translations, hold/tap
 * reading hints, capture screen).
 */
class HotkeySetupDialog : DialogFragment() {

    companion object {
        private const val HOLD_DURATION_MS = 2000L

        private val SYSTEM_KEYS = setOf(
            KeyEvent.KEYCODE_BACK,
            KeyEvent.KEYCODE_HOME,
            KeyEvent.KEYCODE_VOLUME_UP,
            KeyEvent.KEYCODE_VOLUME_DOWN,
            KeyEvent.KEYCODE_POWER
        )

        private const val ARG_TITLE = "title"

        fun newInstance(title: String? = null): HotkeySetupDialog = HotkeySetupDialog().apply {
            if (title != null) arguments = android.os.Bundle().apply { putString(ARG_TITLE, title) }
        }
    }

    var onHotkeySet: ((keyCodes: List<Int>) -> Unit)? = null
    var onCancelled: (() -> Unit)? = null

    private val heldKeys = mutableSetOf<Int>()

    /** The subset of [heldKeys] that types a character, tracked as keys arrive
     *  because only the live [KeyEvent] can answer that (it takes the sending
     *  device's key character map). Drives the warning, never a refusal. */
    private val typingKeys = mutableSetOf<Int>()
    private var countdownTimer: CountDownTimer? = null
    private var resultDelivered = false
    private val mainHandler = Handler(Looper.getMainLooper())

    private lateinit var tvInstruction: TextView
    private lateinit var tvTimer: TextView
    private lateinit var btnCancel: View

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val dialog = Dialog(requireContext())
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        dialog.window?.setBackgroundDrawable(Color.TRANSPARENT.toDrawable())
        return dialog
    }

    override fun onStart() {
        super.onStart()
        val screenWidth = resources.displayMetrics.widthPixels
        dialog?.window?.setLayout(screenWidth / 2, ViewGroup.LayoutParams.WRAP_CONTENT)
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.dialog_hotkey_setup, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        tvInstruction = view.findViewById(R.id.tvInstruction)
        tvTimer = view.findViewById(R.id.tvTimer)
        arguments?.getString(ARG_TITLE)?.let {
            view.findViewById<TextView>(R.id.tvTitle).text = it
        }

        btnCancel = view.findViewById(R.id.btnCancel)
        btnCancel.setOnClickListener {
            cancelAndDismiss()
        }
    }

    override fun onResume() {
        super.onResume()
        PlayTranslateAccessibilityService.instance?.onKeyEventListener = { event ->
            handleKeyEvent(event)
        }
    }

    override fun onPause() {
        PlayTranslateAccessibilityService.instance?.onKeyEventListener = null
        super.onPause()
    }

    override fun onDismiss(dialog: DialogInterface) {
        PlayTranslateAccessibilityService.instance?.onKeyEventListener = null
        countdownTimer?.cancel()
        if (!resultDelivered) {
            onCancelled?.invoke()
        }
        super.onDismiss(dialog)
    }

    private fun handleKeyEvent(event: KeyEvent): Boolean {
        // Only record what dispatch can actually fire. Without this the dialog
        // happily bound keys from sources the hotkey path drops, and the user
        // got a row that reads as set but never triggers.
        if (!PlayTranslateAccessibilityService.isHotkeyEligible(event)) return false
        if (event.keyCode in SYSTEM_KEYS) return false

        // Resolved here, on the event, because it takes the sending device's
        // key character map — by the time the combo is committed we only have
        // keycodes left.
        val typesText = PlayTranslateAccessibilityService.typesText(event)

        // Post to main thread since onKeyEvent may be called from the a11y service thread
        mainHandler.post {
            when (event.action) {
                KeyEvent.ACTION_DOWN -> {
                    if (heldKeys.add(event.keyCode)) {
                        if (typesText) typingKeys.add(event.keyCode)
                        restartTimer()
                        updateKeyDisplay()
                    }
                }
                KeyEvent.ACTION_UP -> {
                    heldKeys.remove(event.keyCode)
                    typingKeys.remove(event.keyCode)
                    if (heldKeys.isEmpty()) {
                        cancelTimer()
                        showInstruction()
                    } else {
                        restartTimer()
                        updateKeyDisplay()
                    }
                }
            }
        }
        return true // consume the event so it doesn't reach the game
    }

    private fun cancelAndDismiss() {
        resultDelivered = false
        dismiss()
    }

    private fun updateKeyDisplay() {
        val combo = heldKeys.sorted().joinToString(" + ") {
            KeyEvent.keyCodeToString(it).removePrefix("KEYCODE_")
        }
        // Warn, do not refuse. Shown while the combo is held, so the whole
        // hold doubles as the chance to reconsider: release and the binding
        // never happens, hold through and it is an informed choice.
        tvInstruction.text = if (comboTakesTypingKey(heldKeys, typingKeys)) {
            "$combo\n${getString(R.string.dialog_hotkey_setup_typing_key)}"
        } else {
            combo
        }
        tvTimer.isVisible = true
        btnCancel.isGone = true
    }

    private fun showInstruction() {
        tvInstruction.text = getString(R.string.dialog_hotkey_setup_instruction)
        tvTimer.isGone = true
        btnCancel.isVisible = true
    }

    private fun restartTimer() {
        countdownTimer?.cancel()
        tvTimer.isVisible = true
        countdownTimer = object : CountDownTimer(HOLD_DURATION_MS, 100) {
            override fun onTick(remaining: Long) {
                tvTimer.text = getString(
                    R.string.dialog_hotkey_setup_countdown,
                    "%.1f".format(remaining / 1000f)
                )
            }
            override fun onFinish() {
                resultDelivered = true
                onHotkeySet?.invoke(heldKeys.sorted())
                dismiss()
            }
        }.start()
    }

    private fun cancelTimer() {
        countdownTimer?.cancel()
        countdownTimer = null
    }
}
