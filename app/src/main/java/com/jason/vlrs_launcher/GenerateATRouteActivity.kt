package com.jason.vlrs_launcher

import android.os.Bundle
import android.os.Environment
import android.util.Log
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
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

        // Set default values for input fields
        findViewById<TextView>(R.id.routeIdInput).text = "WX1-207"
        findViewById<TextView>(R.id.directionIdInput).text = "1"
        findViewById<TextView>(R.id.shiftStartInput).text = "10:00"
        findViewById<TextView>(R.id.shiftEndInput).text = "19:00"

        val fetchButton = findViewById<Button>(R.id.fetchRoutesButton)
        val busRouteOutput = findViewById<TextView>(R.id.busRouteOutput)
        val scheduleOutput = findViewById<TextView>(R.id.scheduleOutput)

        val generateButton = findViewById<Button>(R.id.generateButton)
        generateButton.setOnClickListener {
            generateBusAndScheduleData()
        }

        fetchButton.setOnClickListener {

            val zipFile = File(getHiddenFolder(), "gtfs.zip")
            val gtfsFolder = getGTFSFolder()

            // Check if GTFS zip already exists
            if (zipFile.exists()) {
                Toast.makeText(this, "GTFS zip already exist", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this, "Downloading GTFS zip...", Toast.LENGTH_SHORT).show()
                downloadGTFSZip { downloadedZip ->
                    if (downloadedZip != null) {
                        lifecycleScope.launch(Dispatchers.IO) {
                            val success = extractZip(downloadedZip, gtfsFolder)
                            withContext(Dispatchers.Main) {
                                if (success) {
                                    Toast.makeText(applicationContext, "✅ GTFS data extracted to GTFS folder.", Toast.LENGTH_SHORT).show()

                                } else {
                                    Toast.makeText(applicationContext, "❌ Failed to extract GTFS data.", Toast.LENGTH_SHORT).show()
                                }
                            }
                        }
                    } else {
                        runOnUiThread {
                            Toast.makeText(this, "❌ Failed to download GTFS zip.", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            }

            // Check if GTFS folder is already populated
            val files = gtfsFolder.listFiles()
            if (files != null && files.isNotEmpty()) {
                Toast.makeText(this, "📁 GTFS folder already contains ${files.size} files.", Toast.LENGTH_SHORT).show()
            }
        }
    }

    /**
     * Generates busRouteData and scheduleData from GTFS .txt files in a memory-safe way.
     * Filters trips using routeId and directionId, and shows first 7 stops of the first matching trip.
     */
    private fun generateBusAndScheduleData() {
        val routeId = findViewById<TextView>(R.id.routeIdInput).text.toString().trim()
        val directionId = findViewById<TextView>(R.id.directionIdInput).text.toString().trim().toIntOrNull() ?: 0

        val gtfsDir = File(getGTFSFolder().absolutePath)
        val tripsFile = File(gtfsDir, "trips.txt")
        val stopTimesFile = File(gtfsDir, "stop_times.txt")
        val stopsFile = File(gtfsDir, "stops.txt")

        if (!tripsFile.exists() || !stopTimesFile.exists() || !stopsFile.exists()) {
            Toast.makeText(this, "❌ Missing required GTFS files (trips.txt, stop_times.txt, stops.txt)", Toast.LENGTH_SHORT).show()
            return
        }

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                var firstTripId: String? = null

                // Stream trips.txt line-by-line to find first matching tripId
                tripsFile.bufferedReader().useLines { lines ->
                    lines.drop(1).forEach { line ->
                        val cols = line.split(",")
                        if (cols[0] == routeId && cols.getOrNull(5)?.toIntOrNull() == directionId) {
                            firstTripId = cols.getOrNull(2)
                            return@forEach
                        }
                    }
                }

                if (firstTripId == null) {
                    withContext(Dispatchers.Main) {
                        Toast.makeText(applicationContext, "❌ No trips found for route $routeId with direction $directionId", Toast.LENGTH_SHORT).show()
                    }
                    return@launch
                }

                // Read first 7 stops from stop_times.txt
                val tripStops = mutableListOf<List<String>>()
                stopTimesFile.bufferedReader().useLines { lines ->
                    lines.drop(1).forEach { line ->
                        val cols = line.split(",")
                        if (cols.getOrNull(0) == firstTripId && tripStops.size < 7) {
                            tripStops.add(cols)
                        }
                    }
                }

                // Load all stops from stops.txt into a Map
                val stopsMap = mutableMapOf<String, List<String>>()
                stopsFile.bufferedReader().useLines { lines ->
                    lines.drop(1).forEach { line ->
                        val cols = line.split(",")
                        if (cols.size >= 4) {
                            stopsMap[cols[0]] = cols
                        }
                    }
                }

                // Build busStops with matched stop info
                val busStops = tripStops.mapIndexed { index, row ->
                    val stopId = row[3]
                    val stopInfo = stopsMap[stopId] ?: listOf("", "", "", "")
                    mapOf(
                        "name" to "Stop ${index + 1}",
                        "time" to row[2].take(5),
                        "latitude" to stopInfo[2],
                        "longitude" to stopInfo[3],
                        "address" to stopInfo[1],
                        "abbreviation" to stopInfo[1].split(" ").joinToString("") { it.first().uppercaseChar().toString() }
                    )
                }

                val busRouteData = listOf(
                    mapOf(
                        "starting_point" to busStops.first(),
                        "next_points" to busStops.drop(1)
                    )
                )

                val scheduleData = listOf(
                    mapOf(
                        "routeNo" to "Route 1",
                        "startTime" to busStops.first()["time"],
                        "endTime" to busStops.last()["time"],
                        "dutyName" to routeId,
                        "busStops" to busStops
                    )
                )

                withContext(Dispatchers.Main) {
                    setupCopyButtons(busRouteData.toString(), scheduleData.toString())
                    Toast.makeText(applicationContext, "✅ busRouteData & scheduleData generated.", Toast.LENGTH_SHORT).show()
                }

            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(applicationContext, "❌ Failed to generate data: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    /**
     * Sets up the copy button to copy generated route + schedule data to clipboard.
     */
    /**
     * Sets up copy buttons for busRouteData and scheduleData.
     */
    private fun setupCopyButtons(busData: String, scheduleData: String) {
        val copyBusRouteButton = findViewById<Button>(R.id.copyBusRouteButton)
        val copyScheduleButton = findViewById<Button>(R.id.copyScheduleButton)

        copyBusRouteButton.setOnClickListener {
            val clipboard = getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager
            val clip = android.content.ClipData.newPlainText("busRouteData", busData)
            clipboard.setPrimaryClip(clip)
            Log.d("GTFS", "Copied busRouteData to clipboard")
        }

        copyScheduleButton.setOnClickListener {
            val clipboard = getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager
            val clip = android.content.ClipData.newPlainText("scheduleData", scheduleData)
            clipboard.setPrimaryClip(clip)
            Log.d("GTFS", "Copied scheduleData to clipboard")
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
