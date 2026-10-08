package ru.mahnet.courier

import android.Manifest
import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.media.AudioManager
import android.media.ToneGenerator
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.os.VibrationEffect
import android.os.Vibrator
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.webkit.CookieManager
import android.webkit.GeolocationPermissions
import android.webkit.WebChromeClient
import android.webkit.JavascriptInterface
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.FrameLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import org.json.JSONObject
import java.util.concurrent.Executors

/**
 * Приложение курьера: открывает сайт https://mahnet.ru/courier/ в окне WebView
 * и подключает родной сканер штрихкодов (камера + ML Kit), потому что встроенный
 * в Chrome сканер внутри WebView недоступен.
 */
class MainActivity : AppCompatActivity() {

    companion object {
        // Адрес приложения курьера на вашем сайте. Поменяйте здесь, если сайт переедет.
        const val BASE_URL = "https://mahnet.ru/"
        const val START_URL = BASE_URL + "courier/"
        const val HOST_SUFFIX = "mahnet.ru"
    }

    private lateinit var web: WebView
    private lateinit var root: FrameLayout

    private var scanLayer: FrameLayout? = null
    private var counterView: TextView? = null
    private var resultView: TextView? = null
    private var previewView: PreviewView? = null
    private var provider: ProcessCameraProvider? = null

    private val analysisExecutor = Executors.newSingleThreadExecutor()
    private val scanner by lazy {
        BarcodeScanning.getClient(
            BarcodeScannerOptions.Builder()
                .setBarcodeFormats(
                    Barcode.FORMAT_CODE_128,
                    Barcode.FORMAT_EAN_13,
                    Barcode.FORMAT_CODE_39
                )
                .build()
        )
    }

    @Volatile private var scanning = false
    @Volatile private var paused = false
    private var lastCode = ""
    private var lastAt = 0L
    private var pendingHint = ""
    private var tone: ToneGenerator? = null

