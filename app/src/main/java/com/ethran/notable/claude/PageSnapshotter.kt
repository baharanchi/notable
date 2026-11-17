package com.ethran.notable.claude

import android.graphics.Bitmap
import android.graphics.Color
import android.util.Base64
import androidx.core.graphics.createBitmap
import androidx.core.graphics.scale
import com.ethran.notable.claude.bridge.ClaudeImage
import com.ethran.notable.data.PageSaver
import com.ethran.notable.data.datastore.AppSettings
import com.ethran.notable.editor.PageView
import com.ethran.notable.editor.canvas.CanvasEventBus
import com.ethran.notable.io.PageContentRenderer
import com.ethran.notable.io.RenderTarget
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.max
import kotlin.math.min

/**
 * Turns the page being edited into PNG images for Claude.
 *
 * - [AppSettings.ClaudeCaptureMode.Visible] copies the editor's screen bitmap: exactly what the
 *   user sees, including the PDF or template background, at the current scroll and zoom.
 * - [AppSettings.ClaudeCaptureMode.FullPage] saves pending edits, renders the whole page from the
 *   database and cuts it into screen-sized slices, so long pages stay legible.
 */
@Singleton
class PageSnapshotter @Inject constructor(
    private val pageSaver: PageSaver,
    private val renderer: PageContentRenderer,
) {

    suspend fun capture(page: PageView, mode: AppSettings.ClaudeCaptureMode): List<ClaudeImage> =
        when (mode) {
            AppSettings.ClaudeCaptureMode.Visible -> listOf(captureVisible(page))
            AppSettings.ClaudeCaptureMode.FullPage -> captureFullPage(page)
        }

    private suspend fun captureVisible(page: PageView): ClaudeImage {
        // The screen bitmap is drawn on the main thread; copy it there, between strokes.
        val copy = withContext(Dispatchers.Main.immediate) {
            CanvasEventBus.drawingInProgress.withLock {
                page.windowedBitmap.copy(Bitmap.Config.ARGB_8888, false)
            }
        }
        return withContext(Dispatchers.Default) {
            encode(flattenOnWhite(copy), "Snapshot of the visible part of the page")
        }
    }

    private suspend fun captureFullPage(page: PageView): List<ClaudeImage> {
        val pageId = page.currentPageId
        // The renderer reads the database: write the latest strokes first.
        pageSaver.saveAll()
        val full = withContext(Dispatchers.IO) { renderer.renderPageBitmap(pageId, RenderTarget.Full) }
        return withContext(Dispatchers.Default) {
            val sliceHeight = max(full.width * 4 / 3, 1)
            val count = (full.height + sliceHeight - 1) / sliceHeight
            (0 until count).map { i ->
                val top = i * sliceHeight
                val height = min(sliceHeight, full.height - top)
                val slice = Bitmap.createBitmap(full, 0, top, full.width, height)
                val label = if (count == 1) "Snapshot of the whole page"
                else "Whole page, part ${i + 1} of $count (top to bottom)"
                encode(flattenOnWhite(slice), label)
            }
        }
    }

    /** Transparent areas would come out black for the model; e-ink pages are white. */
    private fun flattenOnWhite(source: Bitmap): Bitmap {
        val out = createBitmap(source.width, source.height)
        val canvas = android.graphics.Canvas(out)
        canvas.drawColor(Color.WHITE)
        canvas.drawBitmap(source, 0f, 0f, null)
        return out
    }

    private fun encode(bitmap: Bitmap, label: String): ClaudeImage {
        val longEdge = max(bitmap.width, bitmap.height)
        val scaled = if (longEdge > MAX_EDGE_PX) {
            val f = MAX_EDGE_PX.toFloat() / longEdge
            bitmap.scale((bitmap.width * f).toInt().coerceAtLeast(1), (bitmap.height * f).toInt().coerceAtLeast(1))
        } else bitmap
        val bytes = ByteArrayOutputStream().use { out ->
            scaled.compress(Bitmap.CompressFormat.PNG, 100, out)
            out.toByteArray()
        }
        return ClaudeImage(
            mime = "image/png",
            data = Base64.encodeToString(bytes, Base64.NO_WRAP),
            label = label,
        )
    }

    private companion object {
        /** Larger images are downscaled by the API anyway; this keeps uploads small. */
        const val MAX_EDGE_PX = 2000
    }
}
