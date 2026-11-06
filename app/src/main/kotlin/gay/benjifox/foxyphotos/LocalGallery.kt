package gay.benjifox.foxyphotos

import android.Manifest
import android.content.ContentResolver
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.util.Size
import android.widget.MediaController
import android.widget.VideoView
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext

internal fun hasMediaAccess(context: android.content.Context): Boolean {
    fun granted(permission: String) = ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
    return if (Build.VERSION.SDK_INT >= 33) {
        granted(Manifest.permission.READ_MEDIA_IMAGES) || granted(Manifest.permission.READ_MEDIA_VIDEO) ||
            (Build.VERSION.SDK_INT >= 34 && granted(Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED))
    } else granted(Manifest.permission.READ_EXTERNAL_STORAGE)
}

internal data class GalleryMedia(val uri: Uri, val timestampMillis: Long, val video: Boolean)

// Register at the cancelling transition, not completion: provider calls block the IO thread.
@OptIn(kotlinx.coroutines.InternalCoroutinesApi::class)
internal suspend fun <T> cancellableMediaIo(block: (android.os.CancellationSignal) -> T): T = withContext(Dispatchers.IO) {
    val context = currentCoroutineContext()
    val signal = android.os.CancellationSignal()
    val registration = context[kotlinx.coroutines.Job]!!.invokeOnCompletion(onCancelling = true, invokeImmediately = true) { cause ->
        if (cause != null) signal.cancel()
    }
    try {
        context.ensureActive()
        block(signal).also { context.ensureActive() }
    } catch (error: android.os.OperationCanceledException) {
        context.ensureActive()
        throw error
    } finally {
        registration.dispose()
    }
}

internal suspend fun queryLocalGallery(resolver: ContentResolver, images: Boolean = true, videos: Boolean = true): List<GalleryMedia> = cancellableMediaIo { signal ->
    buildList {
        listOf(MediaStore.Images.Media.EXTERNAL_CONTENT_URI to false, MediaStore.Video.Media.EXTERNAL_CONTENT_URI to true).forEach { (collection, video) ->
            if ((video && !videos) || (!video && !images)) return@forEach
            val projection = arrayOf(MediaStore.MediaColumns._ID, MediaStore.MediaColumns.DATE_TAKEN, MediaStore.MediaColumns.DATE_ADDED)
            signal.throwIfCanceled()
            val cursor = resolver.query(collection, projection, "${MediaStore.MediaColumns.IS_PENDING} = 0", null, "${MediaStore.MediaColumns.DATE_TAKEN} DESC", signal)
                ?: throw java.io.IOException("Media query unavailable")
            cursor.use {
                while (it.moveToNext()) {
                    signal.throwIfCanceled()
                    add(GalleryMedia(Uri.withAppendedPath(collection, it.getLong(0).toString()),
                        it.getLong(1).takeIf { date -> date > 0 } ?: (it.getLong(2) * 1000), video))
                }
            }
        }
    }.sortedWith(compareByDescending<GalleryMedia> { it.timestampMillis }.thenBy { it.uri.toString() })
}

private val decodeSlots = Semaphore(3)

@Composable
private fun MediaImage(media: GalleryMedia, large: Boolean = false) {
    val resolver = LocalContext.current.contentResolver
    val bitmap by produceState<Bitmap?>(null, media.uri, large) {
        value = withContext(Dispatchers.IO) {
            decodeSlots.withPermit {
                try {
                    if (large && !media.video) ImageDecoder.decodeBitmap(ImageDecoder.createSource(resolver, media.uri)) { decoder, info, _ ->
                        val scale = (2048f / maxOf(info.size.width, info.size.height)).coerceAtMost(1f)
                        decoder.setTargetSize((info.size.width * scale).toInt().coerceAtLeast(1), (info.size.height * scale).toInt().coerceAtLeast(1))
                    } else cancellableMediaIo { signal -> resolver.loadThumbnail(media.uri, Size(320, 320), signal) }
                } catch (_: java.io.IOException) { null } catch (_: SecurityException) { null } catch (_: IllegalArgumentException) { null }
            }
        }
    }
    Box(Modifier.fillMaxSize().background(Color(0xFF24242A)), contentAlignment = Alignment.Center) {
        if (bitmap != null) Image(bitmap!!.asImageBitmap(), contentDescription = if (media.video) "Video" else "Photo",
            modifier = Modifier.fillMaxSize(), contentScale = if (large) ContentScale.Fit else ContentScale.Crop)
        else Text("Unavailable", style = MaterialTheme.typography.labelSmall)
        if (media.video) Text("▶", modifier = Modifier.align(Alignment.BottomEnd).padding(8.dp))
    }
}

