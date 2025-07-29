package com.jason.vlrs_launcher

import android.os.Bundle
import android.util.Log
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

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
            logOutput.text = "Fetching raw data from Auckland Transport...\n"
            lifecycleScope.launch {
                val result = fetchRawRoutes()
                logOutput.append(result)
            }
        }
    }

    private suspend fun fetchRawRoutes(): String = withContext(Dispatchers.IO) {
        val url = "${baseUrl}routes"
        Log.d("AT_RAW", "Requesting: $url")

        val request = Request.Builder()
            .url(url)
            .addHeader("Ocp-Apim-Subscription-Key", apiKey)
            .addHeader("Accept", "application/json")
            .build()

        try {
            val response = client.newCall(request).execute()
            val rawJson = response.body?.string()

            if (!response.isSuccessful || rawJson.isNullOrEmpty()) {
                val errorMsg = "API error ${response.code}: ${rawJson ?: "Empty response"}"
                Log.e("AT_RAW", errorMsg)
                return@withContext errorMsg
            }

            Log.d("AT_RAW", rawJson)
            return@withContext rawJson

        } catch (e: Exception) {
            val exceptionMsg = "Exception: ${e.localizedMessage}"
            Log.e("AT_RAW", exceptionMsg, e)
            return@withContext exceptionMsg
        }
    }
}
