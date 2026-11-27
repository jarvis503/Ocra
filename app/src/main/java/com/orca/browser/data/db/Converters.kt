package com.orca.browser.data.db

import androidx.room.TypeConverter
import com.orca.browser.data.model.BrowsingMode

class Converters {

    @TypeConverter
    fun fromBrowsingMode(mode: BrowsingMode): String = mode.name

    @TypeConverter
    fun toBrowsingMode(value: String): BrowsingMode {
        return try {
            BrowsingMode.valueOf(value)
        } catch (_: IllegalArgumentException) {
            BrowsingMode.NORMAL
        }
    }
}
