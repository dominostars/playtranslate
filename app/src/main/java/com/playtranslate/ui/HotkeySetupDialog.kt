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

/**
 * Reusable dialog for capturing a hotkey combo. Shows a timed hold prompt —
 * the user holds down key(s) for [HOLD_DURATION_MS] to confirm the combo.
 *
 * Receives key events via [PlayTranslateAccessibilityService.onKeyEventListener]
 * since gamepad input is routed to the game display, not the app's dialog window.
 *
 * Used for both "hold to show translations" and "hold to show furigana" hotkeys.
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

        fun newInstance(): HotkeySetupDialog = HotkeySetupDialog()
    }

    var onHotkeySet: ((keyCodes: List<Int>) -> Unit)? = null
    var onCancelled: (() -> Unit)? = null

    private val heldKeys = mutableSetOf<Int>()
    private var countdownTimer: CountDownTimer? = null
    private var resultDelivered = false
    private val mainHandler = Handler(Looper.getMainLooper())

    private lateinit var tvInstruction: TextView
    private lateinit var tvTimer: TextView

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val dialog = Dialog(requireContext())
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
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

        view.findViewById<View>(R.id.btnCancel).setOnClickListener {
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
        if (event.keyCode in SYSTEM_KEYS) return false

        // Post to main thread since onKeyEvent may be called from the a11y service thread
        mainHandler.post {
            when (event.action) {
                KeyEvent.ACTION_DOWN -> {
                    if (heldKeys.add(event.keyCode)) {
                        restartTimer()
                        updateKeyDisplay()
                    }
                }
                KeyEvent.ACTION_UP -> {
                    heldKeys.remove(event.keyCode)
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
        tvInstruction.text = heldKeys.sorted()
            .joinToString(" + ") {
                KeyEvent.keyCodeToString(it).removePrefix("KEYCODE_")
            }
        tvTimer.visibility = View.VISIBLE
    }

    private fun showInstruction() {
        tvInstruction.text = "Hold down key(s) for 2 seconds"
        tvTimer.visibility = View.GONE
    }

    private fun restartTimer() {
        countdownTimer?.cancel()
        tvTimer.visibility = View.VISIBLE
        countdownTimer = object : CountDownTimer(HOLD_DURATION_MS, 100) {
            override fun onTick(remaining: Long) {
                tvTimer.text = "%.1f".format(remaining / 1000f)
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
