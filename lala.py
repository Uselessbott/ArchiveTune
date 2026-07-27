#!/usr/bin/env python3
import os

DEBUG_KOTLIN = "app/src/debug/kotlin/moe/rukamori/archivetune/debug"
DEBUG_RES_LAYOUT = "app/src/debug/res/layout"
DEBUG_MANIFEST_DIR = "app/src/debug"

ACTIVITY_KT = '''package moe.rukamori.archivetune.debug

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.os.Build
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import moe.rukamori.archivetune.R
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

class TunnelPrototypeActivity : AppCompatActivity() {

    private lateinit var logView: TextView
    private lateinit var scrollView: ScrollView
    private lateinit var tokenInput: EditText
    private lateinit var statusEnv: TextView
    private lateinit var statusExtract: TextView
    private lateinit var statusVersion: TextView
    private lateinit var statusAuth: TextView
    private lateinit var statusStart: TextView
    private lateinit var statusApi: TextView
    private lateinit var statusStop: TextView
    private lateinit var publicUrlView: TextView

    private var ngrokProcess: Process? = null
    private val client = OkHttpClient()
    private lateinit var prefs: SharedPreferences
    private var publicUrl: String? = null

    private enum class TestStatus { NOT_RUN, PASS, FAIL }
    private val statusMap = mutableMapOf<String, TestStatus>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_tunnel_prototype)

        prefs = getSharedPreferences("tunnel_prototype", MODE_PRIVATE)

        logView = findViewById(R.id.logView)
        scrollView = findViewById(R.id.scrollView)
        tokenInput = findViewById(R.id.tokenInput)
        publicUrlView = findViewById(R.id.publicUrlView)

        statusEnv = findViewById(R.id.statusEnv)
        statusExtract = findViewById(R.id.statusExtract)
        statusVersion = findViewById(R.id.statusVersion)
        statusAuth = findViewById(R.id.statusAuth)
        statusStart = findViewById(R.id.statusStart)
        statusApi = findViewById(R.id.statusApi)
        statusStop = findViewById(R.id.statusStop)

        tokenInput.setText(prefs.getString("ngrok_token", ""))

        findViewById<Button>(R.id.btn_env).setOnClickListener { runEnv() }
        findViewById<Button>(R.id.btn_extract).setOnClickListener { runExtract() }
        findViewById<Button>(R.id.btn_version).setOnClickListener { runVersion() }
        findViewById<Button>(R.id.btn_auth_start).setOnClickListener { runAuthStart() }
        findViewById<Button>(R.id.btn_start_noauth).setOnClickListener { runStartNoAuth() }
        findViewById<Button>(R.id.btn_api_check).setOnClickListener { runApiCheck() }
        findViewById<Button>(R.id.btn_poll_api).setOnClickListener { runPollApi() }
        findViewById<Button>(R.id.btn_stop).setOnClickListener { runStop() }
        findViewById<Button>(R.id.btn_copy_url).setOnClickListener { copyPublicUrl() }
        findViewById<Button>(R.id.btn_copy_report).setOnClickListener { copyReport() }
        findViewById<Button>(R.id.btn_share_report).setOnClickListener { shareReport() }
        findViewById<Button>(R.id.btn_clear_log).setOnClickListener { logView.text = "" }

        log("=== Tunnel Prototype (Self-Contained) ===")
        log("Enter your ngrok authtoken and run tests in order.")
        log("")
        runEnv()
    }

    private fun log(msg: String) {
        runOnUiThread {
            logView.append("$msg\\n")
            scrollView.post { scrollView.fullScroll(ScrollView.FOCUS_DOWN) }
        }
    }

    private fun setStatus(view: TextView, status: TestStatus, detail: String = "") {
        val icon = when (status) {
            TestStatus.NOT_RUN -> "⏳"
            TestStatus.PASS -> "✅"
            TestStatus.FAIL -> "❌"
        }
        val text = "$icon ${status.name}${if (detail.isNotEmpty()) " ($detail)" else ""}"
        view.text = text
        statusMap[view.tag as String] = status
    }

    private fun setPublicUrl(url: String?) {
        publicUrl = url
        runOnUiThread {
            publicUrlView.text = if (url != null) "Public URL: $url" else "Public URL: (not available)"
        }
    }

    // ---- Environment ----
    private fun runEnv() {
        log("=== Environment ===")
        val info = buildString {
            appendLine("Android SDK: ${Build.VERSION.SDK_INT}")
            appendLine("Android Release: ${Build.VERSION.RELEASE}")
            appendLine("Device: ${Build.MANUFACTURER} ${Build.MODEL}")
            appendLine("ABI: ${Build.SUPPORTED_ABIS.joinToString()}")
            appendLine("FilesDir: ${filesDir.absolutePath}")
            appendLine("UID: ${android.os.Process.myUid()}")
            val ngrok = File(filesDir, "ngrok")
            appendLine("Binary path: ${ngrok.absolutePath}")
            appendLine("Binary exists: ${ngrok.exists()}")
            appendLine("canRead: ${ngrok.canRead()}")
            appendLine("canWrite: ${ngrok.canWrite()}")
            appendLine("canExecute: ${ngrok.canExecute()}")
        }
        log(info)
        setStatus(statusEnv, TestStatus.PASS)
    }

    // ---- Extract ----
    private fun runExtract() {
        log("=== Extract Binary ===")
        try {
            val dest = File(filesDir, "ngrok")
            assets.open("ngrok_arm64").use { input ->
                dest.outputStream().use { output ->
                    input.copyTo(output)
                }
            }
            dest.setExecutable(true)
            log("Extracted to ${dest.absolutePath}")
            log("exists=${dest.exists()}, canRead=${dest.canRead()}, canWrite=${dest.canWrite()}, canExecute=${dest.canExecute()}")

            val hash = dest.inputStream().use { input ->
                val digest = MessageDigest.getInstance("SHA-256")
                val buffer = ByteArray(8192)
                var bytesRead: Int
                while (input.read(buffer).also { bytesRead = it } != -1) {
                    digest.update(buffer, 0, bytesRead)
                }
                digest.digest().joinToString("") { "%02x".format(it) }
            }
            log("SHA-256: $hash")
            setStatus(statusExtract, TestStatus.PASS)
        } catch (e: Exception) {
            log("ERROR: ${e.message}")
            e.printStackTrace()
            setStatus(statusExtract, TestStatus.FAIL, e.message ?: "unknown error")
        }
    }

    // ---- Version ----
    private fun runVersion() {
        log("=== Run Version ===")
        val ngrok = File(filesDir, "ngrok")
        if (!ngrok.exists()) {
            log("ERROR: Binary not found. Run Extract first.")
            setStatus(statusVersion, TestStatus.FAIL, "binary not found")
            return
        }
        try {
            val process = ProcessBuilder(ngrok.absolutePath, "version")
                .directory(filesDir)
                .redirectErrorStream(false)
                .start()
            val stdout = BufferedReader(InputStreamReader(process.inputStream)).readText()
            val stderr = BufferedReader(InputStreamReader(process.errorStream)).readText()
            if (!process.waitFor(10, TimeUnit.SECONDS)) {
                log("Version command timed out, forcing destroy")
                process.destroyForcibly()
                setStatus(statusVersion, TestStatus.FAIL, "timed out")
                return
            }
            log("Exit code: ${process.exitValue()}")
            log("STDOUT: $stdout")
            if (stderr.isNotBlank()) log("STDERR: $stderr")
            if (process.exitValue() == 0) {
                setStatus(statusVersion, TestStatus.PASS)
            } else {
                setStatus(statusVersion, TestStatus.FAIL, "exit ${process.exitValue()}")
            }
        } catch (e: Exception) {
            log("ERROR: ${e.message}")
            e.printStackTrace()
            setStatus(statusVersion, TestStatus.FAIL, e.message ?: "unknown error")
        }
    }

    // ---- Auth + Start ----
    private fun runAuthStart() {
        log("=== Start with Auth ===")
        val token = tokenInput.text.toString().trim()
        if (token.isEmpty()) {
            log("ERROR: Token is empty. Please enter your ngrok authtoken.")
            setStatus(statusAuth, TestStatus.FAIL, "token empty")
            return
        }
        prefs.edit().putString("ngrok_token", token).apply()

        val ngrok = File(filesDir, "ngrok")
        if (!ngrok.exists()) {
            log("ERROR: Binary not found. Run Extract first.")
            setStatus(statusAuth, TestStatus.FAIL, "binary missing")
            return
        }

        try {
            val process = ProcessBuilder(ngrok.absolutePath, "http", "8080", "--authtoken=$token", "--log=stdout", "--log-level=debug")
                .directory(filesDir)
                .redirectErrorStream(false)
                .start()
            ngrokProcess = process

            lifecycleScope.launch(Dispatchers.IO) {
                val stdout = BufferedReader(InputStreamReader(process.inputStream))
                stdout.lineSequence().forEach { log("NGROK: $it") }
            }
            lifecycleScope.launch(Dispatchers.IO) {
                val stderr = BufferedReader(InputStreamReader(process.errorStream))
                stderr.lineSequence().forEach { log("NGROK ERR: $it") }
            }
            lifecycleScope.launch(Dispatchers.IO) {
                if (!process.waitFor(30, TimeUnit.SECONDS)) {
                    log("Process waitFor timed out after 30s, forcing destroy")
                    process.destroyForcibly()
                    setStatus(statusAuth, TestStatus.FAIL, "timed out")
                } else {
                    val code = process.exitValue()
                    log("Process exited with code: $code")
                    if (code == 0) {
                        setStatus(statusAuth, TestStatus.PASS)
                    } else {
                        setStatus(statusAuth, TestStatus.FAIL, "exit $code")
                    }
                }
                if (ngrokProcess === process) ngrokProcess = null
            }

            log("Process started with PID: ${process.pid()}")
            lifecycleScope.launch(Dispatchers.IO) {
                delay(3000)
                discoverPublicUrl()
            }
        } catch (e: Exception) {
            log("ERROR: ${e.message}")
            e.printStackTrace()
            setStatus(statusAuth, TestStatus.FAIL, e.message ?: "unknown error")
        }
    }

    // ---- Start (no auth) ----
    private fun runStartNoAuth() {
        log("=== Start Tunnel (no auth) ===")
        val ngrok = File(filesDir, "ngrok")
        if (!ngrok.exists()) {
            log("ERROR: Binary not found. Run Extract first.")
            setStatus(statusStart, TestStatus.FAIL, "binary missing")
            return
        }
        if (ngrokProcess?.isAlive == true) {
            log("Tunnel already running")
            setStatus(statusStart, TestStatus.PASS)
            return
        }
        try {
            val process = ProcessBuilder(ngrok.absolutePath, "http", "8080", "--log=stdout", "--log-level=debug")
                .directory(filesDir)
                .redirectErrorStream(false)
                .start()
            ngrokProcess = process

            lifecycleScope.launch(Dispatchers.IO) {
                val stdout = BufferedReader(InputStreamReader(process.inputStream))
                stdout.lineSequence().forEach { log("NGROK: $it") }
            }
            lifecycleScope.launch(Dispatchers.IO) {
                val stderr = BufferedReader(InputStreamReader(process.errorStream))
                stderr.lineSequence().forEach { log("NGROK ERR: $it") }
            }
            lifecycleScope.launch(Dispatchers.IO) {
                if (!process.waitFor(30, TimeUnit.SECONDS)) {
                    log("Process waitFor timed out after 30s, forcing destroy")
                    process.destroyForcibly()
                    setStatus(statusStart, TestStatus.FAIL, "timed out")
                } else {
                    val code = process.exitValue()
                    log("Process exited with code: $code")
                    if (code == 0) {
                        setStatus(statusStart, TestStatus.PASS)
                    } else {
                        setStatus(statusStart, TestStatus.FAIL, "exit $code")
                    }
                }
                if (ngrokProcess === process) ngrokProcess = null
            }

            log("Process started with PID: ${process.pid()}")
            lifecycleScope.launch(Dispatchers.IO) {
                delay(3000)
                discoverPublicUrl()
            }
        } catch (e: Exception) {
            log("ERROR: ${e.message}")
            e.printStackTrace()
            setStatus(statusStart, TestStatus.FAIL, e.message ?: "unknown error")
        }
    }

    // ---- API Check ----
    private fun runApiCheck() {
        log("=== API Check (once) ===")
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val request = Request.Builder()
                    .url("http://127.0.0.1:4040/api/tunnels")
                    .build()
                client.newCall(request).execute().use { response ->
                    if (response.isSuccessful) {
                        val body = response.body?.string()
                        log("API Response: $body")
                        setStatus(statusApi, TestStatus.PASS)
                        val url = extractPublicUrl(body)
                        if (url != null) setPublicUrl(url)
                    } else {
                        log("API returned ${response.code}")
                        setStatus(statusApi, TestStatus.FAIL, "HTTP ${response.code}")
                    }
                }
            } catch (e: Exception) {
                log("API ERROR: ${e.message}")
                setStatus(statusApi, TestStatus.FAIL, e.message ?: "unknown error")
            }
        }
    }

    // ---- Poll API ----
    private fun runPollApi() {
        log("=== Poll API (30s) ===")
        lifecycleScope.launch(Dispatchers.IO) {
            var aliveCount = 0
            var errorCount = 0
            var lastUrl: String? = null
            repeat(30) { i ->
                try {
                    val request = Request.Builder()
                        .url("http://127.0.0.1:4040/api/tunnels")
                        .build()
                    client.newCall(request).execute().use { response ->
                        if (response.isSuccessful) {
                            aliveCount++
                            val body = response.body?.string()
                            val url = extractPublicUrl(body)
                            if (url != null) lastUrl = url
                            log("[${i+1}/30] API alive (${response.code})")
                        } else {
                            errorCount++
                            log("[${i+1}/30] API error: ${response.code}")
                        }
                    }
                } catch (e: Exception) {
                    errorCount++
                    log("[${i+1}/30] API exception: ${e.message}")
                }
                delay(1000)
            }
            log("Poll finished: alive=$aliveCount errors=$errorCount")
            if (aliveCount == 30) {
                log("API stable: 30/30 successful")
                setStatus(statusApi, TestStatus.PASS)
                if (lastUrl != null) setPublicUrl(lastUrl)
            } else {
                log("API unstable: $aliveCount successful out of 30")
                setStatus(statusApi, TestStatus.FAIL, "only $aliveCount/30 success")
            }
        }
    }

    // ---- Stop ----
    private fun runStop() {
        log("=== Stop Tunnel ===")
        val process = ngrokProcess
        if (process == null) {
            log("No tunnel running")
            setStatus(statusStop, TestStatus.PASS)
            return
        }
        try {
            process.destroy()
            if (!process.waitFor(5, TimeUnit.SECONDS)) {
                log("Process did not terminate within 5s, forcing destroy")
                process.destroyForcibly()
                setStatus(statusStop, TestStatus.FAIL, "forced kill")
            } else {
                log("Process terminated gracefully")
                setStatus(statusStop, TestStatus.PASS)
            }
            ngrokProcess = null
            setPublicUrl(null)
        } catch (e: Exception) {
            log("ERROR: ${e.message}")
            e.printStackTrace()
            setStatus(statusStop, TestStatus.FAIL, e.message ?: "unknown error")
        }
    }

    // ---- Helper: discover public URL ----
    private suspend fun discoverPublicUrl() {
        try {
            val request = Request.Builder()
                .url("http://127.0.0.1:4040/api/tunnels")
                .build()
            client.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    val body = response.body?.string()
                    val url = extractPublicUrl(body)
                    if (url != null) setPublicUrl(url)
                }
            }
        } catch (_: Exception) { /* ignore */ }
    }

    // ---- Helper: extract public URL ----
    private fun extractPublicUrl(json: String?): String? {
        if (json == null) return null
        val regex = "\\"public_url\\"\\s*:\\s*\\"(https?://[^\\"]+)\\"".toRegex()
        return regex.find(json)?.groupValues?.get(1)
    }

    // ---- Copy URL ----
    private fun copyPublicUrl() {
        val url = publicUrl
        if (url == null) {
            log("No public URL available")
            return
        }
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("Public URL", url))
        log("Public URL copied to clipboard")
    }

    // ---- Generate report ----
    private fun generateReport(): String {
        val sb = StringBuilder()
        sb.appendLine("=== Tunnel Prototype Diagnostic Report ===")
        sb.appendLine("Timestamp: ${System.currentTimeMillis()}")
        sb.appendLine()
        sb.appendLine("--- Environment ---")
        sb.appendLine("Android SDK: ${Build.VERSION.SDK_INT}")
        sb.appendLine("Android Release: ${Build.VERSION.RELEASE}")
        sb.appendLine("Device: ${Build.MANUFACTURER} ${Build.MODEL}")
        sb.appendLine("ABI: ${Build.SUPPORTED_ABIS.joinToString()}")
        sb.appendLine("FilesDir: ${filesDir.absolutePath}")
        sb.appendLine("UID: ${android.os.Process.myUid()}")
        val ngrok = File(filesDir, "ngrok")
        sb.appendLine("Binary path: ${ngrok.absolutePath}")
        sb.appendLine("Binary exists: ${ngrok.exists()}")
        sb.appendLine("canRead: ${ngrok.canRead()}")
        sb.appendLine("canWrite: ${ngrok.canWrite()}")
        sb.appendLine("canExecute: ${ngrok.canExecute()}")
        if (ngrok.exists()) {
            try {
                val hash = ngrok.inputStream().use { input ->
                    val digest = MessageDigest.getInstance("SHA-256")
                    val buffer = ByteArray(8192)
                    var bytesRead: Int
                    while (input.read(buffer).also { bytesRead = it } != -1) {
                        digest.update(buffer, 0, bytesRead)
                    }
                    digest.digest().joinToString("") { "%02x".format(it) }
                }
                sb.appendLine("SHA-256: $hash")
            } catch (_: Exception) { /* ignore */ }
        }
        sb.appendLine()
        sb.appendLine("--- Test Status ---")
        statusMap.forEach { (key, status) ->
            sb.appendLine("$key: $status")
        }
        sb.appendLine()
        sb.appendLine("--- Public URL ---")
        sb.appendLine(publicUrl ?: "(none)")
        sb.appendLine()
        sb.appendLine("--- Process Info ---")
        val proc = ngrokProcess
        if (proc != null && proc.isAlive) {
            sb.appendLine("PID: ${proc.pid()}")
            sb.appendLine("isAlive: true")
        } else {
            sb.appendLine("Process: not running")
        }
        sb.appendLine()
        sb.appendLine("--- Log ---")
        sb.appendLine(logView.text.toString())
        return sb.toString()
    }

    // ---- Copy Report ----
    private fun copyReport() {
        val report = generateReport()
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("Tunnel Prototype Report", report))
        log("Report copied to clipboard")
    }

    // ---- Share Report ----
    private fun shareReport() {
        val report = generateReport()
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, report)
            putExtra(Intent.EXTRA_SUBJECT, "Tunnel Prototype Report")
        }
        startActivity(Intent.createChooser(intent, "Share Report"))
    }
}
'''

