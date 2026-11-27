package com.orca.browser.data.db

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.orca.browser.data.model.BrowsingMode
import com.orca.browser.data.model.Tab
import kotlinx.coroutines.flow.Flow

@Dao
interface TabDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertTab(tab: Tab)

    @Update
    suspend fun updateTab(tab: Tab)

    @Delete
    suspend fun deleteTab(tab: Tab)

    @Query("SELECT * FROM tabs ORDER BY position ASC, createdAt ASC")
    fun getAllTabsOrdered(): Flow<List<Tab>>

    @Query("SELECT * FROM tabs WHERE browsingMode = :mode ORDER BY position ASC, createdAt ASC")
    fun getTabsByMode(mode: BrowsingMode): Flow<List<Tab>>

    @Query("SELECT * FROM tabs WHERE isActive = 1 LIMIT 1")
    suspend fun getActiveTab(): Tab?

    @Query("SELECT * FROM tabs WHERE id = :id LIMIT 1")
    suspend fun getTabById(id: String): Tab?

    @Query("DELETE FROM tabs WHERE id = :id")
    suspend fun deleteTabById(id: String)

    @Query("DELETE FROM tabs WHERE browsingMode = :mode")
    suspend fun deleteTabsByMode(mode: BrowsingMode)

    @Query("DELETE FROM tabs")
    suspend fun clearAllTabs()

    @Query("UPDATE tabs SET url = :url, title = :title WHERE id = :id")
    suspend fun updateTabUrl(id: String, url: String, title: String)

    @Query("UPDATE tabs SET isHibernated = :isHibernated WHERE id = :id")
    suspend fun updateHibernation(id: String, isHibernated: Boolean)

    @Query("UPDATE tabs SET isActive = :isActive WHERE id = :id")
    suspend fun updateActiveState(id: String, isActive: Boolean)

    @Query("UPDATE tabs SET isActive = 0")
    suspend fun deactivateAllTabs()
}
