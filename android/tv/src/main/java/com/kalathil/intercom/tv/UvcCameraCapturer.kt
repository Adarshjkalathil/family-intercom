package com.kalathil.intercom.tv

import android.content.Context
import android.hardware.usb.UsbDevice
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Surface
import com.herohan.uvcapp.CameraHelper
import com.herohan.uvcapp.ICameraHelper
import com.serenegiant.usb.Size
import org.webrtc.CapturerObserver
import org.webrtc.SurfaceTextureHelper
import org.webrtc.VideoCapturer

/**
 * Feeds a USB webcam into WebRTC.
 *
 * Android does not expose UVC cameras through Camera2 - to the platform a USB
 * webcam is just a USB device - so this goes through the USB host API via the
 * UVCAndroid library and hands frames to WebRTC.
 *
 * Frames travel as a SurfaceTexture rather than through a byte-array callback.
 * That keeps them on the GPU, which matters on a budget TV chipset that would
 * otherwise spend most of a core copying 720p frames around.
 *
 * **This is the component most likely to fail on unknown hardware.** Many
 * budget TVs ship kernels without UVC support, or wire the USB port for storage
 * only. Every failure path here reports a specific reason rather than silently
 * producing a black picture, because "it doesn't work" is impossible to debug
 * from another city.
 */
