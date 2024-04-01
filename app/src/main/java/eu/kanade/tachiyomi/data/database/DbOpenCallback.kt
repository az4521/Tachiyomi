package eu.kanade.tachiyomi.data.database

import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import eu.kanade.tachiyomi.data.database.tables.CategoryTable
import eu.kanade.tachiyomi.data.database.tables.ChapterTable
import eu.kanade.tachiyomi.data.database.tables.HistoryTable
import eu.kanade.tachiyomi.data.database.tables.MangaCategoryTable
import eu.kanade.tachiyomi.data.database.tables.MangaTable
import eu.kanade.tachiyomi.data.database.tables.MergedTable
import eu.kanade.tachiyomi.data.database.tables.TrackTable
import exh.metadata.sql.tables.SearchMetadataTable
import exh.metadata.sql.tables.SearchTagTable
import exh.metadata.sql.tables.SearchTitleTable

class DbOpenCallback : SupportSQLiteOpenHelper.Callback(DATABASE_VERSION) {
    companion object {
        /**
         * Name of the database file.
         */
        const val DATABASE_NAME = "tachiyomi.db"

        /**
         * Version of the database.
         */
        const val DATABASE_VERSION = 15 // [EXH + J2K DRAGNDROP + AZ MERGEDSOURES + DEV DATESORT + 1.4 EXTLIB]
    }

    override fun onCreate(db: SupportSQLiteDatabase) =
        with(db) {
            execSQL(MangaTable.createTableQuery)
            execSQL(ChapterTable.createTableQuery)
            execSQL(TrackTable.createTableQuery)
            execSQL(CategoryTable.createTableQuery)
            execSQL(MangaCategoryTable.createTableQuery)
            execSQL(HistoryTable.createTableQuery)
            // EXH -->
            execSQL(SearchMetadataTable.createTableQuery)
            execSQL(SearchTagTable.createTableQuery)
            execSQL(SearchTitleTable.createTableQuery)
            // EXH <--
            // AZ -->
            execSQL(MergedTable.createTableQuery)
            // AZ <--

            // DB indexes
            execSQL(MangaTable.createUrlIndexQuery)
            execSQL(MangaTable.createLibraryIndexQuery)
            execSQL(ChapterTable.createMangaIdIndexQuery)
            execSQL(ChapterTable.createUnreadChaptersIndexQuery)
            execSQL(HistoryTable.createChapterIdIndexQuery)
            // EXH -->
            db.execSQL(SearchMetadataTable.createUploaderIndexQuery)
            db.execSQL(SearchMetadataTable.createIndexedExtraIndexQuery)
            db.execSQL(SearchTagTable.createMangaIdIndexQuery)
            db.execSQL(SearchTagTable.createNamespaceNameIndexQuery)
            db.execSQL(SearchTitleTable.createMangaIdIndexQuery)
            db.execSQL(SearchTitleTable.createTitleIndexQuery)
            // EXH <--
            // AZ -->
            execSQL(MergedTable.createIndexQuery)
            // AZ <--
        }

    override fun onUpgrade(
        db: SupportSQLiteDatabase,
        oldVersion: Int,
        newVersion: Int
    ) {
        if (oldVersion < 2) {
            db.execSQL(ChapterTable.sourceOrderUpdateQuery)

            // Fix kissmanga covers after supporting cloudflare
            db.execSQL(
                """UPDATE mangas SET thumbnail_url =
                    REPLACE(thumbnail_url, '93.174.95.110', 'kissmanga.com') WHERE source = 4"""
            )
        }
        if (oldVersion < 3) {
            // Initialize history tables
            db.execSQL(HistoryTable.createTableQuery)
            db.execSQL(HistoryTable.createChapterIdIndexQuery)
        }
        if (oldVersion < 4) {
            db.execSQL(ChapterTable.bookmarkUpdateQuery)
        }
        if (oldVersion < 5) {
            db.execSQL(ChapterTable.addScanlator)
        }
        if (oldVersion < 6) {
            db.execSQL(TrackTable.addTrackingUrl)
        }
        if (oldVersion < 7) {
            db.execSQL(TrackTable.addLibraryId)
        }
        if (oldVersion < 8) {
            db.execSQL("DROP INDEX IF EXISTS mangas_favorite_index")
            db.execSQL(MangaTable.createLibraryIndexQuery)
            db.execSQL(ChapterTable.createUnreadChaptersIndexQuery)
        }
        // EXH -->
        if (oldVersion < 9) {
            db.execSQL(SearchMetadataTable.createTableQuery)
            db.execSQL(SearchTagTable.createTableQuery)
            db.execSQL(SearchTitleTable.createTableQuery)

            db.execSQL(SearchMetadataTable.createUploaderIndexQuery)
            db.execSQL(SearchMetadataTable.createIndexedExtraIndexQuery)
            db.execSQL(SearchTagTable.createMangaIdIndexQuery)
            db.execSQL(SearchTagTable.createNamespaceNameIndexQuery)
            db.execSQL(SearchTitleTable.createMangaIdIndexQuery)
            db.execSQL(SearchTitleTable.createTitleIndexQuery)
        }
        // Remember to increment any Tachiyomi database upgrades after this
        // EXH <--

        // AZ -->
        if (oldVersion < 10) { // j2k drag and drop
            db.execSQL(CategoryTable.addMangaOrder)
        }
        if (oldVersion < 11) { // merged sources update
            db.execSQL(MergedTable.createTableQuery)
            db.execSQL(MergedTable.createIndexQuery)
        }
        // AZ <--

        if (oldVersion < 12) {
            db.execSQL(TrackTable.addStartDate)
            db.execSQL(TrackTable.addFinishDate)
        }
        if (oldVersion < 13) {
            db.execSQL(MangaTable.addCoverLastModified)
        }
        if (oldVersion < 14) {
            db.execSQL(MangaTable.addDateAdded)
            db.execSQL(MangaTable.backfillDateAdded)
        }
        if (oldVersion < 15) {
            db.execSQL(MangaTable.addUpdateStrategy)
        }
    }

    override fun onConfigure(db: SupportSQLiteDatabase) {
        db.setForeignKeyConstraintsEnabled(true)
    }
}
