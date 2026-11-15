package com.sherwood.wta.data.dao

import androidx.room.*
import com.sherwood.wta.data.model.AppCategory
import kotlinx.coroutines.flow.Flow

@Dao
interface AppCategoryDao {

    @Query("SELECT * FROM app_categories ORDER BY sortOrder ASC, createdAt ASC")
    fun getAllCategories(): Flow<List<AppCategory>>

    @Query("SELECT * FROM app_categories WHERE id = :id")
    suspend fun getCategoryById(id: Long): AppCategory?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(category: AppCategory): Long

    @Update
    suspend fun update(category: AppCategory)

    @Delete
    suspend fun delete(category: AppCategory)

    @Query("DELETE FROM app_categories WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("SELECT COUNT(*) FROM app_categories")
    suspend fun getCategoryCount(): Int

    @Query("UPDATE web_apps SET categoryId = NULL WHERE categoryId = :categoryId")
    suspend fun clearAppsInCategory(categoryId: Long)

    /**
     * Deletes a category and detaches its apps in one transaction.
     *
     * Two separate writes left every app uncategorised when the delete failed. Room only
     * honours `@Transaction` on a `@Dao`, which is why this lives here and not on the
     * repository.
     */
    @Transaction
    suspend fun deleteCategoryWithCleanup(categoryId: Long) {
        clearAppsInCategory(categoryId)
        deleteById(categoryId)
    }
}
