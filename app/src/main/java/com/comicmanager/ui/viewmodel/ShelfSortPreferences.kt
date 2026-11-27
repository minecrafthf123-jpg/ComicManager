package com.comicmanager.ui.viewmodel

import android.content.SharedPreferences

/** Enum names stay stable when later versions add or rearrange sort modes. */
class ShelfSortPreferences(private val preferences: SharedPreferences) {
    fun read(): SortMode {
        val saved = readString(KEY_SORT_MODE)
        return SortMode.entries.firstOrNull { it.name == saved } ?: SortMode.TIME_DESC
    }

    fun write(mode: SortMode) {
        preferences.edit().putString(KEY_SORT_MODE, mode.name).apply()
    }

    internal fun readCustomOrder(): List<Long>? = readString(KEY_CUSTOM_ORDER)?.split(',')
        ?.mapNotNull { it.toLongOrNull()?.takeIf { id -> id > 0 } }
        ?.distinct()

    internal fun writeCustomOrder(ids: List<Long>) {
        preferences.edit().putString(KEY_CUSTOM_ORDER, ids.joinToString(",")).apply()
    }

    private fun readString(key: String): String? = try {
        preferences.getString(key, null)
    } catch (_: ClassCastException) {
        null
    }

    companion object {
        const val FILE_NAME = "shelf_preferences"
        private const val KEY_SORT_MODE = "sort_mode"
        private const val KEY_CUSTOM_ORDER = "custom_order"
    }
}
