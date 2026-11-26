package com.pioneer.stereo

import android.Manifest
import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Color
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.ContactsContract
import android.provider.Settings
import android.telecom.TelecomManager
import android.telephony.TelephonyManager
import android.view.WindowManager
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.webkit.JavascriptInterface
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.webkit.WebViewAssetLoader
import org.json.JSONObject

class MainActivity : AppCompatActivity() {

    private lateinit var web: WebView
    private lateinit var audio: AudioManager
    private lateinit var telecom: TelecomManager

    // Call state mirrored to the web UI: IDLE / RINGING / OFFHOOK
    private var callState = "IDLE"
    private var dir = ""          // "IN" or "OUT"
    private var number = ""
    private var name = ""
    private var since = 0L
    private var pendingDial = ""

    private var fileCb: ValueCallback<Array<Uri>>? = null

    private val pickFiles =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
            fileCb?.onReceiveValue(
                WebChromeClient.FileChooserParams.parseResult(r.resultCode, r.data)
            )
            fileCb = null
        }

    private val askPerms =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            pushState()
        }

    // ---------------------------------------------------------------- receivers

    private val phoneRx = object : BroadcastReceiver() {
        @Suppress("DEPRECATION")
        override fun onReceive(c: Context, i: Intent) {
            val s = i.getStringExtra(TelephonyManager.EXTRA_STATE) ?: return
            val n = i.getStringExtra(TelephonyManager.EXTRA_INCOMING_NUMBER)
            when (s) {
                TelephonyManager.EXTRA_STATE_RINGING -> {
                    if (callState != "RINGING") {
                        since = System.currentTimeMillis()
                        dir = "IN"
                        number = ""
                        name = ""
                    }
                    callState = "RINGING"
                    if (!n.isNullOrEmpty()) {
                        number = n
                        name = lookupName(n)
                    }
                }
                TelephonyManager.EXTRA_STATE_OFFHOOK -> {
                    if (callState == "IDLE") {
                        dir = "OUT"
                        number = pendingDial
                        name = lookupName(pendingDial)
                    }
                    since = System.currentTimeMillis()
                    callState = "OFFHOOK"
                }
                TelephonyManager.EXTRA_STATE_IDLE -> {
                    callState = "IDLE"
                    number = ""
                    name = ""
                    dir = ""
                    pendingDial = ""
                    try { audio.isMicrophoneMute = false } catch (_: Exception) {}
                }
            }
            pushState()
        }
    }

    private val volRx = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) = pushState()
    }

    private val deviceCb = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(added: Array<out AudioDeviceInfo>?) = pushState()
        override fun onAudioDevicesRemoved(removed: Array<out AudioDeviceInfo>?) = pushState()
    }

    // ---------------------------------------------------------------- lifecycle

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        volumeControlStream = AudioManager.STREAM_MUSIC
        audio = getSystemService(AUDIO_SERVICE) as AudioManager
        telecom = getSystemService(TELECOM_SERVICE) as TelecomManager

        web = WebView(this)
        web.setBackgroundColor(Color.BLACK)
        setContentView(web)

        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }

        with(web.settings) {
            javaScriptEnabled = true
            domStorageEnabled = true            // IndexedDB song library
            mediaPlaybackRequiresUserGesture = false
            allowFileAccess = false
        }

        val loader = WebViewAssetLoader.Builder()
            .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(this))
            .build()

        web.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(
                view: WebView, request: WebResourceRequest
            ): WebResourceResponse? = loader.shouldInterceptRequest(request.url)

            override fun shouldOverrideUrlLoading(
                view: WebView, request: WebResourceRequest
            ): Boolean {
                if (request.url.host == "appassets.androidplatform.net") return false
                openExternal(request.url.toString())
                return true
            }

            override fun onPageFinished(view: WebView, url: String) = pushState()
        }

        web.webChromeClient = object : WebChromeClient() {
            override fun onShowFileChooser(
                webView: WebView,
                callback: ValueCallback<Array<Uri>>,
                params: WebChromeClient.FileChooserParams
            ): Boolean {
                fileCb?.onReceiveValue(null)
                fileCb = callback
                return try {
                    pickFiles.launch(params.createIntent())
                    true
                } catch (e: Exception) {
                    fileCb = null
                    callback.onReceiveValue(null)
                    false
                }
            }
        }

        web.addJavascriptInterface(Bridge(), "Android")
        web.loadUrl("https://appassets.androidplatform.net/assets/index.html")

        ContextCompat.registerReceiver(
            this, phoneRx, IntentFilter(TelephonyManager.ACTION_PHONE_STATE_CHANGED),
            ContextCompat.RECEIVER_EXPORTED
        )
        ContextCompat.registerReceiver(
            this, volRx, IntentFilter("android.media.VOLUME_CHANGED_ACTION"),
            ContextCompat.RECEIVER_EXPORTED
        )
        audio.registerAudioDeviceCallback(deviceCb, null)

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                moveTaskToBack(true)
            }
        })

        requestMissingPermissions()
    }

    override fun onDestroy() {
        try { unregisterReceiver(phoneRx) } catch (_: Exception) {}
        try { unregisterReceiver(volRx) } catch (_: Exception) {}
        audio.unregisterAudioDeviceCallback(deviceCb)
        web.destroy()
        super.onDestroy()
    }

    private fun has(p: String) =
        ContextCompat.checkSelfPermission(this, p) == PackageManager.PERMISSION_GRANTED

    private fun requestMissingPermissions() {
        val want = mutableListOf(
            Manifest.permission.READ_PHONE_STATE,
            Manifest.permission.ANSWER_PHONE_CALLS,
            Manifest.permission.CALL_PHONE,
            Manifest.permission.READ_CALL_LOG,
            Manifest.permission.READ_CONTACTS
        )
        if (Build.VERSION.SDK_INT >= 31) want.add(Manifest.permission.BLUETOOTH_CONNECT)
        val missing = want.filter { !has(it) }
        if (missing.isNotEmpty()) askPerms.launch(missing.toTypedArray())
    }

    // ---------------------------------------------------------------- helpers

    private fun stream() =
        if (callState == "OFFHOOK") AudioManager.STREAM_VOICE_CALL else AudioManager.STREAM_MUSIC

    /** Current system volume of the active stream, scaled to the knob's 0..30. */
    private fun volLevel(): Int {
        val s = stream()
        val max = audio.getStreamMaxVolume(s).coerceAtLeast(1)
        return Math.round(audio.getStreamVolume(s) * 30f / max)
    }

    private fun btName(): String {
        val devs = audio.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
        val bt = devs.firstOrNull {
            it.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP ||
                it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO ||
                (Build.VERSION.SDK_INT >= 31 &&
                    (it.type == AudioDeviceInfo.TYPE_BLE_HEADSET ||
                        it.type == AudioDeviceInfo.TYPE_BLE_SPEAKER))
        }
        return bt?.productName?.toString() ?: ""
    }

    private fun lookupName(num: String): String {
        if (num.isEmpty() || !has(Manifest.permission.READ_CONTACTS)) return ""
        try {
            val uri = Uri.withAppendedPath(
                ContactsContract.PhoneLookup.CONTENT_FILTER_URI, Uri.encode(num)
            )
            contentResolver.query(
                uri, arrayOf(ContactsContract.PhoneLookup.DISPLAY_NAME), null, null, null
            )?.use { c -> if (c.moveToFirst()) return c.getString(0) ?: "" }
        } catch (_: Exception) {
        }
        return ""
    }

    private fun openExternal(url: String) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (_: Exception) {
        }
    }

    private fun pushState() {
        runOnUiThread {
            if (!::web.isInitialized) return@runOnUiThread
            volumeControlStream = stream()
            val o = JSONObject()
                .put("call", callState)
                .put("dir", dir)
                .put("num", number)
                .put("name", name)
                .put("since", since)
                .put("bt", btName())
                .put("vol", volLevel())
                .put("mute", audio.isMicrophoneMute)
            web.evaluateJavascript("window.__nativeState&&window.__nativeState($o)", null)
        }
    }

    // ---------------------------------------------------------------- JS bridge

    inner class Bridge {
        @JavascriptInterface
        fun requestState() = pushState()

        @JavascriptInterface
        fun setVolume(level: Int) {
            val s = stream()
            val max = audio.getStreamMaxVolume(s)
            val v = Math.round(level.coerceIn(0, 30) / 30f * max)
            try { audio.setStreamVolume(s, v, 0) } catch (_: Exception) {}
        }

        @JavascriptInterface
        fun answer() {
            runOnUiThread {
                try { telecom.acceptRingingCall() } catch (_: SecurityException) { requestMissingPermissions() }
            }
        }

        @Suppress("DEPRECATION")
        @JavascriptInterface
        fun hangup() {
            runOnUiThread {
                try { telecom.endCall() } catch (_: SecurityException) { requestMissingPermissions() }
            }
        }

        @JavascriptInterface
        fun dial(n: String) {
            val clean = n.filter { it.isDigit() || it == '+' || it == '*' || it == '#' }
            if (clean.isEmpty()) return
            runOnUiThread {
                pendingDial = clean
                val uri = Uri.fromParts("tel", clean, null)
                try {
                    startActivity(Intent(Intent.ACTION_CALL, uri))
                } catch (_: SecurityException) {
                    // No CALL_PHONE permission yet: ask, and open the normal dialer meanwhile.
                    requestMissingPermissions()
                    startActivity(Intent(Intent.ACTION_DIAL, uri))
                }
            }
        }

        @JavascriptInterface
        fun mute(on: Boolean) {
            try { audio.isMicrophoneMute = on } catch (_: Exception) {}
            pushState()
        }

        @JavascriptInterface
        fun openBtSettings() {
            runOnUiThread {
                try { startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS)) } catch (_: Exception) {}
            }
        }

        @JavascriptInterface
        fun openUrl(url: String) {
            runOnUiThread { openExternal(url) }
        }
    }
}
