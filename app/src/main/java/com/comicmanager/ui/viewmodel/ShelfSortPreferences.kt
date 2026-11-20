package com.comicmanager.ui.viewmodel

import android.content.SharedPreferences

/** Enum names stay stable when later versions add or rearrange sort modes. */
class ShelfSortPreferences(private val preferences: SharedPreferences) {
    fun read(): SortMode {
        val saved = try {
            preferences.getString(KEY_SORT_MODE, null)
        } catch (_: ClassCastException) {
            null
        }
        return SortMode.entries.firstOrNull { it.name == saved } ?: SortMode.TIME_DESC
    }

    fun write(mode: SortMode) {
        preferences.edit().putString(KEY_SORT_MODE, mode.name).apply()
    }

    companion object {
        const val FILE_NAME = "shelf_preferences"
        private const val KEY_SORT_MODE = "sort_mode"
    }
}
