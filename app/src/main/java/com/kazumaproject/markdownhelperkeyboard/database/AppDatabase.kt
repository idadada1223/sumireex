package com.kazumaproject.markdownhelperkeyboard.database

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.kazumaproject.data.clicked_symbol.ClickedSymbol
import com.kazumaproject.markdownhelperkeyboard.clicked_symbol.database.ClickedSymbolDao
import com.kazumaproject.markdownhelperkeyboard.clipboard_history.BitmapConverter
import com.kazumaproject.markdownhelperkeyboard.clipboard_history.database.ClipboardHistoryDao
import com.kazumaproject.markdownhelperkeyboard.clipboard_history.database.ClipboardHistoryItem
import com.kazumaproject.markdownhelperkeyboard.clipboard_history.database.ItemTypeConverter
import com.kazumaproject.markdownhelperkeyboard.custom_keyboard.data.CustomKeyboardLayout
import com.kazumaproject.markdownhelperkeyboard.custom_keyboard.data.FlickMapping
import com.kazumaproject.markdownhelperkeyboard.custom_keyboard.data.KeyDefinition
import com.kazumaproject.markdownhelperkeyboard.custom_keyboard.database.KeyboardLayoutDao
import com.kazumaproject.markdownhelperkeyboard.learning.database.LearnDao
import com.kazumaproject.markdownhelperkeyboard.learning.database.LearnEntity
import com.kazumaproject.markdownhelperkeyboard.user_dictionary.database.UserWord
import com.kazumaproject.markdownhelperkeyboard.user_dictionary.database.UserWordDao
import com.kazumaproject.markdownhelperkeyboard.user_template.database.UserTemplate
import com.kazumaproject.markdownhelperkeyboard.user_template.database.UserTemplateDao

/**
 * アプリケーションのRoomデータベース定義。
 *
 * このデータベースには以下のエンティティ（テーブル）が含まれます：
 * - [LearnEntity]: 学習辞書の単語情報。
 * - [ClickedSymbol]: 最近使用した記号の履歴。
 * - [UserWord]: ユーザー辞書の単語情報。
 * - [CustomKeyboardLayout]: カスタムキーボードのレイアウト情報。
 * - [KeyDefinition]: カスタムキーボードの各キーの定義。
 * - [FlickMapping]: カスタムキーボードのフリック入力のマッピング情報。
 * - [UserTemplate]: ユーザー定義の定型文。
 * - [ClipboardHistoryItem]: クリップボードの履歴アイテム。
 *
 * 現在のデータベースバージョンは9です。
 * スキーマのエクスポートは無効化されています（`exportSchema = false`）。
 */
@Database(
    entities = [
        LearnEntity::class,
        ClickedSymbol::class,
        UserWord::class,
        CustomKeyboardLayout::class,
        KeyDefinition::class,
        FlickMapping::class,
        UserTemplate::class,
        ClipboardHistoryItem::class
    ],
    version = 9,
    exportSchema = false
)
/**
 * 型コンバータを指定します。
 * Roomデータベースはデフォルトでは特定の型（例: Bitmap）を直接保存できないため、
 * これらのコンバータクラスが型の変換を行います。
 * - [BitmapConverter]: BitmapとByteArray間の変換。
 * - [ItemTypeConverter]: クリップボードアイテムの種別（テキスト、画像など）のEnumとString間の変換。
 */
@TypeConverters(
    BitmapConverter::class,
    ItemTypeConverter::class
)
abstract class AppDatabase : RoomDatabase() {
    /** 学習辞書 ([LearnEntity]) にアクセスするためのDAOを取得します。 */
    abstract fun learnDao(): LearnDao
    /** 最近使用した記号 ([ClickedSymbol]) にアクセスするためのDAOを取得します。 */
    abstract fun clickedSymbolDao(): ClickedSymbolDao
    /** ユーザー辞書 ([UserWord]) にアクセスするためのDAOを取得します。 */
    abstract fun userWordDao(): UserWordDao
    /** カスタムキーボードレイアウト ([CustomKeyboardLayout], [KeyDefinition], [FlickMapping]) にアクセスするためのDAOを取得します。 */
    abstract fun keyboardLayoutDao(): KeyboardLayoutDao
    /** ユーザー定義の定型文 ([UserTemplate]) にアクセスするためのDAOを取得します。 */
    abstract fun userTemplateDao(): UserTemplateDao
    /** クリップボード履歴 ([ClipboardHistoryItem]) にアクセスするためのDAOを取得します。 */
    abstract fun clipboardHistoryDao(): ClipboardHistoryDao