    // Геолокация для карты маршрута внутри приложения
    private var geoCallback: GeolocationPermissions.Callback? = null
    private var geoOrigin: String? = null
    private val locationPermission =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
            val ok = result.values.any { it }
            geoCallback?.invoke(geoOrigin, ok, false)
            geoCallback = null
            geoOrigin = null
        }

    private val notifPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { _ ->
            askBatteryOnce()
        }

    private val cameraPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) {
                openScanner(pendingHint)
            } else {
                Toast.makeText(
                    this,
                    "Разрешите доступ к камере в настройках телефона, чтобы сканировать штрихкоды",
                    Toast.LENGTH_LONG
                ).show()
                js("window.__onNativeClosed&&window.__onNativeClosed()")
            }
        }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        // Всегда светлая тема, даже если на телефоне включена тёмная
        AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_NO)
        super.onCreate(savedInstanceState)
        // Экран не гаснет, пока приложение открыто
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        root = FrameLayout(this)
        web = WebView(this)
        root.addView(
            web,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        )
        setContentView(root)

        val s = web.settings
        s.javaScriptEnabled = true
        s.domStorageEnabled = true
        s.mediaPlaybackRequiresUserGesture = false
        s.setSupportZoom(false)
        s.builtInZoomControls = false
        s.displayZoomControls = false
        s.userAgentString = s.userAgentString + " MarketCourierApp/1.0"
        s.setGeolocationEnabled(true)
        s.javaScriptCanOpenWindowsAutomatically = false
        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(web, true)

        web.webChromeClient = object : WebChromeClient() {
            override fun onGeolocationPermissionsShowPrompt(origin: String, callback: GeolocationPermissions.Callback) {
                val fine = ContextCompat.checkSelfPermission(this@MainActivity, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
                val coarse = ContextCompat.checkSelfPermission(this@MainActivity, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
                if (fine || coarse) {
                    callback.invoke(origin, true, false)
                } else {
                    geoCallback = callback
                    geoOrigin = origin
                    locationPermission.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
                }
            }
        }

        web.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                // карта и другие вложенные окна (iframe) остаются внутри приложения
                if (!request.isForMainFrame) return false
                val u = request.url
                val scheme = u.scheme ?: ""
                val host = u.host ?: ""
                val external = scheme == "tel" || scheme == "geo" || scheme == "mailto" ||
                    ((scheme == "http" || scheme == "https") && !host.endsWith(HOST_SUFFIX))
                if (external) {
                    try {
                        startActivity(Intent(Intent.ACTION_VIEW, u))
                    } catch (e: ActivityNotFoundException) {
                        Toast.makeText(this@MainActivity, "Нет приложения для этой ссылки", Toast.LENGTH_SHORT).show()
                    }
                    return true
                }
                return false
            }

            override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                if (request.isForMainFrame) {
                    val html = "<html><head><meta name='viewport' content='width=device-width,initial-scale=1'></head>" +
                        "<body style='font-family:sans-serif;text-align:center;padding:48px 20px;background:#f1f4f2;color:#17201b'>" +
                        "<h2>Нет связи с сервером</h2><p>Проверьте интернет и повторите.</p>" +
                        "<button style='font-size:18px;padding:14px 28px;border:0;border-radius:12px;background:#0b6b45;color:#fff' " +
                        "onclick=\"location.href='" + START_URL + "'\">Повторить</button></body></html>"
                    view.loadDataWithBaseURL(null, html, "text/html", "UTF-8", null)
                }
            }
        }

        web.addJavascriptInterface(Bridge(), "AndroidApp")
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            askBatteryOnce()
        }
        web.loadUrl(START_URL)

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                when {
                    scanLayer != null -> closeScanner(true)
                    web.canGoBack() -> web.goBack()
                    else -> {
                        isEnabled = false
                        onBackPressedDispatcher.onBackPressed()
                    }
                }
            }
        })
    }

    // ---------- мост для сайта (window.AndroidApp) ----------
    inner class Bridge {
        @JavascriptInterface
        fun isApp(): Boolean = true

        // Сайт сообщает токен входа: по нему служба оповещений спрашивает сервер, даже когда экран заблокирован
        @JavascriptInterface
        fun setToken(token: String) {
            val prefs = getSharedPreferences(CourierService.PREFS, MODE_PRIVATE)
            val old = prefs.getString("token", null)
            if (old != token) prefs.edit().putString("token", token).remove("seen").apply()
            runOnUiThread {
                try {
                    CourierService.start(this@MainActivity)
                } catch (e: Exception) {
                    // система не дала запустить службу — повторим при следующем открытии
                }
            }
        }

        @JavascriptInterface
        fun clearToken() {
            getSharedPreferences(CourierService.PREFS, MODE_PRIVATE).edit().remove("token").remove("seen").apply()
            runOnUiThread { CourierService.stop(this@MainActivity) }
        }

        @JavascriptInterface
        fun startScan(hint: String, multi: Boolean) {
            runOnUiThread {
                pendingHint = hint
                val granted = ContextCompat.checkSelfPermission(
                    this@MainActivity, Manifest.permission.CAMERA
                ) == PackageManager.PERMISSION_GRANTED
                if (granted) openScanner(hint) else cameraPermission.launch(Manifest.permission.CAMERA)
            }
        }

        @JavascriptInterface
        fun stopScan() {
            runOnUiThread { closeScanner(false) }
        }

        @JavascriptInterface
        fun scanResult(json: String) {
            runOnUiThread { showResult(json) }
        }

        @JavascriptInterface
        fun scanAsk(json: String) {
            runOnUiThread { showAsk(json) }
        }
    }

    // Чтобы оповещения приходили при заблокированном экране, просим не ограничивать приложение в фоне (один раз)
    private fun askBatteryOnce() {
        try {
            val prefs = getSharedPreferences(CourierService.PREFS, MODE_PRIVATE)
            if (prefs.getBoolean("battery_asked", false)) return
            val pm = getSystemService(POWER_SERVICE) as PowerManager
            if (pm.isIgnoringBatteryOptimizations(packageName)) return
            prefs.edit().putBoolean("battery_asked", true).apply()
            val i = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
            i.data = Uri.parse("package:$packageName")
            startActivity(i)
        } catch (e: Exception) {
            // окно настроек недоступно на этом телефоне
        }
    }

    private fun js(code: String) {
        web.post { web.evaluateJavascript(code, null) }
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    // ---------- окно сканера поверх сайта ----------
    private fun openScanner(hint: String) {
        closeScanner(false)
        val layer = FrameLayout(this)
        layer.setBackgroundColor(Color.BLACK)

        val pv = PreviewView(this)
        pv.implementationMode = PreviewView.ImplementationMode.COMPATIBLE
        layer.addView(pv, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        val frame = View(this)
        val border = GradientDrawable()
        border.setColor(Color.TRANSPARENT)
        border.setStroke(dp(3), Color.WHITE)
        border.cornerRadius = dp(14).toFloat()
        frame.background = border
        val fp = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(150))
        fp.gravity = Gravity.CENTER
        fp.leftMargin = dp(32)
        fp.rightMargin = dp(32)
        layer.addView(frame, fp)

        val hintView = TextView(this)
        hintView.text = if (hint.isEmpty()) "Наведите камеру на штрихкод" else hint
        hintView.setTextColor(Color.WHITE)
        hintView.textSize = 18f
        hintView.gravity = Gravity.CENTER
        hintView.setPadding(dp(16), dp(36), dp(16), 0)
        layer.addView(hintView, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP))

        val counter = TextView(this)
        counter.setTextColor(Color.WHITE)
        counter.textSize = 24f
        counter.gravity = Gravity.CENTER
        val cp = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP)
        cp.topMargin = dp(90)
        layer.addView(counter, cp)

        val result = TextView(this)
        result.textSize = 17f
        result.gravity = Gravity.CENTER
        result.setPadding(dp(14), dp(12), dp(14), dp(12))
        result.visibility = View.GONE
        val rp = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM)
        rp.leftMargin = dp(14)
        rp.rightMargin = dp(14)
        rp.bottomMargin = dp(96)
        layer.addView(result, rp)

        val close = Button(this)
        close.text = "Закрыть"
        close.setOnClickListener { closeScanner(true) }
        val bp = FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL)
        bp.bottomMargin = dp(28)
        layer.addView(close, bp)

        root.addView(layer, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        scanLayer = layer
        previewView = pv
        counterView = counter
        resultView = result
        paused = false
        lastCode = ""
        startCamera()
    }

    private fun startCamera() {
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            try {
                val p = future.get()
                provider = p
                val preview = Preview.Builder().build()
                previewView?.let { preview.setSurfaceProvider(it.surfaceProvider) }
                val analysis = ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build()
                analysis.setAnalyzer(analysisExecutor) { proxy -> analyze(proxy) }
                p.unbindAll()
                p.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis)
                scanning = true
            } catch (e: Exception) {
                Toast.makeText(this, "Не удалось включить камеру", Toast.LENGTH_LONG).show()
                closeScanner(true)
            }
        }, ContextCompat.getMainExecutor(this))
    }

    @androidx.camera.core.ExperimentalGetImage
    private fun analyze(proxy: ImageProxy) {
        val media = proxy.image
        if (media == null || !scanning || paused) {
            proxy.close()
            return
        }
        val image = InputImage.fromMediaImage(media, proxy.imageInfo.rotationDegrees)
        scanner.process(image)
            .addOnSuccessListener { codes ->
                val raw = codes.firstOrNull()?.rawValue
                if (raw != null) {
                    val now = System.currentTimeMillis()
                    if (!(raw == lastCode && now - lastAt < 2500)) {
                        lastCode = raw
                        lastAt = now
                        runOnUiThread { if (scanning && !paused) deliver(raw) }
                    }
                }
            }
            .addOnCompleteListener { proxy.close() }
    }

    private fun deliver(raw: String) {
        vibrate()
        js("window.__onNativeCode&&window.__onNativeCode(" + JSONObject.quote(raw) + ")")
    }

    private fun showResult(json: String) {
        val v = resultView ?: return
        try {
            val o = JSONObject(json)
            val ok = o.optBoolean("ok", true)
            val msg = o.optString("msg", "")
            val counter = o.optString("counter", "")
            if (counter.isNotEmpty()) counterView?.text = counter
            if (msg.isEmpty()) {
                v.visibility = View.GONE
            } else {
                v.text = msg
                v.setTextColor(if (ok) Color.parseColor("#1B8A4B") else Color.parseColor("#C62828"))
                v.setBackgroundColor(if (ok) Color.parseColor("#DFF5E8") else Color.parseColor("#FDE3E3"))
                v.visibility = View.VISIBLE
                beep(ok)
            }
        } catch (e: Exception) {
            // игнорируем неверный ответ сайта
        }
    }

    private fun showAsk(json: String) {
        val text = try { JSONObject(json).optString("ask", "") } catch (e: Exception) { "" }
        paused = true
        beep(true)
        AlertDialog.Builder(this)
            .setTitle("Уверены?")
            .setMessage(text)
            .setCancelable(false)
            .setPositiveButton("Да") { _, _ ->
                paused = false
                js("window.__onNativeAnswer&&window.__onNativeAnswer(true)")
            }
            .setNegativeButton("Нет") { _, _ ->
                paused = false
                js("window.__onNativeAnswer&&window.__onNativeAnswer(false)")
            }
            .show()
    }

    private fun closeScanner(notifyWeb: Boolean) {
        scanning = false
        paused = false
        try {
            provider?.unbindAll()
        } catch (e: Exception) {
            // камера уже освобождена
        }
        scanLayer?.let { root.removeView(it) }
        scanLayer = null
        previewView = null
        counterView = null
        resultView = null
        if (notifyWeb) js("window.__onNativeClosed&&window.__onNativeClosed()")
    }

    @Suppress("DEPRECATION")
    private fun vibrate() {
        try {
            val vib = getSystemService(VIBRATOR_SERVICE) as? Vibrator ?: return
            if (android.os.Build.VERSION.SDK_INT >= 26) {
                vib.vibrate(VibrationEffect.createOneShot(60, VibrationEffect.DEFAULT_AMPLITUDE))
            } else {
                vib.vibrate(60)
            }
        } catch (e: Exception) {
            // вибрации нет — не страшно
        }
    }

    private fun beep(ok: Boolean) {
        try {
            if (tone == null) tone = ToneGenerator(AudioManager.STREAM_MUSIC, 80)
            tone?.startTone(if (ok) ToneGenerator.TONE_PROP_BEEP else ToneGenerator.TONE_PROP_NACK, 180)
        } catch (e: Exception) {
            // звука нет — не страшно
        }
    }

    override fun onPause() {
        super.onPause()
        if (scanLayer != null) closeScanner(true)
    }

    override fun onDestroy() {
        super.onDestroy()
        tone?.release()
        analysisExecutor.shutdown()
        web.removeJavascriptInterface("AndroidApp")
    }
}