class UvcCameraCapturer(
    private val context: Context,
    private val onStatus: (String) -> Unit
) : VideoCapturer {

    /** What went wrong, for the UI to show. Null while things are fine. */
    @Volatile
    var lastError: String? = null
        private set

    private var cameraHelper: ICameraHelper? = null
    private var surfaceHelper: SurfaceTextureHelper? = null
    private var observer: CapturerObserver? = null
    private var surface: Surface? = null

    private var device: UsbDevice? = null
    private var capturing = false

    /**
     * UVCAndroid posts every StateCallback to the main thread, and startCapture
     * is called on the main thread too. So startCapture must not wait for the
     * camera to open - it would block the very callbacks it is waiting for. The
     * open is reported asynchronously instead, with a timeout for a camera that
     * never answers.
     */
    private val mainHandler = Handler(Looper.getMainLooper())

    private val openTimeout = Runnable {
        if (!capturing) {
            fail(
                "No USB camera responded within ${OPEN_TIMEOUT_SECONDS}s. " +
                    "Either nothing is plugged in, the TV's USB port does not support cameras, " +
                    "or the permission dialog was not accepted."
            )
        }
    }

    override fun initialize(
        surfaceTextureHelper: SurfaceTextureHelper,
        applicationContext: Context,
        capturerObserver: CapturerObserver
    ) {
        this.surfaceHelper = surfaceTextureHelper
        this.observer = capturerObserver
    }

    override fun startCapture(width: Int, height: Int, framerate: Int) {
        if (capturing) return
        val helper = surfaceHelper ?: run {
            fail("Capturer was not initialised")
            return
        }

        requestedWidth = width
        requestedHeight = height
        requestedFps = framerate

        // The library posts callbacks on the main thread, so create it there.
        val camera = CameraHelper()
        camera.setStateCallback(stateCallback)
        cameraHelper = camera

        // Frames from the webcam land on this SurfaceTexture and go straight to
        // WebRTC without a CPU copy.
        helper.startListening { frame ->
            if (capturing) observer?.onFrameCaptured(frame)
        }
        helper.setTextureSize(width, height)
        surface = Surface(helper.surfaceTexture)

        onStatus("Looking for a USB camera…")

        // Give the USB permission dialog and enumeration a chance to complete.
        // Posted before the device scan so a synchronous failure can cancel it.
        mainHandler.postDelayed(openTimeout, OPEN_TIMEOUT_SECONDS * 1000)

        // Devices already plugged in do not produce an onAttach callback, so
        // check the current list as well as waiting for new arrivals.
        val existing = runCatching { camera.deviceList }.getOrNull().orEmpty()
        if (existing.isNotEmpty()) {
            Log.i(TAG, "found ${existing.size} USB device(s) already attached")
            selectFirstUsable(existing)
        }
    }

    private fun selectFirstUsable(devices: List<UsbDevice>) {
        val helper = cameraHelper ?: return
        // Take the first device the library is willing to treat as a camera.
        val candidate = devices.firstOrNull()
        if (candidate == null) {
            fail("No USB devices are visible to the app at all")
            return
        }
        device = candidate
        Log.i(
            TAG,
            "selecting ${candidate.productName ?: "unnamed"} " +
                "(vendor 0x${candidate.vendorId.toString(16)}, product 0x${candidate.productId.toString(16)})"
        )
        onStatus("Opening ${candidate.productName ?: "USB camera"}…")
        runCatching { helper.selectDevice(candidate) }
            .onFailure { fail("Could not open the USB device: ${it.message}") }
    }

    private val stateCallback = object : ICameraHelper.StateCallback {

        override fun onAttach(usbDevice: UsbDevice) {
            Log.i(TAG, "USB device attached: ${usbDevice.productName}")
            if (device == null) selectFirstUsable(listOf(usbDevice))
        }

        override fun onDeviceOpen(usbDevice: UsbDevice, isFirstOpen: Boolean) {
            Log.i(TAG, "device opened, requesting camera")
            runCatching { cameraHelper?.openCamera() }
                .onFailure { fail("The device opened but is not a camera: ${it.message}") }
        }

        override fun onCameraOpen(usbDevice: UsbDevice) {
            val helper = cameraHelper ?: return
            val target = chooseSize(helper)
            Log.i(TAG, "camera open, preview size ${target?.width}x${target?.height}")

            runCatching {
                target?.let { helper.previewSize = it }
                surface?.let { helper.addSurface(it, false) }
                helper.startPreview()
            }.onFailure {
                fail("Camera opened but preview would not start: ${it.message}")
                return
            }

            capturing = true
            lastError = null
            onStatus("Camera ready")
            mainHandler.removeCallbacks(openTimeout)
            observer?.onCapturerStarted(true)
        }

        override fun onCameraClose(usbDevice: UsbDevice) {
            Log.i(TAG, "camera closing")
            capturing = false
            surface?.let { runCatching { cameraHelper?.removeSurface(it) } }
            observer?.onCapturerStopped()
        }

        override fun onDeviceClose(usbDevice: UsbDevice) {
            Log.i(TAG, "device closed")
        }

        override fun onDetach(usbDevice: UsbDevice) {
            Log.w(TAG, "USB device unplugged")
            capturing = false
            device = null
            fail("The camera was unplugged")
        }

        override fun onCancel(usbDevice: UsbDevice) {
            fail("USB permission was refused. Accept the dialog on the TV and try again.")
        }
    }

    /**
     * Pick the supported size closest to what we asked for.
     *
     * Cheap webcams advertise odd sets of resolutions and will happily fail to
     * start if handed one they do not support, so never assume 1280x720 exists.
     */
    private fun chooseSize(helper: ICameraHelper): Size? {
        val supported = runCatching { helper.supportedSizeList }.getOrNull().orEmpty()
        if (supported.isEmpty()) {
            Log.w(TAG, "camera reported no supported sizes; using its default")
            return null
        }
        Log.i(TAG, "supported sizes: " + supported.joinToString { "${it.width}x${it.height}" })

        val targetPixels = requestedWidth * requestedHeight
        return supported.minByOrNull { size ->
            val diff = kotlin.math.abs(size.width * size.height - targetPixels)
            // Prefer 16:9 so the TV is not letterboxed twice over.
            val aspectPenalty =
                kotlin.math.abs(size.width.toFloat() / size.height - 16f / 9f) * targetPixels
            diff + aspectPenalty.toLong()
        }
    }

    private fun fail(message: String) {
        Log.e(TAG, message)
        lastError = message
        onStatus(message)
        // One failure is enough; do not let the timeout overwrite a more
        // specific reason such as a refused permission.
        mainHandler.removeCallbacks(openTimeout)
        observer?.onCapturerStarted(false)
    }

    override fun stopCapture() {
        mainHandler.removeCallbacks(openTimeout)
        capturing = false
        runCatching {
            surface?.let { cameraHelper?.removeSurface(it) }
            cameraHelper?.stopPreview()
            cameraHelper?.closeCamera()
        }
        surfaceHelper?.stopListening()
        observer?.onCapturerStopped()
    }

    override fun changeCaptureFormat(width: Int, height: Int, framerate: Int) {
        // Renegotiating the USB pipeline mid-call is a reliable way to lose the
        // camera on cheap hardware. WebRTC only asks for this as an optimisation,
        // so declining it is safe.
        Log.d(TAG, "ignoring changeCaptureFormat(${width}x$height@$framerate)")
    }

    override fun dispose() {
        stopCapture()
        runCatching { cameraHelper?.release() }
        cameraHelper = null
        surface?.release()
        surface = null
        device = null
    }

    override fun isScreencast(): Boolean = false

    private var requestedWidth = 1280
    private var requestedHeight = 720
    private var requestedFps = 24

    private companion object {
        const val TAG = "UvcCapturer"
        const val OPEN_TIMEOUT_SECONDS = 12L
    }
}
