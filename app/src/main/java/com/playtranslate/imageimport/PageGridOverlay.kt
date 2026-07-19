package com.playtranslate.imageimport

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.util.LruCache
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.playtranslate.R
import com.playtranslate.themeColor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The page grid: a full-screen modal over the review (scrim + thumbnail
 * grid) for random access into a document — the page chip's chevrons serve
 * the sequential loop; this serves "jump to the page with the diagram".
 * Thumbnails render lazily off-main per cell (job cancelled on recycle,
 * small LRU); the current page carries an accent border. Tap = jump + close;
 * scrim or back closes.
 *
 * Hosted as a child of the inset-padded controls layer, above every other
 * review surface (the CameraGearMenu scrim precedent). Main thread only.
 */
class PageGridOverlay(
    private val activity: Activity,
    private val host: ViewGroup,
    private val scope: CoroutineScope,
) {
    private var root: FrameLayout? = null
    private var thumbCache = LruCache<Int, Bitmap>(THUMB_CACHE_SIZE)

    /** Stamped into every thumbnail job at launch and re-checked before any
     *  cache/bind write: `isOpen` alone is NOT enough — a close+reopen makes
     *  it true again, and this overlay OUTLIVES documents, so a straggler
     *  continuation from document A could otherwise poison document B's
     *  index-keyed cache (stale content, and a local privacy leak for
     *  sensitive files). [close] also cancels outstanding jobs outright;
     *  the stamp is the backstop for already-completed renders whose
     *  main-thread continuation hasn't run yet. */
    private var generation = 0

    /** Outstanding thumbnail jobs, cancelled wholesale at [close]. Main
     *  thread only; reset per open — bounded bookkeeping, no per-completion
     *  removal needed. */
    private val thumbJobs = mutableListOf<Job>()

    val isOpen: Boolean get() = root != null

    fun open(source: PageSource, currentPage: Int, onPick: (Int) -> Unit) {
        if (isOpen) return
        val dp = activity.resources.displayMetrics.density
        val overlay = FrameLayout(activity).apply {
            setBackgroundColor(Color.argb(210, 0, 0, 0))
            isClickable = true
            setOnClickListener { close() }
        }
        val recycler = RecyclerView(activity).apply {
            layoutManager = GridLayoutManager(activity, GRID_SPAN)
            adapter = PageAdapter(source, currentPage, dp) { page ->
                close()
                onPick(page)
            }
            clipToPadding = false
            val pad = (12 * dp).toInt()
            setPadding(pad, pad, pad, pad)
        }
        overlay.addView(
            recycler,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT,
            ),
        )
        host.addView(
            overlay,
            ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT,
            ),
        )
        root = overlay
    }

    fun close() {
        generation++
        thumbJobs.forEach { it.cancel() }
        thumbJobs.clear()
        root?.let { host.removeView(it) }
        root = null
        thumbCache.evictAll()
    }

    fun destroy() = close()

    private inner class PageAdapter(
        private val source: PageSource,
        private val currentPage: Int,
        private val dp: Float,
        private val onPick: (Int) -> Unit,
    ) : RecyclerView.Adapter<PageAdapter.Holder>() {

        inner class Holder(cell: FrameLayout, val image: ImageView, val label: TextView) :
            RecyclerView.ViewHolder(cell) {
            var thumbJob: Job? = null
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
            val cell = FrameLayout(activity).apply {
                layoutParams = RecyclerView.LayoutParams(
                    RecyclerView.LayoutParams.MATCH_PARENT, (168 * dp).toInt(),
                ).apply {
                    val m = (6 * dp).toInt()
                    setMargins(m, m, m, m)
                }
            }
            val image = ImageView(activity).apply {
                scaleType = ImageView.ScaleType.FIT_CENTER
                setBackgroundColor(Color.argb(40, 255, 255, 255))
            }
            cell.addView(
                image,
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT,
                ),
            )
            val label = TextView(activity).apply {
                setTextColor(Color.WHITE)
                textSize = 12f
                setShadowLayer(4f, 0f, 1f, Color.BLACK)
                gravity = Gravity.CENTER
            }
            cell.addView(
                label,
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT,
                    Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL,
                ).apply { bottomMargin = (4 * dp).toInt() },
            )
            return Holder(cell, image, label)
        }

        override fun getItemCount(): Int = source.pageCount

        override fun onBindViewHolder(holder: Holder, position: Int) {
            holder.label.text = (position + 1).toString()
            holder.itemView.foreground = if (position == currentPage) {
                GradientDrawable().apply {
                    setStroke((2.5f * dp).toInt(), activity.themeColor(R.attr.ptAccent))
                    cornerRadius = 4 * dp
                }
            } else null
            holder.itemView.setOnClickListener { onPick(position) }
            holder.thumbJob?.cancel()
            val cached = thumbCache.get(position)
            if (cached != null && !cached.isRecycled) {
                holder.image.setImageBitmap(cached)
                return
            }
            holder.image.setImageBitmap(null)
            val gen = generation
            val job = scope.launch {
                val thumb = withContext(Dispatchers.IO) {
                    source.renderThumb(position, THUMB_MAX_DIM_PX)
                }
                // The generation stamp gates the write — see [generation].
                if (thumb != null && gen == generation && isOpen) {
                    thumbCache.put(position, thumb)
                    // The holder may have been rebound while rendering.
                    if (holder.bindingAdapterPosition == position) {
                        holder.image.setImageBitmap(thumb)
                    }
                }
            }
            holder.thumbJob = job
            thumbJobs.add(job)
        }

        override fun onViewRecycled(holder: Holder) {
            holder.thumbJob?.cancel()
            holder.thumbJob = null
        }
    }

    private companion object {
        const val GRID_SPAN = 3
        const val THUMB_MAX_DIM_PX = 360
        const val THUMB_CACHE_SIZE = 24
    }
}
