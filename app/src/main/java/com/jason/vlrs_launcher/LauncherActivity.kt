package com.jason.vlrs_launcher

import android.Manifest
import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.widget.Button
import android.widget.ImageView
import android.widget.RelativeLayout
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
    private lateinit var uninstallReceiver: BroadcastReceiver
    private val UNINSTALL_REQUEST_CODE = 100
    private lateinit var aid: String
    private lateinit var currentVersion: String
    private lateinit var latestVersion: String
    private lateinit var versionText: TextView // Moved here for global access
    private lateinit var loadingOverlay: RelativeLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_launcher_screen)

        // Find the loading overlay by ID
        loadingOverlay = findViewById(R.id.loadingOverlay)

        // Check and request permission
        checkAndRequestStoragePermission()

        // Register BroadcastReceiver for package removal
        registerUninstallReceiver()

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
            showLoadingOverlay()
            promptUninstallAndInstall()
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
     * Shows the loading overlay for 55 seconds with a spinner and disables clicks on the screen.
     */
    private fun showLoadingOverlay() {
        // Show the overlay
        loadingOverlay.visibility = RelativeLayout.VISIBLE

        // Hide overlay after 55 seconds
        Handler(Looper.getMainLooper()).postDelayed({
            loadingOverlay.visibility = RelativeLayout.GONE
        }, 55000) // 55 seconds
    }

    /**
     * Registers a BroadcastReceiver to listen for the ACTION_PACKAGE_REMOVED broadcast.
     * When triggered, it checks if the specified package (VLRS-Publisher) was removed,
     * and if so, initiates the download and installation of the new APK.
     */
    private fun registerUninstallReceiver() {
        uninstallReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                val packageName = intent.data?.schemeSpecificPart
                if (packageName == MAIN_APP_PACKAGE) {
//                    showToast("VLRS-Publisher uninstalled successfully")
                    // Start download and install process
//                    downloadAndInstallApk("http://43.226.218.98:5000/api/download-latest-apk")
                }
            }
        }

        val intentFilter = IntentFilter(Intent.ACTION_PACKAGE_REMOVED).apply {
            addDataScheme("package")
        }
        registerReceiver(uninstallReceiver, intentFilter)
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
     * Prompts to uninstall the main app using `ACTION_UNINSTALL_PACKAGE`, which provides a callback.
     */
    private fun promptUninstallAndInstall() {
        val uninstallIntent = Intent(Intent.ACTION_UNINSTALL_PACKAGE).apply {
            data = Uri.parse("package:$MAIN_APP_PACKAGE")
            putExtra(Intent.EXTRA_RETURN_RESULT, true)
        }
        startActivityForResult(uninstallIntent, UNINSTALL_REQUEST_CODE)
    }

    /**
     * Handle the result of the uninstall intent.
     * If uninstallation was successful, start the download and install the new APK.
     */
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)

        if (requestCode == UNINSTALL_REQUEST_CODE) {
            if (!isAppInstalled(MAIN_APP_PACKAGE)) {
//                Toast.makeText(this, "VLRS-Publisher uninstalled successfully", Toast.LENGTH_SHORT).show()
                // Proceed with downloading and installing the new APK
                downloadAndInstallApk("http://43.226.218.98:5000/api/download-latest-apk")
            } else {
                Toast.makeText(this, "Uninstallation canceled. Please uninstall to continue.", Toast.LENGTH_SHORT).show()
            }
        }
    }

    /**
     * Checks if a package is installed by attempting to retrieve its PackageInfo.
     */
    private fun isAppInstalled(packageName: String): Boolean {
        return try {
            packageManager.getPackageInfo(packageName, 0)
            true
        } catch (e: PackageManager.NameNotFoundException) {
            false
        }
    }

    /**
     * Download the APK from the server and initiate installation.
     */
    private fun downloadAndInstallApk(apkUrl: String) {
        val request = Request.Builder().url(apkUrl).build()

        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                runOnUiThread {
                    Toast.makeText(this@LauncherActivity, "Failed to download APK", Toast.LENGTH_SHORT).show()
                }
            }

            override fun onResponse(call: Call, response: Response) {
                if (response.isSuccessful) {
                    val apkFile = File(getExternalFilesDir(null), "update.apk")
                    apkFile.writeBytes(response.body!!.bytes())

                    runOnUiThread {
                        installApk(apkFile)
                    }
                } else {
                    runOnUiThread {
                        Toast.makeText(this@LauncherActivity, "Error downloading APK", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        })
    }

    /**
     * Install the downloaded APK file.
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

    /**
     * Unregisters the uninstallReceiver when the activity is destroyed to prevent memory leaks.
     */
    override fun onDestroy() {
        super.onDestroy()
        unregisterReceiver(uninstallReceiver)
    }
}
