package com.jason.vlrs_launcher

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import android.util.Log
import android.widget.Button
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.json.JSONObject
import java.io.File
import java.io.IOException

class LauncherActivity : AppCompatActivity() {

    private lateinit var client: OkHttpClient
    private val REQUEST_MANAGE_EXTERNAL_STORAGE = 1001
    private val REQUEST_WRITE_PERMISSION = 1002
    private val MAIN_APP_PACKAGE = "com.jason.publisher" // Replace with your main app's package name
    private lateinit var aid: String
    private lateinit var currentVersion: String
    private lateinit var latestVersion: String

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_launcher_screen)

        // Check and request permission
        checkAndRequestStoragePermission()

        client = OkHttpClient()
        aid = getOrCreateAid()
        Log.d("LauncherActivity aid", aid)

        // Set up button listeners
        val updateButton = findViewById<Button>(R.id.updateButton)
        val startButton = findViewById<Button>(R.id.startButton)
        val versionText = findViewById<TextView>(R.id.versionText)

        val minimizeButton = findViewById<ImageView>(R.id.minimizeButton)
        val closeButton = findViewById<ImageView>(R.id.closeButton)

        updateButton.setOnClickListener {
            promptUninstallAndInstall(latestVersion)
        }

        startButton.setOnClickListener {
            launchMainApp()
        }

        // Minimize the app
        minimizeButton.setOnClickListener {
            moveTaskToBack(true)
        }

        // Close the app
        closeButton.setOnClickListener {
            finishAndRemoveTask()
        }

        // Placeholder version information until API response
        currentVersion = "1.0.58"  // Set a default value
        latestVersion = "1.0.59"  // Set a default value
        versionText.text = "Version $currentVersion (Update available: $latestVersion)"

        // Fetch current and latest version information
        checkForUpdates()
    }

    /**
     * Ensure permissions are requested before accessing external storage
     */
    private fun checkAndRequestStoragePermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (!Environment.isExternalStorageManager()) {
                val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION)
                intent.data = Uri.parse("package:$packageName")
                startActivityForResult(intent, REQUEST_MANAGE_EXTERNAL_STORAGE)
            }
        } else {
            // For Android 10 and below, request WRITE_EXTERNAL_STORAGE permission
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE),
                REQUEST_WRITE_PERMISSION
            )
        }
    }

    /** Retrieve the AID from the external folder or generate a new one */
    @SuppressLint("HardwareIds")
    private fun getOrCreateAid(): String {
        val documentsDir = File(getExternalFilesDir(null), ".vlrshiddenfolder")

        // Ensure the directory exists or create it
        if (!documentsDir.exists()) {
            val success = documentsDir.mkdirs()
            if (!success) {
                throw RuntimeException("Failed to create directory: ${documentsDir.absolutePath}")
            }
        }

        val aidFile = File(documentsDir, "aid.txt")

        // Check if the aid.txt file exists; if not, create it with a new AID
        if (!aidFile.exists()) {
            val aid = generateNewAid()
            aidFile.writeText(aid)
            return aid
        }

        // If file exists, read the AID from it
        return aidFile.readText().trim()
    }

    /**
     * Function to generate a new AID if needed
     */
    private fun generateNewAid(): String {
        return Settings.Secure.getString(contentResolver, Settings.Secure.ANDROID_ID)
    }

    /** Check for app updates using the generated UUID. */
    private fun checkForUpdates() {
        val requestCurrent = Request.Builder()
            .url("http://43.226.218.98:5000/api/current-version/$aid")
            .build()

        client.newCall(requestCurrent).enqueue(object : Callback {
            override fun onFailure(call: okhttp3.Call, e: IOException) {
                Log.e("LauncherActivity", "Failed to fetch current version information", e)
                runOnUiThread {
                    showToast("Failed to fetch current version information. Please check your connection.")
                }
            }

            override fun onResponse(call: okhttp3.Call, response: Response) {
                if (response.isSuccessful) {
                    val responseData = response.body?.string()
                    val json = JSONObject(responseData!!)
                    currentVersion = json.getString("version")
                    checkLatestVersion(currentVersion)
                } else {
                    runOnUiThread {
                        showToast("Unexpected server response while fetching the current version.")
                    }
                }
            }
        })
    }

    private fun checkLatestVersion(currentVersion: String) {
        val requestLatest = Request.Builder()
            .url("http://43.226.218.98:5000/api/latest-version")
            .build()

        client.newCall(requestLatest).enqueue(object : Callback {
            override fun onFailure(call: okhttp3.Call, e: IOException) {
                Log.e("LauncherActivity", "Failed to fetch latest version information", e)
                runOnUiThread {
                    showToast("Failed to fetch latest version information. Please check your connection.")
                }
            }

            override fun onResponse(call: okhttp3.Call, response: Response) {
                if (response.isSuccessful) {
                    val responseData = response.body?.string()
                    val json = JSONObject(responseData!!)
                    latestVersion = json.getString("version")

                    runOnUiThread {
                        val versionText = findViewById<TextView>(R.id.versionText)
                        versionText.text = "Version $currentVersion (Update available: $latestVersion)"
                    }

                    if (currentVersion != latestVersion) {
                        runOnUiThread {
                            showToast("A new version is available!")
                        }
                    }
                } else {
                    runOnUiThread {
                        showToast("Unexpected server response while fetching the latest version.")
                    }
                }
            }
        })
    }

    private fun promptUninstallAndInstall(latestVersion: String) {
        showToast("A new version is available. Updating...")

        // Step 1: Uninstall the main app if it’s installed
        val intent = Intent(Intent.ACTION_DELETE)
        intent.data = Uri.parse("package:$MAIN_APP_PACKAGE")
        startActivity(intent)

        // Step 2: Download and install the new APK after uninstall
        downloadAndInstallApk("http://43.226.218.98:5000/api/download-latest-apk")
    }

    private fun downloadAndInstallApk(apkUrl: String) {
        val request = Request.Builder().url(apkUrl).build()

        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: okhttp3.Call, e: IOException) {
                runOnUiThread {
                    showToast("Failed to download APK")
                }
            }

            override fun onResponse(call: okhttp3.Call, response: Response) {
                if (response.isSuccessful) {
                    val apkFile = File(getExternalFilesDir(null), "update.apk")
                    apkFile.writeBytes(response.body!!.bytes())

                    runOnUiThread {
                        installApk(apkFile)
                    }
                } else {
                    runOnUiThread {
                        showToast("Error downloading APK")
                    }
                }
            }
        })
    }

    private fun installApk(apkFile: File) {
        val apkUri = FileProvider.getUriForFile(this, "com.jason.vlrs_launcher.provider", apkFile)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(apkUri, "application/vnd.android.package-archive")
            flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK
        }
        startActivity(intent)
    }

    private fun launchMainApp() {
        val launchIntent = packageManager.getLaunchIntentForPackage(MAIN_APP_PACKAGE)
        if (launchIntent != null) {
            startActivity(launchIntent)
        } else {
            showToast("Main app not found")
        }
        finish()
    }

    private fun showToast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }
}
