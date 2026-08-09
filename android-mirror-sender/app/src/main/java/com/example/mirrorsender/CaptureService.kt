package com.example.mirrorsender

import android.app.Activity
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.util.DisplayMetrics
import android.view.WindowManager
import androidx.core.app.NotificationCompat
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileOutputStream
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.Executors

// This is where the real work happens. It runs as a foreground service because
// Android requires that for any app capturing the screen in the background.
//
// Wire format sent to the Mac, per frame:
//   [4-byte big-endian frame length][JPEG bytes]
// Java/Kotlin's DataOutputStream.writeInt() is big-endian, which is what the
// Mac side (Swift, reading .bigEndian) expects.
class CaptureService : Service() {

    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private var socket: Socket? = null
    private var outputStream: DataOutputStream? = null
    private val executor = Executors.newSingleThreadExecutor()
    private var streaming = false
    private var fileServerSocket: ServerSocket? = null

    // ImageReader's callback and MediaProjection's callback both need a Handler
    // backed by a real Looper — a plain background thread (like our executor)
    // doesn't have one, and registering with a null Handler on such a thread
    // throws at runtime. This dedicated HandlerThread supplies that Looper.
    private lateinit var handlerThread: HandlerThread
    private lateinit var backgroundHandler: Handler

    // Android 14+ requires a registered MediaProjection.Callback before
    // createVirtualDisplay() is called, or it throws a SecurityException.
    private val projectionCallback = object : MediaProjection.Callback() {
        override fun onStop() {
            stopSelf()
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        handlerThread = HandlerThread("CaptureServiceHandler").apply { start() }
        backgroundHandler = Handler(handlerThread.looper)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForegroundNotification()

        val resultCode = intent?.getIntExtra("resultCode", Activity.RESULT_CANCELED)
            ?: return START_NOT_STICKY
        @Suppress("DEPRECATION")
        val data = intent.getParcelableExtra<Intent>("data") ?: return START_NOT_STICKY
        val ip = intent.getStringExtra("ip") ?: return START_NOT_STICKY

        val manager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        mediaProjection = manager.getMediaProjection(resultCode, data)
        // Must happen before createVirtualDisplay() — required since Android 14.
        mediaProjection?.registerCallback(projectionCallback, backgroundHandler)

        executor.execute {
            try {
                socket = Socket(ip, PORT)
                outputStream = DataOutputStream(socket!!.getOutputStream())
                startCapture()
            } catch (e: Exception) {
                stopSelf()
            }
        }

        startFileReceiver()

        return START_NOT_STICKY
    }

    // Listens for files the Mac app pushes down (e.g. via drag-and-drop).
    // Saved into this app's own external files/Downloads folder — visible
    // via a file manager under Android/data/com.example.mirrorsender/files/Download.
    private fun startFileReceiver() {
        Thread {
            try {
                fileServerSocket = ServerSocket(FILE_RECEIVE_PORT)
                while (true) {
                    val client = fileServerSocket!!.accept()
                    handleIncomingFile(client)
                }
            } catch (e: Exception) {
                // Listener was closed on shutdown, or failed to bind — either way, nothing to do.
            }
        }.start()
    }

    private fun handleIncomingFile(client: Socket) {
        try {
            val input = DataInputStream(client.getInputStream())
            val nameLength = input.readInt()
            val nameBytes = ByteArray(nameLength)
            input.readFully(nameBytes)
            val fileName = String(nameBytes, Charsets.UTF_8)
            val fileLength = input.readLong()

            val downloadsDir = getExternalFilesDir(android.os.Environment.DIRECTORY_DOWNLOADS)
            val outFile = File(downloadsDir, fileName)
            FileOutputStream(outFile).use { fileOut ->
                val buffer = ByteArray(8192)
                var remaining = fileLength
                while (remaining > 0) {
                    val toRead = minOf(buffer.size.toLong(), remaining).toInt()
                    val read = input.read(buffer, 0, toRead)
                    if (read == -1) break
                    fileOut.write(buffer, 0, read)
                    remaining -= read
                }
            }
        } catch (e: Exception) {
            // Malformed or interrupted transfer — drop it and wait for the next one.
        } finally {
            client.close()
        }
    }

    private fun startForegroundNotification() {
        val channelId = "mirror_capture"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(channelId, "Screen mirroring", NotificationManager.IMPORTANCE_LOW)
            (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(channel)
        }
        val notification = NotificationCompat.Builder(this, channelId)
            .setContentTitle("Mirroring screen")
            .setContentText("Streaming to Mac")
            .setSmallIcon(android.R.drawable.ic_menu_share)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(1, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        } else {
            startForeground(1, notification)
        }
    }

    private fun startCapture() {
        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        (getSystemService(Context.WINDOW_SERVICE) as WindowManager).defaultDisplay.getRealMetrics(metrics)

        // Half resolution keeps JPEG frames small enough to stream smoothly.
        // Bump this up later once things are working.
        val width = metrics.widthPixels / 2
        val height = metrics.heightPixels / 2
        val density = metrics.densityDpi

        imageReader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)

        virtualDisplay = mediaProjection?.createVirtualDisplay(
            "MirrorSender", width, height, density,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            imageReader!!.surface, null, null
        )

        streaming = true
        imageReader?.setOnImageAvailableListener({ reader ->
            if (!streaming) return@setOnImageAvailableListener
            val image = reader.acquireLatestImage() ?: return@setOnImageAvailableListener
            try {
                sendFrame(image, width, height)
            } finally {
                image.close()
            }
        }, backgroundHandler)
    }

    private fun sendFrame(image: Image, width: Int, height: Int) {
        val plane = image.planes[0]
        val buffer = plane.buffer
        val pixelStride = plane.pixelStride
        val rowStride = plane.rowStride
        val rowPadding = rowStride - pixelStride * width

        val bitmap = Bitmap.createBitmap(width + rowPadding / pixelStride, height, Bitmap.Config.ARGB_8888)
        bitmap.copyPixelsFromBuffer(buffer)

        val jpegStream = ByteArrayOutputStream()
        // Quality 50 trades fidelity for speed — this is the first knob to turn
        // if frames look too blocky or arrive too slowly.
        bitmap.compress(Bitmap.CompressFormat.JPEG, 50, jpegStream)
        val jpegBytes = jpegStream.toByteArray()
        bitmap.recycle()

        try {
            outputStream?.let { out ->
                synchronized(out) {
                    out.writeInt(jpegBytes.size)
                    out.write(jpegBytes)
                    out.flush()
                }
            }
        } catch (e: Exception) {
            stopSelf()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        streaming = false
        virtualDisplay?.release()
        imageReader?.close()
        mediaProjection?.unregisterCallback(projectionCallback)
        mediaProjection?.stop()
        try {
            outputStream?.close()
            socket?.close()
            fileServerSocket?.close()
        } catch (_: Exception) {
        }
        executor.shutdown()
        handlerThread.quitSafely()
    }

    companion object {
        const val PORT = 5050
        const val FILE_RECEIVE_PORT = 5051 // Mac connects here to push a file down to the phone
    }
}
