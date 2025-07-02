package com.kazumaproject.markdownhelperkeyboard.repository

import com.kazumaproject.custom_keyboard.data.FlickAction
import com.kazumaproject.custom_keyboard.data.FlickDirection
import com.kazumaproject.custom_keyboard.data.KeyActionMapper
import com.kazumaproject.custom_keyboard.data.KeyData
import com.kazumaproject.custom_keyboard.data.KeyType
import com.kazumaproject.custom_keyboard.data.KeyboardLayout
import com.kazumaproject.markdownhelperkeyboard.custom_keyboard.data.CustomKeyboardLayout
import com.kazumaproject.markdownhelperkeyboard.custom_keyboard.data.FlickMapping
import com.kazumaproject.markdownhelperkeyboard.custom_keyboard.data.FullKeyboardLayout
import com.kazumaproject.markdownhelperkeyboard.custom_keyboard.data.KeyDefinition
import com.kazumaproject.markdownhelperkeyboard.custom_keyboard.data.toDbStrings
import com.kazumaproject.markdownhelperkeyboard.custom_keyboard.data.toFlickAction
import com.kazumaproject.markdownhelperkeyboard.custom_keyboard.database.KeyboardLayoutDao
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import timber.log.Timber
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * カスタムキーボードのレイアウトデータへのアクセスを提供するリポジトリクラス。
 * DAO ([KeyboardLayoutDao]) を介してデータベースと対話し、
 * UIモデルとDBモデル間のデータ変換も行います。
 */