    companion object {
        /**
         * データベースバージョン1から2へのマイグレーション。
         * `clicked_symbol_history` テーブル（最近使用した記号の履歴）を作成します。
         */
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `clicked_symbol_history` (
                      `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                      `mode` TEXT NOT NULL,
                      `symbol` TEXT NOT NULL,
                      `timestamp` INTEGER NOT NULL
                    )
                    """.trimIndent()
                )
            }
        }

        /**
         * データベースバージョン2から3へのマイグレーション。
         * `user_word` テーブル（ユーザー辞書）を作成します。
         */
        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `user_word` (
                      `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                      `word` TEXT NOT NULL,
                      `reading` TEXT NOT NULL,
                      `posIndex` INTEGER NOT NULL,
                      `posScore` INTEGER NOT NULL
                    )
                    """.trimIndent()
                )
            }
        }

        /**
         * データベースバージョン3から4へのマイグレーション。
         * `user_word` テーブルの `reading` カラムにインデックスを作成し、
         * 読みによる前方一致検索のパフォーマンスを向上させます。
         */
        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // SQL command to create an index on the 'reading' column
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_user_word_reading` ON `user_word`(`reading`)")
            }
        }

        /**
         * データベースバージョン4から5へのマイグレーション。
         * カスタムキーボード機能をサポートするため、以下の3つのテーブルを作成します。
         * - `keyboard_layouts`: キーボードレイアウト全体の情報。
         * - `key_definitions`: 各キーの定義情報。`keyboard_layouts` への外部キーを含む。
         * - `flick_mappings`: フリック入力のマッピング情報。`key_definitions` への外部キーを含む。
         */
        val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // 1. keyboard_layouts テーブルの作成
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `keyboard_layouts` (
                        `layoutId` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, 
                        `name` TEXT NOT NULL, 
                        `columnCount` INTEGER NOT NULL, 
                        `rowCount` INTEGER NOT NULL, 
                        `createdAt` INTEGER NOT NULL
                    )
                """.trimIndent()
                )

                // 2. key_definitions テーブルの作成
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `key_definitions` (
                        `keyId` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, 
                        `ownerLayoutId` INTEGER NOT NULL, 
                        `label` TEXT NOT NULL, 
                        `row` INTEGER NOT NULL, 
                        `column` INTEGER NOT NULL, 
                        `rowSpan` INTEGER NOT NULL, 
                        `colSpan` INTEGER NOT NULL, 
                        `keyType` TEXT NOT NULL, 
                        `isSpecialKey` INTEGER NOT NULL, 
                        `drawableResId` INTEGER, 
                        `keyIdentifier` TEXT NOT NULL, 
                        FOREIGN KEY(`ownerLayoutId`) REFERENCES `keyboard_layouts`(`layoutId`) ON DELETE CASCADE ON UPDATE NO ACTION
                    )
                """.trimIndent()
                )
                // key_definitions テーブルのインデックス作成 (外部キーのパフォーマンス向上)
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_key_definitions_ownerLayoutId` ON `key_definitions`(`ownerLayoutId`)")

                // 3. flick_mappings テーブルの作成
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `flick_mappings` (
                        `ownerKeyId` INTEGER NOT NULL, 
                        `stateIndex` INTEGER NOT NULL, 
                        `flickDirection` TEXT NOT NULL, 
                        `actionType` TEXT NOT NULL, 
                        `actionValue` TEXT, 
                        PRIMARY KEY(`ownerKeyId`, `stateIndex`, `flickDirection`), 
                        FOREIGN KEY(`ownerKeyId`) REFERENCES `key_definitions`(`keyId`) ON DELETE CASCADE ON UPDATE NO ACTION
                    )
                """.trimIndent()
                )
            }
        }

        /**
         * データベースバージョン5から6へのマイグレーション。
         * `key_definitions` テーブルに `action` カラムを追加します。
         * この列は、キーが持つ特別な機能（削除、スペースなど）を文字列として保存します。
         */
        val MIGRATION_5_6 = object : Migration(5, 6) { // <<< ★★★ ここから追加 ★★★
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `key_definitions` ADD COLUMN `action` TEXT")
            }
        }

        /**
         * データベースバージョン6から7へのマイグレーション。
         * 定型文機能をサポートするため、`user_template` テーブルを追加します。
         * 検索パフォーマンス向上のため、`reading` カラムにインデックスも作成します。
         */
        val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // user_template テーブルの作成
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `user_template` (
                      `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                      `word` TEXT NOT NULL,
                      `reading` TEXT NOT NULL,
                      `posIndex` INTEGER NOT NULL,
                      `posScore` INTEGER NOT NULL
                    )
                    """.trimIndent()
                )
                // reading 列にインデックスを作成
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_user_template_reading` ON `user_template`(`reading`)")
            }
        }

        /**
         * データベースバージョン7から8へのマイグレーション。
         * `learn_table` に `leftId` と `rightId` カラムを追加します。
         * これらは連接コスト計算のために使用されます。
         * SQLiteではShort型はINTEGERとして扱われ、デフォルト値はNULLとなります。
         */
        val MIGRATION_7_8 = object : Migration(7, 8) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `learn_table` ADD COLUMN `leftId` INTEGER")
                db.execSQL("ALTER TABLE `learn_table` ADD COLUMN `rightId` INTEGER")
            }
        }

        /**
         * データベースバージョン8から9へのマイグレーション。
         * クリップボード履歴機能をサポートするため、`clipboard_history` テーブルを追加します。
         */
        val MIGRATION_8_9 = object : Migration(8, 9) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `clipboard_history` (
                      `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                      `itemType` TEXT NOT NULL,
                      `textData` TEXT,
                      `imageData` BLOB,
                      `timestamp` INTEGER NOT NULL
                    )
                    """.trimIndent()
                )
            }
        }

    }
}
