package com.freespace.guide

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.hardware.SensorManager
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CaptureRequest
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import android.util.Range
import android.util.Size
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.OrientationEventListener
import android.view.Surface
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.camera2.interop.Camera2Interop
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.freespace.guide.databinding.ActivityMainBinding
import com.freespace.guide.feedback.FeedbackDirector
import com.freespace.guide.feedback.Sonifier
import com.freespace.guide.feedback.Speech
import com.freespace.guide.feedback.headsetConnected
import com.freespace.guide.ml.FloorSegmenter
import com.freespace.guide.scene.GeminiSceneNarrator
import com.freespace.guide.sensors.TiltTracker
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.atan
import kotlin.math.max

@ExperimentalCamera2Interop
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var tilt: TiltTracker
    private lateinit var feedback: FeedbackDirector
    private lateinit var speech: Speech
    private lateinit var analysisExecutor: ExecutorService

    private lateinit var geminiNarrator: GeminiSceneNarrator

    private lateinit var gestureDetector: GestureDetector
    private lateinit var orientationListener: OrientationEventListener

    @Volatile
    private var isLandscape = false
    @Volatile
    private var deviceRotationDegrees = 0
    private var cameraSensorOrientation = 90

    private val isAnalyzingScene = AtomicBoolean(false)
    private val captureNextFrame = AtomicBoolean(false)
    private var sceneAnalysisJob: Job? = null
    private var announcementCompletion: CompletableDeferred<Unit>? = null

    private var pipeline: NavigationPipeline? = null

    private var spatial = false

    private var frames = 0
    private var fpsWindowStart = 0L
    private var fps = 0f
    private var lastHudUpdate = 0L

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) {
                startCamera()
            } else {
                Toast.makeText(this, R.string.permission_required, Toast.LENGTH_LONG).show()
                speech.announce(getString(R.string.permission_required))
                finish()
            }
        }

    private val headsetWatch = object : AudioDeviceCallback() {
        override fun onAudioDevicesRemoved(removed: Array<out AudioDeviceInfo>) {
            if (!spatial || headsetConnected(this@MainActivity)) return
            speech.announce(getString(R.string.say_headset_lost))
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val showTextHud = SHOW_HUD_TEXT ?: SHOW_DEBUG_HUD
        val showBars = SHOW_HUD_BARS ?: SHOW_DEBUG_HUD
        binding.hud.visibility = if (showTextHud) android.view.View.VISIBLE else android.view.View.GONE
        binding.overlay.showSectorBars = showBars
        binding.overlay.showContactLine = SHOW_CONTACT_LINE

        spatial = intent.getBooleanExtra(SetupActivity.EXTRA_SPATIAL, false) && headsetConnected(this)

        analysisExecutor = Executors.newSingleThreadExecutor()
        tilt = TiltTracker(this)
        speech = Speech(this).apply {
            enabled = getSharedPreferences(SetupActivity.PREFERENCES, Context.MODE_PRIVATE)
                .getBoolean(SetupActivity.KEY_VOICE, true)
        }
        feedback = FeedbackDirector(this, Sonifier(spatial), speech)

        geminiNarrator = GeminiSceneNarrator(
            context = this,
            apiKey = BuildConfig.GEMINI_API_KEY
        )

        val segmenter = FloorSegmenter.create(this)
        if (segmenter == null) {
            binding.tvStatus.text = getString(R.string.status_model_error)
        } else {
            pipeline = NavigationPipeline(segmenter, tilt)
            binding.tvStatus.text = segmenter.modelName
        }

        setUpControls()
        setUpOrientationListener()

        if (hasCameraPermission()) {
            startCamera()
        } else {
            permissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    private fun setUpControls() {
        val listener = object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent): Boolean = true

            override fun onDoubleTap(e: MotionEvent): Boolean {
                triggerSceneDescription()
                return true
            }
        }
        gestureDetector = GestureDetector(this, listener)
        binding.root.setOnTouchListener { _, event ->
            gestureDetector.onTouchEvent(event)
            true
        }
    }

    private fun setUpOrientationListener() {
        orientationListener = object : OrientationEventListener(this, SensorManager.SENSOR_DELAY_NORMAL) {
            override fun onOrientationChanged(orientation: Int) {
                if (orientation == ORIENTATION_UNKNOWN) return

                val rounded = when (orientation) {
                    in 45..134 -> 90
                    in 135..224 -> 180
                    in 225..314 -> 270
                    else -> 0
                }
                if (rounded != deviceRotationDegrees) {
                    deviceRotationDegrees = rounded
                    val landscape = (rounded == 90 || rounded == 270)
                    if (landscape != isLandscape) {
                        isLandscape = landscape
                        Log.i(TAG, "Orientamento dispositivo: ${if (landscape) "Landscape ($rounded°)" else "Portrait ($rounded°)"}")
                    }
                }
            }
        }
    }

    private fun triggerSceneDescription() {
        if (!isAnalyzingScene.compareAndSet(false, true)) {
            Log.i(TAG, "[SCAN-IGNORED] Analisi scena già in corso, ignoro richiesta duplicata")
            return
        }

        Log.i(TAG, "[SCAN-TRIGGER] Richiesta scansione scena on-demand ricevuta")
        pipeline?.isPaused = true
        feedback.pauseSonifier()
        val completion = CompletableDeferred<Unit>()
        announcementCompletion = completion
        speech.announce(getString(R.string.say_scanning_wait)) {
            completion.complete(Unit)
        }
        captureNextFrame.set(true)
    }

    private suspend fun processSceneScan(bitmap: Bitmap, announcementDone: CompletableDeferred<Unit>?) {
        val startedAt = SystemClock.uptimeMillis()
        Log.i(TAG, "[SCAN-START] Invio snapshot a Gemini Cloud (${bitmap.width}x${bitmap.height}, Landscape: $isLandscape)")

        try {
            val result = coroutineScope {
                val geminiDeferred = async(Dispatchers.IO) {
                    try {
                        geminiNarrator.narrate(
                            bitmap = bitmap,
                            isLandscape = isLandscape
                        )
                    } catch (t: Throwable) {
                        Log.e(TAG, "Errore durante la narrazione con Gemini: ${t.message}", t)
                        GeminiSceneNarrator.NarrationResult(getString(R.string.say_gemini_error), "")
                    }
                }
                geminiDeferred.await()
            }

            val elapsedMs = SystemClock.uptimeMillis() - startedAt
            Log.i(TAG, "[GEMINI-RESULT] Modello: '${result.modelUsed}' in ${elapsedMs}ms: \"${result.text}\"")

            withContext(Dispatchers.Main) {
                if (result.modelUsed.isNotBlank()) {
                    Toast.makeText(
                        this@MainActivity,
                        "Modello AI: ${result.modelUsed} (${elapsedMs}ms)",
                        Toast.LENGTH_LONG
                    ).show()
                    binding.tvStatus.text = "FloorNet + ${result.modelUsed}"
                }
            }
            announcementDone?.await()
            delay(250)
            withContext(Dispatchers.Main) {
                speech.speakDescription(result.text) {
                    resumeFloorNetAndNavigation()
                    isAnalyzingScene.set(false)
                }
            }
        } catch (e: Throwable) {
            Log.e(TAG, "[SCAN-ERROR] Eccezione imprevista durante l'analisi della scena: ${e.message}", e)
            resumeFloorNetAndNavigation()
            isAnalyzingScene.set(false)
        } finally {
            if (!bitmap.isRecycled) {
                bitmap.recycle()
            }
        }
    }

    private fun resumeFloorNetAndNavigation() {
        runOnUiThread {
            pipeline?.isPaused = false
            feedback.resumeSonifier()
            Log.i(TAG, "[FLOORNET-RESUMED] Navigazione FloorNet e guida sonora riattivate.")
        }
    }

    private fun hasCameraPermission() = ContextCompat.checkSelfPermission(
        baseContext, Manifest.permission.CAMERA
    ) == PackageManager.PERMISSION_GRANTED

    private fun startCamera() {
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            val provider = runCatching { future.get() }.getOrNull() ?: return@addListener
            bindUseCases(provider)
        }, ContextCompat.getMainExecutor(this))
    }

    private fun bindUseCases(provider: ProcessCameraProvider) {
        val selector = CameraSelector.DEFAULT_BACK_CAMERA
        val characteristics = provider.availableCameraInfos
            .firstOrNull { selector.filter(listOf(it)).isNotEmpty() }
            ?.let { runCatching { Camera2CameraInfo.from(it) }.getOrNull() }

        cameraSensorOrientation = characteristics?.getCameraCharacteristic(CameraCharacteristics.SENSOR_ORIENTATION) ?: 90
        pipeline?.horizontalFov = horizontalFovOf(characteristics)

        val preview = Preview.Builder()
            .setResolutionSelector(
                ResolutionSelector.Builder()
                    .setAspectRatioStrategy(AspectRatioStrategy.RATIO_16_9_FALLBACK_AUTO_STRATEGY)
                    .build()
            )
            .setTargetRotation(binding.viewFinder.display?.rotation ?: Surface.ROTATION_0)
            .build()
            .also { it.setSurfaceProvider(binding.viewFinder.surfaceProvider) }

        val analysisBuilder = ImageAnalysis.Builder()
            .setResolutionSelector(
                ResolutionSelector.Builder()
                    .setAspectRatioStrategy(AspectRatioStrategy.RATIO_16_9_FALLBACK_AUTO_STRATEGY)
                    .setResolutionStrategy(
                        ResolutionStrategy(
                            ANALYSIS_RESOLUTION,
                            ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER
                        )
                    )
                    .build()
            )
            .setTargetRotation(Surface.ROTATION_0)
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)

        lockFrameRate(analysisBuilder, characteristics)

        val analysis = analysisBuilder.build().also {
            it.setAnalyzer(analysisExecutor, ::onFrame)
        }

        try {
            provider.unbindAll()
            provider.bindToLifecycle(this, selector, preview, analysis)
            feedback.start()
            if (spatial) {
                speech.announce(getString(R.string.say_ready_spatial))
            } else if (speech.enabled) {
                speech.announce(getString(R.string.say_ready_no_audio))
            }
        } catch (error: Exception) {
            Log.e(TAG, "Binding delle use case fallito", error)
            binding.tvStatus.text = error.localizedMessage
        }
    }

    private fun lockFrameRate(
        builder: ImageAnalysis.Builder,
        characteristics: Camera2CameraInfo?,
    ) {
        val available = characteristics
            ?.getCameraCharacteristic(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES)
            ?: return
        val best = available
            .filter { it.upper >= TARGET_FPS }
            .maxByOrNull { it.lower } ?: return
        val chosen = if (best.lower >= TARGET_FPS) Range(TARGET_FPS, best.upper) else best

        Camera2Interop.Extender(builder)
            .setCaptureRequestOption(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, chosen)
            .setCaptureRequestOption(
                CaptureRequest.CONTROL_AF_MODE,
                CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO
            )
        Log.i(TAG, "Intervallo fps richiesto: $chosen")
    }

    private fun horizontalFovOf(characteristics: Camera2CameraInfo?): Float {
        val size = characteristics
            ?.getCameraCharacteristic(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE)
        val focal = characteristics
            ?.getCameraCharacteristic(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)
            ?.firstOrNull()
        if (size == null || focal == null || focal <= 0f) {
            Log.w(TAG, "Caratteristiche ottiche non leggibili, uso il campo visivo predefinito")
            return com.freespace.guide.nav.GroundProjection.DEFAULT_HORIZONTAL_FOV
        }
        val fov = 2f * atan(max(size.width, size.height) / (2f * focal))
        Log.i(TAG, "Campo visivo orizzontale: ${Math.toDegrees(fov.toDouble()).toInt()} gradi")
        return fov
    }

    private fun extractBitmap(image: ImageProxy): Bitmap? {
        val rotation = image.imageInfo.rotationDegrees
        val rawBitmap = runCatching { image.toBitmap() }.getOrNull() ?: extractFromPlane(image) ?: return null

        if (rotation == 0) return rawBitmap

        return try {
            val matrix = android.graphics.Matrix().apply {
                postRotate(rotation.toFloat())
            }
            val rotated = Bitmap.createBitmap(rawBitmap, 0, 0, rawBitmap.width, rawBitmap.height, matrix, true)
            if (rotated != rawBitmap) {
                rawBitmap.recycle()
            }
            rotated
        } catch (t: Throwable) {
            Log.e(TAG, "Rotazione snapshot fallita (rotation: ${rotation}°): ${t.message}", t)
            rawBitmap
        }
    }

    private fun extractFromPlane(image: ImageProxy): Bitmap? {
        return try {
            val plane = image.planes.firstOrNull() ?: return null
            val buffer = plane.buffer
            buffer.rewind()
            val width = image.width
            val height = image.height
            val rowStride = plane.rowStride
            val bmp = Bitmap.createBitmap(rowStride / 4, height, Bitmap.Config.ARGB_8888)
            bmp.copyPixelsFromBuffer(buffer)
            buffer.rewind()
            if (bmp.width != width) {
                Bitmap.createBitmap(bmp, 0, 0, width, height).also { bmp.recycle() }
            } else {
                bmp
            }
        } catch (e: Exception) {
            Log.e(TAG, "[CAPTURE-FALLBACK-ERROR] Fallback estrazione bitmap fallito: ${e.message}", e)
            null
        }
    }

    private fun onFrame(image: ImageProxy) {
        val pipeline = pipeline
        if (pipeline == null) {
            image.close()
            return
        }

        if (captureNextFrame.compareAndSet(true, false)) {
            val captureStartedAt = SystemClock.uptimeMillis()
            val bmp = extractBitmap(image)
            val completion = announcementCompletion
            if (bmp != null) {
                Log.i(TAG, "[CAPTURE-SUCCESS] Snapshot estratto in ${SystemClock.uptimeMillis() - captureStartedAt}ms (${bmp.width}x${bmp.height})")
                sceneAnalysisJob?.cancel()
                sceneAnalysisJob = lifecycleScope.launch(Dispatchers.Default) {
                    processSceneScan(bmp, completion)
                }
            } else {
                Log.e(TAG, "[CAPTURE-FAILED] Impossibile estrarre lo snapshot dal frame!")
                resumeFloorNetAndNavigation()
                isAnalyzingScene.set(false)
            }
        }

        val frame = try {
            image.use { pipeline.process(it) }
        } catch (error: Exception) {
            Log.e(TAG, "Elaborazione del frame fallita", error)
            null
        } ?: return

        feedback.halfSpanRadians = pipeline.halfSpanRadians
        feedback.cameraHeight = pipeline.cameraHeight
        feedback.onCue(frame.cue, frame.reading)

        binding.overlay.submit(
            mask = frame.mask,
            width = pipeline.maskWidth,
            height = pipeline.maskHeight,
            threshold = pipeline.thresholdByte,
            contactRows = frame.reading.contactRows,
            ranges = frame.reading.ranges,
            alert = frame.cue.alert,
        )

        countFrame()
        maybeUpdateHud(frame, pipeline)
    }

    private fun countFrame() {
        val now = SystemClock.uptimeMillis()
        frames++
        if (fpsWindowStart == 0L) {
            fpsWindowStart = now
            return
        }
        val elapsed = now - fpsWindowStart
        if (elapsed >= FPS_WINDOW_MS) {
            fps = frames * 1000f / elapsed
            frames = 0
            fpsWindowStart = now
        }
    }

    private fun maybeUpdateHud(frame: NavigationPipeline.Frame, pipeline: NavigationPipeline) {
        if (!(SHOW_HUD_TEXT ?: SHOW_DEBUG_HUD)) return
        val now = SystemClock.uptimeMillis()
        if (now - lastHudUpdate < HUD_INTERVAL_MS) return
        lastHudUpdate = now

        val currentFps = fps
        val inference = pipeline.inferenceMs
        val front = frame.cue.frontRange
        val free = frame.reading.freeRatio

        runOnUiThread {
            binding.tvPerformance.text = getString(R.string.hud_performance, currentFps, inference)
            binding.tvFront.text = getString(R.string.hud_front, front)
            binding.tvFree.text = getString(R.string.hud_free, free * 100f)
        }
    }

    override fun onStart() {
        super.onStart()
        tilt.start()
        audioManager()?.registerAudioDeviceCallback(headsetWatch, null)
    }

    override fun onResume() {
        super.onResume()
        if (spatial) {
            feedback.start()
        }
    }

    override fun onPause() {
        super.onPause()
        feedback.stop()
        speech.stop()
        if (isAnalyzingScene.getAndSet(false)) {
            sceneAnalysisJob?.cancel()
            sceneAnalysisJob = null
            announcementCompletion?.complete(Unit)
            announcementCompletion = null
            pipeline?.isPaused = false
            feedback.resumeSonifier()
        }
    }

    override fun onStop() {
        super.onStop()
        tilt.stop()
        audioManager()?.unregisterAudioDeviceCallback(headsetWatch)
        feedback.stop()
    }

    private fun audioManager() = getSystemService(Context.AUDIO_SERVICE) as? AudioManager

    override fun onDestroy() {
        super.onDestroy()
        sceneAnalysisJob?.cancel()
        sceneAnalysisJob = null
        announcementCompletion?.complete(Unit)
        announcementCompletion = null
        isAnalyzingScene.set(false)
        feedback.stop()
        speech.shutdown()
        analysisExecutor.shutdown()
        runCatching { analysisExecutor.awaitTermination(500, TimeUnit.MILLISECONDS) }
        binding.overlay.release()
        pipeline?.close()
        pipeline = null
    }

    companion object {
        private const val TAG = "Guida"

        val ANALYSIS_RESOLUTION = Size(640, 360)
        const val TARGET_FPS = 30
        const val FPS_WINDOW_MS = 1000L
        const val HUD_INTERVAL_MS = 250L

        const val SHOW_DEBUG_HUD = true

        val SHOW_HUD_TEXT: Boolean? = null

        val SHOW_HUD_BARS: Boolean? = null

        const val SHOW_CONTACT_LINE = true
    }
}