@Composable
internal fun LocalGallery(modifier: Modifier = Modifier, gridState: LazyGridState = rememberLazyGridState(), onPermission: () -> Unit) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current
    var generation by remember { mutableIntStateOf(0) }
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_RESUME) generation++ }
        lifecycle.lifecycle.addObserver(observer)
        onDispose { lifecycle.lifecycle.removeObserver(observer) }
    }
    fun granted(permission: String) = ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
    val full = if (Build.VERSION.SDK_INT >= 33) granted(Manifest.permission.READ_MEDIA_IMAGES) && granted(Manifest.permission.READ_MEDIA_VIDEO) else granted(Manifest.permission.READ_EXTERNAL_STORAGE)
    val accessible = hasMediaAccess(context)
    var media by remember { mutableStateOf<List<GalleryMedia>>(emptyList()) }
    var status by remember { mutableStateOf("Loading photos…") }
    LaunchedEffect(generation, accessible, full) {
        if (!accessible) { status = "Photo access is off"; media = emptyList(); return@LaunchedEffect }
        status = "Loading photos…"
        try {
            val selectedAccess = Build.VERSION.SDK_INT >= 34 && granted(Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED)
            media = queryLocalGallery(context.contentResolver,
                images = Build.VERSION.SDK_INT < 33 || selectedAccess || granted(Manifest.permission.READ_MEDIA_IMAGES),
                videos = Build.VERSION.SDK_INT < 33 || selectedAccess || granted(Manifest.permission.READ_MEDIA_VIDEO))
            status = if (media.isEmpty()) "No photos or videos available" else ""
        }
        catch (_: SecurityException) { media = emptyList(); status = "Photo access changed. Allow access to continue." }
        catch (_: java.io.IOException) { media = emptyList(); status = "Could not load photos" }
        catch (_: android.database.sqlite.SQLiteException) { media = emptyList(); status = "Could not load photos" }
    }

    var selected by rememberSaveable { mutableStateOf<String?>(null) }
    var selecting by rememberSaveable { mutableStateOf(false) }
    var selectedIds by rememberSaveable { mutableStateOf(arrayListOf<String>()) }
    var selectionError by remember { mutableStateOf<String?>(null) }
    fun backup(ids: Set<String>) {
        if (AndroidBackupPolicy(context).requestItems(ids)) {
            selectionError = null
            selectedIds = arrayListOf()
            selecting = false
            BackupRuntime.command(context, BackupService.START)
        } else selectionError = "Backup busy. Selection kept; finish or cancel the current request first."
    }
    fun clearSelection() {
        selecting = false
        selectedIds = arrayListOf()
        selectionError = null
    }
    BackHandler(enabled = selecting && selected == null) { clearSelection() }
    // Revoke stale selections when media access or the accessible collection changes.
    LaunchedEffect(media, status) {
        if (status == "Loading photos…") return@LaunchedEffect
        val accessibleIds = media.mapTo(hashSetOf()) { it.uri.toString() }
        selectedIds = ArrayList(selectedIds.filter { it in accessibleIds })
    }
    Column(modifier) {
        if (selecting) Surface(color = MaterialTheme.colorScheme.secondaryContainer) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = { clearSelection() }) { Text("Clear") }
                Text("${selectedIds.size} selected", Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
                TextButton(enabled = selectedIds.isNotEmpty(), onClick = { backup(selectedIds.toSet()) }) { Text("Back up selected") }
            }
        }
        selectionError?.let { Text(it) }
        if (!full && accessible) Text("Showing only photos and videos you allow.", Modifier.padding(12.dp), style = MaterialTheme.typography.bodySmall)
        if (!accessible || !full) TextButton(onClick = onPermission) { Text("Allow photos access") }
        if (status.isNotEmpty()) Text(status, Modifier.padding(16.dp))
        else LazyVerticalGrid(columns = GridCells.Adaptive(100.dp), state = gridState,
            horizontalArrangement = Arrangement.spacedBy(2.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            val zone = ZoneId.systemDefault()
            val formatter = DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM)
            media.groupBy { Instant.ofEpochMilli(it.timestampMillis).atZone(zone).toLocalDate() }.forEach { (date, entries) ->
                item(key = "date:$date", span = { GridItemSpan(maxLineSpan) }) { Text(date.format(formatter), Modifier.padding(12.dp), style = MaterialTheme.typography.titleSmall) }
                items(entries, key = { it.uri.toString() }) { entry ->
                    Box(Modifier.aspectRatio(1f).combinedClickable(
                        onClick = {
                            val id = entry.uri.toString()
                            if (selecting) selectedIds = ArrayList(if (id in selectedIds) selectedIds - id else selectedIds + id)
                            else selected = id
                        },
                        onLongClickLabel = "Select media",
                        onLongClick = {
                            selecting = true
                            val id = entry.uri.toString()
                            if (id !in selectedIds) selectedIds = ArrayList(selectedIds + id)
                        },
                    )) {
                        MediaImage(entry)
                        if (selecting) Checkbox(entry.uri.toString() in selectedIds, onCheckedChange = null)
                    }
                }
            }
        }
    }
    val initial = media.indexOfFirst { it.uri.toString() == selected }
    if (initial >= 0) Dialog(onDismissRequest = { selected = null }, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        val pager = rememberPagerState(initialPage = initial, pageCount = { media.size })
        BackHandler { selected = null }
        Box(Modifier.fillMaxSize().background(Color.Black)) {
            HorizontalPager(pager, modifier = Modifier.fillMaxSize(), key = { media[it].uri.toString() }) { index ->
                val entry = media[index]
                if (entry.video && index == pager.currentPage) AndroidView(modifier = Modifier.fillMaxSize(), factory = { ctx ->
                    VideoView(ctx).apply { setVideoURI(entry.uri); setMediaController(MediaController(ctx).also { it.setAnchorView(this) }); setOnPreparedListener { start() } }
                }, onRelease = { it.stopPlayback() })
                else MediaImage(entry, large = true)
            }
            Row(Modifier.statusBarsPadding()) {
                TextButton(onClick = { selected = null }) { Text("Close") }
                TextButton(onClick = { backup(setOf(media[pager.currentPage].uri.toString())) }) { Text("Back up") }
                selectionError?.let { Text(it) }
            }
        }
    }
}
