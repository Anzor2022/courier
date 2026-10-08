package ru.mahnet.courier

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Служба оповещений курьера. Работает в фоне (с постоянным значком в шторке), раз в 15 секунд спрашивает сервер
 * и при новой маршрутке, новых посылках или заявке на забор показывает уведомление со звуком и вибрацией,
 * в том числе на заблокированном экране.
 */
class CourierService : Service() {

    companion object {
        const val PREFS = "courier_prefs"
        const val CH_SERVICE = "courier_service"
        const val CH_ALERT = "courier_alerts_v1"
        private const val ID_FG = 1001
        private const val POLL_MS = 15000L

        fun start(ctx: Context) {
            ContextCompat.startForegroundService(ctx, Intent(ctx, CourierService::class.java))
        }

        fun stop(ctx: Context) {
            ctx.stopService(Intent(ctx, CourierService::class.java))
        }
    }

    @Volatile private var running = false
    private var worker: Thread? = null
    private var wake: PowerManager.WakeLock? = null
    private var alertId = 2000

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannels()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val n: Notification = NotificationCompat.Builder(this, CH_SERVICE)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("Курьер-mahnet")
            .setContentText("Слежу за новыми маршрутками и заборами")
            .setContentIntent(open)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
        if (Build.VERSION.SDK_INT >= 34) {
            ServiceCompat.startForeground(this, ID_FG, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(ID_FG, n)
        }
        if (!running) {
            running = true
            try {
                val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
                wake = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "market:courier").also { it.acquire() }
            } catch (e: Exception) {
                // без блокировки сна служба всё равно работает
            }
            worker = Thread {
                while (running) {
                    try {
                        poll()
                    } catch (e: Exception) {
                        // нет связи — попробуем в следующий раз
                    }
                    try {
                        Thread.sleep(POLL_MS)
                    } catch (e: InterruptedException) {
                        break
                    }
                }
            }.also {
                it.isDaemon = true
                it.start()
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        running = false
        worker?.interrupt()
        try {
            wake?.let { if (it.isHeld) it.release() }
        } catch (e: Exception) {
            // уже освобождена
        }
        super.onDestroy()
    }

    private fun createChannels() {
        if (Build.VERSION.SDK_INT < 26) return
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(
            NotificationChannel(CH_SERVICE, "Работа приложения", NotificationManager.IMPORTANCE_LOW)
        )
        val ch = NotificationChannel(CH_ALERT, "Новые маршрутки и заборы", NotificationManager.IMPORTANCE_HIGH)
        ch.description = "Звуковое оповещение о новых заданиях курьера"
        ch.enableVibration(true)
        ch.vibrationPattern = longArrayOf(0, 400, 200, 400)
        ch.lockscreenVisibility = Notification.VISIBILITY_PUBLIC
        nm.createNotificationChannel(ch)
    }

    private fun poll() {
        val prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
        val token = prefs.getString("token", null)
        if (token.isNullOrEmpty()) {
            stopSelf()
            return
        }
        val conn = URL(MainActivity.BASE_URL + "api.php?a=courier_data").openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        conn.doOutput = true
        conn.connectTimeout = 10000
        conn.readTimeout = 15000
        conn.setRequestProperty("Content-Type", "application/json")
        conn.outputStream.use { it.write(JSONObject().put("t", token).toString().toByteArray()) }
        val code = conn.responseCode
        if (code == 401) {
            prefs.edit().remove("token").remove("seen").apply()
            conn.disconnect()
            stopSelf()
            return
        }
        if (code != 200) {
            conn.disconnect()
            return
        }
        val body = conn.inputStream.bufferedReader().use { it.readText() }
        conn.disconnect()
        val j = JSONObject(body)
        if (!j.optBoolean("ok", false)) return

        val current = HashMap<String, Int>()
        val routes = j.optJSONArray("routes")
        if (routes != null) {
            for (i in 0 until routes.length()) {
                val r = routes.getJSONObject(i)
                if (r.optString("st") != "open") continue
                val units = r.optJSONArray("units")
                current["r" + r.optInt("id")] = units?.length() ?: 0
            }
        }
        val pickNames = HashMap<String, String>()
        val pickups = j.optJSONArray("pickups")
        if (pickups != null) {
            for (i in 0 until pickups.length()) {
                val p = pickups.getJSONObject(i)
                if (p.optLong("picked", 0L) > 0L || p.optLong("whAcc", 0L) > 0L) continue
                val key = "p" + p.optInt("oid") + "_" + p.optInt("sid")
                current[key] = 1
                pickNames[key] = p.optString("shop")
            }
        }

        val seenRaw = prefs.getString("seen", null)
        val seen = HashMap<String, Int>()
        if (seenRaw != null) {
            for (pair in seenRaw.split(";")) {
                val kv = pair.split("=")
                if (kv.size == 2) seen[kv[0]] = kv[1].toIntOrNull() ?: 0
            }
        }
        if (seenRaw != null) {
            for ((k, v) in current) {
                val old = seen[k]
                if (k.startsWith("r")) {
                    if (old == null) alert("Новая маршрутка", "Посылок: $v. Откройте приложение.")
                    else if (v > old) alert("В маршрутку добавлены посылки", "Было $old, стало $v.")
                } else if (old == null) {
                    alert("Новая заявка на забор", pickNames[k] ?: "Заберите товар у продавца")
                }
            }
        }
        prefs.edit().putString("seen", current.entries.joinToString(";") { it.key + "=" + it.value }).apply()
    }

    private fun alert(title: String, text: String) {
        val open = PendingIntent.getActivity(
            this, 1, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val n = NotificationCompat.Builder(this, CH_ALERT)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(open)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setDefaults(NotificationCompat.DEFAULT_ALL)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .build()
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        alertId += 1
        nm.notify(alertId, n)
    }
}
