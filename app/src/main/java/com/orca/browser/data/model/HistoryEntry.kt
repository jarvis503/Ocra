package com.orca.browser.data.model

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "history_entries",
    indices = [
        Index(value = ["visitedAt"]),
        Index(value = ["url"])
    ]
)
data class HistoryEntry(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0L,
    val url: String,
    val title: String,
    val visitedAt: Long = System.currentTimeMillis(),
    @ColumnInfo(typeAffinity = ColumnInfo.BLOB)
    val favicon: ByteArray? = null
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as HistoryEntry

        if (id != other.id) return false
        if (url != other.url) return false
        if (title != other.title) return false
        if (visitedAt != other.visitedAt) return false
        if (!favicon.contentEquals(other.favicon)) return false

        return true
    }

    override fun hashCode(): Int {
        var result = id.hashCode()
        result = 31 * result + url.hashCode()
        result = 31 * result + title.hashCode()
        result = 31 * result + visitedAt.hashCode()
        result = 31 * result + (favicon?.contentHashCode() ?: 0)
        return result
    }
}
