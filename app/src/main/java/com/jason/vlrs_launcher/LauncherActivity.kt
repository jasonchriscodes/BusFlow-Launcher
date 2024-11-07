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
import okhttp3.Call
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
    private val MAIN_APP_PACKAGE = "com.jason.publisher"
    private lateinit var aid: String
    private lateinit var currentVersion: String
    private lateinit var latestVersion: String
    private lateinit var versionText: TextView // Moved here for global access

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
        versionText = findViewById(R.id.versionText) // Initialize TextView

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

        // Set a placeholder until real version data is fetched
        currentVersion = "1.0.0"  // Default value
        latestVersion = "1.0.1"  // Default value
        updateVersionText()

        // Fetch current and latest version information
        checkForUpdates()
    }

    /**
     * Helper function to update versionText
     */
    private fun updateVersionText() {
        versionText.text = "Version $currentVersion (Update available: $latestVersion)"
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

                    // Now check the latest version after getting the current version
                    checkLatestVersion()
                } else {
                    runOnUiThread {
                        showToast("Unexpected server response while fetching the current version.")
                    }
                }
            }
        })
    }

    /**
     * Check app latest version on debian server
     */
    private fun checkLatestVersion() {
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

                    // Update the version information on the UI thread
                    runOnUiThread {
                        updateVersionText()
                        if (currentVersion != latestVersion) {
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

    /**
     * Prompts the user to uninstall the current version of VLRS-Publisher and then initiates the download
     * and installation of the latest version from the specified URL.
     *
     * @param latestVersion The latest version of the app to be installed.
     */
    private fun promptUninstallAndInstall(latestVersion: String) {
        showToast("Preparing to update VLRS-Publisher...")

        // Step 1: Uninstall the main app if it’s installed
        val uninstallIntent = Intent(Intent.ACTION_DELETE)
        uninstallIntent.data = Uri.parse("package:$MAIN_APP_PACKAGE")
        startActivity(uninstallIntent)

        // Step 2: After uninstallation, download and install the new APK
        downloadAndInstallApk("http://43.226.218.98:5000/api/download-latest-apk")
    }

    /**
     * Downloads the latest APK from the specified URL and saves it to a local file.
     * Once downloaded, it proceeds to install the APK on the device.
     *
     * @param apkUrl The URL to download the latest APK.
     */
    private fun downloadAndInstallApk(apkUrl: String) {
        val request = Request.Builder().url(apkUrl).build()

        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                Log.e("LauncherActivity", "Download failed: ${e.message}", e)
                runOnUiThread {
                    showToast("Failed to download APK: ${e.message}")
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

    /**
     * Installs the downloaded APK file by creating an intent with appropriate permissions.
     *
     * @param apkFile The APK file to be installed on the device.
     */
    private fun installApk(apkFile: File) {
        val apkUri = FileProvider.getUriForFile(this, "com.jason.vlrs_launcher.provider", apkFile)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(apkUri, "application/vnd.android.package-archive")
            flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK
        }
        startActivity(intent)
    }

    /**
     * Launches the main application (VLRS-Publisher) by first attempting to start it with its default intent.
     * If the default intent is unavailable, it tries to directly launch the SplashScreen activity.
     * If neither approach succeeds, a message is shown indicating the app was not found.
     */
    private fun launchMainApp() {
        // Try to get the default launch intent for the package
        val launchIntent = packageManager.getLaunchIntentForPackage(MAIN_APP_PACKAGE)
        if (launchIntent != null) {
            startActivity(launchIntent)
        } else {
            // If default intent fails, try launching SplashScreen directly
            val explicitIntent = Intent().setClassName(MAIN_APP_PACKAGE, "com.jason.publisher.SplashScreen")
            if (explicitIntent.resolveActivity(packageManager) != null) {
                startActivity(explicitIntent)
            } else {
                showToast("Main app not found")
            }
        }
        finish()
    }

    private fun showToast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }
}