@Singleton
class KeyboardRepository @Inject constructor(
    private val dao: KeyboardLayoutDao // カスタムキーボードレイアウトデータアクセスオブジェクト
) {

    /**
     * エクスポート用に、全てのカスタムキーボードレイアウト情報をDBから一度に取得します。
     * @return DBモデルである [FullKeyboardLayout] のリスト。
     */
    suspend fun getAllFullLayoutsForExport(): List<FullKeyboardLayout> {
        return dao.getAllFullLayoutsOneShot()
    }

    /**
     * 複数のカスタムキーボードレイアウト情報をデータベースにインポートします。
     * インポート時にレイアウト名が既存のものと重複する場合、自動的にリネーム処理を行います（例: "MyKeyboard (1)"）。
     * @param layouts インポートする [FullKeyboardLayout] のリスト。
     */
    suspend fun importLayouts(layouts: List<FullKeyboardLayout>) {
        for (fullLayout in layouts) {
            // 名前の重複をチェックし、必要であればカウンターを付加して新しい名前を生成
            var newName = fullLayout.layout.name
            var nameExists = dao.findLayoutByName(newName) != null
            var counter = 1
            while (nameExists) {
                newName = "${fullLayout.layout.name} (${counter})"
                nameExists = dao.findLayoutByName(newName) != null
                counter++
            }

            // 挿入用のレイアウト情報を作成 (IDは0、名前は重複回避後のもの、作成日時を現在時刻に)
            val layoutToInsert = fullLayout.layout.copy(
                layoutId = 0, // 新規作成なのでIDは0
                name = newName,
                createdAt = System.currentTimeMillis()
            )
            // 挿入用のキー定義リストを作成
            val keysToInsert = fullLayout.keysWithFlicks.map { it.key }

            // キー識別子とフリック情報のマップを作成
            val flicksMap = fullLayout.keysWithFlicks.associate { keyWithFlicks ->
                keyWithFlicks.key.keyIdentifier to keyWithFlicks.flicks
            }

            // DAOを通じてデータベースに完全なキーボードレイアウト情報を挿入
            dao.insertFullKeyboardLayout(layoutToInsert, keysToInsert, flicksMap)
        }
    }

    /**
     * DBモデルのキーボードレイアウト ([KeyboardLayout]) をUI表示用のモデルに変換します。
     * 特に、キーのラベルが空の場合にタップ時の入力文字で補完する処理や、
     * フリック情報のマッピングを行います。
     * @param dbLayout DBから取得した [KeyboardLayout] オブジェクト。
     * @return UI表示に適した [KeyboardLayout] オブジェクト。
     */
    fun convertLayout(dbLayout: KeyboardLayout): KeyboardLayout {
        // キーIDとタップ時の入力文字のマッピングを作成
        val uuidToTapCharMap = dbLayout.flickKeyMaps.mapNotNull { (uuid, flickActionStates) ->
            val tapAction = flickActionStates.firstOrNull()?.get(FlickDirection.TAP)
            if (tapAction is FlickAction.Input) {
                uuid to tapAction.char
            } else {
                null
            }
        }.toMap()

        // キーIDと最終的なキーラベルのマッピングを作成 (ラベルが空ならタップ文字で補完)
        val uuidToFinalLabelMap = dbLayout.keys.associate { keyData ->
            val uuid = keyData.keyId
            val finalLabel = if (keyData.label.isNotEmpty()) {
                keyData.label
            } else {
                uuidToTapCharMap[uuid]
            }
            uuid to finalLabel
        }.filterValues { it != null } as Map<String, String>

        // フリックキーマップのキーを最終的なラベルに更新
        val newFlickKeyMaps = dbLayout.flickKeyMaps
            .mapNotNull { (uuid, flickActions) ->
                val finalLabel = uuidToFinalLabelMap[uuid]
                if (finalLabel != null) {
                    finalLabel to flickActions
                } else {
                    null
                }
            }
            .toMap()

        // キーデータのラベルを最終的なラベルに更新
        val newKeys = dbLayout.keys.map { keyData ->
            if (keyData.isSpecialKey) {
                keyData
            } else {
                val finalLabel = uuidToFinalLabelMap[keyData.keyId]
                if (finalLabel != null) {
                    keyData.copy(label = finalLabel)
                } else {
                    keyData
                }
            }
        }
        // 更新されたキーとフリックキーマップで新しいKeyboardLayoutオブジェクトを返す
        return dbLayout.copy(
            keys = newKeys,
            flickKeyMaps = newFlickKeyMaps
        )
    }

    /**
     * 全てのカスタムキーボードレイアウトをUIモデルの [KeyboardLayout] のFlowとして取得します。
     * データベースの変更を監視し、自動的にUIに反映させることができます。
     * @return UIモデルの [KeyboardLayout] のリストを放出するFlow。
     */
    fun getAllCustomKeyboardLayouts(): Flow<List<KeyboardLayout>> {
        return dao.getAllFullLayouts().map { dbLayouts ->
            dbLayouts.map { dbLayout ->
                convertToUiModel(dbLayout) // DBモデルからUIモデルへ変換
            }
        }
    }

    /**
     * 指定された名前のキーボードレイアウトが既に存在するかどうかを確認します。
     * 更新時など、名前の重複を避けるために使用されます。
     * @param name 確認するキーボード名。
     * @param currentId 現在編集中のレイアウトID（更新の場合）。新規作成時はnull。
     * @return 名前が存在すればtrue、そうでなければfalse。
     */
    suspend fun doesNameExist(name: String, currentId: Long?): Boolean {
        val foundLayout = dao.findLayoutByName(name)
        return when {
            foundLayout == null -> false // 見つからなければ存在しない
            foundLayout.layoutId == currentId -> false // 自分自身の名前なら重複と見なさない
            else -> true // それ以外は重複
        }
    }

    /**
     * カスタムキーボードレイアウトのリストをFlowとして取得します。
     * @return [CustomKeyboardLayout] のリストを放出するFlow。
     */
    fun getLayouts(): Flow<List<CustomKeyboardLayout>> = dao.getLayoutsList()

    /**
     * カスタムキーボードレイアウトのリストをFlowではなく、suspend関数で一度だけ取得します。
     * @return [CustomKeyboardLayout] のリスト。
     */
    suspend fun getLayoutsNotFlow(): List<CustomKeyboardLayout> =
        dao.getLayoutsListNotFlow()

    /**
     * 指定されたIDのカスタムキーボードレイアウトの名前を取得します。
     * @param id レイアウトID。
     * @return レイアウト名。存在しない場合はnull。
     */
    suspend fun getLayoutName(id: Long): String? = dao.getLayoutName(id)

    /**
     * 指定されたIDの完全なカスタムキーボードレイアウト情報をUIモデルのFlowとして取得します。
     * @param id レイアウトID。
     * @return UIモデルの [KeyboardLayout] を放出するFlow。
     */
    fun getFullLayout(id: Long): Flow<KeyboardLayout> {
        return dao.getFullLayoutById(id).map { dbLayout ->
            convertToUiModel(dbLayout) // DBモデルからUIモデルへ変換
        }
    }

    /**
     * カスタムキーボードレイアウトを保存（新規作成または更新）します。
     * UIモデルの [KeyboardLayout] をDBモデルに変換してから保存処理を行います。
     * @param layout 保存するUIモデルの [KeyboardLayout]。
     * @param name キーボード名。
     * @param id レイアウトID。新規作成の場合はnull、更新の場合は既存のID。
     */
    suspend fun saveLayout(layout: KeyboardLayout, name: String, id: Long?) {
        Timber.d("saveLayout: $layout")
        // DB保存用のCustomKeyboardLayoutオブジェクトを作成
        val dbLayout = CustomKeyboardLayout(
            layoutId = id ?: 0, // IDがnullなら新規作成 (ID=0)
            name = name,
            columnCount = layout.columnCount,
            rowCount = layout.rowCount
        )
        Timber.d("saveLayout db: $dbLayout")
        // UIモデルからDBモデル（KeyDefinitionリストとFlickMappingマップ）へ変換
        val (keys, flicksMap) = convertToDbModel(layout)

        if (id != null && id > 0) {
            // 更新の場合は、まずレイアウト情報を更新し、
            // その後、関連する古いキー定義とフリックマッピングを削除
            dao.updateLayout(dbLayout)
            dao.deleteKeysAndFlicksForLayout(id)
        }

        // 新規作成または更新されたレイアウト情報、キー定義、フリックマッピングをDBに挿入
        dao.insertFullKeyboardLayout(dbLayout, keys, flicksMap)
    }

    /**
     * 指定されたIDのカスタムキーボードレイアウトを削除します。
     * 関連するキー定義やフリックマッピングもカスケード削除されます（DBスキーマ定義による）。
     * @param id 削除するレイアウトID。
     */
    suspend fun deleteLayout(id: Long) {
        dao.deleteLayout(id)
    }

    /**
     * 指定されたIDのカスタムキーボードレイアウトを複製します。
     * 複製されたレイアウトは名前に「 (コピー)」が付き、重複する場合はさらに連番が振られます。
     * @param id 複製元のレイアウトID。
     */
    suspend fun duplicateLayout(id: Long) {
        val originalLayout = dao.getFullLayoutOneShot(id) ?: return // 複製元が存在しなければ終了
        // 新しい名前を生成（重複回避処理）
        val newName = originalLayout.layout.name + " (コピー)"
        var nameExists = dao.findLayoutByName(newName) != null
        var counter = 2
        var finalName = newName
        while (nameExists) {
            finalName = "$newName ($counter)"
            nameExists = dao.findLayoutByName(finalName) != null
            counter++
        }
        // 新しいレイアウト情報を作成
        val newLayoutInfo = originalLayout.layout.copy(
            layoutId = 0, // 新規作成なのでIDは0
            name = finalName,
            createdAt = System.currentTimeMillis()
        )
        // キー定義とフリックマッピングをコピー
        val keys = originalLayout.keysWithFlicks.map { it.key }
        val flicksMap = originalLayout.keysWithFlicks.associate { keyWithFlicks ->
            keyWithFlicks.key.keyIdentifier to keyWithFlicks.flicks
        }
        // 新しいレイアウトとしてDBに挿入
        dao.insertFullKeyboardLayout(newLayoutInfo, keys, flicksMap)
    }

    /**
     * DBモデルの [FullKeyboardLayout] をUI表示用の [KeyboardLayout] に変換します。
     * @param dbLayout DBから取得した [FullKeyboardLayout] オブジェクト。
     * @return UI表示に適した [KeyboardLayout] オブジェクト。
     */
    private fun convertToUiModel(dbLayout: FullKeyboardLayout): KeyboardLayout {
        // フリックマッピングをUIモデルの形式に変換
        val flickMaps = dbLayout.keysWithFlicks.associate { keyWithFlicks ->
            val identifier = keyWithFlicks.key.keyIdentifier
            val flicksByState = keyWithFlicks.flicks.groupBy { it.stateIndex }
                .mapValues { (_, stateFlicks) ->
                    stateFlicks.associate { flick ->
                        flick.flickDirection to flick.toFlickAction() // DBの文字列からFlickActionオブジェクトへ
                    }
                }
                .toSortedMap() // 状態インデックスでソート
                .values.toList()
            identifier to flicksByState
        }
        // キー定義をUIモデルの形式に変換
        val keys = dbLayout.keysWithFlicks.map { keyWithFlicks ->
            val dbKey = keyWithFlicks.key
            // 特殊キーの場合はアクションオブジェクトをマッピング
            val actionObject = if (dbKey.isSpecialKey) {
                KeyActionMapper.toKeyAction(dbKey.action)
            } else {
                null
            }
            KeyData(
                label = dbKey.label,
                row = dbKey.row,
                column = dbKey.column,
                isFlickable = dbKey.keyType != KeyType.NORMAL,
                keyType = dbKey.keyType,
                rowSpan = dbKey.rowSpan,
                colSpan = dbKey.colSpan,
                isSpecialKey = dbKey.isSpecialKey,
                drawableResId = dbKey.drawableResId,
                keyId = dbKey.keyIdentifier,
                action = actionObject
            )
        }
        // UIモデルのKeyboardLayoutオブジェクトを生成して返す
        return KeyboardLayout(
            keys = keys,
            flickKeyMaps = flickMaps,
            columnCount = dbLayout.layout.columnCount,
            rowCount = dbLayout.layout.rowCount
        )
    }

    /**
     * UIモデルの [KeyboardLayout] をDB保存用のモデル ([KeyDefinition] リストと [FlickMapping] マップのペア) に変換します。
     * @param uiLayout UIモデルの [KeyboardLayout] オブジェクト。
     * @return DB保存に適した [KeyDefinition] のリストと、キー識別子をキーとする [FlickMapping] のリストのマップのペア。
     */
    private fun convertToDbModel(uiLayout: KeyboardLayout): Pair<List<KeyDefinition>, Map<String, List<FlickMapping>>> {
        val keys = mutableListOf<KeyDefinition>()
        val flicksMap = mutableMapOf<String, MutableList<FlickMapping>>()

        uiLayout.keys.forEach { keyData ->
            // キー識別子（なければ新規生成）
            val keyIdentifier = keyData.keyId ?: UUID.randomUUID().toString()
            // 特殊キーのアクションを文字列に変換
            val actionString: String? = if (keyData.isSpecialKey) {
                KeyActionMapper.fromKeyAction(keyData.action)
            } else {
                null
            }
            // KeyDefinitionオブジェクトを作成してリストに追加
            keys.add(
                KeyDefinition(
                    keyId = 0, // DB側で自動採番
                    ownerLayoutId = 0, // 保存時に実際のレイアウトIDが設定される
                    label = keyData.label,
                    row = keyData.row,
                    column = keyData.column,
                    rowSpan = keyData.rowSpan,
                    colSpan = keyData.colSpan,
                    keyType = keyData.keyType,
                    isSpecialKey = keyData.isSpecialKey,
                    drawableResId = keyData.drawableResId,
                    keyIdentifier = keyIdentifier,
                    action = actionString
                )
            )
            // フリックマッピング情報をDBモデルに変換
            uiLayout.flickKeyMaps[keyIdentifier]?.forEachIndexed { stateIndex, stateMap ->
                stateMap.forEach { (direction, flickAction) ->
                    val (actionType, actionValue) = flickAction.toDbStrings() // FlickActionをDB保存用の文字列に
                    val flick = FlickMapping(
                        ownerKeyId = 0, // 保存時に実際のキーIDが設定される
                        stateIndex = stateIndex,
                        flickDirection = direction,
                        actionType = actionType,
                        actionValue = actionValue
                    )
                    flicksMap.getOrPut(keyIdentifier) { mutableListOf() }.add(flick)
                }
            }
        }
        return Pair(keys, flicksMap)
    }
}
