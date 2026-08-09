package com.example.mirrorsender

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.database.Cursor
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import java.io.DataOutputStream
import java.net.Socket

// Entry point. All this screen does is:
// 1) ask you for the Mac's IP address
// 2) ask Android for screen-capture permission
// 3) hand both off to CaptureService, which does the actual work
// 4) let you pick a file to push over to the Mac
class MainActivity : AppCompatActivity() {

    private lateinit var ipInput: EditText
    private lateinit var statusText: TextView

    private val projectionManager by lazy {
        getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
    }

    companion object {
        const val REQUEST_CODE = 1001
        const val FILE_SEND_PORT = 5052 // Android connects out to the Mac's file-receive listener
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        ipInput = findViewById(R.id.ipInput)
        statusText = findViewById(R.id.statusText)
        val startButton = findViewById<Button>(R.id.startButton)
        val stopButton = findViewById<Button>(R.id.stopButton)

        startButton.setOnClickListener {
            if (ipInput.text.toString().trim().isEmpty()) {
                statusText.text = "Enter your Mac's IP address first"
                return@setOnClickListener
            }
            // This pops the system "Allow this app to capture your screen?" dialog
            startActivityForResult(projectionManager.createScreenCaptureIntent(), REQUEST_CODE)
        }

        stopButton.setOnClickListener {
            stopService(Intent(this, CaptureService::class.java))
            statusText.text = "Stopped"
        }

        val sendFileButton = findViewById<Button>(R.id.sendFileButton)
        sendFileButton.setOnClickListener {
            if (ipInput.text.toString().trim().isEmpty()) {
                statusText.text = "Enter your Mac's IP address first"
            } else {
                filePicker.launch(arrayOf("*/*"))
            }
        }
    }

    private val filePicker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) sendFileToMac(uri)
    }

    private fun queryFileName(uri: Uri): String? {
        var name: String? = null
        val cursor: Cursor? = contentResolver.query(uri, null, null, null, null)
        cursor?.use {
            if (it.moveToFirst()) {
                val index = it.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (index >= 0) name = it.getString(index)
            }
        }
        return name
    }

    private fun sendFileToMac(uri: Uri) {
        val ip = ipInput.text.toString().trim()
        statusText.text = "Sending file..."

        Thread {
            try {
                val fileName = queryFileName(uri) ?: "file"
                val bytes = contentResolver.openInputStream(uri)?.use { it.readBytes() }
                    ?: throw IllegalStateException("Couldn't read file")

                Socket(ip, FILE_SEND_PORT).use { socket ->
                    val out = DataOutputStream(socket.getOutputStream())
                    val nameBytes = fileName.toByteArray(Charsets.UTF_8)
                    out.writeInt(nameBytes.size)
                    out.write(nameBytes)
                    out.writeLong(bytes.size.toLong())
                    out.write(bytes)
                    out.flush()
                }
                runOnUiThread { statusText.text = "Sent $fileName to Mac" }
            } catch (e: Exception) {
                runOnUiThread { statusText.text = "File send failed: ${e.message}" }
            }
        }.start()
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_CODE && resultCode == Activity.RESULT_OK && data != null) {
            val serviceIntent = Intent(this, CaptureService::class.java).apply {
                putExtra("resultCode", resultCode)
                putExtra("data", data)
                putExtra("ip", ipInput.text.toString().trim())
            }
            startForegroundService(serviceIntent)
            statusText.text = "Streaming started"
        } else {
            statusText.text = "Screen capture permission denied"
        }
    }
}
