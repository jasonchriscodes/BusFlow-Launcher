package com.jason.vlrs_launcher

import android.Manifest
import android.annotation.SuppressLint
import android.app.AlertDialog
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
import okhttp3.RequestBody
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
            updateCurrentVersionOnServer()
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

        val thingsboardDashboardLink = findViewById<ImageView>(R.id.thingsboardDashboardLink)
        thingsboardDashboardLink.setOnClickListener {
            openThingsboardDashboard()
        }

        val createRouteAppLink = findViewById<ImageView>(R.id.createRouteAppLink)
        createRouteAppLink.setOnClickListener {
            checkAndLaunchOrDownloadRouteApp()
        }

        val customerServiceLink = findViewById<ImageView>(R.id.customerServiceLink)
        customerServiceLink.setOnClickListener {
            val intent = Intent(this, FeedbackActivity::class.java)
            startActivity(intent)
        }

        val registerHelpLink = findViewById<ImageView>(R.id.registerHelpLink)
        registerHelpLink.setOnClickListener {
            openRegisterHelp()
        }

        // Set a placeholder until real version data is fetched
        currentVersion = "1.0.0"  // Default value
        latestVersion = "1.0.1"  // Default value
        updateVersionText()

        // Fetch current and latest version information
        checkForUpdates()
    }

    /**
     * Opens the help PDF in a browser.
     */
    private fun openRegisterHelp() {
        val pdfUrl = "http://43.226.218.98:5000/api/view-registration-guide"
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(pdfUrl))
        try {
            startActivity(intent)
        } catch (e: Exception) {
            showToast("Failed to open the registration guide.")
            Log.e("LauncherActivity", "Error opening registration guide", e)
        }
    }

    /**
     * Attempts to launch the route generation app (bus_route) if installed.
     * If the app is not installed, it downloads and installs the app.
     */
    private fun checkAndLaunchOrDownloadRouteApp() {
        val routeAppPackage = "com.example.bus_route"

        try {
            // Always try to launch the app first
            launchCreateRouteApp(routeAppPackage)
            showToast("Launching Create Route app.")
        } catch (e: Exception) {
            Log.e("LauncherActivity", "Failed to launch Create Route app: ${e.message}", e)

            // If launching fails, check installation status
            if (!isAppInstalled(routeAppPackage)) {
                showToast("Create Route app not found. Downloading...")
                downloadAndInstallRouteApp()
            } else {
                showToast("Create Route app exists but failed to launch.")
            }
        }
    }

    /**
     * Launches the specified application by package name.
     */
    private fun launchCreateRouteApp(packageName: String) {
        val launchIntent = packageManager.getLaunchIntentForPackage(packageName)
        if (launchIntent != null) {
            Log.d("LauncherActivity", "Launching $packageName with default intent.")
            startActivity(launchIntent)
        } else {
            // Explicitly try to launch the main activity
            val explicitIntent = Intent().setClassName(packageName, "$packageName.MainActivity")
            if (explicitIntent.resolveActivity(packageManager) != null) {
                Log.d("LauncherActivity", "Launching $packageName with explicit intent.")
                startActivity(explicitIntent)
            } else {
                throw Exception("$packageName launch failed. No matching intent found.")
            }
        }
    }

    /**
     * Downloads and installs the route generation app APK from the server.
     */
    private fun downloadAndInstallRouteApp() {
        showLoadingOverlay()

        val downloadUrl = "http://43.226.218.98:5000/api/download-route-generation-apk"
        val request = Request.Builder().url(downloadUrl).build()

        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                Log.e("LauncherActivity", "Failed to download APK: ${e.message}", e)
                runOnUiThread {
                    hideLoadingOverlay()
                    showToast("Failed to download APK. Please check your connection.")
                }
            }

            override fun onResponse(call: Call, response: Response) {
                if (!response.isSuccessful) {
                    Log.e("LauncherActivity", "Download failed: ${response.message}")
                    runOnUiThread {
                        hideLoadingOverlay()
                        showToast("Download failed. Server error.")
                    }
                    return
                }

                val apkFile = File(getExternalFilesDir(null), "route-generation-release.apk")
                try {
                    response.body?.byteStream()?.use { inputStream ->
                        apkFile.outputStream().use { outputStream ->
                            inputStream.copyTo(outputStream)
                        }
                    }

                    Log.d("LauncherActivity", "APK saved at: ${apkFile.absolutePath}")
                    runOnUiThread {
                        hideLoadingOverlay()
                        showToast("Download complete. Installing APK...")
                        installApk(apkFile)
                    }
                } catch (e: IOException) {
                    Log.e("LauncherActivity", "Error saving APK: ${e.message}", e)
                    runOnUiThread {
                        hideLoadingOverlay()
                        showToast("Error saving APK. Please try again.")
                    }
                }
            }
        })
    }

    private fun hideLoadingOverlay() {
        loadingOverlay.visibility = RelativeLayout.GONE
    }


    /**
     * Opens the Thingsboard dashboard URL in the default browser.
     */
    private fun openThingsboardDashboard() {
        val dashboardUrl = "http://43.226.218.97:8080/home"
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(dashboardUrl))
        startActivity(intent)
    }

    /** Update the current folder on the server with the latest version for the device UUID. */
    private fun updateCurrentVersionOnServer() {
        val request = Request.Builder()
            .url("http://43.226.218.98:5000/api/update-current-folder/$aid")
            .post(RequestBody.create(null, ByteArray(0)))
            .build()

        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                Log.e("SplashScreen", "Failed to update the current version on the server", e)
                runOnUiThread {
                    showFailureDialog("Failed to update the current version on the server. Please check your connection.")
                }
            }

            override fun onResponse(call: Call, response: Response) {
                if (response.isSuccessful) {
                    runOnUiThread {
                    }
                } else {
                    runOnUiThread {
                        showFailureDialog("Unexpected server response while updating the current version.")
                    }
                }
            }
        })
    }

    /**
     * Show an error dialog if fetching data fails.
     * @param message The error message to display.
     */
    private fun showFailureDialog(message: String) {
        val builder = AlertDialog.Builder(this)
        builder.setTitle("Error")
        builder.setMessage(message)
        builder.setPositiveButton("OK") { dialog, _ ->
            dialog.dismiss()
        }
        builder.setCancelable(false)
        builder.show()
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
        if(currentVersion == latestVersion){
            versionText.text = "Your version $currentVersion is up to date."
        } else {
            versionText.text = "Version $currentVersion (Update available: $latestVersion)"
        }
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
     * Sometimes an app is installed but disabled. Ensure the app is enabled before attempting to launch it
     */
    private fun isAppInstalledAndEnabled(packageName: String): Boolean {
        return try {
            val packageInfo = packageManager.getPackageInfo(packageName, 0)
            val isEnabled = packageInfo.applicationInfo.enabled
            Log.d("LauncherActivity", "$packageName is installed and enabled: $isEnabled")
            isEnabled
        } catch (e: PackageManager.NameNotFoundException) {
            Log.d("LauncherActivity", "$packageName is NOT installed.")
            false
        } catch (e: Exception) {
            Log.e("LauncherActivity", "Unexpected error while checking $packageName: ${e.message}", e)
            false
        }
    }

    /**
     * Instead of relying on FileProvider and manually invoking the installer, consider using the Intent approach with the system package installer to install and verify the APK
     */
    private fun installApkUsingPackageInstaller(apkFile: File) {
        val apkUri = FileProvider.getUriForFile(this, "com.jason.vlrs_launcher.provider", apkFile)
        val installIntent = Intent(Intent.ACTION_INSTALL_PACKAGE).apply {
            data = apkUri
            flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK
        }
        try {
            Log.d("LauncherActivity", "Starting APK installation using Package Installer.")
            startActivity(installIntent)
        } catch (e: Exception) {
            Log.e("LauncherActivity", "Error launching Package Installer: ${e.message}", e)
            showToast("Error launching Package Installer. Please try again.")
        }
    }

    /**
     * Add a loop or delay to check if the package becomes available after installation
     */
    private fun retryLaunchAfterInstall(packageName: String) {
        val maxRetries = 5
        var attempt = 0

        val handler = Handler(Looper.getMainLooper())
        val checkRunnable = object : Runnable {
            override fun run() {
                if (isAppInstalledAndEnabled(packageName)) {
                    Log.d("LauncherActivity", "Package $packageName is installed. Launching now.")
                    launchCreateRouteApp(packageName)
                } else if (attempt < maxRetries) {
                    attempt++
                    Log.d("LauncherActivity", "Retry $attempt: Checking if $packageName is installed.")
                    handler.postDelayed(this, 2000) // Check every 2 seconds
                } else {
                    Log.e("LauncherActivity", "Max retries reached. $packageName not found.")
                    showToast("Failed to install and launch Create Route app.")
                }
            }
        }

        handler.post(checkRunnable)
    }

    /**
     * Checks if the specified package is installed on the device.
     * Logs the result for debugging purposes.
     */
    private fun isAppInstalled(packageName: String): Boolean {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                packageManager.getPackageInfo(packageName, PackageManager.PackageInfoFlags.of(0))
            } else {
                @Suppress("DEPRECATION")
                packageManager.getPackageInfo(packageName, 0)
            }
            Log.d("LauncherActivity", "$packageName is installed.")
            true
        } catch (e: PackageManager.NameNotFoundException) {
            Log.d("LauncherActivity", "$packageName is NOT installed.")
            false
        } catch (e: Exception) {
            Log.e("LauncherActivity", "Unexpected error while checking $packageName: ${e.message}", e)
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
        try {
            Log.d("LauncherActivity", "Starting APK installation from: ${apkFile.absolutePath}")
            startActivity(intent)
            Handler(Looper.getMainLooper()).postDelayed({
                val isInstalled = isAppInstalled("com.example.bus_route")
                Log.d("LauncherActivity", "Post-install check: com.example.bus_route installed = $isInstalled")
                if (isInstalled) {
                    launchCreateRouteApp("com.example.bus_route")
                } else {
                    Log.e("LauncherActivity", "Installation failed for com.example.bus_route.")
                }
            }, 5000) // Delay to allow installation to complete
        } catch (e: Exception) {
            Log.e("LauncherActivity", "Error during APK installation: ${e.message}", e)
        }
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
