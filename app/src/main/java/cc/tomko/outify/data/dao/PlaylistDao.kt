package cc.tomko.outify.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import cc.tomko.outify.data.database.PlaylistEntity
import cc.tomko.outify.data.database.playlist.PlaylistItemEntity
import cc.tomko.outify.data.database.playlist.PlaylistWithItems
import kotlinx.coroutines.flow.Flow
import javax.inject.Singleton

@Dao
@Singleton
interface PlaylistDao {
    @Transaction
    @Query("SELECT * FROM playlists WHERE id = :id")
    suspend fun getPlaylistWithItems(id: String): PlaylistWithItems?

    @Transaction
    @Query("SELECT * FROM playlists WHERE id IN (:ids)")
    suspend fun getPlaylistsWithItems(ids: List<String>): List<PlaylistWithItems>

    @Transaction
    @Query("SELECT * FROM playlists WHERE id IN (:ids)")
    fun getPlaylistsWithItemsFlow(ids: List<String>): Flow<List<PlaylistWithItems>>

    @Transaction
    @Query("SELECT * FROM playlists")
    fun getPlaylistsWithItemsFlow(): Flow<List<PlaylistWithItems>>

    @Transaction
    @Query("SELECT * FROM playlists WHERE id = :id")
    fun getPlaylistWithItemsFlow(id: String): Flow<PlaylistWithItems?>

    /** The playlists themselves, without their items. */
    @Query("SELECT * FROM playlists WHERE id IN (:ids)")
    suspend fun getPlaylists(ids: List<String>): List<PlaylistEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertPlaylist(entity: PlaylistEntity)

    @Query("DELETE FROM playlist_items WHERE playlistId = :playlistId")
    suspend fun deleteItems(playlistId: String)

    @Query("DELETE FROM playlist_items WHERE playlistId = :playlistId AND trackUri IN (:trackUris)")
    suspend fun deleteItemsByUris(playlistId: String, trackUris: List<String>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertItems(items: List<PlaylistItemEntity>)

    @Query("SELECT COALESCE(MAX(position), -1) FROM playlist_items WHERE playlistId = :playlistId")
    suspend fun getMaxPosition(playlistId: String): Int
}
