package com.example.rawcam

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.SurfaceTexture
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.Range
import android.view.TextureView
import android.view.View
import android.widget.CompoundButton
import android.widget.ImageView
import android.widget.RadioGroup
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.android.material.switchmaterial.SwitchMaterial
import java.util.Locale
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.min
import kotlin.math.roundToInt

class MainActivity : AppCompatActivity() {

    private lateinit var textureView: TextureView
    private lateinit var gridOverlay: GridOverlayView
    private lateinit var switchRaw: SwitchMaterial
    private lateinit var exposureModeGroup: RadioGroup
    private lateinit var manualControlsPanel: View
    private lateinit var rowIso: View
    private lateinit var rowShutter: View
    private lateinit var tvIsoLabel: TextView
    private lateinit var tvShutterLabel: TextView
    private lateinit var seekIso: SeekBar
    private lateinit var seekShutter: SeekBar
    private lateinit var btnCapture: View
    private lateinit var lastShotThumbnail: ImageView

    private lateinit var cameraController: CameraController
    private var capabilities: CameraCapabilities? = null
    private lateinit var prefs: android.content.SharedPreferences

    // The shutter slider only ever needs to reach 1 full second — capping its
    // mapped range there (rather than the device's raw max, which can run to
    // several seconds on some sensors) keeps 1s cleanly reachable at the
    // slider's own maximum and gives the whole practical handheld range
    // better precision. Falls back to the device's true max if that's lower.
    private var shutterSliderRange: Range<Long>? = null
    private var lastPhotoUri: Uri? = null

