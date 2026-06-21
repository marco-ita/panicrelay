package com.marco.panicrelay

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.telecom.TelecomManager
import android.telephony.SmsManager
import android.view.WindowManager
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.Properties
import javax.mail.Authenticator
import javax.mail.Message
import javax.mail.PasswordAuthentication
import javax.mail.Session
import javax.mail.Transport
import javax.mail.internet.InternetAddress
import javax.mail.internet.MimeMessage

/**
 * Fires every configured action when the spray is activated.
 * Runs its network/SMS work off the main thread.
 */
class AlertManager(private val ctx: Context) {

    private val prefs = Prefs(ctx)

    fun fire(rawValueHex: String) {
        val loc = lastKnownLocation()
        val mapsLink = loc?.let { "https://maps.google.com/?q=${it.latitude},${it.longitude}" }
            ?: "posizione non disponibile"
        val fullText = "${prefs.message} $mapsLink"

        log("TRIGGER value=$rawValueHex | $mapsLink")

        if (prefs.enablePhoneNotif) showNotification(mapsLink)
        if (prefs.enableSms) sendSms(fullText)
        if (prefs.enableCall) placeCall()
        if (prefs.enableWebhook) postWebhook(rawValueHex, loc, fullText)
        if (prefs.enableEmail) sendEmail(fullText)
        if (prefs.enableTelegram) sendTelegram(fullText)
    }

    // ---- SMS -------------------------------------------------------------
    @SuppressLint("MissingPermission")
    private fun sendSms(text: String) {
        if (!hasPerm(Manifest.permission.SEND_SMS)) { log("SMS skipped: no permission"); return }
        val sms = smsManager() ?: return
        for (number in prefs.contacts()) {
            try {
                val parts = sms.divideMessage(text)
                sms.sendMultipartTextMessage(number, null, parts, null, null)
                log("SMS sent to $number")
            } catch (e: Exception) {
                log("SMS error to $number: ${e.message}")
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun smsManager(): SmsManager? = try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
            ctx.getSystemService(SmsManager::class.java)
        else SmsManager.getDefault()
    } catch (e: Exception) { log("SmsManager error: ${e.message}"); null }

    // ---- Call ------------------------------------------------------------
    // A direct startActivity is blocked when the app is in the background, so we
    // also raise a full-screen-intent notification (CATEGORY_CALL). On a locked /
    // off screen the system launches it automatically; that is the only sanctioned
    // way to start the dialer from the background.
    @SuppressLint("MissingPermission")
    private fun placeCall() {
        val number = prefs.contacts().firstOrNull() ?: return
        if (!hasPerm(Manifest.permission.CALL_PHONE)) { log("Call skipped: no permission"); return }

        // Primary: ask the Telecom system service to place the call. This works
        // from the background because the system places it, not us starting an
        // activity (which Android 14/15 blocks from the background).
        try {
            val tm = ctx.getSystemService(Context.TELECOM_SERVICE) as TelecomManager
            tm.placeCall(Uri.fromParts("tel", number, null), null)
            log("Call placed via Telecom to $number")
            return
        } catch (e: Exception) {
            log("Telecom placeCall failed (${e.message}); falling back to full-screen intent")
        }

        // Fallback: full-screen-intent trampoline (needs the full-screen permission).
        try {
            ensureAlertChannel()
            val callIntent = Intent(ctx, CallActivity::class.java)
                .putExtra("number", number)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            val pi = PendingIntent.getActivity(
                ctx, 1, callIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            val n = NotificationCompat.Builder(ctx, ALERT_CH)
                .setSmallIcon(android.R.drawable.ic_menu_call)
                .setContentTitle("Chiamata di emergenza")
                .setContentText(number)
                .setPriority(NotificationCompat.PRIORITY_MAX)
                .setCategory(NotificationCompat.CATEGORY_CALL)
                .setFullScreenIntent(pi, true)
                .setAutoCancel(true)
                .build()
            ctx.getSystemService(NotificationManager::class.java).notify(CALL_ID, n)
            log("Call launched via FSI to $number")
        } catch (e: Exception) {
            log("Call FSI error: ${e.message}")
        }
    }

    // ---- Phone notification + sound -------------------------------------
    private fun ensureAlertChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = ctx.getSystemService(NotificationManager::class.java)
            val ch = NotificationChannel(ALERT_CH, "Allarmi", NotificationManager.IMPORTANCE_HIGH)
            ch.setSound(RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM), null)
            ch.enableVibration(true)
            ch.vibrationPattern = longArrayOf(0, 600, 200, 600, 200, 600)
            nm.createNotificationChannel(ch)
        }
    }

