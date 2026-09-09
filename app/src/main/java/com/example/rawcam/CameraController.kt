package com.example.rawcam

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageFormat
import android.graphics.Matrix
import android.graphics.RectF
import android.hardware.camera2.CameraAccessException
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.CaptureFailure
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.DngCreator
import android.hardware.camera2.TotalCaptureResult
import android.media.ExifInterface
import android.media.Image
import android.media.ImageReader
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.HandlerThread
import android.provider.MediaStore
import android.util.Log
import android.util.Range
import android.util.Size
import android.view.OrientationEventListener
import android.view.Surface
import android.view.TextureView
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import kotlin.math.max
import kotlin.math.roundToInt

/** Simplified view of CONTROL_AF_STATE for the UI's focus indicator dot. */
enum class FocusState { INACTIVE, SEARCHING, FOCUSED, NOT_FOCUSED }

/**
 * Device capability info + current manual-control values, so the UI can
 * configure the ISO/shutter sliders without guessing what the hardware
 * supports or what values are currently in effect.
 */
data class CameraCapabilities(
    val manualSensorSupported: Boolean, // false = device can't do manual ISO/shutter at all
    val isoRange: Range<Int>?,
    val exposureTimeRangeNs: Range<Long>?,
    val defaultIso: Int,
    val defaultExposureTimeNs: Long
)

/**
 * Owns the Camera2 session. Two capture modes:
 *  - Auto (default): normal auto-exposure (AE_MODE_ON — camera picks both ISO
 *    and shutter), continuous autofocus. Behaves like a normal camera.
 *  - Manual: ISO and shutter speed are set directly via sliders. The same
 *    values drive both the live preview and the actual capture, so what's on
 *    screen is exactly what gets saved — no rescaling, no metering
 *    guesswork. Switching into Manual seeds the sliders from whatever Auto
 *    was actually doing on the most recent frame (see
 *    lastAutoIso/lastAutoExposureNs), so the starting point is scene-matched
 *    rather than an arbitrary guess — no separate one-time "convergence"
 *    step needed, since Auto mode already continuously reports real
 *    auto-exposure readings on every frame.
 *  - Focus is always continuous autofocus, in both modes — never manual.
 *    (An earlier version offered manual focus via a slider; it was removed
 *    because turning a focus dial precisely without any live sharpness
 *    feedback proved genuinely hard to use well, and this app deliberately
 *    stayed away from the engineering needed to fix that properly — real-time
 *    focus peaking — as a bigger, less-proven undertaking than anything else
 *    here. Continuous AF is mature and reliable; better to lean on it fully
 *    than offer a manual control that's difficult to use accurately.)
 *  - White balance always stays auto in both modes — never treated as a
 *    "manual" control here, it's a basic necessary correction, not a creative one.
 *  - On the (rare) device without manual sensor control, Manual mode's
 *    ISO/shutter fall back to normal auto-exposure gracefully.
 *  - Cosmetic / synthetic processing turned off where the device allows it:
 *    edge enhancement off, noise reduction MINIMAL, no color-fringing
 *    correction, no scene modes, no color effects. True in both modes.
 *  - Still captures are built from TEMPLATE_PREVIEW rather than
 *    TEMPLATE_STILL_CAPTURE, since OEMs often hang their computational-
 *    photography tuning off the still-capture template. Single frame only.
 *  - RAW + JPEG switch: adds a DNG (RAW sensor data) from the same exposure
 *    as the JPEG.
 */
