package com.jason.vlrs_launcher

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.widget.Button
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
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
    private val MAIN_APP_PACKAGE = "com.jason.publisher" // Replace with your main app's package name
    private val AID = "unique-device-id" // Replace with the unique identifier retrieval for your device
    private lateinit var currentVersion: String
    private lateinit var latestVersion: String

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_launcher_screen)

        client = OkHttpClient()

        // Set up button listeners
        val updateButton = findViewById<Button>(R.id.updateButton)
        val startButton = findViewById<Button>(R.id.startButton)
        val versionText = findViewById<TextView>(R.id.versionText)

        updateButton.setOnClickListener {
            promptUninstallAndInstall(latestVersion)
        }

        startButton.setOnClickListener {
            launchMainApp()
        }

        // Placeholder version information until API response
        currentVersion = "1.0.58"  // Set a default value
        latestVersion = "1.0.59"  // Set a default value
        versionText.text = "Version $currentVersion (Update available: $latestVersion)"

        // Fetch current and latest version information
        checkForUpdates()
    }

    private fun checkForUpdates() {
        val requestCurrent = Request.Builder()
            .url("http://43.226.218.98:5000/api/current-version/$AID")
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