LAYOUT_XML = '''<?xml version="1.0" encoding="utf-8"?>
<LinearLayout xmlns:android="http://schemas.android.com/apk/res/android"
    android:layout_width="match_parent"
    android:layout_height="match_parent"
    android:orientation="vertical"
    android:padding="16dp">

    <TextView
        android:layout_width="wrap_content"
        android:layout_height="wrap_content"
        android:text="Tunnel Prototype"
        android:textSize="24sp"
        android:layout_marginBottom="8dp"/>

    <EditText
        android:id="@+id/tokenInput"
        android:layout_width="match_parent"
        android:layout_height="wrap_content"
        android:hint="Enter ngrok authtoken"
        android:inputType="textPassword"
        android:layout_marginBottom="8dp"/>

    <LinearLayout
        android:layout_width="match_parent"
        android:layout_height="wrap_content"
        android:orientation="vertical">

        <LinearLayout
            android:layout_width="match_parent"
            android:layout_height="wrap_content"
            android:orientation="horizontal"
            android:weightSum="2">
            <Button
                android:id="@+id/btn_env"
                android:layout_width="0dp"
                android:layout_height="wrap_content"
                android:layout_weight="1"
                android:text="Env"/>
            <TextView
                android:id="@+id/statusEnv"
                android:layout_width="0dp"
                android:layout_height="wrap_content"
                android:layout_weight="1"
                android:tag="Environment"
                android:text="⏳ Not run"
                android:paddingStart="8dp"/>
        </LinearLayout>

        <LinearLayout
            android:layout_width="match_parent"
            android:layout_height="wrap_content"
            android:orientation="horizontal"
            android:weightSum="2">
            <Button
                android:id="@+id/btn_extract"
                android:layout_width="0dp"
                android:layout_height="wrap_content"
                android:layout_weight="1"
                android:text="Extract"/>
            <TextView
                android:id="@+id/statusExtract"
                android:layout_width="0dp"
                android:layout_height="wrap_content"
                android:layout_weight="1"
                android:tag="Extract"
                android:text="⏳ Not run"
                android:paddingStart="8dp"/>
        </LinearLayout>

        <LinearLayout
            android:layout_width="match_parent"
            android:layout_height="wrap_content"
            android:orientation="horizontal"
            android:weightSum="2">
            <Button
                android:id="@+id/btn_version"
                android:layout_width="0dp"
                android:layout_height="wrap_content"
                android:layout_weight="1"
                android:text="Version"/>
            <TextView
                android:id="@+id/statusVersion"
                android:layout_width="0dp"
                android:layout_height="wrap_content"
                android:layout_weight="1"
                android:tag="Version"
                android:text="⏳ Not run"
                android:paddingStart="8dp"/>
        </LinearLayout>

        <LinearLayout
            android:layout_width="match_parent"
            android:layout_height="wrap_content"
            android:orientation="horizontal"
            android:weightSum="2">
            <Button
                android:id="@+id/btn_auth_start"
                android:layout_width="0dp"
                android:layout_height="wrap_content"
                android:layout_weight="1"
                android:text="Auth+Start"/>
            <TextView
                android:id="@+id/statusAuth"
                android:layout_width="0dp"
                android:layout_height="wrap_content"
                android:layout_weight="1"
                android:tag="AuthStart"
                android:text="⏳ Not run"
                android:paddingStart="8dp"/>
        </LinearLayout>

        <LinearLayout
            android:layout_width="match_parent"
            android:layout_height="wrap_content"
            android:orientation="horizontal"
            android:weightSum="2">
            <Button
                android:id="@+id/btn_start_noauth"
                android:layout_width="0dp"
                android:layout_height="wrap_content"
                android:layout_weight="1"
                android:text="Start (no auth)"/>
            <TextView
                android:id="@+id/statusStart"
                android:layout_width="0dp"
                android:layout_height="wrap_content"
                android:layout_weight="1"
                android:tag="StartNoAuth"
                android:text="⏳ Not run"
                android:paddingStart="8dp"/>
        </LinearLayout>

        <LinearLayout
            android:layout_width="match_parent"
            android:layout_height="wrap_content"
            android:orientation="horizontal"
            android:weightSum="2">
            <Button
                android:id="@+id/btn_api_check"
                android:layout_width="0dp"
                android:layout_height="wrap_content"
                android:layout_weight="1"
                android:text="API Check"/>
            <TextView
                android:id="@+id/statusApi"
                android:layout_width="0dp"
                android:layout_height="wrap_content"
                android:layout_weight="1"
                android:tag="ApiCheck"
                android:text="⏳ Not run"
                android:paddingStart="8dp"/>
        </LinearLayout>

        <LinearLayout
            android:layout_width="match_parent"
            android:layout_height="wrap_content"
            android:orientation="horizontal"
            android:weightSum="2">
            <Button
                android:id="@+id/btn_poll_api"
                android:layout_width="0dp"
                android:layout_height="wrap_content"
                android:layout_weight="1"
                android:text="Poll API (30s)"/>
            <TextView
                android:id="@+id/statusApi"
                android:layout_width="0dp"
                android:layout_height="wrap_content"
                android:layout_weight="1"
                android:tag="PollApi"
                android:text="⏳ Not run"
                android:paddingStart="8dp"/>
        </LinearLayout>

        <LinearLayout
            android:layout_width="match_parent"
            android:layout_height="wrap_content"
            android:orientation="horizontal"
            android:weightSum="2">
            <Button
                android:id="@+id/btn_stop"
                android:layout_width="0dp"
                android:layout_height="wrap_content"
                android:layout_weight="1"
                android:text="Stop"/>
            <TextView
                android:id="@+id/statusStop"
                android:layout_width="0dp"
                android:layout_height="wrap_content"
                android:layout_weight="1"
                android:tag="Stop"
                android:text="⏳ Not run"
                android:paddingStart="8dp"/>
        </LinearLayout>
    </LinearLayout>

    <TextView
        android:id="@+id/publicUrlView"
        android:layout_width="match_parent"
        android:layout_height="wrap_content"
        android:text="Public URL: (not available)"
        android:textStyle="bold"
        android:layout_marginTop="8dp"/>

    <LinearLayout
        android:layout_width="match_parent"
        android:layout_height="wrap_content"
        android:orientation="horizontal"
        android:weightSum="4">
        <Button
            android:id="@+id/btn_copy_url"
            android:layout_width="0dp"
            android:layout_height="wrap_content"
            android:layout_weight="1"
            android:text="Copy URL"/>
        <Button
            android:id="@+id/btn_copy_report"
            android:layout_width="0dp"
            android:layout_height="wrap_content"
            android:layout_weight="1"
            android:text="Copy Report"/>
        <Button
            android:id="@+id/btn_share_report"
            android:layout_width="0dp"
            android:layout_height="wrap_content"
            android:layout_weight="1"
            android:text="Share Report"/>
        <Button
            android:id="@+id/btn_clear_log"
            android:layout_width="0dp"
            android:layout_height="wrap_content"
            android:layout_weight="1"
            android:text="Clear Log"/>
    </LinearLayout>

    <ScrollView
        android:id="@+id/scrollView"
        android:layout_width="match_parent"
        android:layout_height="0dp"
        android:layout_weight="1"
        android:background="#f0f0f0"
        android:padding="8dp"
        android:layout_marginTop="8dp">

        <TextView
            android:id="@+id/logView"
            android:layout_width="match_parent"
            android:layout_height="wrap_content"
            android:fontFamily="monospace"
            android:textSize="12sp"
            android:text="Logs will appear here...\\n"/>
    </ScrollView>
</LinearLayout>
'''

