package com.dejavu.customer.data

import android.content.Context
import androidx.paging.ExperimentalPagingApi
import androidx.paging.LoadType
import androidx.paging.PagingSource
import androidx.paging.PagingState
import androidx.paging.RemoteMediator
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.withTransaction
import com.dejavu.core.model.Page
import com.dejavu.core.model.StoreProduct
import com.dejavu.core.network.ApiJson

/** One product of a list (a category, a search, a brand…) at its place in that list. */
@Entity(tableName = "list_products", primaryKeys = ["listKey", "position"])
data class CachedProduct(
    val listKey: String,
    val position: Int,
    val productId: String,
    /** The product as the server sent it. */
    val json: String,
) {
    fun product(): StoreProduct? = runCatching { ApiJson.decodeFromString(StoreProduct.serializer(), json) }.getOrNull()
}

/** How far a list was loaded, and when it was last fetched. */
@Entity(tableName = "list_pages")
data class ListPage(
    @PrimaryKey val listKey: String,
    /** The next page to fetch; null = the list is complete. */
    val nextPage: Int?,
    val updatedAt: Long,
)

@Dao
interface CatalogDao {
    @Query("SELECT * FROM list_products WHERE listKey = :key ORDER BY position")
    fun products(key: String): PagingSource<Int, CachedProduct>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(items: List<CachedProduct>)

    @Query("DELETE FROM list_products WHERE listKey = :key")
    suspend fun clear(key: String)

    @Query("SELECT COUNT(*) FROM list_products WHERE listKey = :key")
    suspend fun count(key: String): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun savePage(page: ListPage)

    @Query("SELECT * FROM list_pages WHERE listKey = :key")
    suspend fun page(key: String): ListPage?

    @Query("DELETE FROM list_products")
    suspend fun clearProducts()

    @Query("DELETE FROM list_pages")
    suspend fun clearPages()
}

/** The catalogue on this phone, so lists open at once and still work without a connection. */
@Database(entities = [CachedProduct::class, ListPage::class], version = 1, exportSchema = false)
abstract class CatalogDb : RoomDatabase() {
    abstract fun dao(): CatalogDao

    /** Prices depend on who is signed in (merchants): forget everything on sign-in / sign-out. */
    suspend fun forget() {
        withTransaction {
            dao().clearProducts()
            dao().clearPages()
        }
    }

    companion object {
        fun open(context: Context): CatalogDb =
            Room.databaseBuilder(context, CatalogDb::class.java, "catalog.db").fallbackToDestructiveMigration(dropAllTables = true).build()
    }
}

/**
 * Network-bound pages: what is in Room shows at once; the server is asked for a fresh first page
 * when the list is older than [FRESH_MS], and for the next pages as the list scrolls. With no
 * connection the saved pages keep showing.
 */
@OptIn(ExperimentalPagingApi::class)
class ProductsMediator(
    private val key: String,
    private val db: CatalogDb,
    private val fetch: suspend (page: Int) -> Result<Page<StoreProduct>>,
) : RemoteMediator<Int, CachedProduct>() {
    private val dao = db.dao()

    override suspend fun initialize(): InitializeAction {
        val saved = dao.page(key) ?: return InitializeAction.LAUNCH_INITIAL_REFRESH
        return if (System.currentTimeMillis() - saved.updatedAt < FRESH_MS) InitializeAction.SKIP_INITIAL_REFRESH else InitializeAction.LAUNCH_INITIAL_REFRESH
    }

    override suspend fun load(loadType: LoadType, state: PagingState<Int, CachedProduct>): MediatorResult {
        val page = when (loadType) {
            LoadType.REFRESH -> 1
            LoadType.PREPEND -> return MediatorResult.Success(endOfPaginationReached = true)
            LoadType.APPEND -> {
                // Nothing fetched yet (the first page failed): wait for the next refresh.
                val saved = dao.page(key) ?: return MediatorResult.Success(endOfPaginationReached = true)
                saved.nextPage ?: return MediatorResult.Success(endOfPaginationReached = true)
            }
        }
        val result = fetch(page).getOrElse { return MediatorResult.Error(it) }
        db.withTransaction {
            if (loadType == LoadType.REFRESH) dao.clear(key)
            val start = if (loadType == LoadType.REFRESH) 0 else dao.count(key)
            dao.insert(result.items.mapIndexed { i, p -> CachedProduct(key, start + i, p.id, ApiJson.encodeToString(StoreProduct.serializer(), p)) })
            dao.savePage(ListPage(key, if (result.hasMore) result.page + 1 else null, System.currentTimeMillis()))
        }
        return MediatorResult.Success(endOfPaginationReached = !result.hasMore)
    }

    private companion object {
        /** A list fetched in the last 5 minutes is shown without asking the server again. */
        const val FRESH_MS = 5 * 60_000L
    }
}
