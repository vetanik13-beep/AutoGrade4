package com.autograde.app

import android.content.ContentValues
import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.Effect
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.SingleColorLut
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.transformer.Composition
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.Effects
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.Transformer
import androidx.media3.ui.PlayerView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { MaterialTheme { Surface(Modifier.fillMaxSize()) { App() } } }
    }
}

@androidx.annotation.OptIn(UnstableApi::class)
@Composable
fun App() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var videoUri by remember { mutableStateOf<Uri?>(null) }
    var stats by remember { mutableStateOf<Stats?>(null) }
    var target by remember { mutableStateOf(Target()) }
    var strength by remember { mutableFloatStateOf(1f) }
    var gradeOn by remember { mutableStateOf(true) }
    var status by remember { mutableStateOf("Pick a video to start.") }
    var exporting by remember { mutableStateOf(false) }

    val player = remember {
        ExoPlayer.Builder(context).build().apply { repeatMode = Player.REPEAT_MODE_ALL }
    }
    DisposableEffect(Unit) { onDispose { player.release() } }

    val pickVideo = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) {
            videoUri = uri
            status = "Analyzing video…"
            scope.launch {
                stats = withContext(Dispatchers.Default) { ColorGrade.analyzeVideo(context, uri) }
                status = "Auto grade applied. Adjust strength if needed."
            }
        }
    }

    val pickReference = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) scope.launch {
            val s = withContext(Dispatchers.Default) {
                val opts = BitmapFactory.Options().apply { inSampleSize = 4 }
                val bmp = context.contentResolver.openInputStream(uri)?.use {
                    BitmapFactory.decodeStream(it, null, opts)
                }
                bmp?.let { ColorGrade.statsOf(listOf(it)) }
            }
            if (s != null) {
                target = Target(luma = s.luma, sat = s.sat)
                status = "Reference look loaded."
            }
        }
    }

    fun lutFor(): Effect? {
        val s = stats ?: return null
        val p = ColorGrade.paramsFor(s, target)
        return SingleColorLut.createFromCube(ColorGrade.buildCube(p, strength))
    }

    LaunchedEffect(videoUri) {
        videoUri?.let {
            player.setMediaItem(MediaItem.fromUri(it))
            player.prepare()
            player.playWhenReady = true
        }
    }
    LaunchedEffect(stats, target, strength, gradeOn) {
        val lut = if (gradeOn) lutFor() else null
        player.setVideoEffects(listOfNotNull(lut))
    }

    Column(
        Modifier.fillMaxSize().padding(16.dp).statusBarsPadding(),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("AutoGrade", style = MaterialTheme.typography.headlineSmall)

        AndroidView(
            factory = { ctx -> PlayerView(ctx).also { it.player = player } },
            modifier = Modifier.fillMaxWidth().weight(1f)
        )

        Text(status, style = MaterialTheme.typography.bodyMedium)

        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Grade on", Modifier.weight(1f))
            Switch(checked = gradeOn, onCheckedChange = { gradeOn = it })
        }

        Text("Strength: ${(strength * 100).toInt()}%")
        Slider(value = strength, onValueChange = { strength = it }, valueRange = 0f..1f)

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { pickVideo.launch("video/*") }, modifier = Modifier.weight(1f)) { Text("Video") }
            OutlinedButton(onClick = { pickReference.launch("image/*") }, modifier = Modifier.weight(1f)) { Text("Reference photo") }
        }

        Button(
            enabled = videoUri != null && stats != null && !exporting,
            modifier = Modifier.fillMaxWidth(),
            onClick = {
                val lut = lutFor() ?: return@Button
                exporting = true
                status = "Exporting…"
                exportVideo(context, videoUri!!, lut) { msg ->
                    status = msg
                    exporting = false
                }
            }
        ) { Text(if (exporting) "Exporting…" else "Export graded video") }
    }
}

@androidx.annotation.OptIn(UnstableApi::class)
fun exportVideo(context: Context, uri: Uri, lut: Effect, done: (String) -> Unit) {
    val out = File(context.cacheDir, "graded_${System.currentTimeMillis()}.mp4")
    val item = EditedMediaItem.Builder(MediaItem.fromUri(uri))
        .setEffects(Effects(emptyList(), listOf(lut)))
        .build()
    val transformer = Transformer.Builder(context)
        .addListener(object : Transformer.Listener {
            override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                Thread {
                    val msg = try {
                        saveToGallery(context, out)
                        "Saved to Movies/AutoGrade."
                    } catch (e: Exception) {
                        "Export done but saving failed: ${e.message}"
                    }
                    android.os.Handler(context.mainLooper).post { done(msg) }
                }.start()
            }

            override fun onError(composition: Composition, exportResult: ExportResult, exportException: ExportException) {
                done("Export failed: ${exportException.message}")
            }
        })
        .build()
    transformer.start(item, out.absolutePath)
}

fun saveToGallery(context: Context, file: File) {
    val values = ContentValues().apply {
        put(MediaStore.Video.Media.DISPLAY_NAME, "AutoGrade_${System.currentTimeMillis()}.mp4")
        put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
        put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/AutoGrade")
    }
    val dest = context.contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
        ?: error("Could not create file")
    context.contentResolver.openOutputStream(dest)!!.use { o -> file.inputStream().use { it.copyTo(o) } }
    file.delete()
}
