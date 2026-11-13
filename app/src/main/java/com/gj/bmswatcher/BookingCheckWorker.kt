package com.gj.bmswatcher

import android.Manifest
import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume

class BookingCheckWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val ctx = applicationContext
        val prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val manual = inputData.getBoolean(KEY_MANUAL, false)

        // A scheduled run that fires after the user pressed Stop does nothing.
        if (!manual && !prefs.getBoolean(KEY_ACTIVE, false)) return Result.success()

        val url = prefs.getString(KEY_URL, "").orEmpty()
        val interval = prefs.getInt(KEY_INTERVAL, 15)
        val targetDate = extractDate(url)

        if (url.isBlank() || targetDate == null) {
            saveLog(prefs, "", "", "ERROR: URL must end with a YYYYMMDD date")
            return Result.success()
        }

        val (finalUrl, title) = try {
            loadInWebView(url)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            Pair("", "")
        }

        val dateKept = finalUrl.contains(targetDate)
        val titleBad = title.isBlank() ||
            title.contains("Just a moment", ignoreCase = true) ||
            title.contains("Attention Required", ignoreCase = true) ||
            title.contains("Access Denied", ignoreCase = true)
        val open = dateKept && !titleBad

        val resultText = when {
            finalUrl.isBlank() -> "NO RESULT: page failed to load or timed out"
            titleBad -> "BLOCKED/BLANK: Cloudflare challenge or empty title, cannot tell"
            !dateKept -> "CLOSED: redirected away from $targetDate"
            else -> "OPEN: bookings detected!"
        }
        saveLog(prefs, finalUrl, title, resultText)

        if (open) {
            val shown = notifyOpen(ctx, url)
            if (!shown) {
                saveLog(prefs, finalUrl, title, "$resultText (but notification permission is OFF!)")
            }
            if (!manual) {
                prefs.edit().putBoolean(KEY_ACTIVE, false).apply()
                WorkManager.getInstance(ctx).cancelAllWorkByTag(TAG)
            }
        } else if (!manual && interval < 15 && prefs.getBoolean(KEY_ACTIVE, false)) {
            scheduleNextOneTime(ctx, interval)
        }
        return Result.success()
    }

    @SuppressLint("SetJavaScriptEnabled")
    private suspend fun loadInWebView(url: String): Pair<String, String> =
        withContext(Dispatchers.Main) {
            suspendCancellableCoroutine<Pair<String, String>> { cont ->
                val handler = Handler(Looper.getMainLooper())
                val webView = WebView(applicationContext)
                var done = false

                fun finish() {
                    if (done) return
                    done = true
                    handler.removeCallbacksAndMessages(null)
                    val result = Pair(webView.url.orEmpty(), webView.title.orEmpty())
                    webView.stopLoading()
                    webView.destroy()
                    if (cont.isActive) cont.resume(result)
                }

                val settleRunnable = Runnable { finish() }
                val timeoutRunnable = Runnable { finish() }

                webView.settings.apply {
                    javaScriptEnabled = true
                    domStorageEnabled = true
                    // Default WebView UA contains "; wv" which makes sites treat it as an embedded view.
                    userAgentString = userAgentString.replace("; wv", "")
                }
                webView.layout(0, 0, 1080, 1920)
                webView.onResume()
                webView.resumeTimers()

                webView.webViewClient = object : WebViewClient() {
                    override fun onPageFinished(view: WebView?, url: String?) {
                        // Wait SETTLE_MS after the latest page-finished so the JS redirect can happen.
                        handler.removeCallbacks(settleRunnable)
                        handler.postDelayed(settleRunnable, SETTLE_MS)
                    }
                }

                handler.postDelayed(timeoutRunnable, TIMEOUT_MS)
                cont.invokeOnCancellation { handler.post { finish() } }
                webView.loadUrl(url)
            }
        }

    @SuppressLint("MissingPermission")
    private fun notifyOpen(ctx: Context, url: String): Boolean {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) return false

        createChannel(ctx)

        val viewIntent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val pending = PendingIntent.getActivity(
            ctx, 0, viewIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(ctx, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("Bookings are OPEN!")
            .setContentText("Tap to open BookMyShow now.")
            .setStyle(NotificationCompat.BigTextStyle().bigText("Bookings opened for your date. Tap to open BookMyShow now."))
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setSound(alertSound())
            .setVibrate(VIBRATION)
            .setContentIntent(pending)
            .setAutoCancel(true)
            .build()

        NotificationManagerCompat.from(ctx).notify(NOTIF_ID, notification)
        return true
    }

    companion object {
        const val PREFS = "bms_prefs"
        const val KEY_URL = "url"
        const val KEY_INTERVAL = "interval"
        const val KEY_ACTIVE = "active"
        const val KEY_MANUAL = "manual"
        const val KEY_LOG_TIME = "log_time"
        const val KEY_LOG_URL = "log_url"
        const val KEY_LOG_TITLE = "log_title"
        const val KEY_LOG_RESULT = "log_result"

        const val TAG = "bms_monitor"
        const val UNIQUE_PERIODIC = "bms_periodic"
        const val CHANNEL_ID = "bms_alerts_v1"
        const val NOTIF_ID = 4242

        private const val SETTLE_MS = 6_000L
        private const val TIMEOUT_MS = 60_000L
        private val VIBRATION = longArrayOf(0, 600, 300, 600, 300, 900)

        fun extractDate(url: String): String? {
            val path = url.substringBefore('?').substringBefore('#').trimEnd('/')
            val d = path.takeLast(8)
            return if (d.length == 8 && d.all { it.isDigit() }) d else null
        }

        private fun alertSound(): Uri =
            RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
                ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)

        private fun createChannel(ctx: Context) {
            if (Build.VERSION.SDK_INT < 26) return
            val channel = NotificationChannel(
                CHANNEL_ID, "Booking alerts", NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Alerts when BookMyShow bookings open"
                enableVibration(true)
                vibrationPattern = VIBRATION
                setSound(
                    alertSound(),
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ALARM)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build()
                )
            }
            ctx.getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }

        private fun constraints() = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        fun saveLog(prefs: SharedPreferences, url: String, title: String, result: String) {
            val time = SimpleDateFormat("dd MMM yyyy, HH:mm:ss", Locale.getDefault()).format(Date())
            prefs.edit()
                .putString(KEY_LOG_TIME, time)
                .putString(KEY_LOG_URL, url)
                .putString(KEY_LOG_TITLE, title)
                .putString(KEY_LOG_RESULT, result)
                .apply()
        }

        fun startMonitoring(ctx: Context, intervalMin: Int) {
            val wm = WorkManager.getInstance(ctx)
            wm.cancelAllWorkByTag(TAG)
            if (intervalMin >= 15) {
                val req = PeriodicWorkRequestBuilder<BookingCheckWorker>(
                    intervalMin.toLong(), TimeUnit.MINUTES
                ).setConstraints(constraints()).addTag(TAG).build()
                wm.enqueueUniquePeriodicWork(UNIQUE_PERIODIC, ExistingPeriodicWorkPolicy.UPDATE, req)
            } else {
                val req = OneTimeWorkRequestBuilder<BookingCheckWorker>()
                    .setConstraints(constraints())
                    .addTag(TAG)
                    .build()
                wm.enqueue(req)
            }
        }

        fun scheduleNextOneTime(ctx: Context, intervalMin: Int) {
            val req = OneTimeWorkRequestBuilder<BookingCheckWorker>()
                .setInitialDelay(intervalMin.toLong(), TimeUnit.MINUTES)
                .setConstraints(constraints())
                .addTag(TAG)
                .build()
            WorkManager.getInstance(ctx).enqueue(req)
        }

        fun stopMonitoring(ctx: Context) {
            WorkManager.getInstance(ctx).cancelAllWorkByTag(TAG)
            WorkManager.getInstance(ctx).cancelUniqueWork(UNIQUE_PERIODIC)
        }

        fun runTestNow(ctx: Context) {
            val req = OneTimeWorkRequestBuilder<BookingCheckWorker>()
                .setConstraints(constraints())
                .setInputData(workDataOf(KEY_MANUAL to true))
                .addTag("bms_test")
                .build()
            WorkManager.getInstance(ctx).enqueue(req)
        }
    }
}
