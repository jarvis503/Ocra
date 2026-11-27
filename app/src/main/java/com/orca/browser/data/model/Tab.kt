package com.orca.browser.data.model

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import java.util.UUID

@Entity(
    tableName = "tabs",
    indices = [
        Index(value = ["position"]),
        Index(value = ["browsingMode"]),
        Index(value = ["isActive"])
    ]
)
data class Tab(
    @PrimaryKey
    val id: String = UUID.randomUUID().toString(),
    val url: String = "about:blank",
    val title: String = "New Tab",
    @ColumnInfo(typeAffinity = ColumnInfo.BLOB)
    val favicon: ByteArray? = null,
    val position: Int = 0,
    val isActive: Boolean = false,
    val browsingMode: BrowsingMode = BrowsingMode.NORMAL,
    val lastAccessedTime: Long = System.currentTimeMillis(),
    val isHibernated: Boolean = false,
    val isRedirect: Boolean = false,
    val sourceTabId: String? = null,
    val createdAt: Long = System.currentTimeMillis()
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as Tab

        if (id != other.id) return false
        if (url != other.url) return false
        if (title != other.title) return false
        if (!favicon.contentEquals(other.favicon)) return false
        if (position != other.position) return false
        if (isActive != other.isActive) return false
        if (browsingMode != other.browsingMode) return false
        if (lastAccessedTime != other.lastAccessedTime) return false
        if (isHibernated != other.isHibernated) return false
        if (isRedirect != other.isRedirect) return false
        if (sourceTabId != other.sourceTabId) return false
        if (createdAt != other.createdAt) return false

        return true
    }

    override fun hashCode(): Int {
        var result = id.hashCode()
        result = 31 * result + url.hashCode()
        result = 31 * result + title.hashCode()
        result = 31 * result + (favicon?.contentHashCode() ?: 0)
        result = 31 * result + position
        result = 31 * result + isActive.hashCode()
        result = 31 * result + browsingMode.hashCode()
        result = 31 * result + lastAccessedTime.hashCode()
        result = 31 * result + isHibernated.hashCode()
        result = 31 * result + isRedirect.hashCode()
        result = 31 * result + (sourceTabId?.hashCode() ?: 0)
        result = 31 * result + createdAt.hashCode()
        return result
    }
}