ANDROID_MANIFEST_DEBUG = '''<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android"
    xmlns:tools="http://schemas.android.com/tools"
    package="moe.rukamori.archivetune">

    <application>
        <activity
            android:name=".debug.TunnelPrototypeActivity"
            android:label="Tunnel Prototype"
            android:exported="true"
            tools:ignore="ExportedActivity" />
    </application>
</manifest>
'''

def create_dirs_and_files():
    os.makedirs(DEBUG_KOTLIN, exist_ok=True)
    os.makedirs(DEBUG_RES_LAYOUT, exist_ok=True)

    with open(os.path.join(DEBUG_KOTLIN, "TunnelPrototypeActivity.kt"), 'w') as f:
        f.write(ACTIVITY_KT)

    with open(os.path.join(DEBUG_RES_LAYOUT, "activity_tunnel_prototype.xml"), 'w') as f:
        f.write(LAYOUT_XML)

    with open(os.path.join(DEBUG_MANIFEST_DIR, "AndroidManifest.xml"), 'w') as f:
        f.write(ANDROID_MANIFEST_DEBUG)

    print("Created self-contained debug prototype:")
    print("  app/src/debug/kotlin/.../TunnelPrototypeActivity.kt")
    print("  app/src/debug/res/layout/activity_tunnel_prototype.xml")
    print("  app/src/debug/AndroidManifest.xml")
    print()
    print("Next steps:")
    print("1. Place the ARM64 ngrok binary in app/src/main/assets/ngrok_arm64")
    print("2. Build the debug variant and install the APK")
    print("3. Open TunnelPrototypeActivity from the launcher")
    print("4. Enter your ngrok authtoken and run tests in order")
    print("5. Use Copy Report / Share Report to send diagnostic info")

if __name__ == "__main__":
    create_dirs_and_files()