    private fun showNotification(mapsLink: String) {
        ensureAlertChannel()
        val nm = ctx.getSystemService(NotificationManager::class.java)
        val n = NotificationCompat.Builder(ctx, ALERT_CH)
            .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
            .setContentTitle("ALLARME ATTIVATO")
            .setContentText(mapsLink)
            .setStyle(NotificationCompat.BigTextStyle().bigText(mapsLink))
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setSound(RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM))
            .setVibrate(longArrayOf(0, 600, 200, 600, 200, 600))
            .setAutoCancel(true)
            .build()
        nm.notify(ALERT_ID, n)
    }

    // ---- Telegram (push to a group with your receivers) ----------------
    private fun sendTelegram(text: String) {
        val token = prefs.tgToken
        val chatId = prefs.tgChatId
        if (token.isBlank() || chatId.isBlank()) { log("Telegram skipped: config incompleta"); return }
        Thread {
            try {
                val url = URL("https://api.telegram.org/bot$token/sendMessage")
                val body = "chat_id=" + URLEncoder.encode(chatId, "UTF-8") +
                    "&text=" + URLEncoder.encode(text, "UTF-8")
                val conn = (url.openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    connectTimeout = 8000
                    readTimeout = 8000
                    doOutput = true
                    setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
                }
                conn.outputStream.use { it.write(body.toByteArray()) }
                val code = conn.responseCode
                conn.disconnect()
                log("Telegram POST -> HTTP $code")
            } catch (e: Exception) {
                log("Telegram error: ${e.message}")
            }
        }.start()
    }

    // ---- Email (SMTP, e.g. Gmail with an App Password) ------------------
    private fun sendEmail(text: String) {
        val to = prefs.emailTo
        val user = prefs.smtpUser
        val pass = prefs.smtpPass
        if (to.isBlank() || user.isBlank() || pass.isBlank()) {
            log("Email skipped: config incompleta"); return
        }
        Thread {
            try {
                val props = Properties().apply {
                    put("mail.smtp.auth", "true")
                    put("mail.smtp.starttls.enable", "true")
                    put("mail.smtp.host", "smtp.gmail.com")
                    put("mail.smtp.port", "587")
                    put("mail.smtp.connectiontimeout", "10000")
                    put("mail.smtp.timeout", "10000")
                }
                val session = Session.getInstance(props, object : Authenticator() {
                    override fun getPasswordAuthentication() =
                        PasswordAuthentication(user, pass)
                })
                val msg = MimeMessage(session).apply {
                    setFrom(InternetAddress(user))
                    to.split(",", ";").map { it.trim() }.filter { it.isNotEmpty() }
                        .forEach { addRecipient(Message.RecipientType.TO, InternetAddress(it)) }
                    subject = "ALLARME PanicRelay"
                    setText(text)
                }
                Transport.send(msg)
                log("Email sent to $to")
            } catch (e: Exception) {
                log("Email error: ${e.message}")
            }
        }.start()
    }

    // ---- Webhook (point this at n8n -> Telegram/email) ------------------
    private fun postWebhook(rawValueHex: String, loc: Location?, text: String) {
        val url = prefs.webhookUrl
        if (url.isBlank()) return
        Thread {
            try {
                val body = JSONObject().apply {
                    put("event", "panic_trigger")
                    put("value", rawValueHex)
                    put("message", text)
                    put("lat", loc?.latitude ?: JSONObject.NULL)
                    put("lng", loc?.longitude ?: JSONObject.NULL)
                    put("time", iso(Date()))
                }.toString()
                val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    connectTimeout = 8000
                    readTimeout = 8000
                    doOutput = true
                    setRequestProperty("Content-Type", "application/json")
                }
                conn.outputStream.use { it.write(body.toByteArray()) }
                val code = conn.responseCode
                conn.disconnect()
                log("Webhook POST -> HTTP $code")
            } catch (e: Exception) {
                log("Webhook error: ${e.message}")
            }
        }.start()
    }

    // ---- Location --------------------------------------------------------
    @SuppressLint("MissingPermission")
    private fun lastKnownLocation(): Location? {
        if (!hasPerm(Manifest.permission.ACCESS_FINE_LOCATION) &&
            !hasPerm(Manifest.permission.ACCESS_COARSE_LOCATION)) return null
        return try {
            val lm = ctx.getSystemService(Context.LOCATION_SERVICE) as LocationManager
            val providers = lm.getProviders(true)
            var best: Location? = null
            for (p in providers) {
                val l = lm.getLastKnownLocation(p) ?: continue
                if (best == null || l.time > best!!.time) best = l
            }
            best
        } catch (e: Exception) { log("Location error: ${e.message}"); null }
    }

    // ---- Local event log -------------------------------------------------
    fun log(line: String) {
        try {
            val f = File(ctx.filesDir, "events.log")
            f.appendText("${iso(Date())}  $line\n")
        } catch (_: Exception) { }
    }

    private fun hasPerm(p: String) =
        ContextCompat.checkSelfPermission(ctx, p) == PackageManager.PERMISSION_GRANTED

    private fun iso(d: Date) =
        SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(d)

    companion object {
        const val ALERT_CH = "alerts"
        const val ALERT_ID = 42
        const val CALL_ID = 43
    }
}

/**
 * Tiny transparent trampoline. Launched (incl. via full-screen intent) it runs in
 * the foreground, so it is allowed to start the system dialer; then it closes.
 */
class CallActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Show over the lock screen and turn the screen on, so the call is
        // placed and visible even when the phone was idle/locked.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                    WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON or
                    WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
            )
        }
        val number = intent.getStringExtra("number")
        if (number != null &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.CALL_PHONE)
                == PackageManager.PERMISSION_GRANTED) {
            try {
                startActivity(
                    Intent(Intent.ACTION_CALL, Uri.parse("tel:$number"))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            } catch (_: Exception) {}
        }
        finish()
    }
}
