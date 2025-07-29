package com.jason.vlrs_launcher

import android.os.Bundle
import android.os.Environment
import android.util.Log
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.util.zip.ZipInputStream

class GenerateATRouteActivity : AppCompatActivity() {

    private val apiKey = "7f27b8830c8946ccb992b08355bd53dd"
    private val baseUrl = "https://api.at.govt.nz/gtfs/v3/"
    private val client = OkHttpClient()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_generate_at_route)

        val fetchButton = findViewById<Button>(R.id.fetchRoutesButton)
        val logOutput = findViewById<TextView>(R.id.logOutput)

        fetchButton.setOnClickListener {
            val logOutput = findViewById<TextView>(R.id.logOutput)
            logOutput.text = ""

            val zipFile = File(getHiddenFolder(), "gtfs.zip")
            val gtfsFolder = getGTFSFolder()

            // Check if GTFS zip already exists
            if (zipFile.exists()) {
                logOutput.append("⚠️ GTFS zip file already exists.\n")
            } else {
                logOutput.append("Downloading GTFS zip...\n")
                downloadGTFSZip { downloadedZip ->
                    if (downloadedZip != null) {
                        lifecycleScope.launch(Dispatchers.IO) {
                            val success = extractZip(downloadedZip, gtfsFolder)
                            withContext(Dispatchers.Main) {
                                if (success) {
                                    logOutput.append("✅ GTFS data extracted to GTFS folder.\n")
                                } else {
                                    logOutput.append("❌ Failed to extract GTFS data.\n")
                                }
                            }
                        }
                    } else {
                        runOnUiThread {
                            logOutput.append("❌ Failed to download GTFS zip.\n")
                        }
                    }
                }
            }

            // Check if GTFS folder is already populated
            val files = gtfsFolder.listFiles()
            if (files != null && files.isNotEmpty()) {
                logOutput.append("📁 GTFS folder already contains ${files.size} files.\n")
            }
        }
    }

    /**
     * Downloads the GTFS zip file from Auckland Transport to the hidden folder.
     * Calls onComplete with the downloaded file or null on failure.
     */
    private fun downloadGTFSZip(onComplete: (File?) -> Unit) {
        val url = "https://gtfs.at.govt.nz/gtfs.zip"
        val request = Request.Builder().url(url).build()
        val client = OkHttpClient()

        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                Log.e("GTFS", "Download failed", e)
                onComplete(null)
            }

            override fun onResponse(call: Call, response: Response) {
                if (response.isSuccessful) {
                    val zipFile = File(getHiddenFolder(), "gtfs.zip")
                    response.body?.byteStream()?.use { input ->
                        zipFile.outputStream().use { output -> input.copyTo(output) }
                    }
                    Log.d("GTFS", "Downloaded GTFS zip to ${zipFile.absolutePath}")
                    onComplete(zipFile)
                } else {
                    Log.e("GTFS", "Download failed: ${response.code}")
                    onComplete(null)
                }
            }
        })
    }
    /**
     * Returns the GTFS subfolder inside the hidden folder.
     * Creates it if it does not exist.
     */
    private fun getGTFSFolder(): File {
        val gtfsFolder = File(getHiddenFolder(), "GTFS")
        if (!gtfsFolder.exists()) gtfsFolder.mkdirs()
        return gtfsFolder
    }

    /**
     * Gets the hidden folder directory where cached bus data is stored.
     * Creates the folder if it does not exist.
     *
     * @return File representing the hidden directory.
     */
    private fun getHiddenFolder(): File {
        val docsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS)
        val hiddenFolder = File(docsDir, ".vlrshiddenfolder")
        if (!hiddenFolder.exists()) hiddenFolder.mkdirs()
        return hiddenFolder
    }

    /**
     * Extracts the contents of the given GTFS zip file into the specified output directory.
     * Returns true if successful, false otherwise.
     */
    private fun extractZip(zipFile: File, outputDir: File): Boolean {
        return try {
            ZipInputStream(FileInputStream(zipFile)).use { zis ->
                var entry = zis.nextEntry
                while (entry != null) {
                    val newFile = File(outputDir, entry.name)
                    if (entry.isDirectory) {
                        newFile.mkdirs()
                    } else {
                        // Ensure parent directories exist
                        newFile.parentFile?.mkdirs()
                        FileOutputStream(newFile).use { output -> zis.copyTo(output) }
                    }
                    zis.closeEntry()
                    entry = zis.nextEntry
                }
            }
            Log.d("GTFS", "Extraction complete to ${outputDir.absolutePath}")
            true
        } catch (e: Exception) {
            Log.e("GTFS", "Extraction failed", e)
            false
        }
    }
}