    // WRITE_EXTERNAL_STORAGE is only needed (and only declared, maxSdkVersion 28)
    // pre-Android 10 — scoped storage on 10+ needs no extra permission for this.
    private val requiredPermissions: Array<String>
        get() = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            arrayOf(Manifest.permission.CAMERA)
        } else {
            arrayOf(Manifest.permission.CAMERA, Manifest.permission.WRITE_EXTERNAL_STORAGE)
        }

    private val requestPermissions =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { results ->
            if (results.values.all { it }) {
                openCameraIfReady()
            } else {
                Toast.makeText(this, R.string.status_permission_denied, Toast.LENGTH_LONG).show()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        textureView = findViewById(R.id.textureView)
        gridOverlay = findViewById(R.id.gridOverlay)
        switchRaw = findViewById(R.id.switchRaw)
        exposureModeGroup = findViewById(R.id.exposureModeGroup)
        manualControlsPanel = findViewById(R.id.manualControlsPanel)
        rowIso = findViewById(R.id.rowIso)
        rowShutter = findViewById(R.id.rowShutter)
        tvIsoLabel = findViewById(R.id.tvIsoLabel)
        tvShutterLabel = findViewById(R.id.tvShutterLabel)
        seekIso = findViewById(R.id.seekIso)
        seekShutter = findViewById(R.id.seekShutter)
        btnCapture = findViewById(R.id.btnCapture)
        lastShotThumbnail = findViewById(R.id.lastShotThumbnail)

        prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)

        cameraController = CameraController(
            context = this,
            // The on-screen status line was removed by request. Status text
            // is dropped rather than surfaced; genuine failures are still
            // logged in CameraController, and permission denial still raises
            // a Toast below.
            onStatus = { },
            onCapabilities = { caps -> runOnUiThread { applyCapabilities(caps) } },
            onFocusState = { state -> runOnUiThread { gridOverlay.setFocusState(state) } },
            onAutoShutterUpdate = { ns -> runOnUiThread { showAutoShutter(ns) } },
            onLastPhoto = { bitmap, uri -> runOnUiThread { showLastPhoto(bitmap, uri) } }
        )

        // Restore last-used settings BEFORE attaching listeners, so applying
        // them doesn't fire callbacks into a camera that isn't open yet.
        // Defaults on a fresh install: RAW on, Manual on.
        val savedRaw = prefs.getBoolean(KEY_RAW_ENABLED, true)
        val savedManual = prefs.getBoolean(KEY_MANUAL_MODE, true)
        switchRaw.isChecked = savedRaw
        exposureModeGroup.check(if (savedManual) R.id.radioManual else R.id.radioAuto)
        manualControlsPanel.visibility = if (savedManual) View.VISIBLE else View.GONE
        // The controller defaults to Auto, so tell it the restored mode
        // directly — the listeners below aren't attached yet.
        cameraController.setManualMode(savedManual)

        switchRaw.setOnCheckedChangeListener { _: CompoundButton, isChecked: Boolean ->
            prefs.edit().putBoolean(KEY_RAW_ENABLED, isChecked).apply()
            cameraController.setRawEnabled(isChecked)
        }

        exposureModeGroup.setOnCheckedChangeListener { _: RadioGroup, checkedId: Int ->
            val manual = checkedId == R.id.radioManual
            prefs.edit().putBoolean(KEY_MANUAL_MODE, manual).apply()
            cameraController.setManualMode(manual)
            manualControlsPanel.visibility = if (manual) View.VISIBLE else View.GONE
        }

        // Tapping the shutter label hands control back to auto-metering after
        // you've set it by hand. (Switching Auto -> Manual does the same.)
        tvShutterLabel.setOnClickListener {
            cameraController.resumeShutterAutoTracking()
        }

        setUpManualSliders()

        btnCapture.setOnClickListener {
            setCaptureButtonEnabled(false)
            cameraController.captureStill {
                runOnUiThread { setCaptureButtonEnabled(true) }
            }
        }

        lastShotThumbnail.setOnClickListener {
            val uri = lastPhotoUri ?: return@setOnClickListener
            try {
                startActivity(Intent(Intent.ACTION_VIEW).apply { setDataAndType(uri, "image/*") })
            } catch (e: Exception) {
                Toast.makeText(this, R.string.status_no_viewer, Toast.LENGTH_SHORT).show()
            }
        }

        textureView.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int) {
                openCameraIfReady()
            }

            override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) {}

            override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean {
                cameraController.closeCamera()
                return true
            }

            override fun onSurfaceTextureUpdated(surface: SurfaceTexture) {}
        }
    }

    private fun showLastPhoto(bitmap: Bitmap, uri: Uri) {
        lastPhotoUri = uri
        lastShotThumbnail.setImageBitmap(bitmap)
        lastShotThumbnail.visibility = View.VISIBLE
    }

    /**
     * A plain View (see shutter_button.xml) doesn't get MaterialButton's
     * built-in disabled styling, so this fakes it with alpha.
     */
    private fun setCaptureButtonEnabled(enabled: Boolean) {
        btnCapture.isEnabled = enabled
        btnCapture.alpha = if (enabled) 1f else 0.5f
    }

    // -----------------------------------------------------------------------
    // Manual controls: capability-driven visibility + slider <-> value mapping
    // -----------------------------------------------------------------------

    /** Called whenever capabilities or current manual values change. */
    private fun applyCapabilities(caps: CameraCapabilities) {
        capabilities = caps

        val isoRange = caps.isoRange
        val expRange = caps.exposureTimeRangeNs
        val hasManualExposure = caps.manualSensorSupported && isoRange != null && expRange != null

        exposureModeGroup.isEnabled = hasManualExposure
        rowIso.visibility = if (hasManualExposure) View.VISIBLE else View.GONE
        rowShutter.visibility = if (hasManualExposure) View.VISIBLE else View.GONE

        if (hasManualExposure && isoRange != null && expRange != null) {
            seekIso.max = SEEK_MAX
            seekIso.progress = progressFromIso(caps.defaultIso, isoRange)
            tvIsoLabel.text = getString(R.string.label_iso_value, caps.defaultIso)

            val sliderRange = Range(expRange.lower, min(expRange.upper, ONE_SECOND_NS).coerceAtLeast(expRange.lower))
            shutterSliderRange = sliderRange
            seekShutter.max = SEEK_MAX
            seekShutter.progress = progressFromExposureNs(caps.defaultExposureTimeNs, sliderRange)
            updateShutterLabel(caps.defaultExposureTimeNs)
        }
    }

    /**
     * Shutter label reflects whether the value is being tracked automatically
     * (ISO priority) or was set by hand, so the two states are never
     * ambiguous on screen.
     */
    private fun updateShutterLabel(ns: Long) {
        val res = if (cameraController.isShutterAutoTracking()) {
            R.string.label_shutter_auto
        } else {
            R.string.label_shutter_manual
        }
        tvShutterLabel.text = getString(res, formatShutterSpeed(ns))
    }

    /**
     * Pushed from the camera while shutter auto-tracking is on. Moves the
     * slider programmatically — which fires the listener with fromUser=false,
     * so it won't be mistaken for the user taking manual control.
     */
    private fun showAutoShutter(ns: Long) {
        val range = shutterSliderRange ?: return
        seekShutter.progress = progressFromExposureNs(ns, range)
        updateShutterLabel(ns)
    }

    private fun setUpManualSliders() {
        seekIso.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                val range = capabilities?.isoRange ?: return
                val iso = isoFromProgress(progress, range)
                tvIsoLabel.text = getString(R.string.label_iso_value, iso)
                if (fromUser) cameraController.setManualIso(iso)
            }
            override fun onStartTrackingTouch(seekBar: SeekBar) {}
            override fun onStopTrackingTouch(seekBar: SeekBar) {}
        })

        seekShutter.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                val range = shutterSliderRange ?: return
                val ns = exposureNsFromProgress(progress, range)
                if (fromUser) cameraController.setManualExposureTimeNs(ns)
                updateShutterLabel(ns)
            }
            override fun onStartTrackingTouch(seekBar: SeekBar) {}
            override fun onStopTrackingTouch(seekBar: SeekBar) {}
        })
    }

    // ISO: linear across the sensor's supported range.
    private fun progressFromIso(iso: Int, range: Range<Int>): Int {
        val span = (range.upper - range.lower).coerceAtLeast(1)
        return (((iso - range.lower).toFloat() / span) * SEEK_MAX).toInt().coerceIn(0, SEEK_MAX)
    }

    private fun isoFromProgress(progress: Int, range: Range<Int>): Int {
        val span = range.upper - range.lower
        return (range.lower + (progress.toFloat() / SEEK_MAX * span).toInt()).coerceIn(range.lower, range.upper)
    }

    // Shutter speed: logarithmic, since the range spans nanoseconds to (up to)
    // a full second and a linear slider would cram all the usable speeds into
    // a tiny sliver.
    private fun progressFromExposureNs(ns: Long, range: Range<Long>): Int {
        val logMin = ln(range.lower.toDouble())
        val logMax = ln(range.upper.toDouble())
        val span = (logMax - logMin).takeIf { it > 0 } ?: 1.0
        val logVal = ln(ns.toDouble().coerceIn(range.lower.toDouble(), range.upper.toDouble()))
        return (((logVal - logMin) / span) * SEEK_MAX).toInt().coerceIn(0, SEEK_MAX)
    }

    private fun exposureNsFromProgress(progress: Int, range: Range<Long>): Long {
        val logMin = ln(range.lower.toDouble())
        val logMax = ln(range.upper.toDouble())
        val logVal = logMin + (progress.toDouble() / SEEK_MAX) * (logMax - logMin)
        return exp(logVal).toLong().coerceIn(range.lower, range.upper)
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

    // -----------------------------------------------------------------------
    // Lifecycle / permissions
    // -----------------------------------------------------------------------

    private fun openCameraIfReady() {
        val missing = requiredPermissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isEmpty()) {
            if (textureView.isAvailable) {
                cameraController.openCamera(textureView, switchRaw.isChecked)
            }
        } else {
            requestPermissions.launch(missing.toTypedArray())
        }
    }

    override fun onResume() {
        super.onResume()
        if (textureView.isAvailable) {
            openCameraIfReady()
        }
    }

    override fun onPause() {
        cameraController.closeCamera()
        super.onPause()
    }

    override fun onDestroy() {
        cameraController.shutdown()
        super.onDestroy()
    }

    companion object {
        private const val SEEK_MAX = 1000
        private const val PREFS_NAME = "35photo_settings"
        private const val KEY_RAW_ENABLED = "raw_enabled"
        private const val KEY_MANUAL_MODE = "manual_mode"
        private const val ONE_SECOND_NS = 1_000_000_000L
    }
}
