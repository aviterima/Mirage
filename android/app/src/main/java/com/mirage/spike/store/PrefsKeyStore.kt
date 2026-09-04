package com.mirage.spike.store

import android.content.Context
import java.util.UUID

/** The user's own Google key (optional) and a stable anonymous install id for the hosted gateway. */
class PrefsKeyStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("mirage_keys", Context.MODE_PRIVATE)

    var userKey: String
        get() = prefs.getString("user_key", "") ?: ""
        set(v) { prefs.edit().putString("user_key", v.trim()).apply() }

    val installId: String
        get() = prefs.getString("install_id", null) ?: UUID.randomUUID().toString().also {
            prefs.edit().putString("install_id", it).apply()
        }
}
