package com.playtranslate.imageimport

import android.content.ClipboardManager
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.Toast
import androidx.activity.addCallback
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.IntentCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import com.playtranslate.DetectionLog
import com.playtranslate.OcrTokenScope
import com.playtranslate.OverlayMode
import com.playtranslate.Prefs
import com.playtranslate.R
import com.playtranslate.applyEdgeToEdge
import com.playtranslate.applyTheme
import com.playtranslate.camera.CameraGearMenu
import com.playtranslate.camera.CameraRegionUi
import com.playtranslate.language.SourceLanguageProfiles
import com.playtranslate.ocr.registry.OcrModelManager
import com.playtranslate.ocr.registry.selectionToken
import com.playtranslate.themeColor
import com.playtranslate.ui.DismissReason
import com.playtranslate.ui.OverlayAlert
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The import-image translation tool (Settings → Tools → Import image, plus
 * the system share sheet's ACTION_SEND image target): pick an image from the
 * photo library, the file browser, or the clipboard, and review it through
 * the camera tool's frozen-snapshot experience — OCR + translation, overlay
 * boxes over the letterboxed image, the capture result panel, word lookup,
 * region crop, and a gear menu whose Language/OCR/Overlays selections are
 * the import tool's OWN scope (inheriting the global until set).
 *
 * Standalone full-bleed activity like CameraActivity — NOT a
 * SettingsSubPageActivity: insets pad only the floating controls; the image
 * extends behind the system bars.
 */
class ImageImportActivity : AppCompatActivity() {

    private lateinit var prefs: Prefs
    private lateinit var session: ImageImportSession
    private lateinit var controller: ImageReviewController
    private var gearMenu: CameraGearMenu? = null

    private lateinit var landingGroup: LinearLayout
    private lateinit var pasteChip: Button
    private lateinit var pageChip: LinearLayout
    private lateinit var pageLabel: android.widget.TextView
    private lateinit var prevPage: android.widget.ImageButton
    private lateinit var nextPage: android.widget.ImageButton
    private var pageGrid: PageGridOverlay? = null

    /** Entered via the share sheet: dismissing the review finishes back to
     *  the sharing app instead of showing the picker landing. */
    private var fromShare = false

    /** Read-settings fingerprint (language configuration + the IMPORT-scoped
     *  OCR engine resolution); a diff on resume re-reads the retained image.
     *  The token matters for the pickers' not-yet-downloaded picks: the
     *  download screen persists the import token while this activity is
     *  paused. */
    private var sessionLangKey: String? = null

    private val pickPhoto =
        registerForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
            uri?.let(::loadFromUri)
        }

    private val pickFile =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            uri?.let(::loadFromUri)
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        applyTheme(this)
        applyEdgeToEdge(this)
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_image_import)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        prefs = Prefs(this)

        landingGroup = findViewById(R.id.importLandingGroup)
        pasteChip = findViewById(R.id.importPasteChip)
        // Chip views bind BEFORE the controller: its construction syncs
        // controls, which fans into updatePageChip.
        pageChip = findViewById(R.id.importPageChip)
        pageLabel = findViewById(R.id.importPageLabel)
        prevPage = findViewById(R.id.importPrevPage)
        nextPage = findViewById(R.id.importNextPage)

        // Only the floating controls avoid the system bars / cutout; the
        // image underneath stays full-bleed.
        val controls = findViewById<FrameLayout>(R.id.importControls)
        ViewCompat.setOnApplyWindowInsetsListener(controls) { v, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }

        // The panel's in-place edit needs the IME to overlay the image, not
        // resize the layout: the sheet lifts itself via its ime-inset bottom
        // margin, same as its over-game window mode.
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING)

        session = ImageImportSession(
            context = this,
            scope = lifecycleScope,
            overlayHost = findViewById(R.id.importOverlayHost),
            onSlowOcr = { maybeShowSlowOcrPrompt() },
            onSceneCompleted = { gen -> controller.onSceneCompleted(gen) },
        )
        controller = ImageReviewController(
            activity = this,
            session = session,
            backButton = findViewById(R.id.importBack),
            regionButton = findViewById(R.id.importRegionSelect),
            controlPill = findViewById(R.id.importControlPill),
            imageFrame = findViewById(R.id.importImageFrame),
            panelHost = findViewById(R.id.importPanelHost),
            regionUi = CameraRegionUi(
                activity = this,
                fullBleedHost = findViewById(R.id.importOverlayHost),
                controlsHost = controls,
            ),
            scope = lifecycleScope,
            onExit = { finish() },
            onReviewClosed = {
                if (fromShare) finish() else landingGroup.isVisible = true
            },
            onPageStateChanged = { updatePageChip() },
        )
        gearMenu = CameraGearMenu(
            activity = this,
            pill = findViewById(R.id.importControlPill),
            iconRow = findViewById(R.id.importPillIconRow),
            menuHost = findViewById(R.id.importPillMenu),
            gearButton = findViewById(R.id.importSettings),
            controlsHost = controls,
            onLanguageRow = {
                // Same target as the camera's Language row: the source picker
                // screen. Return lands in onResume, whose langKey diff
                // re-reads the retained image.
                com.playtranslate.ui.LanguageSetupActivity.selectionDelegate = null
                startActivity(
                    Intent(this, com.playtranslate.ui.LanguageSetupActivity::class.java)
                        .putExtra(
                            com.playtranslate.ui.LanguageSetupActivity.EXTRA_MODE,
                            com.playtranslate.ui.LanguageSetupActivity.MODE_SOURCE,
                        )
                )
            },
            onOcrRow = { showPillOcrPicker() },
            onCycleOverlayMode = { cycleOverlayFlavor() },
            // The import tool's own scoped selections label the rows.
            ocrToken = { prefs.importOcrBackendToken(it) },
            overlayMode = { prefs.importOverlayMode },
        )
        sessionLangKey = langKey()

        onBackPressedDispatcher.addCallback(this) {
            when {
                // Modals close first: the page grid, then the gear menu —
                // back never reaches the review under either.
                pageGrid?.isOpen == true -> pageGrid?.close()
                gearMenu?.isOpen == true -> gearMenu?.close()
                controller.isCropActive -> controller.cancelCrop()
                controller.isReviewing -> controller.dismissReview()
                else -> finish()
            }
        }

        pageGrid = PageGridOverlay(this, controls, lifecycleScope)
        prevPage.setOnClickListener { controller.goToPage(controller.currentPage - 1) }
        nextPage.setOnClickListener { controller.goToPage(controller.currentPage + 1) }
        pageLabel.setOnClickListener {
            val src = controller.pageSource ?: return@setOnClickListener
            pageGrid?.open(src, controller.currentPage) { page -> controller.goToPage(page) }
        }

        findViewById<Button>(R.id.importPickPhotos).setOnClickListener {
            pickPhoto.launch(
                PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
            )
        }
        findViewById<Button>(R.id.importPickFiles).setOnClickListener {
            pickFile.launch(
                arrayOf(
                    "image/*",
                    "application/pdf",
                    "application/zip",
                    "application/x-cbz",
                    "application/vnd.comicbook+zip",
                ),
            )
        }
        pasteChip.setOnClickListener { pasteFromClipboard() }

        // Entry routing: a share lands straight in review; a process-death
        // recreate restores from the review's own saved frame file (the
        // original URI's read grant rarely survives the process — SAF
        // grants die with it unless persisted, photo-picker/share/clipboard
        // grants are not persistable at all); the explicit-intent launch
        // shows the landing. The restore wins over re-reading a redelivered
        // share intent: same image, and the file read cannot hit a dead
        // grant. Frame files are per-cycle unique, so the restore target is
        // the SAVED path; the sweep collects orphans from crashed/killed
        // reviews while explicitly keeping that target.
        val restorePath = savedInstanceState?.getString(STATE_FRAME_PATH)
        lifecycleScope.launch(Dispatchers.IO) {
            ImageImportSession.sweepOrphanedFrames(this@ImageImportActivity, keepPath = restorePath)
            // Comic-archive working copies orphaned by a crash (their
            // review's close never ran) — same age gate as the frames.
            val cutoff = System.currentTimeMillis() - CBZ_SWEEP_AGE_MS
            cacheDir.listFiles()?.forEach { f ->
                if (f.isFile && f.name.startsWith(CbzPageSource.TEMP_PREFIX) &&
                    f.lastModified() < cutoff
                ) {
                    runCatching { f.delete() }
                }
            }
        }
        val shareMime = intent?.type
        val sharedUri =
            if (intent?.action == Intent.ACTION_SEND &&
                (shareMime?.startsWith("image/") == true || shareMime == "application/pdf")
            ) {
                IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)
            } else null
        fromShare = sharedUri != null
        when {
            restorePath != null -> restoreFromFile(java.io.File(restorePath))
            sharedUri != null -> loadFromUri(sharedUri, shareMime)
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        // Null before the first cache publication or on a no-text review —
        // those restores degrade to the landing, which loses nothing.
        // SINGLE-IMAGE reviews only: documents are not retained across
        // process death by design (a one-page frame restore of page N would
        // masquerade as the whole document).
        session.currentFramePath()
            ?.takeIf { controller.isReviewing && controller.pageCount == 1 }
            ?.let { outState.putString(STATE_FRAME_PATH, it) }
    }

    override fun onPause() {
        super.onPause()
        // Modal menus don't survive leaving the screen — and a collapse
        // animation caught by the outgoing window transition reads as the
        // pill deforming mid-morph.
        gearMenu?.closeInstant()
    }

    override fun onResume() {
        super.onResume()
        controller.syncControls()
        if (sessionLangKey != null && sessionLangKey != langKey()) {
            refreshAfterReadSettingsChange()
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        // Clipboard reads require window focus (Android 10+); the DESCRIPTION
        // check is silent — the system's paste notice only fires on clip DATA
        // access, which waits for the chip tap.
        if (hasFocus) syncPasteChip()
    }

    override fun onDestroy() {
        pageGrid?.destroy()
        pageGrid = null
        gearMenu?.destroy()
        gearMenu = null
        if (::controller.isInitialized) controller.release()
        super.onDestroy()
    }

    /** Re-derive the page chip from the controller's page + chrome state.
     *  Fired on page/count changes AND every syncControls (crop hides the
     *  chip with the rest of the chrome). */
    private fun updatePageChip() {
        if (!::controller.isInitialized) return
        val count = controller.pageCount
        val show = controller.isReviewing && count > 1 && !controller.isCropActive
        pageChip.isVisible = show
        if (!show) {
            if (pageGrid?.isOpen == true) pageGrid?.close()
            return
        }
        val current = controller.currentPage
        pageLabel.text = getString(R.string.image_import_page_chip, current + 1, count)
        prevPage.isEnabled = current > 0
        prevPage.alpha = if (current > 0) 1f else 0.35f
        nextPage.isEnabled = current < count - 1
        nextPage.alpha = if (current < count - 1) 1f else 0.35f
    }

    // ── File sources ────────────────────────────────────────────────────

    private fun loadFromUri(uri: Uri, declaredMime: String? = null) =
        loadFrom { openPageSource(this, uri, declaredMime) }

    /** Process-death restore: re-decode the review's own saved frame file
     *  (single-image reviews only — documents are not retained across
     *  process death by design; the user re-picks). A purged or swept file
     *  degrades to the landing like any bad source. */
    private fun restoreFromFile(file: java.io.File) = loadFrom {
        when (val r = UprightImageDecoder.decode(file)) {
            is UprightImageDecoder.Result.Success ->
                OpenResult.Ready(ImagePageSource(r.bitmap))
            is UprightImageDecoder.Result.Failure ->
                OpenResult.Failure(OpenResult.FailureReason.CORRUPT)
        }
    }

    /** True while an open/render is in flight. Loads are single-flight: a
     *  tap during one is ignored rather than queued — kills double-tap jank
     *  (paste chip) and makes a stale open finishing after a newer one
     *  structurally impossible. Main thread only. */
    private var loadInFlight = false

    private fun loadFrom(open: () -> OpenResult) {
        if (loadInFlight) return
        loadInFlight = true
        lifecycleScope.launch {
            // A source opened on IO is a CLOSEABLE (PDF descriptor/renderer,
            // CBZ working copy). Cancellation bites at the withContext
            // RESUME — the IO block completes but nothing after it runs —
            // so the source must be captured where the coroutine's finally
            // can reach it, and cleared only when ownership transfers to
            // the controller. Without this, destroying the activity mid-open
            // stranded the resource until process death / the orphan sweep.
            var pending: PageSource? = null
            try {
                // Open + first-page render together off-main: the review
                // starts with a ready bitmap, and an unreadable first page
                // fails the whole open (nothing to land on).
                val outcome = withContext(Dispatchers.IO) {
                    when (val r = open()) {
                        is OpenResult.Failure -> null to r.reason
                        is OpenResult.Ready -> {
                            pending = r.source
                            val first = r.source.renderPage(0)
                            if (first == null) {
                                pending = null
                                r.source.close()
                                null to OpenResult.FailureReason.CORRUPT
                            } else {
                                Pair(r.source, first) to null
                            }
                        }
                    }
                }
                loadInFlight = false
                if (isFinishing || isDestroyed) return@launch
                val ready = outcome.first
                if (ready != null) {
                    landingGroup.isVisible = false
                    controller.startDocument(ready.first, ready.second)
                    // Ownership transferred — the finally must not close it.
                    pending = null
                } else {
                    Toast.makeText(
                        this@ImageImportActivity, failureText(outcome.second), Toast.LENGTH_LONG,
                    ).show()
                    // A share entry with an unreadable file has nothing to
                    // land on; the landing still offers the other sources.
                    landingGroup.isVisible = true
                    fromShare = false
                }
            } finally {
                pending?.closeAsync()
            }
        }
    }

    private fun failureText(reason: OpenResult.FailureReason?): Int = when (reason) {
        OpenResult.FailureReason.UNSUPPORTED -> R.string.image_import_unsupported
        OpenResult.FailureReason.PASSWORD_PROTECTED -> R.string.image_import_password_protected
        OpenResult.FailureReason.EMPTY -> R.string.image_import_empty
        else -> R.string.image_import_decode_failed
    }

    private fun syncPasteChip() {
        // Only meaningful on the landing; the review has no paste surface.
        val clipboard = getSystemService(ClipboardManager::class.java)
        pasteChip.isVisible =
            clipboard?.primaryClipDescription?.hasMimeType("image/*") == true
    }

    private fun pasteFromClipboard() {
        val clipboard = getSystemService(ClipboardManager::class.java)
        val uri = clipboard?.primaryClip?.getItemAt(0)?.uri
        if (uri == null) {
            // The description promised an image but the item carries no URI
            // (some apps put inline data in exotic shapes) — nothing to read.
            Toast.makeText(this, R.string.image_import_decode_failed, Toast.LENGTH_LONG).show()
            return
        }
        // The clip DESCRIPTION already told us this is an image (the chip
        // only shows on that) — pass that knowledge through so routing
        // never depends on the clipboard provider's getType(), which may
        // be null.
        val desc = clipboard.primaryClipDescription
        val imageMime = desc?.let { d ->
            (0 until d.mimeTypeCount).map { d.getMimeType(it) }
                .firstOrNull { it.startsWith("image/") }
        } ?: "image/*"
        loadFromUri(uri, imageMime)
    }

    // ── Read settings (language / OCR / flavor) ─────────────────────────

    /** Mirror of the camera activity's fingerprint, on the IMPORT scope. */
    private fun langKey(): String =
        "${prefs.sourceLangId}|${prefs.targetLang}|${prefs.targetChineseVariant}|" +
            (
                OcrModelManager.selectedBackend(
                    this, prefs.sourceLangId, prefs.importOcrBackendToken(prefs.sourceLangId),
                )?.selectionToken ?: ""
            )

    /** The read settings changed (LanguageSetupActivity return, an OCR pick,
     *  a completed download): re-read the retained image under them. langKey
     *  is stamped here so onResume's diff doesn't double-fire. */
    private fun refreshAfterReadSettingsChange() {
        sessionLangKey = langKey()
        controller.refreshReview()
    }

    /** The pill gear menu's OCR row: the shared picker in its activity form.
     *  A changed, downloaded pick re-reads in place; a not-downloaded pick
     *  deep-links the OCR download screen (the review stays behind it and
     *  refreshes on return via the langKey diff). */
    private fun showPillOcrPicker() {
        val srcId = prefs.sourceLangId
        val token = OcrModelManager
            .selectedBackend(this, srcId, prefs.importOcrBackendToken(srcId))
            ?.selectionToken ?: ""
        com.playtranslate.ui.OcrPicker.populate(
            OverlayAlert.Builder(this),
            this,
            srcId,
            token,
            onReOcr = { refreshAfterReadSettingsChange() },
            onDownload = { backend ->
                // IMPORT scope: the download screen persists the import
                // token, only on verified success — the global/live engine
                // never moves on an import-only action.
                startActivity(
                    com.playtranslate.ui.CaptureOverlaySettingsActivity.downloadIntent(
                        this, srcId, backend.selectionToken, scope = OcrTokenScope.IMPORT,
                    )
                )
            },
            // The import tool's OCR choice is its own per-flow setting.
            applyToken = { backend ->
                prefs.setImportOcrBackendToken(srcId, backend.selectionToken)
            },
        ).show()
    }

    /** Cycle the import tool's own Translation vs Furigana/Pinyin flavor
     *  (the gear menu's Overlays row; cycles in place, menu stays open).
     *  Repaints only when boxes are actually the current presentation —
     *  painting from an expanded panel would resurrect boxes it didn't ask
     *  for; the pref applies whenever boxes next show. The repaint path
     *  never translates, so a cycle mid-load can't duplicate the pipeline's
     *  in-flight backend batch. */
    private fun cycleOverlayFlavor() {
        prefs.importOverlayMode = if (prefs.importOverlayMode == OverlayMode.TRANSLATION) {
            OverlayMode.FURIGANA
        } else {
            OverlayMode.TRANSLATION
        }
        if (controller.isReviewing && session.hasVisibleOverlays()) session.showOverlays()
    }

    // ── Slow-OCR rescue prompt ──────────────────────────────────────────

    /** The rescue alert currently up, if any. The in-activity [OverlayAlert]
     *  detaches itself on activity pause, which records no answer — the
     *  next slow read may offer again, same contract as the camera. */
    private var slowOcrAlert: OverlayAlert? = null

    /** A review cycle's OCR ran past the shared slow threshold: offer the
     *  one-tap switch to the rescue engine — the camera's offer
     *  (CameraActivity.maybeShowSlowOcrPrompt) on fully IMPORT-scoped state:
     *  the switch writes the import token, the answered latch is the
     *  import's own, and accepting re-reads the retained image. */
    private fun maybeShowSlowOcrPrompt() {
        if (isFinishing || isDestroyed) return
        if (slowOcrAlert?.isShowing == true) return
        // The timer only wraps review cycles, but a dismissal can race the
        // callback — never prompt over the landing.
        if (!controller.isReviewing) return
        val id = prefs.sourceLangId
        if (prefs.importSlowOcrPromptAnswered(id)) return
        val selected =
            OcrModelManager.selectedBackend(this, id, prefs.importOcrBackendToken(id)) ?: return
        val rescue = OcrModelManager.slowOcrRescue(
            available = OcrModelManager.availableBackends(this, id),
            selected = selected,
            mlKitFloor = SourceLanguageProfiles[id].mlKitFloor,
        ) ?: return

        slowOcrAlert = OverlayAlert.Builder(this)
            .setTitle(getString(R.string.slow_ocr_prompt_title))
            .setMessage(getString(R.string.slow_ocr_prompt_message_import))
            .addButton(
                getString(R.string.slow_ocr_prompt_switch),
                themeColor(R.attr.ptAccent),
            ) {
                slowOcrAlert = null
                // Import-scoped, BOTH pieces: only this tool's engine
                // switches, and the answered latch scopes with it.
                prefs.setImportSlowOcrPromptAnswered(id)
                prefs.setImportOcrBackendToken(id, rescue.selectionToken)
                DetectionLog.log("import slow-OCR prompt: ${id.code} switched to ${rescue.selectionToken}")
                // The refresh below re-reads the retained image on the
                // rescue engine and stamps the fingerprint so onResume
                // doesn't refresh a second time.
                refreshAfterReadSettingsChange()
            }
            .addCancelButton(getString(R.string.slow_ocr_prompt_keep)) { reason ->
                slowOcrAlert = null
                // Only an explicit user dismissal (button, scrim, back) is
                // a decision; a lifecycle detach may offer again.
                if (reason == DismissReason.USER) prefs.setImportSlowOcrPromptAnswered(id)
            }
            .show()
        DetectionLog.log(
            "import slow-OCR prompt shown for ${id.code} (selected=${selected.selectionToken})"
        )
    }

    private companion object {
        /** The active review's saved frame path at save time — restored on
         *  recreate. The review pins orientation, so this only fires on
         *  true process death. */
        const val STATE_FRAME_PATH = "import_frame_path"

        /** Crash-orphan cutoff for CBZ working copies (mirrors the frame
         *  sweep's 24h). */
        const val CBZ_SWEEP_AGE_MS = 24 * 60 * 60 * 1000L
    }
}
