package com.example.ikev2manager.data

import android.content.Context

class ServerStore(context: Context) {
    private val prefs = context.getSharedPreferences("server_store", Context.MODE_PRIVATE)

    fun load(): String = prefs.getString("server_list", "") ?: ""

    fun save(raw: String) {
        prefs.edit().putString("server_list", raw).apply()
    }
}
