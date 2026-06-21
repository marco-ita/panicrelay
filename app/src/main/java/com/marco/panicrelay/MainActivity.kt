package com.marco.panicrelay

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.text.InputType
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import java.io.File

/**
 * Single-screen configuration: emergency numbers, message, webhook, actions,
 * plus Start / Stop / Test and a view of the local event log.
 * UI is built in code to keep the project dependency-light.
 */
class MainActivity : Activity() {

    private lateinit var prefs: Prefs
    private lateinit var macField: EditText
    private lateinit var c1Field: EditText
    private lateinit var c2Field: EditText
    private lateinit var msgField: EditText
    private lateinit var hookField: EditText
    private lateinit var emailToField: EditText
    private lateinit var smtpUserField: EditText
    private lateinit var smtpPassField: EditText
    private lateinit var tgTokenField: EditText
    private lateinit var tgChatIdField: EditText
    private lateinit var cbSms: CheckBox
    private lateinit var cbCall: CheckBox
    private lateinit var cbNotif: CheckBox
    private lateinit var cbHook: CheckBox
    private lateinit var cbEmail: CheckBox
    private lateinit var cbTelegram: CheckBox
    private lateinit var logView: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        setContentView(buildUi())
        loadValues()
        requestAllPermissions()
    }

    override fun onResume() {
        super.onResume()
        refreshLog()
    }

    private fun buildUi(): View {
        val scroll = ScrollView(this)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(40, 50, 40, 50)
        }
        scroll.addView(root)

        root.addView(title("PanicRelay"))
        root.addView(label("MAC dispositivo"))
        macField = field(InputType.TYPE_CLASS_TEXT).also { root.addView(it) }

        root.addView(label("Contatto 1 (con prefisso, es. +39…)"))
        c1Field = field(InputType.TYPE_CLASS_PHONE).also { root.addView(it) }

        root.addView(label("Contatto 2 (opzionale)"))
        c2Field = field(InputType.TYPE_CLASS_PHONE).also { root.addView(it) }

        root.addView(label("Messaggio SMS"))
        msgField = field(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE)
            .also { it.minLines = 2; root.addView(it) }

        root.addView(label("Webhook URL (es. il tuo n8n) — opzionale"))
        hookField = field(InputType.TYPE_TEXT_VARIATION_URI).also { root.addView(it) }

        root.addView(label("Email destinatario (separa con , per più indirizzi)"))
        emailToField = field(InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS).also { root.addView(it) }

        root.addView(label("Email mittente (Gmail dedicato)"))
        smtpUserField = field(InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS).also { root.addView(it) }

        root.addView(label("Password per app (App Password Gmail)"))
        smtpPassField = field(InputType.TYPE_TEXT_VARIATION_PASSWORD).also { root.addView(it) }

        root.addView(label("Telegram - Token del bot (da @BotFather)"))
        tgTokenField = field(InputType.TYPE_CLASS_TEXT).also { root.addView(it) }

        root.addView(label("Telegram - ID chat/gruppo (es. -100…)"))
        tgChatIdField = field(InputType.TYPE_CLASS_TEXT).also { root.addView(it) }

        cbSms = check("Invia SMS con posizione").also { root.addView(it) }
        cbCall = check("Chiama il 1° contatto").also { root.addView(it) }
        cbNotif = check("Notifica + suono sul telefono").also { root.addView(it) }
        cbHook = check("Invia al webhook (n8n)").also { root.addView(it) }
        cbEmail = check("Invia email").also { root.addView(it) }
        cbTelegram = check("Invia notifica Telegram").also { root.addView(it) }

        root.addView(button("SALVA E AVVIA") { saveAndStart() })
        root.addView(button("FERMA") { stopGuard() })
        root.addView(button("TEST ALLARME (simula)") { testAlert() })
        root.addView(button("ESCLUDI DA OTTIM. BATTERIA") { askBatteryExemption() })
        root.addView(button("ABILITA CHIAMATA A SCHERMO BLOCCATO") { askFullScreenIntent() })
        root.addView(button("AGGIORNA LOG") { refreshLog() })

        root.addView(label("Log eventi"))
        logView = TextView(this).apply {
            setTextColor(Color.DKGRAY)
            textSize = 12f
            setPadding(0, 10, 0, 0)
        }
        root.addView(logView)

        return scroll
    }

    // ---- value binding ---------------------------------------------------
    private fun loadValues() {
        macField.setText(prefs.deviceMac)
        c1Field.setText(prefs.contact1)
        c2Field.setText(prefs.contact2)
        msgField.setText(prefs.message)
        hookField.setText(prefs.webhookUrl)
        emailToField.setText(prefs.emailTo)
        smtpUserField.setText(prefs.smtpUser)
        smtpPassField.setText(prefs.smtpPass)
        tgTokenField.setText(prefs.tgToken)
        tgChatIdField.setText(prefs.tgChatId)
        cbSms.isChecked = prefs.enableSms
        cbCall.isChecked = prefs.enableCall
        cbNotif.isChecked = prefs.enablePhoneNotif
        cbHook.isChecked = prefs.enableWebhook
        cbEmail.isChecked = prefs.enableEmail
        cbTelegram.isChecked = prefs.enableTelegram
    }

    private fun saveValues() {
        prefs.deviceMac = macField.text.toString()
        prefs.contact1 = c1Field.text.toString()
        prefs.contact2 = c2Field.text.toString()
        prefs.message = msgField.text.toString()
        prefs.webhookUrl = hookField.text.toString()
        prefs.emailTo = emailToField.text.toString()
        prefs.smtpUser = smtpUserField.text.toString()
        prefs.smtpPass = smtpPassField.text.toString()
        prefs.tgToken = tgTokenField.text.toString()
        prefs.tgChatId = tgChatIdField.text.toString()
        prefs.enableSms = cbSms.isChecked
        prefs.enableCall = cbCall.isChecked
        prefs.enablePhoneNotif = cbNotif.isChecked
        prefs.enableWebhook = cbHook.isChecked
        prefs.enableEmail = cbEmail.isChecked
        prefs.enableTelegram = cbTelegram.isChecked
    }

    private fun saveAndStart() {
        saveValues()
        if (prefs.contacts().isEmpty() && cbSms.isChecked) {
            toast("Inserisci almeno un contatto")
            return
        }
        requestAllPermissions()
        PanicService.start(this)
        toast("Guard avviato")
        refreshLog()
    }

    private fun stopGuard() {
        PanicService.stop(this)
        toast("Guard fermato")
    }

    private fun testAlert() {
        saveValues()
        PanicService.test(this)
        toast("Allarme di test inviato")
        logView.postDelayed({ refreshLog() }, 1500)
    }

    private fun refreshLog() {
        val f = File(filesDir, "events.log")
        val text = if (f.exists()) {
            f.readLines().takeLast(40).reversed().joinToString("\n")
        } else "(nessun evento)"
        logView.text = text
    }

    // ---- permissions -----------------------------------------------------
    private fun requestAllPermissions() {
        val needed = mutableListOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.SEND_SMS,
            Manifest.permission.CALL_PHONE
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            needed += Manifest.permission.BLUETOOTH_SCAN
            needed += Manifest.permission.BLUETOOTH_CONNECT
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            needed += Manifest.permission.POST_NOTIFICATIONS
        }
        val missing = needed.filter {
            ContextCompat.checkSelfPermission(this, it) != android.content.pm.PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty()) requestPermissions(missing.toTypedArray(), 1)
    }

    private fun askFullScreenIntent() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            try {
                startActivity(Intent(
                    Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT,
                    Uri.parse("package:$packageName")
                ))
            } catch (e: Exception) { toast("Apri a mano: Impostazioni > Notifiche a schermo intero") }
        } else toast("Non necessario su questa versione di Android")
    }

    private fun askBatteryExemption() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        if (!pm.isIgnoringBatteryOptimizations(packageName)) {
            try {
                startActivity(Intent(
                    Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                    Uri.parse("package:$packageName")
                ))
            } catch (e: Exception) { toast("Apri manualmente: Impostazioni > Batteria") }
        } else toast("Già esclusa")
    }

    // ---- tiny view helpers ----------------------------------------------
    private fun title(t: String) = TextView(this).apply {
        text = t; textSize = 24f; setTextColor(Color.parseColor("#e91e8c"))
        setPadding(0, 0, 0, 20)
    }
    private fun label(t: String) = TextView(this).apply {
        text = t; textSize = 13f; setTextColor(Color.GRAY); setPadding(0, 24, 0, 4)
    }
    private fun field(type: Int) = EditText(this).apply {
        inputType = type
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
    }
    private fun check(t: String) = CheckBox(this).apply { text = t; setPadding(0, 16, 0, 16) }
    private fun button(t: String, onClick: () -> Unit) = Button(this).apply {
        text = t
        setOnClickListener { onClick() }
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            .apply { topMargin = 16 }
    }
    private fun toast(t: String) = Toast.makeText(this, t, Toast.LENGTH_SHORT).show()
}
