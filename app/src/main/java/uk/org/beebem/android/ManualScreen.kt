package uk.org.beebem.android

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color as AndroidColor
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

// ── Elite's original manual ────────────────────────────────────────────────
//
// The Space Trader's Flight Training Manual that came with BBC Micro Elite,
// as the scan published on bbcelite.com (Mark Moxon's annotated Elite site).
// It is downloaded once into the app's files and shown page by page with
// Android's PdfRenderer, so it is the original printed manual, not a copy
// made by this app.

private const val MANUAL_URL =
    "https://elite.bbcelite.com/pdfs/Space_Traders_Flight_Training_Manual_BBC_Micro.pdf"
private const val MANUAL_FILE = "elite_manual.pdf"

// PdfRenderer allows one page open at a time and is not thread-safe.
private val pdfDispatcher = Executors.newSingleThreadExecutor().asCoroutineDispatcher()

private suspend fun downloadManual(context: Context, onProgress: (Float) -> Unit): File? =
    withContext(Dispatchers.IO) {
        val target = File(context.filesDir, MANUAL_FILE)
        if (target.exists() && target.length() > 0) return@withContext target
        val tmp = File(context.filesDir, "$MANUAL_FILE.tmp")
        runCatching {
            val conn = URL(MANUAL_URL).openConnection() as HttpURLConnection
            conn.connectTimeout = 15_000
            conn.readTimeout = 60_000
            val total = conn.contentLength.toLong()
            var done = 0L
            conn.inputStream.use { input ->
                tmp.outputStream().use { out ->
                    val buf = ByteArray(16384)
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        done += n
                        if (total > 0) onProgress(done.toFloat() / total)
                    }
                }
            }
            conn.disconnect()
            if (!tmp.renameTo(target)) { tmp.copyTo(target, overwrite = true); tmp.delete() }
            target
        }.getOrElse { tmp.delete(); null }
    }

@Composable
fun EliteManualScreen(onDismiss: () -> Unit) {
    val context = LocalContext.current
    var progress by remember { mutableFloatStateOf(0f) }
    var failed by remember { mutableStateOf(false) }
    var renderer by remember { mutableStateOf<PdfRenderer?>(null) }

    LaunchedEffect(Unit) {
        val file = downloadManual(context) { progress = it }
        if (file == null) { failed = true; return@LaunchedEffect }
        renderer = withContext(pdfDispatcher) {
            runCatching {
                PdfRenderer(ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY))
            }.getOrNull()
        }
        if (renderer == null) { file.delete(); failed = true }
    }
    DisposableEffect(Unit) {
        onDispose { renderer?.let { r -> pdfDispatcher.executor.execute { runCatching { r.close() } } } }
    }

    var wide by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()

    Column(Modifier.fillMaxSize().background(Color(0xFF05070C))) {
        Row(
            modifier = Modifier.fillMaxWidth().height(36.dp).padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("ELITE · SPACE TRADER'S FLIGHT TRAINING MANUAL", color = Color(0xFFFFE000),
                fontSize = 12.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f))
            renderer?.let {
                Text("page ${listState.firstVisibleItemIndex + 1} / ${it.pageCount}",
                    color = Color(0xFF00E0FF), fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                TextButton(onClick = { wide = !wide }) {
                    Text(if (wide) "SMALLER" else "LARGER", color = Color(0xFF00E0FF), fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace)
                }
            }
            TextButton(onClick = onDismiss) {
                Text("CLOSE", color = Color.White, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
            }
        }

        val r = renderer
        when {
            failed -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("Couldn't fetch the manual from bbcelite.com.\nCheck the Wi-Fi and try again.",
                    color = Color.White, fontSize = 13.sp)
            }
            r == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("Fetching the manual from bbcelite.com…  ${(progress * 100).toInt()}%",
                    color = Color(0xFF00E0FF), fontSize = 13.sp, fontFamily = FontFamily.Monospace)
            }
            else -> LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp),
                contentPadding = PaddingValues(vertical = 8.dp),
            ) {
                items(r.pageCount) { index ->
                    ManualPage(r, index, Modifier.fillMaxWidth(if (wide) 0.98f else 0.62f))
                }
            }
        }
    }
}

@Composable
private fun ManualPage(renderer: PdfRenderer, index: Int, modifier: Modifier) {
    // Each page is rendered when it scrolls into view and dropped when it leaves.
    val bitmap by produceState<Bitmap?>(null, renderer, index) {
        value = withContext(pdfDispatcher) {
            runCatching {
                val page = renderer.openPage(index)
                try {
                    val width = 1400
                    val height = (width.toFloat() * page.height / page.width).toInt()
                    Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).also { bmp ->
                        bmp.eraseColor(AndroidColor.WHITE)
                        page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    }
                } finally {
                    page.close()
                }
            }.getOrNull()
        }
    }
    val bmp = bitmap
    if (bmp == null) {
        Box(modifier.aspectRatio(0.7f).background(Color(0xFF111111)))
    } else {
        Image(
            bitmap = bmp.asImageBitmap(),
            contentDescription = "Manual page ${index + 1}",
            contentScale = ContentScale.FillWidth,
            modifier = modifier,
        )
    }
}
