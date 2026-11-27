package com.orca.browser.data.db

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.orca.browser.data.model.HistoryEntry
import kotlinx.coroutines.flow.Flow

@Dao
interface HistoryDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertEntry(entry: HistoryEntry)

    @Delete
    suspend fun deleteEntry(entry: HistoryEntry)

    @Query("SELECT * FROM history_entries ORDER BY visitedAt DESC")
    fun getAllEntries(): Flow<List<HistoryEntry>>

    @Query(
        "SELECT * FROM history_entries " +
            "WHERE url LIKE '%' || :query || '%' OR title LIKE '%' || :query || '%' " +
            "ORDER BY visitedAt DESC"
    )
    fun searchEntries(query: String): Flow<List<HistoryEntry>>

    @Query("DELETE FROM history_entries")
    suspend fun clearAllHistory()

    @Query("DELETE FROM history_entries WHERE id = :id")
    suspend fun deleteEntryById(id: Long)
}
