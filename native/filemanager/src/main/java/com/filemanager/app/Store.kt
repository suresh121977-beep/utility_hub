package com.filemanager.app

import android.content.Context
import java.security.MessageDigest
import java.security.SecureRandom

/** Small persistent store: bookmarks, hidden folders, locked folders and the PIN (salted SHA-256). */
class Store(ctx: Context) {
    private val sp = ctx.getSharedPreferences("fm_store", Context.MODE_PRIVATE)

    fun get(key: String): Set<String> = sp.getStringSet(key, emptySet())!!.toSet()

    fun put(key: String, value: Set<String>) {
        sp.edit().putStringSet(key, value.toSet()).apply()
    }

    fun hasPin(): Boolean = sp.contains("pin_hash")

    fun setPin(pin: String) {
        val bytes = ByteArray(16)
        SecureRandom().nextBytes(bytes)
        val salt = bytes.joinToString("") { "%02x".format(it) }
        sp.edit().putString("pin_salt", salt).putString("pin_hash", hash(salt, pin)).apply()
    }

    fun checkPin(pin: String): Boolean {
        val salt = sp.getString("pin_salt", "") ?: ""
        return hash(salt, pin) == sp.getString("pin_hash", null)
    }

    private fun hash(salt: String, pin: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest((salt + pin).toByteArray())
            .joinToString("") { "%02x".format(it) }
}
