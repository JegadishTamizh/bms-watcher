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

data class KeywordMatch(val matched: Boolean, val where: String)

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
        val keywords = prefs.getString(KEY_KEYWORDS, "").orEmpty().trim()
        val targetDate = extractDate(url)

        if (url.isBlank() || targetDate == null) {
            saveLog(prefs, "", "", "ERROR: URL must end with a YYYYMMDD date", "")
            return Result.success()
        }

        val (finalUrl, title, pageText) = try {
            loadInWebView(url)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            Triple("", "", "")
        }

        val lowerText = pageText.lowercase()
        val noShows = EMPTY_PHRASES.any { lowerText.contains(it) }
        val dateKept = finalUrl.contains(targetDate)
        val titleBad = title.isBlank() ||
            title.contains("Just a moment", ignoreCase = true) ||
            title.contains("Attention Required", ignoreCase = true) ||
            title.contains("Access Denied", ignoreCase = true)
        val pageOpen = dateKept && !titleBad && !noShows

        // Keyword check only runs when the page itself looks open.
        var matchInfo = ""
        var open = pageOpen
        if (pageOpen && keywords.isNotBlank()) {
            val m = matchKeywords(pageText, keywords)
            open = m.matched
            matchInfo = if (m.matched) m.where else "none"
        }

        val resultText = when {
            finalUrl.isBlank() -> "NO RESULT: page failed to load or timed out"
            titleBad -> "BLOCKED/BLANK: Cloudflare challenge or empty title, cannot tell"
            !dateKept -> "CLOSED: redirected away from $targetDate"
            noShows -> "CLOSED: page says no shows found yet"
            keywords.isNotBlank() && !open -> "OPEN page, but no keyword match (still watching)"
            keywords.isNotBlank() -> "OPEN: keyword match found!"
            else -> "OPEN: bookings detected!"
        }

        // Show a short snippet of the page text in the log so wording can be checked.
        val snippet = pageText.replace(Regex("\\s+"), " ").trim().take(120)
        val titleForLog = if (snippet.isBlank()) title else "$title || TEXT: $snippet"
        saveLog(prefs, finalUrl, titleForLog, resultText, matchInfo)

        if (open) {
            val shown = notifyOpen(ctx, url, matchInfo)
            if (!shown) {
                saveLog(prefs, finalUrl, titleForLog, "$resultText (but notification permission is OFF!)", matchInfo)
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
    private suspend fun loadInWebView(url: String): Triple<String, String, String> =
        withContext(Dispatchers.Main) {
            suspendCancellableCoroutine<Triple<String, String, String>> { cont ->
                val handler = Handler(Looper.getMainLooper())
                val webView = WebView(applicationContext)
                var done = false

                fun cleanup() {
                    webView.stopLoading()
                    webView.destroy()
                }

                fun finish(readText: Boolean) {
                    if (done) return
                    done = true
                    handler.removeCallbacksAndMessages(null)
                    val u = webView.url.orEmpty()
                    val t = webView.title.orEmpty()

                    if (!readText) {
                        cleanup()
                        if (cont.isActive) cont.resume(Triple(u, t, ""))
                        return
                    }

                    var replied = false
                    fun reply(text: String) {
                        if (replied) return
                        replied = true
                        cleanup()
                        if (cont.isActive) cont.resume(Triple(u, t, text))
                    }

                    // Fallback in case the JavaScript callback never arrives.
                    handler.postDelayed({ reply("") }, 3_000L)
                    webView.evaluateJavascript(
                        "(function(){return document.body ? document.body.innerText.substring(0, 4000) : '';})()"
                    ) { raw -> reply(decodeJs(raw)) }
                }

                val settleRunnable = Runnable { finish(true) }
                val timeoutRunnable = Runnable { finish(true) }

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
                cont.invokeOnCancellation { handler.post { finish(false) } }
                webView.loadUrl(url)
            }
        }

    private fun decodeJs(raw: String?): String {
        if (raw.isNullOrBlank() || raw == "null") return ""
        return try {
            org.json.JSONTokener(raw).nextValue() as? String ?: ""
        } catch (e: Exception) {
            raw
        }
    }

    @SuppressLint("MissingPermission")
    private fun notifyOpen(ctx: Context, url: String, detail: String): Boolean {
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

        val shortText = if (detail.isBlank()) "Tap to open BookMyShow now." else "Match: $detail"
        val notification = NotificationCompat.Builder(ctx, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("Bookings are OPEN!")
            .setContentText(shortText)
            .setStyle(NotificationCompat.BigTextStyle().bigText("$shortText Tap to open BookMyShow."))
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
        const val KEY_KEYWORDS = "keywords"
        const val KEY_ACTIVE = "active"
        const val KEY_MANUAL = "manual"
        const val KEY_LOG_TIME = "log_time"
        const val KEY_LOG_URL = "log_url"
        const val KEY_LOG_TITLE = "log_title"
        const val KEY_LOG_RESULT = "log_result"
        const val KEY_LOG_MATCH = "log_match"

        const val TAG = "bms_monitor"
        const val UNIQUE_PERIODIC = "bms_periodic"
        const val CHANNEL_ID = "bms_alerts_v1"
        const val NOTIF_ID = 4242

        private const val SETTLE_MS = 6_000L
        private const val TIMEOUT_MS = 60_000L
        private val VIBRATION = longArrayOf(0, 600, 300, 600, 300, 900)

        // If the page text contains any of these (lowercase), treat bookings as NOT open.
        private val EMPTY_PHRASES = listOf(
            "could not find",
            "couldn't find",
            "no shows",
            "no showtimes",
            "no results found",
            "nothing found",
            "find anything",
            "no shows available"
        )

        fun extractDate(url: String): String? {
            val path = url.substringBefore('?').substringBefore('#').trimEnd('/')
            val d = path.takeLast(8)
            return if (d.length == 8 && d.all { it.isDigit() }) d else null
        }

        // Each theatre section in the page text starts with the theatre name and
        // contains a line like "Cancellation available" or "Non-cancellable".
        private fun isCancelMarker(line: String): Boolean {
            val l = line.lowercase()
            return l.contains("cancellation available") ||
                l.contains("non-cancellable") ||
                l.contains("non cancellable")
        }

        // Offer lines can sit between a theatre name and its cancellation line.
        private fun isPromo(line: String): Boolean {
            val l = line.lowercase()
            return l.contains("code:") || l.contains("% off") || l.contains("offer")
        }

        /**
         * keywords: comma separated alternatives. Inside one alternative, every word
         * must appear in the same theatre section. Capital letters are ignored.
         */
        fun matchKeywords(pageText: String, keywords: String): KeywordMatch {
            val entries = keywords.split(',')
                .map { it.trim().lowercase() }
                .filter { it.isNotBlank() }
                .map { e -> e.split(Regex("\\s+")).filter { it.isNotBlank() } }
            if (entries.isEmpty()) return KeywordMatch(true, "")

            val lines = pageText.split('\n').map { it.trim() }.filter { it.isNotEmpty() }
            val markers = lines.indices.filter { isCancelMarker(lines[it]) }

            val blocks = mutableListOf<Pair<String, String>>()
            if (markers.isEmpty()) {
                // Could not split into theatres: fall back to the whole page.
                blocks.add(Pair("whole page (no theatre sections found)", lines.joinToString(" ")))
            } else {
                val starts = markers.map { m ->
                    var i = m - 1
                    while (i > 0 && isPromo(lines[i])) i--
                    if (i < 0) 0 else i
                }
                for (k in starts.indices) {
                    val from = starts[k]
                    val to = if (k + 1 < starts.size) starts[k + 1] else lines.size
                    if (to <= from) continue
                    blocks.add(Pair(lines[from], lines.subList(from, to).joinToString(" ")))
                }
            }

            for ((name, text) in blocks) {
                val low = text.lowercase()
                for (words in entries) {
                    if (words.all { low.contains(it) }) {
                        return KeywordMatch(true, "${words.joinToString(" ")} in $name")
                    }
                }
            }
            return KeywordMatch(false, "")
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

        fun saveLog(prefs: SharedPreferences, url: String, title: String, result: String, match: String) {
            val time = SimpleDateFormat("dd MMM yyyy, HH:mm:ss", Locale.getDefault()).format(Date())
            prefs.edit()
                .putString(KEY_LOG_TIME, time)
                .putString(KEY_LOG_URL, url)
                .putString(KEY_LOG_TITLE, title)
                .putString(KEY_LOG_RESULT, result)
                .putString(KEY_LOG_MATCH, match)
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
