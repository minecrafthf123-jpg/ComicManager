package com.comicmanager.ui.viewmodel

import android.content.SharedPreferences
import org.junit.Assert.assertEquals
import org.junit.Test
import java.lang.reflect.Proxy

class ShelfSortPreferencesTest {
    @Test
    fun recreatingThePreferenceReaderRestoresEverySortModeByName() {
        val backing = PreferenceMemory()
        for (mode in SortMode.entries) {
            ShelfSortPreferences(backing.preferences).write(mode)
            assertEquals(mode.name, backing.values.values.single())
            assertEquals(mode, ShelfSortPreferences(backing.preferences).read())
        }
    }

    @Test
    fun upgradesWithoutSavedPreferenceKeepThePreviousDefault() {
        assertEquals(SortMode.TIME_DESC, ShelfSortPreferences(PreferenceMemory().preferences).read())
    }

    @Test
    fun unknownOrWrongTypePreferenceDoesNotBreakStartup() {
        val backing = PreferenceMemory()
        ShelfSortPreferences(backing.preferences).write(SortMode.NAME_NUM_ASC)
        val key = backing.values.keys.single()
        backing.values[key] = "REMOVED_MODE"
        assertEquals(SortMode.TIME_DESC, ShelfSortPreferences(backing.preferences).read())
        backing.values[key] = 42
        assertEquals(SortMode.TIME_DESC, ShelfSortPreferences(backing.preferences).read())
    }

    private class PreferenceMemory {
        val values = mutableMapOf<String, Any?>()
        val preferences = Proxy.newProxyInstance(
            SharedPreferences::class.java.classLoader,
            arrayOf(SharedPreferences::class.java)
        ) { _, method, arguments ->
            when (method.name) {
                "getString" -> {
                    val value = values[arguments!![0] as String] ?: arguments[1]
                    if (value != null && value !is String) throw ClassCastException()
                    value
                }
                "edit" -> editor()
                else -> error("Unexpected preference call: ${method.name}")
            }
        } as SharedPreferences

        private fun editor(): SharedPreferences.Editor {
            val pending = mutableMapOf<String, Any?>()
            return Proxy.newProxyInstance(
                SharedPreferences.Editor::class.java.classLoader,
                arrayOf(SharedPreferences.Editor::class.java)
            ) { proxy, method, arguments ->
                when (method.name) {
                    "putString" -> {
                        pending[arguments!![0] as String] = arguments[1]
                        proxy
                    }
                    "apply" -> { values.putAll(pending); null }
                    else -> error("Unexpected editor call: ${method.name}")
                }
            } as SharedPreferences.Editor
        }
    }
}
