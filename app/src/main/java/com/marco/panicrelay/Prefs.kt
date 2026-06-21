package com.marco.panicrelay

import android.content.Context

/** Simple SharedPreferences wrapper holding all user configuration. */
class Prefs(ctx: Context) {
    private val sp = ctx.getSharedPreferences("panicrelay", Context.MODE_PRIVATE)

    var deviceMac: String
        get() = sp.getString("mac", DEFAULT_MAC) ?: DEFAULT_MAC
        set(v) = sp.edit().putString("mac", v.trim().uppercase()).apply()

    var contact1: String
        get() = sp.getString("c1", "") ?: ""
        set(v) = sp.edit().putString("c1", v.trim()).apply()

    var contact2: String
        get() = sp.getString("c2", "") ?: ""
        set(v) = sp.edit().putString("c2", v.trim()).apply()

    var message: String
        get() = sp.getString("msg", DEFAULT_MSG) ?: DEFAULT_MSG
        set(v) = sp.edit().putString("msg", v).apply()

    var webhookUrl: String
        get() = sp.getString("hook", "") ?: ""
        set(v) = sp.edit().putString("hook", v.trim()).apply()

    var enableSms: Boolean
        get() = sp.getBoolean("sms", true)
        set(v) = sp.edit().putBoolean("sms", v).apply()

    var enableCall: Boolean
        get() = sp.getBoolean("call", true)
        set(v) = sp.edit().putBoolean("call", v).apply()

    var enablePhoneNotif: Boolean
        get() = sp.getBoolean("notif", true)
        set(v) = sp.edit().putBoolean("notif", v).apply()

    var enableWebhook: Boolean
        get() = sp.getBoolean("hookOn", false)
        set(v) = sp.edit().putBoolean("hookOn", v).apply()

    var enableEmail: Boolean
        get() = sp.getBoolean("emailOn", false)
        set(v) = sp.edit().putBoolean("emailOn", v).apply()

    var emailTo: String
        get() = sp.getString("emailTo", "") ?: ""
        set(v) = sp.edit().putString("emailTo", v.trim()).apply()

    var smtpUser: String
        get() = sp.getString("smtpUser", "") ?: ""
        set(v) = sp.edit().putString("smtpUser", v.trim()).apply()

    var smtpPass: String
        get() = sp.getString("smtpPass", "") ?: ""
        set(v) = sp.edit().putString("smtpPass", v.trim()).apply()

    var enableTelegram: Boolean
        get() = sp.getBoolean("tgOn", false)
        set(v) = sp.edit().putBoolean("tgOn", v).apply()

    var tgToken: String
        get() = sp.getString("tgToken", "") ?: ""
        set(v) = sp.edit().putString("tgToken", v.trim()).apply()

    var tgChatId: String
        get() = sp.getString("tgChatId", "") ?: ""
        set(v) = sp.edit().putString("tgChatId", v.trim()).apply()

    /** Whether the guard service should be running (used to auto-restart on boot). */
    var serviceEnabled: Boolean
        get() = sp.getBoolean("svcOn", false)
        set(v) = sp.edit().putBoolean("svcOn", v).apply()

    fun contacts(): List<String> =
        listOf(contact1, contact2).map { it.trim() }.filter { it.isNotEmpty() }

    companion object {
        // NOTE: valore illustrativo lasciato vuoto per privacy.
        // L'indirizzo Bluetooth reale del dispositivo va inserito nell'app a runtime.
        const val DEFAULT_MAC = ""
        const val DEFAULT_MSG = "EMERGENZA: ho attivato il mio dispositivo di sicurezza. Questa e' la mia posizione:"
    }
}