class CameraController(
    private val context: Context,
    private val onStatus: (String) -> Unit,
    private val onCapabilities: (CameraCapabilities) -> Unit,
    private val onFocusState: (FocusState) -> Unit,
    private val onAutoShutterUpdate: (Long) -> Unit,
    private val onLastPhoto: (Bitmap, Uri) -> Unit
) {
    private val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager

    private var characteristics: CameraCharacteristics? = null
    private var cameraDevice: CameraDevice? = null
    private var captureSession: CameraCaptureSession? = null

    private var jpegReader: ImageReader? = null
    private var rawReader: ImageReader? = null

    private var previewSurface: Surface? = null
    private var textureView: TextureView? = null

    private var rawEnabled = false
    private var rawSupported = false

    // Manual control state and device capability.
    private var manualModeEnabled = false
    private var manualSensorSupported = false
    private var isoRange: Range<Int>? = null
    private var manualIso = 0
    private var exposureTimeRange: Range<Long>? = null
    private var manualExposureTimeNs = 0L

    // Cached from the most recent AUTO-mode frame's real CaptureResult — used
    // to seed Manual mode's sliders with a scene-appropriate starting point
    // the moment Manual is switched on, rather than a stale/arbitrary default.
    private var lastAutoIso = 0
    private var lastAutoExposureNs = 0L
    private var hasAutoReading = false

    // ISO-priority sub-state within Manual mode. Default true: ISO stays
    // pinned where the user set it and the shutter tracks the scene
    // automatically (computed from AE's live readings via reciprocity).
    // Becomes false the moment the user drags the shutter slider, at which
    // point Manual is fully manual and fully WYSIWYG again.
    private var shutterAutoTracking = true

    private val cameraOpenCloseLock = Semaphore(1)

    private var backgroundThread: HandlerThread? = null
    private var backgroundHandler: Handler? = null

    // A RAW image (from its ImageReader callback) and its metadata (from the
    // capture's CaptureCallback) arrive via two independent callbacks with no
    // guaranteed ordering — DngCreator needs both, so each is buffered until
    // its counterpart shows up too, rather than assuming the result is always
    // ready first (it isn't always, and silently dropped the DNG when it wasn't).
    private var pendingCaptureResult: TotalCaptureResult? = null
    private var pendingRawImage: Image? = null
    private var onCaptureFinished: (() -> Unit)? = null
    private var currentBaseName: String = ""
    private var expectedOutputs = 0
    private var receivedOutputs = 0
    // Computed fresh at the start of every captureStill() — accounts for the
    // phone's actual physical rotation at that moment, not just the sensor's
    // fixed mounting angle. Shared by the JPEG tag, the DNG, and the thumbnail
    // so all three always agree.
    private var currentCaptureOrientationDegrees = 90
    // Accelerometer-based, bucketed to the nearest 90° — see
    // computeCaptureOrientationDegrees() for why this replaced Display.rotation.
    private var orientationEventListener: OrientationEventListener? = null
    private var rawDeviceRotationDegrees = 0

    init {
        startBackgroundThread()
    }

    // ---------------------------------------------------------------------
    // Lifecycle
    // ---------------------------------------------------------------------

    fun openCamera(textureView: TextureView, rawRequested: Boolean) {
        this.textureView = textureView
        this.rawEnabled = rawRequested
        closeCamera()
        startOrientationListener()

        val id = selectBackCameraId()
        if (id == null) {
            onStatus(context.getString(R.string.status_no_camera))
            return
        }

        val chars = cameraManager.getCameraCharacteristics(id)
        characteristics = chars
        rawSupported = hasRawCapability(chars)

        val map = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
        if (map == null) {
            onStatus(context.getString(R.string.status_no_camera))
            return
        }

        val caps = chars.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES)
        manualSensorSupported =
            caps?.contains(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_SENSOR) == true

        isoRange = chars.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE)
        exposureTimeRange = chars.get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE)

        // Seed with sensible midpoint defaults only once — never clobber a
        // value the user (or a prior auto reading) already set, including on
        // later reopens triggered by toggling RAW.
        if (manualIso == 0) {
            manualIso = isoRange?.lower ?: 100
        }
        if (manualExposureTimeNs == 0L) {
            val defaultNs = 16_666_667L // ~1/60s
            manualExposureTimeNs = exposureTimeRange?.let { defaultNs.coerceIn(it.lower, it.upper) } ?: defaultNs
        }

        onCapabilities(currentCapabilities())

        val stillSize = largestSize(map.getOutputSizes(ImageFormat.JPEG))
        jpegReader = ImageReader.newInstance(stillSize.width, stillSize.height, ImageFormat.JPEG, 2).apply {
            setOnImageAvailableListener({ reader ->
                reader.acquireLatestImage()?.let { saveJpeg(it) }
            }, backgroundHandler)
        }

        rawReader = null
        if (rawEnabled && rawSupported) {
            val rawSizes = map.getOutputSizes(ImageFormat.RAW_SENSOR)
            if (rawSizes != null && rawSizes.isNotEmpty()) {
                val rawSize = largestSize(rawSizes)
                rawReader = ImageReader.newInstance(rawSize.width, rawSize.height, ImageFormat.RAW_SENSOR, 2).apply {
                    setOnImageAvailableListener({ reader ->
                        reader.acquireLatestImage()?.let { image ->
                            pendingRawImage = image
                            tryFinishRaw()
                        }
                    }, backgroundHandler)
                }
            }
        }

        configureTransform(textureView, stillSize)

        try {
            if (!cameraOpenCloseLock.tryAcquire(2500, TimeUnit.MILLISECONDS)) {
                onStatus(context.getString(R.string.status_camera_error))
                return
            }
            cameraManager.openCamera(id, stateCallback, backgroundHandler)
        } catch (e: SecurityException) {
            cameraOpenCloseLock.release()
            onStatus(context.getString(R.string.status_permission_denied))
        } catch (e: CameraAccessException) {
            cameraOpenCloseLock.release()
            onStatus(context.getString(R.string.status_camera_error))
        }
    }

    fun setRawEnabled(enabled: Boolean) {
        if (rawEnabled == enabled) return
        val tv = textureView ?: return
        openCamera(tv, enabled)
    }

    /**
     * Entering Manual keeps ISO wherever it already is (lowest by default)
     * and re-enables shutter auto-tracking, so the shutter immediately
     * starts following the scene rather than sitting on a stale value.
     */
    fun setManualMode(enabled: Boolean) {
        if (manualModeEnabled == enabled) return
        manualModeEnabled = enabled
        shutterAutoTracking = true
        if (enabled) {
            // Seed from a real AE reading when one is available (i.e. coming
            // from Auto mode), so metering starts close rather than having to
            // converge from an arbitrary value.
            if (hasAutoReading) computeAutoShutterNs()?.let { manualExposureTimeNs = it }
            startMeteringLoop()
        } else {
            stopMeteringLoop()
        }
        onCapabilities(currentCapabilities())
        if (captureSession != null) startPreview()
    }

    /**
     * ISO stays pinned to this value. While shutter auto-tracking is on,
     * changing ISO doesn't change scene brightness — the tracked shutter
     * rescales proportionally to compensate — so auto-tracking is kept on
     * here rather than treated as a manual override.
     */
    fun setManualIso(value: Int) {
        manualIso = value
        // No manual rescale needed — with the preview running at the new ISO,
        // the metering loop sees the brightness change directly on the next
        // pass and corrects the shutter itself.
        if (captureSession != null) startPreview()
    }

    /** Dragging the shutter drops out of auto-tracking into full manual. */
    fun setManualExposureTimeNs(value: Long) {
        manualExposureTimeNs = value
        shutterAutoTracking = false
        stopMeteringLoop()
        if (captureSession != null) startPreview()
    }

    /** Hands the shutter back to the metering loop. */
    fun resumeShutterAutoTracking() {
        if (shutterAutoTracking) return
        shutterAutoTracking = true
        startMeteringLoop()
        if (captureSession != null) startPreview()
    }

    fun isShutterAutoTracking(): Boolean = shutterAutoTracking

    // ---------------------------------------------------------------------
    // Metering loop (ISO priority, WYSIWYG)
    // ---------------------------------------------------------------------

    /**
     * Camera2 offers no ISO-priority mode, and won't report a scene reading
     * while AE is off — so with ISO pinned and the preview running fully
     * manual, this app has to meter for itself.
     *
     * Rather than adding a second camera stream for frame analysis (which
     * would mean four concurrent streams alongside preview + JPEG + RAW, and
     * some devices won't allow that), brightness is read straight off the
     * TextureView already displaying the preview: a small 32x24 grab, a few
     * times a second. That measures exactly what's on screen, which is by
     * definition what will be captured.
     *
     * Correction is proportional, with a gamma term: preview luma is
     * gamma-encoded, so doubling exposure time does NOT double the measured
     * value. Raising the ratio to GAMMA converts the perceptual error back
     * into a linear exposure ratio, which makes this converge in one or two
     * passes instead of creeping.
     */
    private val meteringRunnable = object : Runnable {
        override fun run() {
            if (manualModeEnabled && shutterAutoTracking) runMeteringPass()
            backgroundHandler?.postDelayed(this, METERING_INTERVAL_MS)
        }
    }

    private fun startMeteringLoop() {
        backgroundHandler?.removeCallbacks(meteringRunnable)
        backgroundHandler?.postDelayed(meteringRunnable, METERING_INTERVAL_MS)
    }

    private fun stopMeteringLoop() {
        backgroundHandler?.removeCallbacks(meteringRunnable)
    }

    /** TextureView.getBitmap() must run on the view's own thread. */
    private fun runMeteringPass() {
        val tv = textureView ?: return
        tv.post {
            val bitmap = try {
                if (tv.isAvailable) tv.getBitmap(METER_WIDTH, METER_HEIGHT) else null
            } catch (e: Exception) {
                Log.e(TAG, "Metering grab failed", e)
                null
            } ?: return@post
            val luma = meanLuma(bitmap)
            bitmap.recycle()
            backgroundHandler?.post { applyMeteringCorrection(luma) }
        }
    }

    private fun meanLuma(bitmap: Bitmap): Float {
        val w = bitmap.width
        val h = bitmap.height
        if (w <= 0 || h <= 0) return TARGET_LUMA
        val pixels = IntArray(w * h)
        bitmap.getPixels(pixels, 0, w, 0, 0, w, h)
        var total = 0.0
        for (p in pixels) {
            val r = (p shr 16) and 0xFF
            val g = (p shr 8) and 0xFF
            val b = p and 0xFF
            total += 0.299 * r + 0.587 * g + 0.114 * b
        }
        return (total / pixels.size).toFloat()
    }

    private fun applyMeteringCorrection(measuredLuma: Float) {
        if (!manualModeEnabled || !shutterAutoTracking) return
        val range = exposureTimeRange ?: return
        // A fully black frame carries no usable signal; treat it as a floor
        // so the ratio can't explode or divide by zero.
        val measured = measuredLuma.coerceAtLeast(1f)

        val perceptualRatio = TARGET_LUMA / measured
        val exposureRatio = Math.pow(perceptualRatio.toDouble(), GAMMA)
            .coerceIn(1.0 / MAX_STEP_RATIO, MAX_STEP_RATIO)

        val current = manualExposureTimeNs.coerceIn(range.lower, range.upper)
        val proposed = (current * exposureRatio).toLong().coerceIn(range.lower, range.upper)

        // Ignore changes too small to be worth restarting the repeating
        // request for — this is what lets a static scene settle instead of
        // hunting back and forth forever.
        val changeRatio = proposed.toDouble() / current.toDouble()
        if (changeRatio > (1.0 - MIN_CHANGE_RATIO) && changeRatio < (1.0 + MIN_CHANGE_RATIO)) return

        manualExposureTimeNs = proposed
        onAutoShutterUpdate(proposed)
        startPreview()
    }

    /**
     * Reciprocity: halving ISO needs double the exposure time for the same
     * total light. Converts AE's live reading into the equivalent shutter
     * speed at our pinned ISO. Clamped to what the sensor actually supports
     * — note that hitting that clamp in very dim light means the capture
     * will come out darker than the AE-rendered preview suggested.
     */
    private fun computeAutoShutterNs(): Long? {
        if (!hasAutoReading || manualIso <= 0 || lastAutoIso <= 0) return null
        val range = exposureTimeRange ?: return null
        val ratio = lastAutoIso.toDouble() / manualIso.toDouble()
        return (lastAutoExposureNs * ratio).toLong().coerceIn(range.lower, range.upper)
    }

    private fun currentCapabilities() = CameraCapabilities(
        manualSensorSupported = manualSensorSupported,
        isoRange = isoRange,
        exposureTimeRangeNs = exposureTimeRange,
        defaultIso = manualIso,
        defaultExposureTimeNs = manualExposureTimeNs
    )

    fun closeCamera() {
        stopOrientationListener()
        stopMeteringLoop()
        try {
            cameraOpenCloseLock.acquire()
            captureSession?.close(); captureSession = null
            cameraDevice?.close(); cameraDevice = null
            jpegReader?.close(); jpegReader = null
            rawReader?.close(); rawReader = null
            previewSurface?.release(); previewSurface = null
            pendingRawImage?.close(); pendingRawImage = null
            pendingCaptureResult = null
        } catch (e: InterruptedException) {
            Log.e(TAG, "Interrupted while closing camera", e)
        } finally {
            cameraOpenCloseLock.release()
        }
    }

    fun shutdown() {
        closeCamera()
        stopBackgroundThread()
    }

    // ---------------------------------------------------------------------
    // Camera device / session setup
    // ---------------------------------------------------------------------

    private val stateCallback = object : CameraDevice.StateCallback() {
        override fun onOpened(device: CameraDevice) {
            cameraOpenCloseLock.release()
            cameraDevice = device
            createCaptureSession()
        }

        override fun onDisconnected(device: CameraDevice) {
            cameraOpenCloseLock.release()
            device.close()
            cameraDevice = null
        }

        override fun onError(device: CameraDevice, error: Int) {
            cameraOpenCloseLock.release()
            device.close()
            cameraDevice = null
            onStatus(context.getString(R.string.status_camera_error))
        }
    }

    private fun createCaptureSession() {
        val device = cameraDevice ?: return
        val tv = textureView ?: return
        val surfaceTexture = tv.surfaceTexture ?: return
        val stillReader = jpegReader ?: return

        surfaceTexture.setDefaultBufferSize(stillReader.width, stillReader.height)
        val preview = Surface(surfaceTexture)
        previewSurface = preview

        val outputs = mutableListOf(preview, stillReader.surface)
        rawReader?.let { outputs.add(it.surface) }

        try {
            device.createCaptureSession(outputs, object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(session: CameraCaptureSession) {
                    captureSession = session
                    startPreview()
                }

                override fun onConfigureFailed(session: CameraCaptureSession) {
                    onStatus(context.getString(R.string.status_camera_error))
                }
            }, backgroundHandler)
        } catch (e: CameraAccessException) {
            onStatus(context.getString(R.string.status_camera_error))
        }
    }

    private fun startPreview() {
        val device = cameraDevice ?: return
        val session = captureSession ?: return
        val surface = previewSurface ?: return
        try {
            val builder = device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
            builder.addTarget(surface)
            applyCaptureParameters(builder, isStillCapture = false)
            session.setRepeatingRequest(builder.build(), previewCaptureCallback, backgroundHandler)
            onStatus(buildStatusMessage())
            if (manualModeEnabled && shutterAutoTracking) startMeteringLoop()
        } catch (e: CameraAccessException) {
            onStatus(context.getString(R.string.status_camera_error))
        }
    }

    /**
     * Reads CONTROL_AF_STATE off every preview frame (purely informational —
     * a colored dot). Also caches AE's real ISO/shutter readings whenever AE
     * is actually running — which is Auto mode, and also Manual mode while
     * shutter auto-tracking is on (ISO priority). In that second case the
     * reading is converted to an equivalent shutter at the pinned ISO and
     * pushed to the UI, throttled so this doesn't spam the main thread at
     * full frame rate.
     */
    private val previewCaptureCallback = object : CameraCaptureSession.CaptureCallback() {
        override fun onCaptureCompleted(
            session: CameraCaptureSession,
            request: CaptureRequest,
            result: TotalCaptureResult
        ) {
            onFocusState(mapAfState(result.get(CaptureResult.CONTROL_AF_STATE)))

            // Only Auto mode has real AE readings to cache. They're kept so
            // that switching into Manual can seed the shutter from a real
            // scene measurement instead of an arbitrary starting value.
            if (!manualModeEnabled) {
                val iso = result.get(CaptureResult.SENSOR_SENSITIVITY)
                val exposure = result.get(CaptureResult.SENSOR_EXPOSURE_TIME)
                if (iso != null && exposure != null) {
                    lastAutoIso = iso
                    lastAutoExposureNs = exposure
                    hasAutoReading = true
                }
            }
        }
    }

    private fun mapAfState(state: Int?): FocusState = when (state) {
        CameraMetadata.CONTROL_AF_STATE_PASSIVE_SCAN, CameraMetadata.CONTROL_AF_STATE_ACTIVE_SCAN ->
            FocusState.SEARCHING
        CameraMetadata.CONTROL_AF_STATE_PASSIVE_FOCUSED, CameraMetadata.CONTROL_AF_STATE_FOCUSED_LOCKED ->
            FocusState.FOCUSED
        CameraMetadata.CONTROL_AF_STATE_PASSIVE_UNFOCUSED, CameraMetadata.CONTROL_AF_STATE_NOT_FOCUSED_LOCKED ->
            FocusState.NOT_FOCUSED
        else -> FocusState.INACTIVE
    }

    private fun buildStatusMessage(): String {
        val modeInfo = if (manualModeEnabled && manualSensorSupported) {
            val shutterTag = if (shutterAutoTracking) "auto" else "set"
            "Manual · ISO $manualIso · ${formatShutterSpeed(manualExposureTimeNs)} ($shutterTag)"
        } else {
            "Auto"
        }
        return if (rawEnabled && rawSupported) {
            context.getString(R.string.status_ready_raw, modeInfo)
        } else if (rawEnabled && !rawSupported) {
            context.getString(R.string.status_raw_unsupported)
        } else {
            context.getString(R.string.status_ready, modeInfo)
        }
    }

    private fun formatShutterSpeed(ns: Long): String {
        val seconds = ns / 1_000_000_000.0
        return if (seconds >= 1.0) {
            String.format(Locale.US, "%.1fs", seconds)
        } else {
            val denominator = (1.0 / seconds).roundToInt().coerceAtLeast(1)
            "1/${denominator}s"
        }
    }

    // ---------------------------------------------------------------------
    // Still capture
    // ---------------------------------------------------------------------

    fun captureStill(onComplete: () -> Unit) {
        val device = cameraDevice
        val session = captureSession
        val stillReader = jpegReader
        if (device == null || session == null || stillReader == null) {
            onComplete()
            return
        }

        onCaptureFinished = onComplete
        currentBaseName = "IMG_${timestamp()}"
        val includeRaw = rawEnabled && rawReader != null
        expectedOutputs = if (includeRaw) 2 else 1
        receivedOutputs = 0
        pendingCaptureResult = null
        pendingRawImage?.close()
        pendingRawImage = null
        // Computed once, here, and reused for both the JPEG tag and the DNG's
        // orientation — guarantees they can never disagree, even if the phone
        // gets rotated in the brief window between capture and RAW processing.
        currentCaptureOrientationDegrees = computeCaptureOrientationDegrees()

        try {
            // TEMPLATE_PREVIEW, not TEMPLATE_STILL_CAPTURE — see class doc.
            val builder = device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
            builder.addTarget(stillReader.surface)
            if (includeRaw) builder.addTarget(rawReader!!.surface)
            applyCaptureParameters(builder, isStillCapture = true)

            session.capture(builder.build(), object : CameraCaptureSession.CaptureCallback() {
                override fun onCaptureCompleted(
                    session: CameraCaptureSession,
                    request: CaptureRequest,
                    result: TotalCaptureResult
                ) {
                    pendingCaptureResult = result
                    tryFinishRaw()
                }

                override fun onCaptureFailed(
                    session: CameraCaptureSession,
                    request: CaptureRequest,
                    failure: CaptureFailure
                ) {
                    onStatus(context.getString(R.string.status_capture_failed))
                    finishCapture()
                }
            }, backgroundHandler)
        } catch (e: CameraAccessException) {
            onStatus(context.getString(R.string.status_capture_failed))
            onComplete()
        }
    }

    /**
     * The single place capture-request parameters are set.
     *
     * Exposure has three cases:
     *  - Auto mode: AE fully on, camera picks ISO and shutter.
     *  - Manual + shutter auto-tracking (ISO priority, the default): the
     *    PREVIEW leaves AE on, because that's the only way Camera2 will
     *    report back live scene readings to rescale from — but the still
     *    CAPTURE pins ISO and applies the reciprocity-computed shutter.
     *    This is the one place preview and capture deliberately differ:
     *    brightness matches, but the preview can't show motion blur that a
     *    slow pinned-ISO shutter will produce in dim light.
     *  - Manual + user-set shutter: AE off everywhere, preview and capture
     *    both use the exact same values — fully WYSIWYG, as before.
     */
    private fun applyCaptureParameters(builder: CaptureRequest.Builder, isStillCapture: Boolean) {
        val chars = characteristics ?: return

        builder.set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO)

        val iso = isoRange
        val expTime = exposureTimeRange
        // Manual mode always uses the pinned values — preview AND capture,
        // whether the shutter is auto-tracked or hand-set. The preview is
        // therefore always exactly what gets saved (WYSIWYG), including any
        // motion blur a slow shutter produces. When auto-tracking is on, the
        // shutter value itself is maintained by this app's own metering loop
        // (see runMeteringPass) rather than by AE.
        val useManualExposure = manualModeEnabled && manualSensorSupported && iso != null && expTime != null

        if (useManualExposure && iso != null && expTime != null) {
            builder.set(CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_OFF)
            builder.set(CaptureRequest.SENSOR_SENSITIVITY, manualIso.coerceIn(iso.lower, iso.upper))
            builder.set(
                CaptureRequest.SENSOR_EXPOSURE_TIME,
                manualExposureTimeNs.coerceIn(expTime.lower, expTime.upper)
            )
        } else {
            builder.set(CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_ON)
        }

        // White balance always stays auto — a basic necessary correction, not
        // a creative control this app exposes.
        builder.set(CaptureRequest.CONTROL_AWB_MODE, CameraMetadata.CONTROL_AWB_MODE_AUTO)

        // Always continuous autofocus — in both modes, never manual. See
        // class doc for why manual focus was removed.
        builder.set(CaptureRequest.CONTROL_AF_MODE, CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_PICTURE)

        // No scene-based "enhancement" (sunset boost, food mode saturation, etc.)
        val availableScene = chars.get(CameraCharacteristics.CONTROL_AVAILABLE_SCENE_MODES)
        if (availableScene?.contains(CameraMetadata.CONTROL_SCENE_MODE_DISABLED) == true) {
            builder.set(CaptureRequest.CONTROL_SCENE_MODE, CameraMetadata.CONTROL_SCENE_MODE_DISABLED)
        }

        // No color filters (sepia/mono/etc.)
        builder.set(CaptureRequest.CONTROL_EFFECT_MODE, CameraMetadata.CONTROL_EFFECT_MODE_OFF)

        // No artificial sharpening.
        val availableEdge = chars.get(CameraCharacteristics.EDGE_AVAILABLE_EDGE_MODES)
        builder.set(
            CaptureRequest.EDGE_MODE,
            pickBest(availableEdge, CameraMetadata.EDGE_MODE_OFF, CameraMetadata.EDGE_MODE_FAST)
        )

        // MINIMAL, not OFF: keeps only the noise handling inherent to a usable
        // image, skips the aggressive smoothing that erases fine detail/skin texture.
        val availableNr = chars.get(CameraCharacteristics.NOISE_REDUCTION_AVAILABLE_NOISE_REDUCTION_MODES)
        builder.set(
            CaptureRequest.NOISE_REDUCTION_MODE,
            pickBest(
                availableNr,
                CameraMetadata.NOISE_REDUCTION_MODE_MINIMAL,
                CameraMetadata.NOISE_REDUCTION_MODE_OFF,
                CameraMetadata.NOISE_REDUCTION_MODE_FAST
            )
        )

        val availableAberration = chars.get(CameraCharacteristics.COLOR_CORRECTION_AVAILABLE_ABERRATION_MODES)
        builder.set(
            CaptureRequest.COLOR_CORRECTION_ABERRATION_MODE,
            pickBest(
                availableAberration,
                CameraMetadata.COLOR_CORRECTION_ABERRATION_MODE_OFF,
                CameraMetadata.COLOR_CORRECTION_ABERRATION_MODE_FAST
            )
        )

        if (isStillCapture) {
            builder.set(CaptureRequest.JPEG_QUALITY, 100.toByte())
            builder.set(CaptureRequest.JPEG_ORIENTATION, currentCaptureOrientationDegrees)
        }
    }

    private fun pickBest(available: IntArray?, vararg preferenceOrder: Int): Int {
        if (available == null) return preferenceOrder.last()
        for (mode in preferenceOrder) {
            if (available.contains(mode)) return mode
        }
        return available.firstOrNull() ?: preferenceOrder.last()
    }

    // ---------------------------------------------------------------------
    // Saving
    // ---------------------------------------------------------------------

    private fun saveJpeg(image: Image) {
        var uri: Uri? = null
        try {
            val buffer = image.planes[0].buffer
            val bytes = ByteArray(buffer.remaining())
            buffer.get(bytes)

            uri = createMediaStoreEntry("$currentBaseName.jpg", "image/jpeg")
            if (uri == null) {
                onStatus(context.getString(R.string.status_save_failed))
                return
            }
            context.contentResolver.openOutputStream(uri)?.use { it.write(bytes) }
            finalizePending(uri)

            decodeThumbnail(bytes, THUMBNAIL_MAX_DIMENSION)?.let { thumb -> onLastPhoto(thumb, uri) }

            onStatus(context.getString(R.string.status_saved_jpeg, "$currentBaseName.jpg"))
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save JPEG", e)
            uri?.let { context.contentResolver.delete(it, null, null) }
            onStatus(context.getString(R.string.status_save_failed))
        } finally {
            image.close()
            finishCapture()
        }
    }

    /**
     * JPEG_ORIENTATION is just an EXIF hint — it doesn't rotate the actual
     * pixel data — so a naive decode would show the thumbnail sideways. This
     * applies the same rotation value the capture itself used, so the
     * thumbnail matches what the saved photo actually looks like.
     */
    private fun decodeThumbnail(bytes: ByteArray, maxDimension: Int): Bitmap? {
        val boundsOptions = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, boundsOptions)
        if (boundsOptions.outWidth <= 0 || boundsOptions.outHeight <= 0) return null

        var sampleSize = 1
        while (boundsOptions.outWidth / (sampleSize * 2) >= maxDimension &&
            boundsOptions.outHeight / (sampleSize * 2) >= maxDimension
        ) {
            sampleSize *= 2
        }
        val decodeOptions = BitmapFactory.Options().apply { inSampleSize = sampleSize }
        val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, decodeOptions) ?: return null

        val rotation = currentCaptureOrientationDegrees
        if (rotation == 0) return decoded
        val matrix = Matrix().apply { postRotate(rotation.toFloat()) }
        val rotated = Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, matrix, true)
        if (rotated !== decoded) decoded.recycle()
        return rotated
    }

    /**
     * Only proceeds once both the RAW image and its CaptureResult have
     * arrived — see the field comment on pendingRawImage for why both are
     * buffered rather than assumed to arrive in a particular order.
     */
    private fun tryFinishRaw() {
        val image = pendingRawImage ?: return
        val result = pendingCaptureResult ?: return
        pendingRawImage = null
        saveRaw(image, result)
    }

    /**
     * DngCreator needs an EXIF orientation constant, not a plain degree
     * value — same underlying rotation angle as JPEG_ORIENTATION, just a
     * different encoding, and DngCreator does not read JPEG_ORIENTATION at
     * all (they're entirely independent settings). Takes the same
     * currentCaptureOrientationDegrees value the JPEG used, so the two can
     * never disagree.
     */
    private fun exifOrientationFor(degrees: Int): Int {
        return when (degrees) {
            90 -> ExifInterface.ORIENTATION_ROTATE_90
            180 -> ExifInterface.ORIENTATION_ROTATE_180
            270 -> ExifInterface.ORIENTATION_ROTATE_270
            else -> ExifInterface.ORIENTATION_NORMAL
        }
    }

    private fun saveRaw(image: Image, result: TotalCaptureResult) {
        val chars = characteristics
        if (chars == null) {
            image.close()
            finishCapture()
            return
        }
        var uri: Uri? = null
        try {
            uri = createMediaStoreEntry("$currentBaseName.dng", "image/x-adobe-dng")
            if (uri == null) {
                onStatus(context.getString(R.string.status_save_failed))
                return
            }
            context.contentResolver.openOutputStream(uri)?.use { out ->
                val dngCreator = DngCreator(chars, result)
                dngCreator.setOrientation(exifOrientationFor(currentCaptureOrientationDegrees))
                dngCreator.writeImage(out, image)
            }
            finalizePending(uri)

            onStatus(context.getString(R.string.status_saved_dng, "$currentBaseName.dng"))
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save DNG", e)
            uri?.let { context.contentResolver.delete(it, null, null) }
            onStatus(context.getString(R.string.status_save_failed))
        } finally {
            image.close()
            finishCapture()
        }
    }

    private fun finishCapture() {
        receivedOutputs++
        if (receivedOutputs >= expectedOutputs) {
            onCaptureFinished?.invoke()
            onCaptureFinished = null
        }
    }

    /**
     * Inserts a new row into the system's Pictures collection so the file shows up
     * in Gallery/Photos immediately, instead of sitting in app-private storage.
     * On Android 10+ this is a scoped-storage insert (no permission needed beyond
     * the entry itself). On Android 9 and below it falls back to a real file path
     * under the public Pictures directory, which needs WRITE_EXTERNAL_STORAGE.
     */
    private fun createMediaStoreEntry(filename: String, mimeType: String): Uri? {
        val resolver = context.contentResolver
        val collection = MediaStore.Images.Media.EXTERNAL_CONTENT_URI

        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, filename)
            put(MediaStore.Images.Media.MIME_TYPE, mimeType)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.Images.Media.RELATIVE_PATH, "${Environment.DIRECTORY_PICTURES}/RawCam")
                put(MediaStore.Images.Media.IS_PENDING, 1)
            } else {
                val dir = File(
                    Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES),
                    "RawCam"
                )
                if (!dir.exists()) dir.mkdirs()
                put(MediaStore.Images.Media.DATA, File(dir, filename).absolutePath)
            }
        }

        return try {
            resolver.insert(collection, values)
        } catch (e: Exception) {
            Log.e(TAG, "MediaStore insert failed", e)
            null
        }
    }

    private fun finalizePending(uri: Uri) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }
            context.contentResolver.update(uri, values, null, null)
        }
    }

    private fun timestamp(): String =
        SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())

    // ---------------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------------

    /**
     * `Display.rotation` (an earlier approach here) is read through this
     * app's own Activity context — and that context's window is locked to
     * portrait, so it gets stuck reflecting the locked window orientation
     * rather than the phone's true physical rotation.
     * `OrientationEventListener` reads the accelerometer directly instead,
     * which has no relationship to the window lock at all.
     *
     * NOTE ON THE SIGN: there are two conflicting versions of this formula
     * in circulation, both from Google. The official CaptureRequest
     * JPEG_ORIENTATION documentation uses PLUS (negating deviceOrientation
     * only for front-facing cameras); Google's own Camera2 app source uses
     * MINUS for the back camera. An earlier version of this function used
     * minus and produced correct portrait but 180°-flipped landscape.
     * Plus is correct here, confirmed against real device measurements
     * rather than either doc:
     *   portrait  (device   0°) -> (90 +   0) % 360 =  90  [confirmed]
     *   landscape (device 270°) -> (90 + 270) % 360 =   0  [confirmed]
     * Note that portrait alone can't distinguish the two signs — both give
     * 90 — so only landscape actually tests this.
     */
    private fun computeCaptureOrientationDegrees(): Int {
        val chars = characteristics ?: return 90
        val sensorOrientation = chars.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 90
        return (sensorOrientation + rawDeviceRotationDegrees + 360) % 360
    }

    private fun startOrientationListener() {
        val listener = object : OrientationEventListener(context) {
            override fun onOrientationChanged(orientation: Int) {
                if (orientation == ORIENTATION_UNKNOWN) return
                // Snap the raw accelerometer reading (0-359) to the nearest
                // 90° bucket, wrapping correctly at the 360/0 boundary.
                rawDeviceRotationDegrees = (((orientation + 45) / 90) * 90) % 360
            }
        }
        if (listener.canDetectOrientation()) {
            listener.enable()
            orientationEventListener = listener
        }
    }

    private fun stopOrientationListener() {
        orientationEventListener?.disable()
        orientationEventListener = null
    }

    private fun selectBackCameraId(): String? {
        for (id in cameraManager.cameraIdList) {
            val chars = cameraManager.getCameraCharacteristics(id)
            if (chars.get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK) {
                return id
            }
        }
        return cameraManager.cameraIdList.firstOrNull()
    }

    private fun hasRawCapability(chars: CameraCharacteristics): Boolean {
        val caps = chars.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES) ?: return false
        return caps.contains(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_RAW)
    }

    private fun largestSize(sizes: Array<Size>): Size =
        sizes.maxByOrNull { it.width.toLong() * it.height.toLong() } ?: sizes[0]

    private fun configureTransform(textureView: TextureView, previewSize: Size) {
        val viewWidth = textureView.width
        val viewHeight = textureView.height
        if (viewWidth == 0 || viewHeight == 0) return

        val matrix = Matrix()
        val viewRect = RectF(0f, 0f, viewWidth.toFloat(), viewHeight.toFloat())
        val bufferRect = RectF(0f, 0f, previewSize.height.toFloat(), previewSize.width.toFloat())
        val centerX = viewRect.centerX()
        val centerY = viewRect.centerY()

        bufferRect.offset(centerX - bufferRect.centerX(), centerY - bufferRect.centerY())
        matrix.setRectToRect(viewRect, bufferRect, Matrix.ScaleToFit.FILL)
        val scale = max(
            viewHeight.toFloat() / previewSize.height,
            viewWidth.toFloat() / previewSize.width
        )
        matrix.postScale(scale, scale, centerX, centerY)
        textureView.setTransform(matrix)
    }

    private fun startBackgroundThread() {
        backgroundThread = HandlerThread("CameraBackground").also { it.start() }
        backgroundHandler = Handler(backgroundThread!!.looper)
    }

    private fun stopBackgroundThread() {
        backgroundThread?.quitSafely()
        try {
            backgroundThread?.join()
        } catch (e: InterruptedException) {
            Log.e(TAG, "Background thread join interrupted", e)
        }
        backgroundThread = null
        backgroundHandler = null
    }

    companion object {
        private const val TAG = "CameraController"
        private const val THUMBNAIL_MAX_DIMENSION = 200
        // How often the auto-tracked shutter value is pushed to the UI.
        // Without this, it'd fire at full preview frame rate.
        // Metering loop tuning.
        private const val METERING_INTERVAL_MS = 400L
        private const val METER_WIDTH = 32
        private const val METER_HEIGHT = 24
        private const val TARGET_LUMA = 118f   // slightly under mid-grey, standard metering target
        private const val GAMMA = 2.2          // preview luma is gamma-encoded, not linear
        private const val MAX_STEP_RATIO = 4.0 // cap per-pass correction so it can't lurch
        private const val MIN_CHANGE_RATIO = 0.08 // ignore <8% changes; stops endless hunting
    }
}
