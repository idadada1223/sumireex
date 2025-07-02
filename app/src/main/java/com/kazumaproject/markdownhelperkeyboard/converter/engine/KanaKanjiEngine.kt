package com.kazumaproject.markdownhelperkeyboard.converter.engine

import android.content.Context
import androidx.core.text.isDigitsOnly
import com.kazumaproject.Louds.LOUDS
import com.kazumaproject.Louds.with_term_id.LOUDSWithTermId
import com.kazumaproject.convertFullWidthToHalfWidth
import com.kazumaproject.data.emoji.Emoji
import com.kazumaproject.dictionary.TokenArray
import com.kazumaproject.domain.categorizeEmoji
import com.kazumaproject.domain.sortByEmojiCategory
import com.kazumaproject.hiraToKata
import com.kazumaproject.markdownhelperkeyboard.converter.bitset.SuccinctBitVector
import com.kazumaproject.markdownhelperkeyboard.converter.candidate.Candidate
import com.kazumaproject.markdownhelperkeyboard.converter.graph.GraphBuilder
import com.kazumaproject.markdownhelperkeyboard.ime_service.extensions.addCommasToNumber
import com.kazumaproject.markdownhelperkeyboard.ime_service.extensions.convertToKanjiNotation
import com.kazumaproject.markdownhelperkeyboard.ime_service.extensions.isAllEnglishLetters
import com.kazumaproject.markdownhelperkeyboard.ime_service.extensions.toNumber
import com.kazumaproject.markdownhelperkeyboard.ime_service.extensions.toNumberExponent
import com.kazumaproject.markdownhelperkeyboard.repository.LearnRepository
import com.kazumaproject.markdownhelperkeyboard.repository.UserDictionaryRepository
import com.kazumaproject.toFullWidthDigitsEfficient
import com.kazumaproject.viterbi.FindPath
import java.io.BufferedInputStream
import java.io.ObjectInputStream
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import java.util.zip.ZipInputStream

/**
 * かな漢字変換エンジンのコアクラス。
 * Viterbiアルゴリズムに基づき、各種辞書データ（システム辞書、単漢字辞書、絵文字、その他オプション辞書）を
 * 参照して、入力された読みから最適な変換候補を生成します。
 */
class KanaKanjiEngine {

    // Viterbiアルゴリズムによるグラフ構築用
    private lateinit var graphBuilder: GraphBuilder
    // Viterbiアルゴリズムによる最適経路探索用
    private lateinit var findPath: FindPath

    // 単語間の接続コストを格納する配列
    private lateinit var connectionIds: ShortArray

    // システム辞書関連のデータ構造
    private lateinit var systemYomiTrie: LOUDSWithTermId // 読みのトライ木 (LOUDS形式、タームID付き)
    private lateinit var systemTangoTrie: LOUDS // 単語のトライ木 (LOUDS形式)
    private lateinit var systemTokenArray: TokenArray // トークン情報配列
    private lateinit var systemSuccinctBitVectorLBSYomi: SuccinctBitVector // 読みトライ木のLBSビットベクトル
    private lateinit var systemSuccinctBitVectorIsLeafYomi: SuccinctBitVector // 読みトライ木のisLeafビットベクトル
    private lateinit var systemSuccinctBitVectorTokenArray: SuccinctBitVector // トークン配列のビットベクトル
    private lateinit var systemSuccinctBitVectorTangoLBS: SuccinctBitVector // 単語トライ木のLBSビットベクトル

    // 単漢字辞書関連のデータ構造 (システム辞書と同様の構成)
    private lateinit var singleKanjiYomiTrie: LOUDSWithTermId
    private lateinit var singleKanjiTangoTrie: LOUDS
    private lateinit var singleKanjiTokenArray: TokenArray
    private lateinit var singleKanjiSuccinctBitVectorLBSYomi: SuccinctBitVector
    private lateinit var singleKanjiSuccinctBitVectorIsLeafYomi: SuccinctBitVector
    private lateinit var singleKanjiSuccinctBitVectorTokenArray: SuccinctBitVector
    private lateinit var singleKanjiSuccinctBitVectorTangoLBS: SuccinctBitVector

    // 絵文字辞書関連のデータ構造
    private lateinit var emojiYomiTrie: LOUDSWithTermId
    private lateinit var emojiTangoTrie: LOUDS
    private lateinit var emojiTokenArray: TokenArray
    private lateinit var emojiSuccinctBitVectorLBSYomi: SuccinctBitVector
    private lateinit var emojiSuccinctBitVectorIsLeafYomi: SuccinctBitVector
    private lateinit var emojiSuccinctBitVectorTokenArray: SuccinctBitVector
    private lateinit var emojiSuccinctBitVectorTangoLBS: SuccinctBitVector

    // 顔文字辞書関連のデータ構造
    private lateinit var emoticonYomiTrie: LOUDSWithTermId
    private lateinit var emoticonTangoTrie: LOUDS
    private lateinit var emoticonTokenArray: TokenArray
    private lateinit var emoticonSuccinctBitVectorLBSYomi: SuccinctBitVector
    private lateinit var emoticonSuccinctBitVectorIsLeafYomi: SuccinctBitVector
    private lateinit var emoticonSuccinctBitVectorTokenArray: SuccinctBitVector
    private lateinit var emoticonSuccinctBitVectorTangoLBS: SuccinctBitVector

    // 記号辞書関連のデータ構造
    private lateinit var symbolYomiTrie: LOUDSWithTermId
    private lateinit var symbolTangoTrie: LOUDS
    private lateinit var symbolTokenArray: TokenArray
    private lateinit var symbolSuccinctBitVectorLBSYomi: SuccinctBitVector
    private lateinit var symbolSuccinctBitVectorIsLeafYomi: SuccinctBitVector
    private lateinit var symbolSuccinctBitVectorTokenArray: SuccinctBitVector
    private lateinit var symbolSuccinctBitVectorTangoLBS: SuccinctBitVector

    // 読み補正辞書関連のデータ構造
    private lateinit var readingCorrectionYomiTrie: LOUDSWithTermId
    private lateinit var readingCorrectionTangoTrie: LOUDS
    private lateinit var readingCorrectionTokenArray: TokenArray
    private lateinit var readingCorrectionSuccinctBitVectorLBSYomi: SuccinctBitVector
    private lateinit var readingCorrectionSuccinctBitVectorIsLeafYomi: SuccinctBitVector
    private lateinit var readingCorrectionSuccinctBitVectorTokenArray: SuccinctBitVector
    private lateinit var readingCorrectionSuccinctBitVectorTangoLBS: SuccinctBitVector

    // ことわざ辞書関連のデータ構造
    private lateinit var kotowazaYomiTrie: LOUDSWithTermId
    private lateinit var kotowazaTangoTrie: LOUDS
    private lateinit var kotowazaTokenArray: TokenArray
    private lateinit var kotowazaSuccinctBitVectorLBSYomi: SuccinctBitVector
    private lateinit var kotowazaSuccinctBitVectorIsLeafYomi: SuccinctBitVector
    private lateinit var kotowazaSuccinctBitVectorTokenArray: SuccinctBitVector
    private lateinit var kotowazaSuccinctBitVectorTangoLBS: SuccinctBitVector

    // Mozc UT 人名辞書 (オプション)
    private var personYomiTrie: LOUDSWithTermId? = null
    private var personTangoTrie: LOUDS? = null
    private var personTokenArray: TokenArray? = null
    private var personSuccinctBitVectorLBSYomi: SuccinctBitVector? = null
    private var personSuccinctBitVectorIsLeaf: SuccinctBitVector? = null
    private var personSuccinctBitVectorTokenArray: SuccinctBitVector? = null
    private var personSuccinctBitVectorLBSTango: SuccinctBitVector? = null

    // Mozc UT 地名辞書 (オプション)
    private var placesYomiTrie: LOUDSWithTermId? = null
    private var placesTangoTrie: LOUDS? = null
    private var placesTokenArray: TokenArray? = null
    private var placesSuccinctBitVectorLBSYomi: SuccinctBitVector? = null
    private var placesSuccinctBitVectorIsLeaf: SuccinctBitVector? = null
    private var placesSuccinctBitVectorTokenArray: SuccinctBitVector? = null
    private var placesSuccinctBitVectorLBSTango: SuccinctBitVector? = null

    // Mozc UT Wikipedia辞書 (オプション)
    private var wikiYomiTrie: LOUDSWithTermId? = null
    private var wikiTangoTrie: LOUDS? = null
    private var wikiTokenArray: TokenArray? = null
    private var wikiSuccinctBitVectorLBSYomi: SuccinctBitVector? = null
    private var wikiSuccinctBitVectorIsLeaf: SuccinctBitVector? = null
    private var wikiSuccinctBitVectorTokenArray: SuccinctBitVector? = null
    private var wikiSuccinctBitVectorLBSTango: SuccinctBitVector? = null

    // Mozc UT Neologd辞書 (オプション)
    private var neologdYomiTrie: LOUDSWithTermId? = null
    private var neologdTangoTrie: LOUDS? = null
    private var neologdTokenArray: TokenArray? = null
    private var neologdSuccinctBitVectorLBSYomi: SuccinctBitVector? = null
    private var neologdSuccinctBitVectorIsLeaf: SuccinctBitVector? = null
    private var neologdSuccinctBitVectorTokenArray: SuccinctBitVector? = null
    private var neologdSuccinctBitVectorLBSTango: SuccinctBitVector? = null

    // Mozc UT Web辞書 (オプション)
    private var webYomiTrie: LOUDSWithTermId? = null
    private var webTangoTrie: LOUDS? = null
    private var webTokenArray: TokenArray? = null
    private var webSuccinctBitVectorLBSYomi: SuccinctBitVector? = null
    private var webSuccinctBitVectorIsLeaf: SuccinctBitVector? = null
    private var webSuccinctBitVectorTokenArray: SuccinctBitVector? = null
    private var webSuccinctBitVectorLBSTango: SuccinctBitVector? = null

    // 英語入力エンジン
    private lateinit var englishEngine: EnglishEngine

    companion object {
        // 変換候補のスコア計算に使用するオフセット値
        const val SCORE_OFFSET = 8000
        const val SCORE_OFFSET_SMALL = 6000
    }

    /**
     * かな漢字変換エンジンを構築（初期化）します。
     * 各種辞書データやViterbiアルゴリズム関連のコンポーネントをセットアップします。
     * このメソッドは、IMEサービスの初期化時などに呼び出され、変換処理に必要なリソースを準備します。
     *
     * @param graphBuilder Viterbiアルゴリズムで使用するグラフを構築するビルダー。
     * @param findPath Viterbiアルゴリズムで最適経路（最良の変換候補）を探索するクラス。
     * @param connectionIdList 単語間の接続コスト情報。
     * @param systemTangoTrie システム辞書の単語トライ木。
     * @param systemYomiTrie システム辞書の読みトライ木。
     * @param systemTokenArray システム辞書のトークン情報。
     * @param systemSuccinctBitVectorLBSYomi システム辞書の読みトライ木LBSビットベクトル。
     * @param systemSuccinctBitVectorIsLeafYomi システム辞書の読みトライ木isLeafビットベクトル。
     * @param systemSuccinctBitVectorTokenArray システム辞書のトークン配列ビットベクトル。
     * @param systemSuccinctBitVectorTangoLBS システム辞書の単語トライ木LBSビットベクトル。
     * @param singleKanjiTangoTrie 単漢字辞書の単語トライ木。
     * @param singleKanjiYomiTrie 単漢字辞書の読みトライ木。
     * @param singleKanjiTokenArray 単漢字辞書のトークン情報。
     * @param singleKanjiSuccinctBitVectorLBSYomi 単漢字辞書の読みトライ木LBSビットベクトル。
     * @param singleKanjiSuccinctBitVectorIsLeafYomi 単漢字辞書の読みトライ木isLeafビットベクトル。
     * @param singleKanjiSuccinctBitVectorTokenArray 単漢字辞書のトークン配列ビットベクトル。
     * @param singleKanjiSuccinctBitVectorTangoLBS 単漢字辞書の単語トライ木LBSビットベクトル。
     * @param emojiTangoTrie 絵文字辞書の単語トライ木。
     * @param emojiYomiTrie 絵文字辞書の読みトライ木。
     * @param emojiTokenArray 絵文字辞書のトークン情報。
     * @param emojiSuccinctBitVectorLBSYomi 絵文字辞書の読みトライ木LBSビットベクトル。
     * @param emojiSuccinctBitVectorIsLeafYomi 絵文字辞書の読みトライ木isLeafビットベクトル。
     * @param emojiSuccinctBitVectorTokenArray 絵文字辞書のトークン配列ビットベクトル。
     * @param emojiSuccinctBitVectorTangoLBS 絵文字辞書の単語トライ木LBSビットベクトル。
     * @param emoticonTangoTrie 顔文字辞書の単語トライ木。
     * @param emoticonYomiTrie 顔文字辞書の読みトライ木。
     * @param emoticonTokenArray 顔文字辞書のトークン情報。
     * @param emoticonSuccinctBitVectorLBSYomi 顔文字辞書の読みトライ木LBSビットベクトル。
     * @param emoticonSuccinctBitVectorIsLeafYomi 顔文字辞書の読みトライ木isLeafビットベクトル。
     * @param emoticonSuccinctBitVectorTokenArray 顔文字辞書のトークン配列ビットベクトル。
     * @param emoticonSuccinctBitVectorTangoLBS 顔文字辞書の単語トライ木LBSビットベクトル。
     * @param symbolTangoTrie 記号辞書の単語トライ木。
     * @param symbolYomiTrie 記号辞書の読みトライ木。
     * @param symbolTokenArray 記号辞書のトークン情報。
     * @param symbolSuccinctBitVectorLBSYomi 記号辞書の読みトライ木LBSビットベクトル。
     * @param symbolSuccinctBitVectorIsLeafYomi 記号辞書の読みトライ木isLeafビットベクトル。
     * @param symbolSuccinctBitVectorTokenArray 記号辞書のトークン配列ビットベクトル。
     * @param symbolSuccinctBitVectorTangoLBS 記号辞書の単語トライ木LBSビットベクトル。
     * @param readingCorrectionTangoTrie 読み補正辞書の単語トライ木。
     * @param readingCorrectionYomiTrie 読み補正辞書の読みトライ木。
     * @param readingCorrectionTokenArray 読み補正辞書のトークン情報。
     * @param readingCorrectionSuccinctBitVectorLBSYomi 読み補正辞書の読みトライ木LBSビットベクトル。
     * @param readingCorrectionSuccinctBitVectorIsLeafYomi 読み補正辞書の読みトライ木isLeafビットベクトル。
     * @param readingCorrectionSuccinctBitVectorTokenArray 読み補正辞書のトークン配列ビットベクトル。
     * @param readingCorrectionSuccinctBitVectorTangoLBS 読み補正辞書の単語トライ木LBSビットベクトル。
     * @param kotowazaTangoTrie ことわざ辞書の単語トライ木。
     * @param kotowazaYomiTrie ことわざ辞書の読みトライ木。
     * @param kotowazaTokenArray ことわざ辞書のトークン情報。
     * @param kotowazaSuccinctBitVectorLBSYomi ことわざ辞書の読みトライ木LBSビットベクトル。
     * @param kotowazaSuccinctBitVectorIsLeafYomi ことわざ辞書の読みトライ木isLeafビットベクトル。
     * @param kotowazaSuccinctBitVectorTokenArray ことわざ辞書のトークン配列ビットベクトル。
     * @param kotowazaSuccinctBitVectorTangoLBS ことわざ辞書の単語トライ木LBSビットベクトル。
     * @param englishEngine 英語入力処理用のエンジン。
     */
    fun buildEngine(
        graphBuilder: GraphBuilder,
        findPath: FindPath,
        connectionIdList: ShortArray,

        systemTangoTrie: LOUDS,
        systemYomiTrie: LOUDSWithTermId,
        systemTokenArray: TokenArray,
        systemSuccinctBitVectorLBSYomi: SuccinctBitVector,
        systemSuccinctBitVectorIsLeafYomi: SuccinctBitVector,
        systemSuccinctBitVectorTokenArray: SuccinctBitVector,
        systemSuccinctBitVectorTangoLBS: SuccinctBitVector,

        singleKanjiTangoTrie: LOUDS,
        singleKanjiYomiTrie: LOUDSWithTermId,
        singleKanjiTokenArray: TokenArray,
        singleKanjiSuccinctBitVectorLBSYomi: SuccinctBitVector,
        singleKanjiSuccinctBitVectorIsLeafYomi: SuccinctBitVector,
        singleKanjiSuccinctBitVectorTokenArray: SuccinctBitVector,
        singleKanjiSuccinctBitVectorTangoLBS: SuccinctBitVector,

        emojiTangoTrie: LOUDS,
        emojiYomiTrie: LOUDSWithTermId,
        emojiTokenArray: TokenArray,
        emojiSuccinctBitVectorLBSYomi: SuccinctBitVector,
        emojiSuccinctBitVectorIsLeafYomi: SuccinctBitVector,
        emojiSuccinctBitVectorTokenArray: SuccinctBitVector,
        emojiSuccinctBitVectorTangoLBS: SuccinctBitVector,

        emoticonTangoTrie: LOUDS,
        emoticonYomiTrie: LOUDSWithTermId,
        emoticonTokenArray: TokenArray,
        emoticonSuccinctBitVectorLBSYomi: SuccinctBitVector,
        emoticonSuccinctBitVectorIsLeafYomi: SuccinctBitVector,
        emoticonSuccinctBitVectorTokenArray: SuccinctBitVector,
        emoticonSuccinctBitVectorTangoLBS: SuccinctBitVector,

        symbolTangoTrie: LOUDS,
        symbolYomiTrie: LOUDSWithTermId,
        symbolTokenArray: TokenArray,
        symbolSuccinctBitVectorLBSYomi: SuccinctBitVector,
        symbolSuccinctBitVectorIsLeafYomi: SuccinctBitVector,
        symbolSuccinctBitVectorTokenArray: SuccinctBitVector,
        symbolSuccinctBitVectorTangoLBS: SuccinctBitVector,

        readingCorrectionTangoTrie: LOUDS,
        readingCorrectionYomiTrie: LOUDSWithTermId,
        readingCorrectionTokenArray: TokenArray,
        readingCorrectionSuccinctBitVectorLBSYomi: SuccinctBitVector,
        readingCorrectionSuccinctBitVectorIsLeafYomi: SuccinctBitVector,
        readingCorrectionSuccinctBitVectorTokenArray: SuccinctBitVector,
        readingCorrectionSuccinctBitVectorTangoLBS: SuccinctBitVector,

        kotowazaTangoTrie: LOUDS,
        kotowazaYomiTrie: LOUDSWithTermId,
        kotowazaTokenArray: TokenArray,
        kotowazaSuccinctBitVectorLBSYomi: SuccinctBitVector,
        kotowazaSuccinctBitVectorIsLeafYomi: SuccinctBitVector,
        kotowazaSuccinctBitVectorTokenArray: SuccinctBitVector,
        kotowazaSuccinctBitVectorTangoLBS: SuccinctBitVector,

        englishEngine: EnglishEngine
    ) {
        // Viterbiアルゴリズム関連のコンポーネントを初期化
        this@KanaKanjiEngine.graphBuilder = graphBuilder
        this@KanaKanjiEngine.findPath = findPath

        // システム辞書関連のデータを初期化
        this@KanaKanjiEngine.connectionIds = connectionIdList
        this@KanaKanjiEngine.systemTangoTrie = systemTangoTrie
        this@KanaKanjiEngine.systemTokenArray = systemTokenArray
        this@KanaKanjiEngine.systemYomiTrie = systemYomiTrie
        this@KanaKanjiEngine.systemSuccinctBitVectorLBSYomi = systemSuccinctBitVectorLBSYomi
        this@KanaKanjiEngine.systemSuccinctBitVectorIsLeafYomi = systemSuccinctBitVectorIsLeafYomi
        this@KanaKanjiEngine.systemSuccinctBitVectorTokenArray =
            systemSuccinctBitVectorTokenArray
        this@KanaKanjiEngine.systemSuccinctBitVectorTangoLBS = systemSuccinctBitVectorTangoLBS

        // 単漢字辞書関連のデータを初期化
        this@KanaKanjiEngine.singleKanjiTangoTrie = singleKanjiTangoTrie
        this@KanaKanjiEngine.singleKanjiTokenArray = singleKanjiTokenArray
        this@KanaKanjiEngine.singleKanjiYomiTrie = singleKanjiYomiTrie
        this@KanaKanjiEngine.singleKanjiSuccinctBitVectorLBSYomi =
            singleKanjiSuccinctBitVectorLBSYomi
        this@KanaKanjiEngine.singleKanjiSuccinctBitVectorIsLeafYomi =
            singleKanjiSuccinctBitVectorIsLeafYomi
        this@KanaKanjiEngine.singleKanjiSuccinctBitVectorTokenArray =
            singleKanjiSuccinctBitVectorTokenArray
        this@KanaKanjiEngine.singleKanjiSuccinctBitVectorTangoLBS =
            singleKanjiSuccinctBitVectorTangoLBS

        // 絵文字辞書関連のデータを初期化
        this@KanaKanjiEngine.emojiTangoTrie = emojiTangoTrie
        this@KanaKanjiEngine.emojiTokenArray = emojiTokenArray
        this@KanaKanjiEngine.emojiYomiTrie = emojiYomiTrie
        this@KanaKanjiEngine.emojiSuccinctBitVectorLBSYomi = emojiSuccinctBitVectorLBSYomi
        this@KanaKanjiEngine.emojiSuccinctBitVectorIsLeafYomi = emojiSuccinctBitVectorIsLeafYomi
        this@KanaKanjiEngine.emojiSuccinctBitVectorTokenArray = emojiSuccinctBitVectorTokenArray
        this@KanaKanjiEngine.emojiSuccinctBitVectorTangoLBS = emojiSuccinctBitVectorTangoLBS

        // 顔文字辞書関連のデータを初期化
        this@KanaKanjiEngine.emoticonTangoTrie = emoticonTangoTrie
        this@KanaKanjiEngine.emoticonTokenArray = emoticonTokenArray
        this@KanaKanjiEngine.emoticonYomiTrie = emoticonYomiTrie
        this@KanaKanjiEngine.emoticonSuccinctBitVectorLBSYomi = emoticonSuccinctBitVectorLBSYomi
        this@KanaKanjiEngine.emoticonSuccinctBitVectorIsLeafYomi =
            emoticonSuccinctBitVectorIsLeafYomi
        this@KanaKanjiEngine.emoticonSuccinctBitVectorTokenArray =
            emoticonSuccinctBitVectorTokenArray
        this@KanaKanjiEngine.emoticonSuccinctBitVectorTangoLBS =
            emoticonSuccinctBitVectorTangoLBS

        // 記号辞書関連のデータを初期化
        this@KanaKanjiEngine.symbolTangoTrie = symbolTangoTrie
        this@KanaKanjiEngine.symbolTokenArray = symbolTokenArray
        this@KanaKanjiEngine.symbolYomiTrie = symbolYomiTrie
        this@KanaKanjiEngine.symbolSuccinctBitVectorLBSYomi = symbolSuccinctBitVectorLBSYomi
        this@KanaKanjiEngine.symbolSuccinctBitVectorIsLeafYomi = symbolSuccinctBitVectorIsLeafYomi
        this@KanaKanjiEngine.symbolSuccinctBitVectorTokenArray = symbolSuccinctBitVectorTokenArray
        this@KanaKanjiEngine.symbolSuccinctBitVectorTangoLBS = symbolSuccinctBitVectorTangoLBS

        // 読み補正辞書関連のデータを初期化
        this@KanaKanjiEngine.readingCorrectionTangoTrie = readingCorrectionTangoTrie
        this@KanaKanjiEngine.readingCorrectionTokenArray = readingCorrectionTokenArray
        this@KanaKanjiEngine.readingCorrectionYomiTrie = readingCorrectionYomiTrie
        this@KanaKanjiEngine.readingCorrectionSuccinctBitVectorLBSYomi =
            readingCorrectionSuccinctBitVectorLBSYomi
        this@KanaKanjiEngine.readingCorrectionSuccinctBitVectorIsLeafYomi =
            readingCorrectionSuccinctBitVectorIsLeafYomi
        this@KanaKanjiEngine.readingCorrectionSuccinctBitVectorTokenArray =
            readingCorrectionSuccinctBitVectorTokenArray
        this@KanaKanjiEngine.readingCorrectionSuccinctBitVectorTangoLBS =
            readingCorrectionSuccinctBitVectorTangoLBS

        // ことわざ辞書関連のデータを初期化
        this@KanaKanjiEngine.kotowazaTangoTrie = kotowazaTangoTrie
        this@KanaKanjiEngine.kotowazaTokenArray = kotowazaTokenArray
        this@KanaKanjiEngine.kotowazaYomiTrie = kotowazaYomiTrie
        this@KanaKanjiEngine.kotowazaSuccinctBitVectorLBSYomi = kotowazaSuccinctBitVectorLBSYomi
        this@KanaKanjiEngine.kotowazaSuccinctBitVectorIsLeafYomi =
            kotowazaSuccinctBitVectorIsLeafYomi
        this@KanaKanjiEngine.kotowazaSuccinctBitVectorTokenArray =
            kotowazaSuccinctBitVectorTokenArray
        this@KanaKanjiEngine.kotowazaSuccinctBitVectorTangoLBS =
            kotowazaSuccinctBitVectorTangoLBS

        // 英語入力エンジンを初期化
        this@KanaKanjiEngine.englishEngine = englishEngine
    }

    /**
     * Mozc UT 人名辞書をビルド（ロード）します。
     * assetsから辞書ファイルを読み込み、メモリ上に展開します。
     * @param context アプリケーションコンテキスト。
     */
    fun buildPersonNamesDictionary(context: Context) {
        val objectInputTango =
            ObjectInputStream(BufferedInputStream(context.assets.open("person_name/tango_person_names.dat")))
        val objectInputYomi =
            ObjectInputStream(BufferedInputStream(context.assets.open("person_name/yomi_person_names.dat")))
        val objectInputTokenArray =
            ObjectInputStream(BufferedInputStream(context.assets.open("person_name/token_person_names.dat")))
        val objectInputReadPOSTable =
            ObjectInputStream(BufferedInputStream(context.assets.open("pos_table.dat")))

        this.personTangoTrie = LOUDS().readExternalNotCompress(objectInputTango)
        this.personYomiTrie = LOUDSWithTermId().readExternalNotCompress(objectInputYomi)

        this.personTokenArray = TokenArray()
        this.personTokenArray?.readExternal(objectInputTokenArray)
        this.personTokenArray?.readPOSTable(objectInputReadPOSTable)

        this.personSuccinctBitVectorLBSYomi = SuccinctBitVector(personYomiTrie!!.LBS)
        this.personSuccinctBitVectorIsLeaf = SuccinctBitVector(personYomiTrie!!.isLeaf)
        this.personSuccinctBitVectorTokenArray = SuccinctBitVector(personTokenArray!!.bitvector)
        this.personSuccinctBitVectorLBSTango = SuccinctBitVector(personTangoTrie!!.LBS)
    }

    /**
     * Mozc UT 地名辞書をビルド（ロード）します。
     * assetsから辞書ファイルを読み込み、メモリ上に展開します。
     * @param context アプリケーションコンテキスト。
     */
    fun buildPlaceDictionary(context: Context) {
        val zipInputStreamTango = ZipInputStream(context.assets.open("places/tango_places.dat.zip"))
        zipInputStreamTango.nextEntry
        ObjectInputStream(BufferedInputStream(zipInputStreamTango)).use {
            this.placesTangoTrie = LOUDS().readExternalNotCompress(it)
        }
        val zipInputStreamYomi = ZipInputStream(context.assets.open("places/yomi_places.dat.zip"))
        zipInputStreamYomi.nextEntry
        ObjectInputStream(BufferedInputStream(zipInputStreamYomi)).use {
            this.placesYomiTrie = LOUDSWithTermId().readExternalNotCompress(it)
        }

        this.placesTokenArray = TokenArray()

        ZipInputStream(context.assets.open("places/token_places.dat.zip")).use { zipStream ->
            var entry = zipStream.nextEntry
            while (entry != null) {
                if (entry.name == "token_places.dat") {
                    ObjectInputStream(BufferedInputStream(zipStream)).use { objectInput ->
                        this.placesTokenArray?.readExternal(objectInput)
                    }
                    break
                }
                entry = zipStream.nextEntry
            }
        }

        val objectInputReadPOSTable =
            ObjectInputStream(BufferedInputStream(context.assets.open("pos_table.dat")))

        this.placesTokenArray?.readPOSTable(objectInputReadPOSTable)
        this.placesSuccinctBitVectorLBSYomi = SuccinctBitVector(placesYomiTrie!!.LBS)
        this.placesSuccinctBitVectorIsLeaf = SuccinctBitVector(placesYomiTrie!!.isLeaf)
        this.placesSuccinctBitVectorTokenArray = SuccinctBitVector(placesTokenArray!!.bitvector)
        this.placesSuccinctBitVectorLBSTango = SuccinctBitVector(placesTangoTrie!!.LBS)
    }

    /**
     * Mozc UT Wikipedia辞書をビルド（ロード）します。
     * assetsから辞書ファイルを読み込み、メモリ上に展開します。
     * @param context アプリケーションコンテキスト。
     */
    fun buildWikiDictionary(context: Context) {
        val zipInputStreamTango = ZipInputStream(context.assets.open("wiki/tango_wiki.dat.zip"))
        zipInputStreamTango.nextEntry
        ObjectInputStream(BufferedInputStream(zipInputStreamTango)).use {
            this.wikiTangoTrie = LOUDS().readExternalNotCompress(it)
        }
        val zipInputStreamYomi = ZipInputStream(context.assets.open("wiki/yomi_wiki.dat.zip"))
        zipInputStreamYomi.nextEntry
        ObjectInputStream(BufferedInputStream(zipInputStreamYomi)).use {
            this.wikiYomiTrie = LOUDSWithTermId().readExternalNotCompress(it)
        }

        this.wikiTokenArray = TokenArray()

        ZipInputStream(context.assets.open("wiki/token_wiki.dat.zip")).use { zipStream ->
            var entry = zipStream.nextEntry
            while (entry != null) {
                if (entry.name == "token_wiki.dat") {
                    ObjectInputStream(BufferedInputStream(zipStream)).use { objectInput ->
                        this.wikiTokenArray?.readExternal(objectInput)
                    }
                    break
                }
                entry = zipStream.nextEntry
            }
        }

        val objectInputReadPOSTable =
            ObjectInputStream(BufferedInputStream(context.assets.open("pos_table.dat")))

        this.wikiTokenArray?.readPOSTable(objectInputReadPOSTable)

        this.wikiSuccinctBitVectorLBSYomi = SuccinctBitVector(wikiYomiTrie!!.LBS)
        this.wikiSuccinctBitVectorIsLeaf = SuccinctBitVector(wikiYomiTrie!!.isLeaf)
        this.wikiSuccinctBitVectorTokenArray = SuccinctBitVector(wikiTokenArray!!.bitvector)
        this.wikiSuccinctBitVectorLBSTango = SuccinctBitVector(wikiTangoTrie!!.LBS)
    }

    /**
     * Mozc UT Neologd辞書をビルド（ロード）します。
     * assetsから辞書ファイルを読み込み、メモリ上に展開します。
     * @param context アプリケーションコンテキスト。
     */
    fun buildNeologdDictionary(context: Context) {
        val zipInputStreamTango =
            ZipInputStream(context.assets.open("neologd/tango_neologd.dat.zip"))
        zipInputStreamTango.nextEntry
        ObjectInputStream(BufferedInputStream(zipInputStreamTango)).use {
            this.neologdTangoTrie = LOUDS().readExternalNotCompress(it)
        }
        val zipInputStreamYomi = ZipInputStream(context.assets.open("neologd/yomi_neologd.dat.zip"))
        zipInputStreamYomi.nextEntry
        ObjectInputStream(BufferedInputStream(zipInputStreamYomi)).use {
            this.neologdYomiTrie = LOUDSWithTermId().readExternalNotCompress(it)
        }

        this.neologdTokenArray = TokenArray()

        ZipInputStream(context.assets.open("neologd/token_neologd.dat.zip")).use { zipStream ->
            var entry = zipStream.nextEntry
            while (entry != null) {
                if (entry.name == "token_neologd.dat") {
                    ObjectInputStream(BufferedInputStream(zipStream)).use { objectInput ->
                        this.neologdTokenArray?.readExternal(objectInput)
                    }
                    break
                }
                entry = zipStream.nextEntry
            }
        }

        val objectInputReadPOSTable =
            ObjectInputStream(BufferedInputStream(context.assets.open("pos_table.dat")))

        this.neologdTokenArray?.readPOSTable(objectInputReadPOSTable)

        this.neologdSuccinctBitVectorLBSYomi = SuccinctBitVector(neologdYomiTrie!!.LBS)
        this.neologdSuccinctBitVectorIsLeaf = SuccinctBitVector(neologdYomiTrie!!.isLeaf)
        this.neologdSuccinctBitVectorTokenArray = SuccinctBitVector(neologdTokenArray!!.bitvector)
        this.neologdSuccinctBitVectorLBSTango = SuccinctBitVector(neologdTangoTrie!!.LBS)
    }

    /**
     * Mozc UT Web辞書をビルド（ロード）します。
     * assetsから辞書ファイルを読み込み、メモリ上に展開します。
     * @param context アプリケーションコンテキスト。
     */
    fun buildWebDictionary(context: Context) {
        val zipInputStreamTango =
            ZipInputStream(context.assets.open("web/tango_web.dat.zip"))
        zipInputStreamTango.nextEntry
        ObjectInputStream(BufferedInputStream(zipInputStreamTango)).use {
            this.webTangoTrie = LOUDS().readExternalNotCompress(it)
        }
        val zipInputStreamYomi = ZipInputStream(context.assets.open("web/yomi_web.dat.zip"))
        zipInputStreamYomi.nextEntry
        ObjectInputStream(BufferedInputStream(zipInputStreamYomi)).use {
            this.webYomiTrie = LOUDSWithTermId().readExternalNotCompress(it)
        }

        this.webTokenArray = TokenArray()

        ZipInputStream(context.assets.open("web/token_web.dat.zip")).use { zipStream ->
            var entry = zipStream.nextEntry
            while (entry != null) {
                if (entry.name == "token_web.dat") {
                    ObjectInputStream(BufferedInputStream(zipStream)).use { objectInput ->
                        this.webTokenArray?.readExternal(objectInput)
                    }
                    break
                }
                entry = zipStream.nextEntry
            }
        }

        val objectInputReadPOSTable =
            ObjectInputStream(BufferedInputStream(context.assets.open("pos_table.dat")))

        this.webTokenArray?.readPOSTable(objectInputReadPOSTable)

        this.webSuccinctBitVectorLBSYomi = SuccinctBitVector(webYomiTrie!!.LBS)
        this.webSuccinctBitVectorIsLeaf = SuccinctBitVector(webYomiTrie!!.isLeaf)
        this.webSuccinctBitVectorTokenArray = SuccinctBitVector(webTokenArray!!.bitvector)
        this.webSuccinctBitVectorLBSTango = SuccinctBitVector(webTangoTrie!!.LBS)
    }

    /** Mozc UT 人名辞書のリソースを解放します。 */
    fun releasePersonNamesDictionary() {
        this.personTangoTrie = null
        this.personYomiTrie = null
        this.personTokenArray = null
        this.personSuccinctBitVectorLBSYomi = null
        this.personSuccinctBitVectorIsLeaf = null
        this.personSuccinctBitVectorTokenArray = null
        this.personSuccinctBitVectorLBSTango = null
    }

    /** Mozc UT 地名辞書のリソースを解放します。 */
    fun releasePlacesDictionary() {
        this.placesTangoTrie = null
        this.placesYomiTrie = null
        this.placesTokenArray = null
        this.placesSuccinctBitVectorLBSYomi = null
        this.placesSuccinctBitVectorIsLeaf = null
        this.placesSuccinctBitVectorTokenArray = null
        this.placesSuccinctBitVectorLBSTango = null
    }

    /** Mozc UT Wikipedia辞書のリソースを解放します。 */
    fun releaseWikiDictionary() {
        this.wikiTangoTrie = null
        this.wikiYomiTrie = null
        this.wikiTokenArray = null
        this.wikiSuccinctBitVectorLBSYomi = null
        this.wikiSuccinctBitVectorIsLeaf = null
        this.wikiSuccinctBitVectorTokenArray = null
        this.wikiSuccinctBitVectorLBSTango = null
    }

    /** Mozc UT Neologd辞書のリソースを解放します。 */
    fun releaseNeologdDictionary() {
        this.neologdTangoTrie = null
        this.neologdYomiTrie = null
        this.neologdTokenArray = null
        this.neologdSuccinctBitVectorLBSYomi = null
        this.neologdSuccinctBitVectorIsLeaf = null
        this.neologdSuccinctBitVectorTokenArray = null
        this.neologdSuccinctBitVectorLBSTango = null
    }

    /** Mozc UT Web辞書のリソースを解放します。 */
    fun releaseWebDictionary() {
        this.webTangoTrie = null
        this.webYomiTrie = null
        this.webTokenArray = null
        this.webSuccinctBitVectorLBSYomi = null
        this.webSuccinctBitVectorIsLeaf = null
        this.webSuccinctBitVectorTokenArray = null
        this.webSuccinctBitVectorLBSTango = null
    }

    /** Mozc UT 人名辞書が初期化済みかどうかを返します。 */
    fun isMozcUTPersonDictionariesInitialized(): Boolean {
        return !(this.personYomiTrie == null || this.personTangoTrie == null || this.personTokenArray == null)
    }

    /** Mozc UT 地名辞書が初期化済みかどうかを返します。 */
    fun isMozcUTPlacesDictionariesInitialized(): Boolean {
        return !(this.placesYomiTrie == null || this.placesTangoTrie == null || this.placesTokenArray == null)
    }

    /** Mozc UT Wikipedia辞書が初期化済みかどうかを返します。 */
    fun isMozcUTWikiDictionariesInitialized(): Boolean {
        return !(this.wikiYomiTrie == null || this.wikiTangoTrie == null || this.wikiTokenArray == null)
    }

    /** Mozc UT Neologd辞書が初期化済みかどうかを返します。 */
    fun isMozcUTNeologdDictionariesInitialized(): Boolean {
        return !(this.neologdYomiTrie == null || this.neologdTangoTrie == null || this.neologdTokenArray == null)
    }

    /** Mozc UT Web辞書が初期化済みかどうかを返します。 */
    fun isMozcUTWebDictionariesInitialized(): Boolean {
        return !(this.webYomiTrie == null || this.webTangoTrie == null || this.webTokenArray == null)
    }

    /**
     * 入力された読み文字列から変換候補のリストを生成します。
     *
     * @param input 変換対象の読み文字列。
     * @param n 取得する候補の最大数。
     * @param mozcUtPersonName Mozc UT 人名辞書を使用するかどうか。
     * @param mozcUTPlaces Mozc UT 地名辞書を使用するかどうか。
     * @param mozcUTWiki Mozc UT Wikipedia辞書を使用するかどうか。
     * @param mozcUTNeologd Mozc UT Neologd辞書を使用するかどうか。
     * @param mozcUTWeb Mozc UT Web辞書を使用するかどうか。
     * @param userDictionaryRepository ユーザー辞書リポジトリ。
     * @param learnRepository 学習辞書リポジトリ（null許容）。
     * @return 生成された変換候補のリスト。
     */
    suspend fun getCandidates(
        input: String,
        n: Int,
        mozcUtPersonName: Boolean?,
        mozcUTPlaces: Boolean?,
        mozcUTWiki: Boolean?,
        mozcUTNeologd: Boolean?,
        mozcUTWeb: Boolean?,
        userDictionaryRepository: UserDictionaryRepository,
        learnRepository: LearnRepository?
    ): List<Candidate> {

        // 1. Viterbiアルゴリズムを用いて、システム辞書から基本的な変換候補を生成します。
        //    ユーザー辞書や学習辞書も考慮されます。
        val graph = graphBuilder.constructGraph(
            input,
            systemYomiTrie,
            systemTangoTrie,
            systemTokenArray,
            succinctBitVectorLBSYomi = systemSuccinctBitVectorLBSYomi,
            succinctBitVectorIsLeafYomi = systemSuccinctBitVectorIsLeafYomi,
            succinctBitVectorTokenArray = systemSuccinctBitVectorTokenArray,
            succinctBitVectorTangoLBS = systemSuccinctBitVectorTangoLBS,
            userDictionaryRepository = userDictionaryRepository,
            learnRepository = learnRepository
        )
        val resultNBestFinalDeferred: List<Candidate> =
            findPath.backwardAStar(graph, input.length, connectionIds, n)

        // 2. 入力が数字のみの場合、全角数字の候補を追加します。
        if (input.isDigitsOnly()) {
            val fullWidth = Candidate(
                string = input.toFullWidthDigitsEfficient(),
                type = 22, // 数字タイプ
                length = input.length.toUByte(),
                score = 8000,
                leftId = 2040, // 数字用のID
                rightId = 2040
            )
            return resultNBestFinalDeferred + fullWidth
        }

        // 3. ひらがなとカタカナの候補を生成します。
        val hirakanaAndKana = listOf(
            Candidate(input, 3, input.length.toUByte(), 6000), // ひらがな
            Candidate(input.hiraToKata(), 4, input.length.toUByte(), 6000) // カタカナ
        )

        // 4. 絵文字、顔文字、記号の予測変換を行います。
        //    入力文字列の共通接頭辞を持つものを検索します。
        val emojiCommonPrefixDeferred = deferredPredictionEmojiSymbols(
            input = input,
            yomiTrie = emojiYomiTrie,
            succinctBitVector = emojiSuccinctBitVectorLBSYomi
        )
        val emoticonCommonPrefixDeferred = deferredPredictionEmojiSymbols(
            input = input,
            yomiTrie = emoticonYomiTrie,
            succinctBitVector = emoticonSuccinctBitVectorLBSYomi,
        )
        val symbolCommonPrefixDeferred = deferredPredictionEmojiSymbols(
            input = input,
            yomiTrie = symbolYomiTrie,
            succinctBitVector = symbolSuccinctBitVectorLBSYomi,
        )

        // 5. 上記の共通接頭辞検索結果から、実際の絵文字、顔文字、記号の候補を生成します。
        val emojiListDeferred = deferredFromDictionarySymbols(
            input = input,
            commonPrefixListString = emojiCommonPrefixDeferred,
            yomiTrie = emojiYomiTrie, tokenArray = emojiTokenArray, tangoTrie = emojiTangoTrie,
            succinctBitVectorLBSYomi = emojiSuccinctBitVectorLBSYomi,
            succinctBitVectorIsLeafYomi = emojiSuccinctBitVectorIsLeafYomi,
            succinctBitVectorTokenArray = emojiSuccinctBitVectorTokenArray,
            succinctBitVectorTangoLBS = emojiSuccinctBitVectorTangoLBS,
            type = 11 // 絵文字タイプ
        )
        val emoticonListDeferred = deferredFromDictionarySymbols(
            input = input,
            commonPrefixListString = emoticonCommonPrefixDeferred,
            yomiTrie = emoticonYomiTrie, tokenArray = emoticonTokenArray, tangoTrie = emoticonTangoTrie,
            succinctBitVectorLBSYomi = emoticonSuccinctBitVectorLBSYomi,
            succinctBitVectorIsLeafYomi = emoticonSuccinctBitVectorIsLeafYomi,
            succinctBitVectorTokenArray = emoticonSuccinctBitVectorTokenArray,
            succinctBitVectorTangoLBS = emoticonSuccinctBitVectorTangoLBS,
            type = 12 // 顔文字タイプ
        )
        val symbolListDeferred = deferredFromDictionarySymbols(
            input = input,
            commonPrefixListString = symbolCommonPrefixDeferred,
            yomiTrie = symbolYomiTrie, tokenArray = symbolTokenArray, tangoTrie = symbolTangoTrie,
            succinctBitVectorLBSYomi = symbolSuccinctBitVectorLBSYomi,
            succinctBitVectorIsLeafYomi = symbolSuccinctBitVectorIsLeafYomi,
            succinctBitVectorTokenArray = symbolSuccinctBitVectorTokenArray,
            succinctBitVectorTangoLBS = symbolSuccinctBitVectorTangoLBS,
            type = 13 // 記号タイプ
        )

        // 6. 単漢字辞書から候補を生成します。
        val singleKanjiListDeferred = deferredFromDictionarySingleKanji(
            input = input,
            yomiTrie = singleKanjiYomiTrie, tokenArray = singleKanjiTokenArray, tangoTrie = singleKanjiTangoTrie,
            succinctBitVectorLBSYomi = singleKanjiSuccinctBitVectorLBSYomi,
            succinctBitVectorIsLeafYomi = singleKanjiSuccinctBitVectorIsLeafYomi,
            succinctBitVectorTokenArray = singleKanjiSuccinctBitVectorTokenArray,
            succinctBitVectorTangoLBS = singleKanjiSuccinctBitVectorTangoLBS,
            type = 7 // 単漢字タイプ
        )

        // 7. 入力が記号のみの場合、半角記号の候補も追加します。
        val symbolCommonPrefixDeferredHalfWidth =
            if (input.all { !it.isLetterOrDigit() && !it.isWhitespace() }) listOf(input.convertFullWidthToHalfWidth())
            else emptyList()
        val symbolHalfWidthListDeferred =
            if (symbolCommonPrefixDeferredHalfWidth.isEmpty()) emptyList() else deferredFromDictionary(
                commonPrefixListString = symbolCommonPrefixDeferredHalfWidth,
                yomiTrie = symbolYomiTrie, tokenArray = symbolTokenArray, tangoTrie = symbolTangoTrie,
                succinctBitVectorLBSYomi = symbolSuccinctBitVectorLBSYomi,
                succinctBitVectorIsLeafYomi = symbolSuccinctBitVectorIsLeafYomi,
                succinctBitVectorTokenArray = symbolSuccinctBitVectorTokenArray,
                succinctBitVectorTangoLBS = symbolSuccinctBitVectorTangoLBS,
                type = 21 // 半角記号タイプ
            )

        // 8. 入力が英字のみの場合、英語エンジンの候補を追加します。
        val englishDeferred = if (input.isAllEnglishLetters()) {
            englishEngine.getCandidates(input, n)
        } else {
            emptyList()
        }

        // 9. 入力が1文字の場合、基本的な候補のみを返します (処理負荷軽減のため)。
        if (input.length == 1) return resultNBestFinalDeferred + englishDeferred + hirakanaAndKana + emojiListDeferred + emoticonListDeferred + symbolListDeferred + symbolHalfWidthListDeferred + singleKanjiListDeferred

        // 10. システム辞書からの予測変換 (入力文字列が部分的に一致する単語の候補)。
        val yomiPartOfDeferred = if (input.length > 16) { // 長すぎる入力は予測対象外
            emptyList()
        } else {
            systemYomiTrie.commonPrefixSearch(
                str = input,
                succinctBitVector = systemSuccinctBitVectorLBSYomi
            ).asReversed()
        }

        // 11. システム辞書からの前方一致予測変換。
        val predictiveSearchDeferred = deferredPrediction(
            input = input,
            yomiTrie = systemYomiTrie,
            succinctBitVector = systemSuccinctBitVectorLBSYomi
        )

        // 12. 読み補正辞書、ことわざ辞書からの予測変換。
        val readingCorrectionCommonPrefixDeferred = deferredPrediction(
            input = input,
            yomiTrie = readingCorrectionYomiTrie,
            succinctBitVector = readingCorrectionSuccinctBitVectorLBSYomi
        )
        val kotowazaCommonPrefixDeferred = deferredPrediction(
            input = input,
            yomiTrie = kotowazaYomiTrie,
            succinctBitVector = kotowazaSuccinctBitVectorLBSYomi
        )

        // 13. 予測変換の結果を実際の候補リストに変換します。
        val predictiveSearchResult: List<Candidate> =
            predictiveSearchDeferred
                .filter { it.length != input.length } // 完全一致は除く (Viterbiで処理済みのため)
                .flatMap { yomi ->
                    // トライ木からタームIDを取得し、トークン配列を参照して候補を生成
                    val nodeIndex = systemYomiTrie.getNodeIndex(yomi, succinctBitVector = systemSuccinctBitVectorLBSYomi)
                    val termId = systemYomiTrie.getTermId(nodeIndex, systemSuccinctBitVectorIsLeafYomi)
                    systemTokenArray
                        .getListDictionaryByYomiTermId(termId, succinctBitVector = systemSuccinctBitVectorTokenArray)
                        .map { token ->
                            // スコア計算 (入力長と読みの長さの差で調整)
                            val baseCost = token.wordCost.toInt()
                            val score = when {
                                yomi.length == input.length -> baseCost
                                input.length <= 5 -> baseCost + SCORE_OFFSET * (yomi.length - input.length)
                                else -> baseCost + SCORE_OFFSET_SMALL
                            }
                            Candidate(
                                string = when (token.nodeId) { // 単語文字列の取得
                                    -2 -> yomi // 読みそのもの
                                    -1 -> yomi.hiraToKata() // カタカナ
                                    else -> systemTangoTrie.getLetter(token.nodeId, systemSuccinctBitVectorTangoLBS)
                                },
                                type = 9, // 予測変換タイプ
                                length = yomi.length.toUByte(),
                                score = score,
                                leftId = systemTokenArray.leftIds[token.posTableIndex.toInt()],
                                rightId = systemTokenArray.rightIds[token.posTableIndex.toInt()]
                            )
                        }
                }
                .sortedBy { it.score } // スコアでソート
                .take(n) // 上位n件を取得

        // 14. 部分一致した読みに対する候補リストを生成します。
        val yomiPartListDeferred: List<Candidate> = yomiPartOfDeferred.flatMap { yomi ->
            val termId = systemYomiTrie.getTermId(
                systemYomiTrie.getNodeIndex(yomi, systemSuccinctBitVectorLBSYomi),
                systemSuccinctBitVectorIsLeafYomi
            )
            systemTokenArray.getListDictionaryByYomiTermId(termId, succinctBitVector = systemSuccinctBitVectorTokenArray)
                .map {
                    Candidate(
                        string = when (it.nodeId) {
                            -2 -> yomi
                            -1 -> yomi.hiraToKata()
                            else -> systemTangoTrie.getLetter(it.nodeId, systemSuccinctBitVectorTangoLBS)
                        },
                        type = if (yomi.length == input.length) 2 else 5, // タイプ設定 (完全一致か部分一致か)
                        length = yomi.length.toUByte(),
                        score = it.wordCost.toInt(),
                        leftId = systemTokenArray.leftIds[it.posTableIndex.toInt()],
                        rightId = systemTokenArray.rightIds[it.posTableIndex.toInt()]
                    )
                }
        }

        // 15. 読み補正辞書、ことわざ辞書からの候補リストを生成します。
        val readingCorrectionListDeferred: List<Candidate> =
            readingCorrectionCommonPrefixDeferred.flatMap { yomi ->
                // (predictiveSearchResult と同様の処理)
                val termId = readingCorrectionYomiTrie.getTermIdShortArray(
                    readingCorrectionYomiTrie.getNodeIndex(yomi, readingCorrectionSuccinctBitVectorLBSYomi),
                    readingCorrectionSuccinctBitVectorIsLeafYomi
                )
                readingCorrectionTokenArray.getListDictionaryByYomiTermIdShortArray(termId, readingCorrectionSuccinctBitVectorTokenArray)
                    .map {
                        Candidate(
                            string = when (it.nodeId) {
                                -2 -> yomi
                                -1 -> yomi.hiraToKata()
                                else -> readingCorrectionTangoTrie.getLetterShortArray(it.nodeId, readingCorrectionSuccinctBitVectorTangoLBS)
                            },
                            type = 15, // 読み補正タイプ
                            length = yomi.length.toUByte(),
                            score = if (yomi.length == input.length) it.wordCost.toInt() + 4000 else it.wordCost.toInt() + SCORE_OFFSET * (yomi.length - input.length),
                            leftId = readingCorrectionTokenArray.leftIds[it.posTableIndex.toInt()],
                            rightId = readingCorrectionTokenArray.rightIds[it.posTableIndex.toInt()]
                        )
                    }
            }
        val kotowazaListDeferred: List<Candidate> = kotowazaCommonPrefixDeferred.flatMap { yomi ->
            // (predictiveSearchResult と同様の処理)
            val termId = kotowazaYomiTrie.getTermIdShortArray(
                kotowazaYomiTrie.getNodeIndex(yomi, kotowazaSuccinctBitVectorLBSYomi),
                kotowazaSuccinctBitVectorIsLeafYomi
            )
            kotowazaTokenArray.getListDictionaryByYomiTermIdShortArray(termId, kotowazaSuccinctBitVectorTokenArray)
                .map {
                    Candidate(
                        string = when (it.nodeId) {
                            -2 -> yomi
                            -1 -> yomi.hiraToKata()
                            else -> kotowazaTangoTrie.getLetterShortArray(it.nodeId, kotowazaSuccinctBitVectorTangoLBS)
                        },
                        type = 16, // ことわざタイプ
                        length = yomi.length.toUByte(),
                        score = if (yomi.length == input.length) it.wordCost.toInt() else it.wordCost.toInt() + SCORE_OFFSET * (yomi.length - input.length),
                        leftId = kotowazaTokenArray.leftIds[it.posTableIndex.toInt()],
                        rightId = kotowazaTokenArray.rightIds[it.posTableIndex.toInt()]
                    )
                }
        }

        // 16. 「きょう」「きのう」「あした」などの入力に対して、日付関連の候補を生成します。
        val listOfDictionaryToday: List<Candidate> = when (input) {
            "きょう" -> createCandidatesForDate(Calendar.getInstance(), input)
            "きのう" -> createCandidatesForDate(Calendar.getInstance().apply { add(Calendar.DAY_OF_YEAR, -1) }, input)
            "あした" -> createCandidatesForDate(Calendar.getInstance().apply { add(Calendar.DAY_OF_YEAR, 1) }, input)
            else -> emptyList()
        }

        // 17. 数字入力に対する漢数字や桁区切りなどの候補を生成します。
        val numPair = input.toNumber()
        val expoPair = input.toNumberExponent()
        val numbersDeferred = if (numPair != null && expoPair != null) {
            // (漢数字、桁区切り、指数表記などの候補生成ロジック)
            val (firstNum, secondNum) = numPair
            val listOfNums = listOf(firstNum, secondNum)
            listOf(Candidate(string = firstNum.toLong().convertToKanjiNotation(), type = 17, length = input.length.toUByte(), score = 8000, leftId = 2040, rightId = 2040)) +
                    listOfNums.map { Candidate(string = firstNum.addCommasToNumber(), type = 19, length = input.length.toUByte(), score = 8001, leftId = 2040, rightId = 2040) } +
                    listOfNums.map { Candidate(string = it, type = 18, length = input.length.toUByte(), score = 8002, leftId = 2040, rightId = 2040) } +
                    listOf(Candidate(string = expoPair.first, type = 20, length = input.length.toUByte(), score = 8003, leftId = 2040, rightId = 2040))
        } else if (numPair != null) {
            // (漢数字、桁区切りなどの候補生成ロジック)
            val (firstNum, secondNum) = numPair
            val listOfNums = listOf(firstNum, secondNum)
            listOf(Candidate(string = firstNum.toLong().convertToKanjiNotation(), type = 17, length = input.length.toUByte(), score = 8000, leftId = 2040, rightId = 2040)) +
                    listOfNums.map { Candidate(string = firstNum.addCommasToNumber(), type = 19, length = input.length.toUByte(), score = 8001, leftId = 2040, rightId = 2040) } +
                    listOfNums.map { Candidate(string = it, type = 18, length = input.length.toUByte(), score = 8002, leftId = 2040, rightId = 2040) }
        } else {
            emptyList()
        }

        // 18. オプションのMozc UT辞書が有効な場合、それぞれの辞書から候補を取得します。
        val mozcUTPersonNames = if (mozcUtPersonName == true) getMozcUTPersonNames(input) else emptyList()
        val mozcUTPlacesList = if (mozcUTPlaces == true) getMozcUTPlace(input) else emptyList()
        val mozcUTWikiList = if (mozcUTWiki == true) getMozcUTWiki(input) else emptyList()
        val mozcUTNeologdList = if (mozcUTNeologd == true) getMozcUTNeologd(input) else emptyList()
        val mozcUTWebList = if (mozcUTWeb == true) getMozcUTWeb(input) else emptyList()

        // 19. 全ての候補リストを結合し、スコアと文字列でソートします。
        val resultList = resultNBestFinalDeferred +
                readingCorrectionListDeferred +
                predictiveSearchResult +
                kotowazaListDeferred +
                mozcUTPersonNames +
                mozcUTPlacesList +
                mozcUTWikiList +
                mozcUTNeologdList +
                mozcUTWebList
        val resultListFinal = resultList.sortedWith(
            compareBy<Candidate> { it.score }
                .thenBy { it.string }
        )

        // 20. 最終的な候補リストを返します。特定の順序で結合しています。
        return resultListFinal +
                numbersDeferred +
                symbolHalfWidthListDeferred +
                englishDeferred +
                (listOfDictionaryToday + emojiListDeferred + emoticonListDeferred).sortedBy { it.score } +
                symbolListDeferred +
                hirakanaAndKana +
                yomiPartListDeferred +
                singleKanjiListDeferred
    }

    /**
     * シンボルキーボード表示用に、絵文字辞書から全ての絵文字候補を取得します。
     * カテゴリ分類とソートも行います。
     * @return 絵文字のリスト。
     */
    /**
     * Mozc UT 人名辞書をビルド（ロード）します。
     * assetsから辞書ファイルを読み込み、メモリ上に展開します。
     * @param context アプリケーションコンテキスト。
     */
    fun buildPersonNamesDictionary(context: Context) {
        val objectInputTango =
            ObjectInputStream(BufferedInputStream(context.assets.open("person_name/tango_person_names.dat")))
        val objectInputYomi =
            ObjectInputStream(BufferedInputStream(context.assets.open("person_name/yomi_person_names.dat")))
        val objectInputTokenArray =
            ObjectInputStream(BufferedInputStream(context.assets.open("person_name/token_person_names.dat")))
        val objectInputReadPOSTable =
            ObjectInputStream(BufferedInputStream(context.assets.open("pos_table.dat")))

        this.personTangoTrie = LOUDS().readExternalNotCompress(objectInputTango)
        this.personYomiTrie = LOUDSWithTermId().readExternalNotCompress(objectInputYomi)

        this.personTokenArray = TokenArray()
        this.personTokenArray?.readExternal(objectInputTokenArray)
        this.personTokenArray?.readPOSTable(objectInputReadPOSTable)

        this.personSuccinctBitVectorLBSYomi = SuccinctBitVector(personYomiTrie!!.LBS)
        this.personSuccinctBitVectorIsLeaf = SuccinctBitVector(personYomiTrie!!.isLeaf)
        this.personSuccinctBitVectorTokenArray = SuccinctBitVector(personTokenArray!!.bitvector)
        this.personSuccinctBitVectorLBSTango = SuccinctBitVector(personTangoTrie!!.LBS)
    }

    /**
     * Mozc UT 地名辞書をビルド（ロード）します。
     * assetsから辞書ファイルを読み込み、メモリ上に展開します。
     * @param context アプリケーションコンテキスト。
     */
    fun buildPlaceDictionary(context: Context) {
        val zipInputStreamTango = ZipInputStream(context.assets.open("places/tango_places.dat.zip"))
        zipInputStreamTango.nextEntry
        ObjectInputStream(BufferedInputStream(zipInputStreamTango)).use {
            this.placesTangoTrie = LOUDS().readExternalNotCompress(it)
        }
        val zipInputStreamYomi = ZipInputStream(context.assets.open("places/yomi_places.dat.zip"))
        zipInputStreamYomi.nextEntry
        ObjectInputStream(BufferedInputStream(zipInputStreamYomi)).use {
            this.placesYomiTrie = LOUDSWithTermId().readExternalNotCompress(it)
        }

        this.placesTokenArray = TokenArray()

        ZipInputStream(context.assets.open("places/token_places.dat.zip")).use { zipStream ->
            var entry = zipStream.nextEntry
            while (entry != null) {
                if (entry.name == "token_places.dat") {
                    ObjectInputStream(BufferedInputStream(zipStream)).use { objectInput ->
                        this.placesTokenArray?.readExternal(objectInput)
                    }
                    break
                }
                entry = zipStream.nextEntry
            }
        }

        val objectInputReadPOSTable =
            ObjectInputStream(BufferedInputStream(context.assets.open("pos_table.dat")))

        this.placesTokenArray?.readPOSTable(objectInputReadPOSTable)
        this.placesSuccinctBitVectorLBSYomi = SuccinctBitVector(placesYomiTrie!!.LBS)
        this.placesSuccinctBitVectorIsLeaf = SuccinctBitVector(placesYomiTrie!!.isLeaf)
        this.placesSuccinctBitVectorTokenArray = SuccinctBitVector(placesTokenArray!!.bitvector)
        this.placesSuccinctBitVectorLBSTango = SuccinctBitVector(placesTangoTrie!!.LBS)
    }

    /**
     * Mozc UT Wikipedia辞書をビルド（ロード）します。
     * assetsから辞書ファイルを読み込み、メモリ上に展開します。
     * @param context アプリケーションコンテキスト。
     */
    fun buildWikiDictionary(context: Context) {
        val zipInputStreamTango = ZipInputStream(context.assets.open("wiki/tango_wiki.dat.zip"))
        zipInputStreamTango.nextEntry
        ObjectInputStream(BufferedInputStream(zipInputStreamTango)).use {
            this.wikiTangoTrie = LOUDS().readExternalNotCompress(it)
        }
        val zipInputStreamYomi = ZipInputStream(context.assets.open("wiki/yomi_wiki.dat.zip"))
        zipInputStreamYomi.nextEntry
        ObjectInputStream(BufferedInputStream(zipInputStreamYomi)).use {
            this.wikiYomiTrie = LOUDSWithTermId().readExternalNotCompress(it)
        }

        this.wikiTokenArray = TokenArray()

        ZipInputStream(context.assets.open("wiki/token_wiki.dat.zip")).use { zipStream ->
            var entry = zipStream.nextEntry
            while (entry != null) {
                if (entry.name == "token_wiki.dat") {
                    ObjectInputStream(BufferedInputStream(zipStream)).use { objectInput ->
                        this.wikiTokenArray?.readExternal(objectInput)
                    }
                    break
                }
                entry = zipStream.nextEntry
            }
        }

        val objectInputReadPOSTable =
            ObjectInputStream(BufferedInputStream(context.assets.open("pos_table.dat")))

        this.wikiTokenArray?.readPOSTable(objectInputReadPOSTable)

        this.wikiSuccinctBitVectorLBSYomi = SuccinctBitVector(wikiYomiTrie!!.LBS)
        this.wikiSuccinctBitVectorIsLeaf = SuccinctBitVector(wikiYomiTrie!!.isLeaf)
        this.wikiSuccinctBitVectorTokenArray = SuccinctBitVector(wikiTokenArray!!.bitvector)
        this.wikiSuccinctBitVectorLBSTango = SuccinctBitVector(wikiTangoTrie!!.LBS)
    }

    /**
     * Mozc UT Neologd辞書をビルド（ロード）します。
     * assetsから辞書ファイルを読み込み、メモリ上に展開します。
     * @param context アプリケーションコンテキスト。
     */
    fun buildNeologdDictionary(context: Context) {
        val zipInputStreamTango =
            ZipInputStream(context.assets.open("neologd/tango_neologd.dat.zip"))
        zipInputStreamTango.nextEntry
        ObjectInputStream(BufferedInputStream(zipInputStreamTango)).use {
            this.neologdTangoTrie = LOUDS().readExternalNotCompress(it)
        }
        val zipInputStreamYomi = ZipInputStream(context.assets.open("neologd/yomi_neologd.dat.zip"))
        zipInputStreamYomi.nextEntry
        ObjectInputStream(BufferedInputStream(zipInputStreamYomi)).use {
            this.neologdYomiTrie = LOUDSWithTermId().readExternalNotCompress(it)
        }

        this.neologdTokenArray = TokenArray()

        ZipInputStream(context.assets.open("neologd/token_neologd.dat.zip")).use { zipStream ->
            var entry = zipStream.nextEntry
            while (entry != null) {
                if (entry.name == "token_neologd.dat") {
                    ObjectInputStream(BufferedInputStream(zipStream)).use { objectInput ->
                        this.neologdTokenArray?.readExternal(objectInput)
                    }
                    break
                }
                entry = zipStream.nextEntry
            }
        }

        val objectInputReadPOSTable =
            ObjectInputStream(BufferedInputStream(context.assets.open("pos_table.dat")))

        this.neologdTokenArray?.readPOSTable(objectInputReadPOSTable)

        this.neologdSuccinctBitVectorLBSYomi = SuccinctBitVector(neologdYomiTrie!!.LBS)
        this.neologdSuccinctBitVectorIsLeaf = SuccinctBitVector(neologdYomiTrie!!.isLeaf)
        this.neologdSuccinctBitVectorTokenArray = SuccinctBitVector(neologdTokenArray!!.bitvector)
        this.neologdSuccinctBitVectorLBSTango = SuccinctBitVector(neologdTangoTrie!!.LBS)
    }

    /**
     * Mozc UT Web辞書をビルド（ロード）します。
     * assetsから辞書ファイルを読み込み、メモリ上に展開します。
     * @param context アプリケーションコンテキスト。
     */
    fun buildWebDictionary(context: Context) {
        val zipInputStreamTango =
            ZipInputStream(context.assets.open("web/tango_web.dat.zip"))
        zipInputStreamTango.nextEntry
        ObjectInputStream(BufferedInputStream(zipInputStreamTango)).use {
            this.webTangoTrie = LOUDS().readExternalNotCompress(it)
        }
        val zipInputStreamYomi = ZipInputStream(context.assets.open("web/yomi_web.dat.zip"))
        zipInputStreamYomi.nextEntry
        ObjectInputStream(BufferedInputStream(zipInputStreamYomi)).use {
            this.webYomiTrie = LOUDSWithTermId().readExternalNotCompress(it)
        }

        this.webTokenArray = TokenArray()

        ZipInputStream(context.assets.open("web/token_web.dat.zip")).use { zipStream ->
            var entry = zipStream.nextEntry
            while (entry != null) {
                if (entry.name == "token_web.dat") {
                    ObjectInputStream(BufferedInputStream(zipStream)).use { objectInput ->
                        this.webTokenArray?.readExternal(objectInput)
                    }
                    break
                }
                entry = zipStream.nextEntry
            }
        }

        val objectInputReadPOSTable =
            ObjectInputStream(BufferedInputStream(context.assets.open("pos_table.dat")))

        this.webTokenArray?.readPOSTable(objectInputReadPOSTable)

        this.webSuccinctBitVectorLBSYomi = SuccinctBitVector(webYomiTrie!!.LBS)
        this.webSuccinctBitVectorIsLeaf = SuccinctBitVector(webYomiTrie!!.isLeaf)
        this.webSuccinctBitVectorTokenArray = SuccinctBitVector(webTokenArray!!.bitvector)
        this.webSuccinctBitVectorLBSTango = SuccinctBitVector(webTangoTrie!!.LBS)
    }

    /** Mozc UT 人名辞書のリソースを解放します。 */
    fun releasePersonNamesDictionary() {
        this.personTangoTrie = null
        this.personYomiTrie = null
        this.personTokenArray = null
        this.personSuccinctBitVectorLBSYomi = null
        this.personSuccinctBitVectorIsLeaf = null
        this.personSuccinctBitVectorTokenArray = null
        this.personSuccinctBitVectorLBSTango = null
    }

    /** Mozc UT 地名辞書のリソースを解放します。 */
    fun releasePlacesDictionary() {
        this.placesTangoTrie = null
        this.placesYomiTrie = null
        this.placesTokenArray = null
        this.placesSuccinctBitVectorLBSYomi = null
        this.placesSuccinctBitVectorIsLeaf = null
        this.placesSuccinctBitVectorTokenArray = null
        this.placesSuccinctBitVectorLBSTango = null
    }

    /** Mozc UT Wikipedia辞書のリソースを解放します。 */
    fun releaseWikiDictionary() {
        this.wikiTangoTrie = null
        this.wikiYomiTrie = null
        this.wikiTokenArray = null
        this.wikiSuccinctBitVectorLBSYomi = null
        this.wikiSuccinctBitVectorIsLeaf = null
        this.wikiSuccinctBitVectorTokenArray = null
        this.wikiSuccinctBitVectorLBSTango = null
    }

    /** Mozc UT Neologd辞書のリソースを解放します。 */
    fun releaseNeologdDictionary() {
        this.neologdTangoTrie = null
        this.neologdYomiTrie = null
        this.neologdTokenArray = null
        this.neologdSuccinctBitVectorLBSYomi = null
        this.neologdSuccinctBitVectorIsLeaf = null
        this.neologdSuccinctBitVectorTokenArray = null
        this.neologdSuccinctBitVectorLBSTango = null
    }

    /** Mozc UT Web辞書のリソースを解放します。 */
    fun releaseWebDictionary() {
        this.webTangoTrie = null
        this.webYomiTrie = null
        this.webTokenArray = null
        this.webSuccinctBitVectorLBSYomi = null
        this.webSuccinctBitVectorIsLeaf = null
        this.webSuccinctBitVectorTokenArray = null
        this.webSuccinctBitVectorLBSTango = null
    }

    /** Mozc UT 人名辞書が初期化済みかどうかを返します。 */
    fun isMozcUTPersonDictionariesInitialized(): Boolean {
        return !(this.personYomiTrie == null || this.personTangoTrie == null || this.personTokenArray == null)
    }

    /** Mozc UT 地名辞書が初期化済みかどうかを返します。 */
    fun isMozcUTPlacesDictionariesInitialized(): Boolean {
        return !(this.placesYomiTrie == null || this.placesTangoTrie == null || this.placesTokenArray == null)
    }

    /** Mozc UT Wikipedia辞書が初期化済みかどうかを返します。 */
    fun isMozcUTWikiDictionariesInitialized(): Boolean {
        return !(this.wikiYomiTrie == null || this.wikiTangoTrie == null || this.wikiTokenArray == null)
    }

    /** Mozc UT Neologd辞書が初期化済みかどうかを返します。 */
    fun isMozcUTNeologdDictionariesInitialized(): Boolean {
        return !(this.neologdYomiTrie == null || this.neologdTangoTrie == null || this.neologdTokenArray == null)
    }

    /** Mozc UT Web辞書が初期化済みかどうかを返します。 */
    fun isMozcUTWebDictionariesInitialized(): Boolean {
        return !(this.webYomiTrie == null || this.webTangoTrie == null || this.webTokenArray == null)
    }

    /**
     * 入力された読み文字列から変換候補のリストを生成します。
     *
     * @param input 変換対象の読み文字列。
     * @param n 取得する候補の最大数。
     * @param mozcUtPersonName Mozc UT 人名辞書を使用するかどうか。
     * @param mozcUTPlaces Mozc UT 地名辞書を使用するかどうか。
     * @param mozcUTWiki Mozc UT Wikipedia辞書を使用するかどうか。
     * @param mozcUTNeologd Mozc UT Neologd辞書を使用するかどうか。
     * @param mozcUTWeb Mozc UT Web辞書を使用するかどうか。
     * @param userDictionaryRepository ユーザー辞書リポジトリ。
     * @param learnRepository 学習辞書リポジトリ（null許容）。
     * @return 生成された変換候補のリスト。
     */
    suspend fun getCandidates(
        input: String,
        n: Int,
        mozcUtPersonName: Boolean?,
        mozcUTPlaces: Boolean?,
        mozcUTWiki: Boolean?,
        mozcUTNeologd: Boolean?,
        mozcUTWeb: Boolean?,
        userDictionaryRepository: UserDictionaryRepository,
        learnRepository: LearnRepository?
    ): List<Candidate> {

        // 1. Viterbiアルゴリズムを用いて、システム辞書から基本的な変換候補を生成します。
        //    ユーザー辞書や学習辞書も考慮されます。
        val graph = graphBuilder.constructGraph(
            input,
            systemYomiTrie,
            systemTangoTrie,
            systemTokenArray,
            succinctBitVectorLBSYomi = systemSuccinctBitVectorLBSYomi,
            succinctBitVectorIsLeafYomi = systemSuccinctBitVectorIsLeafYomi,
            succinctBitVectorTokenArray = systemSuccinctBitVectorTokenArray,
            succinctBitVectorTangoLBS = systemSuccinctBitVectorTangoLBS,
            userDictionaryRepository = userDictionaryRepository,
            learnRepository = learnRepository
        )
        val resultNBestFinalDeferred: List<Candidate> =
            findPath.backwardAStar(graph, input.length, connectionIds, n)

        // 2. 入力が数字のみの場合、全角数字の候補を追加します。
        if (input.isDigitsOnly()) {
            val fullWidth = Candidate(
                string = input.toFullWidthDigitsEfficient(),
                type = 22, // 数字タイプ
                length = input.length.toUByte(),
                score = 8000,
                leftId = 2040, // 数字用のID
                rightId = 2040
            )
            return resultNBestFinalDeferred + fullWidth
        }

        // 3. ひらがなとカタカナの候補を生成します。
        val hirakanaAndKana = listOf(
            Candidate(input, 3, input.length.toUByte(), 6000), // ひらがな
            Candidate(input.hiraToKata(), 4, input.length.toUByte(), 6000) // カタカナ
        )

        // 4. 絵文字、顔文字、記号の予測変換を行います。
        //    入力文字列の共通接頭辞を持つものを検索します。
        val emojiCommonPrefixDeferred = deferredPredictionEmojiSymbols(
            input = input,
            yomiTrie = emojiYomiTrie,
            succinctBitVector = emojiSuccinctBitVectorLBSYomi
        )
        val emoticonCommonPrefixDeferred = deferredPredictionEmojiSymbols(
            input = input,
            yomiTrie = emoticonYomiTrie,
            succinctBitVector = emoticonSuccinctBitVectorLBSYomi,
        )
        val symbolCommonPrefixDeferred = deferredPredictionEmojiSymbols(
            input = input,
            yomiTrie = symbolYomiTrie,
            succinctBitVector = symbolSuccinctBitVectorLBSYomi,
        )

        // 5. 上記の共通接頭辞検索結果から、実際の絵文字、顔文字、記号の候補を生成します。
        val emojiListDeferred = deferredFromDictionarySymbols(
            input = input,
            commonPrefixListString = emojiCommonPrefixDeferred,
            yomiTrie = emojiYomiTrie, tokenArray = emojiTokenArray, tangoTrie = emojiTangoTrie,
            succinctBitVectorLBSYomi = emojiSuccinctBitVectorLBSYomi,
            succinctBitVectorIsLeafYomi = emojiSuccinctBitVectorIsLeafYomi,
            succinctBitVectorTokenArray = emojiSuccinctBitVectorTokenArray,
            succinctBitVectorTangoLBS = emojiSuccinctBitVectorTangoLBS,
            type = 11 // 絵文字タイプ
        )
        val emoticonListDeferred = deferredFromDictionarySymbols(
            input = input,
            commonPrefixListString = emoticonCommonPrefixDeferred,
            yomiTrie = emoticonYomiTrie, tokenArray = emoticonTokenArray, tangoTrie = emoticonTangoTrie,
            succinctBitVectorLBSYomi = emoticonSuccinctBitVectorLBSYomi,
            succinctBitVectorIsLeafYomi = emoticonSuccinctBitVectorIsLeafYomi,
            succinctBitVectorTokenArray = emoticonSuccinctBitVectorTokenArray,
            succinctBitVectorTangoLBS = emoticonSuccinctBitVectorTangoLBS,
            type = 12 // 顔文字タイプ
        )
        val symbolListDeferred = deferredFromDictionarySymbols(
            input = input,
            commonPrefixListString = symbolCommonPrefixDeferred,
            yomiTrie = symbolYomiTrie, tokenArray = symbolTokenArray, tangoTrie = symbolTangoTrie,
            succinctBitVectorLBSYomi = symbolSuccinctBitVectorLBSYomi,
            succinctBitVectorIsLeafYomi = symbolSuccinctBitVectorIsLeafYomi,
            succinctBitVectorTokenArray = symbolSuccinctBitVectorTokenArray,
            succinctBitVectorTangoLBS = symbolSuccinctBitVectorTangoLBS,
            type = 13 // 記号タイプ
        )

        // 6. 単漢字辞書から候補を生成します。
        val singleKanjiListDeferred = deferredFromDictionarySingleKanji(
            input = input,
            yomiTrie = singleKanjiYomiTrie, tokenArray = singleKanjiTokenArray, tangoTrie = singleKanjiTangoTrie,
            succinctBitVectorLBSYomi = singleKanjiSuccinctBitVectorLBSYomi,
            succinctBitVectorIsLeafYomi = singleKanjiSuccinctBitVectorIsLeafYomi,
            succinctBitVectorTokenArray = singleKanjiSuccinctBitVectorTokenArray,
            succinctBitVectorTangoLBS = singleKanjiSuccinctBitVectorTangoLBS,
            type = 7 // 単漢字タイプ
        )

        // 7. 入力が記号のみの場合、半角記号の候補も追加します。
        val symbolCommonPrefixDeferredHalfWidth =
            if (input.all { !it.isLetterOrDigit() && !it.isWhitespace() }) listOf(input.convertFullWidthToHalfWidth())
            else emptyList()
        val symbolHalfWidthListDeferred =
            if (symbolCommonPrefixDeferredHalfWidth.isEmpty()) emptyList() else deferredFromDictionary(
                commonPrefixListString = symbolCommonPrefixDeferredHalfWidth,
                yomiTrie = symbolYomiTrie, tokenArray = symbolTokenArray, tangoTrie = symbolTangoTrie,
                succinctBitVectorLBSYomi = symbolSuccinctBitVectorLBSYomi,
                succinctBitVectorIsLeafYomi = symbolSuccinctBitVectorIsLeafYomi,
                succinctBitVectorTokenArray = symbolSuccinctBitVectorTokenArray,
                succinctBitVectorTangoLBS = symbolSuccinctBitVectorTangoLBS,
                type = 21 // 半角記号タイプ
            )

        // 8. 入力が英字のみの場合、英語エンジンの候補を追加します。
        val englishDeferred = if (input.isAllEnglishLetters()) {
            englishEngine.getCandidates(input, n)
        } else {
            emptyList()
        }

        // 9. 入力が1文字の場合、基本的な候補のみを返します (処理負荷軽減のため)。
        if (input.length == 1) return resultNBestFinalDeferred + englishDeferred + hirakanaAndKana + emojiListDeferred + emoticonListDeferred + symbolListDeferred + symbolHalfWidthListDeferred + singleKanjiListDeferred

        // 10. システム辞書からの予測変換 (入力文字列が部分的に一致する単語の候補)。
        val yomiPartOfDeferred = if (input.length > 16) { // 長すぎる入力は予測対象外
            emptyList()
        } else {
            systemYomiTrie.commonPrefixSearch(
                str = input,
                succinctBitVector = systemSuccinctBitVectorLBSYomi
            ).asReversed()
        }

        // 11. システム辞書からの前方一致予測変換。
        val predictiveSearchDeferred = deferredPrediction(
            input = input,
            yomiTrie = systemYomiTrie,
            succinctBitVector = systemSuccinctBitVectorLBSYomi
        )

        // 12. 読み補正辞書、ことわざ辞書からの予測変換。
        val readingCorrectionCommonPrefixDeferred = deferredPrediction(
            input = input,
            yomiTrie = readingCorrectionYomiTrie,
            succinctBitVector = readingCorrectionSuccinctBitVectorLBSYomi
        )
        val kotowazaCommonPrefixDeferred = deferredPrediction(
            input = input,
            yomiTrie = kotowazaYomiTrie,
            succinctBitVector = kotowazaSuccinctBitVectorLBSYomi
        )

        // 13. 予測変換の結果を実際の候補リストに変換します。
        val predictiveSearchResult: List<Candidate> =
            predictiveSearchDeferred
                .filter { it.length != input.length } // 完全一致は除く (Viterbiで処理済みのため)
                .flatMap { yomi ->
                    // トライ木からタームIDを取得し、トークン配列を参照して候補を生成
                    val nodeIndex = systemYomiTrie.getNodeIndex(yomi, succinctBitVector = systemSuccinctBitVectorLBSYomi)
                    val termId = systemYomiTrie.getTermId(nodeIndex, systemSuccinctBitVectorIsLeafYomi)
                    systemTokenArray
                        .getListDictionaryByYomiTermId(termId, succinctBitVector = systemSuccinctBitVectorTokenArray)
                        .map { token ->
                            // スコア計算 (入力長と読みの長さの差で調整)
                            val baseCost = token.wordCost.toInt()
                            val score = when {
                                yomi.length == input.length -> baseCost
                                input.length <= 5 -> baseCost + SCORE_OFFSET * (yomi.length - input.length)
                                else -> baseCost + SCORE_OFFSET_SMALL
                            }
                            Candidate(
                                string = when (token.nodeId) { // 単語文字列の取得
                                    -2 -> yomi // 読みそのもの
                                    -1 -> yomi.hiraToKata() // カタカナ
                                    else -> systemTangoTrie.getLetter(token.nodeId, systemSuccinctBitVectorTangoLBS)
                                },
                                type = 9, // 予測変換タイプ
                                length = yomi.length.toUByte(),
                                score = score,
                                leftId = systemTokenArray.leftIds[token.posTableIndex.toInt()],
                                rightId = systemTokenArray.rightIds[token.posTableIndex.toInt()]
                            )
                        }
                }
                .sortedBy { it.score } // スコアでソート
                .take(n) // 上位n件を取得

        // 14. 部分一致した読みに対する候補リストを生成します。
        val yomiPartListDeferred: List<Candidate> = yomiPartOfDeferred.flatMap { yomi ->
            val termId = systemYomiTrie.getTermId(
                systemYomiTrie.getNodeIndex(yomi, systemSuccinctBitVectorLBSYomi),
                systemSuccinctBitVectorIsLeafYomi
            )
            systemTokenArray.getListDictionaryByYomiTermId(termId, succinctBitVector = systemSuccinctBitVectorTokenArray)
                .map {
                    Candidate(
                        string = when (it.nodeId) {
                            -2 -> yomi
                            -1 -> yomi.hiraToKata()
                            else -> systemTangoTrie.getLetter(it.nodeId, systemSuccinctBitVectorTangoLBS)
                        },
                        type = if (yomi.length == input.length) 2 else 5, // タイプ設定 (完全一致か部分一致か)
                        length = yomi.length.toUByte(),
                        score = it.wordCost.toInt(),
                        leftId = systemTokenArray.leftIds[it.posTableIndex.toInt()],
                        rightId = systemTokenArray.rightIds[it.posTableIndex.toInt()]
                    )
                }
        }

        // 15. 読み補正辞書、ことわざ辞書からの候補リストを生成します。
        val readingCorrectionListDeferred: List<Candidate> =
            readingCorrectionCommonPrefixDeferred.flatMap { yomi ->
                // (predictiveSearchResult と同様の処理)
                val termId = readingCorrectionYomiTrie.getTermIdShortArray(
                    readingCorrectionYomiTrie.getNodeIndex(yomi, readingCorrectionSuccinctBitVectorLBSYomi),
                    readingCorrectionSuccinctBitVectorIsLeafYomi
                )
                readingCorrectionTokenArray.getListDictionaryByYomiTermIdShortArray(termId, readingCorrectionSuccinctBitVectorTokenArray)
                    .map {
                        Candidate(
                            string = when (it.nodeId) {
                                -2 -> yomi
                                -1 -> yomi.hiraToKata()
                                else -> readingCorrectionTangoTrie.getLetterShortArray(it.nodeId, readingCorrectionSuccinctBitVectorTangoLBS)
                            },
                            type = 15, // 読み補正タイプ
                            length = yomi.length.toUByte(),
                            score = if (yomi.length == input.length) it.wordCost.toInt() + 4000 else it.wordCost.toInt() + SCORE_OFFSET * (yomi.length - input.length),
                            leftId = readingCorrectionTokenArray.leftIds[it.posTableIndex.toInt()],
                            rightId = readingCorrectionTokenArray.rightIds[it.posTableIndex.toInt()]
                        )
                    }
            }
        val kotowazaListDeferred: List<Candidate> = kotowazaCommonPrefixDeferred.flatMap { yomi ->
            // (predictiveSearchResult と同様の処理)
            val termId = kotowazaYomiTrie.getTermIdShortArray(
                kotowazaYomiTrie.getNodeIndex(yomi, kotowazaSuccinctBitVectorLBSYomi),
                kotowazaSuccinctBitVectorIsLeafYomi
            )
            kotowazaTokenArray.getListDictionaryByYomiTermIdShortArray(termId, kotowazaSuccinctBitVectorTokenArray)
                .map {
                    Candidate(
                        string = when (it.nodeId) {
                            -2 -> yomi
                            -1 -> yomi.hiraToKata()
                            else -> kotowazaTangoTrie.getLetterShortArray(it.nodeId, kotowazaSuccinctBitVectorTangoLBS)
                        },
                        type = 16, // ことわざタイプ
                        length = yomi.length.toUByte(),
                        score = if (yomi.length == input.length) it.wordCost.toInt() else it.wordCost.toInt() + SCORE_OFFSET * (yomi.length - input.length),
                        leftId = kotowazaTokenArray.leftIds[it.posTableIndex.toInt()],
                        rightId = kotowazaTokenArray.rightIds[it.posTableIndex.toInt()]
                    )
                }
        }

        // 16. 「きょう」「きのう」「あした」などの入力に対して、日付関連の候補を生成します。
        val listOfDictionaryToday: List<Candidate> = when (input) {
            "きょう" -> createCandidatesForDate(Calendar.getInstance(), input)
            "きのう" -> createCandidatesForDate(Calendar.getInstance().apply { add(Calendar.DAY_OF_YEAR, -1) }, input)
            "あした" -> createCandidatesForDate(Calendar.getInstance().apply { add(Calendar.DAY_OF_YEAR, 1) }, input)
            else -> emptyList()
        }

        // 17. 数字入力に対する漢数字や桁区切りなどの候補を生成します。
        val numPair = input.toNumber()
        val expoPair = input.toNumberExponent()
        val numbersDeferred = if (numPair != null && expoPair != null) {
            // (漢数字、桁区切り、指数表記などの候補生成ロジック)
            val (firstNum, secondNum) = numPair
            val listOfNums = listOf(firstNum, secondNum)
            listOf(Candidate(string = firstNum.toLong().convertToKanjiNotation(), type = 17, length = input.length.toUByte(), score = 8000, leftId = 2040, rightId = 2040)) +
                    listOfNums.map { Candidate(string = firstNum.addCommasToNumber(), type = 19, length = input.length.toUByte(), score = 8001, leftId = 2040, rightId = 2040) } +
                    listOfNums.map { Candidate(string = it, type = 18, length = input.length.toUByte(), score = 8002, leftId = 2040, rightId = 2040) } +
                    listOf(Candidate(string = expoPair.first, type = 20, length = input.length.toUByte(), score = 8003, leftId = 2040, rightId = 2040))
        } else if (numPair != null) {
            // (漢数字、桁区切りなどの候補生成ロジック)
            val (firstNum, secondNum) = numPair
            val listOfNums = listOf(firstNum, secondNum)
            listOf(Candidate(string = firstNum.toLong().convertToKanjiNotation(), type = 17, length = input.length.toUByte(), score = 8000, leftId = 2040, rightId = 2040)) +
                    listOfNums.map { Candidate(string = firstNum.addCommasToNumber(), type = 19, length = input.length.toUByte(), score = 8001, leftId = 2040, rightId = 2040) } +
                    listOfNums.map { Candidate(string = it, type = 18, length = input.length.toUByte(), score = 8002, leftId = 2040, rightId = 2040) }
        } else {
            emptyList()
        }

        // 18. オプションのMozc UT辞書が有効な場合、それぞれの辞書から候補を取得します。
        val mozcUTPersonNames = if (mozcUtPersonName == true) getMozcUTPersonNames(input) else emptyList()
        val mozcUTPlacesList = if (mozcUTPlaces == true) getMozcUTPlace(input) else emptyList()
        val mozcUTWikiList = if (mozcUTWiki == true) getMozcUTWiki(input) else emptyList()
        val mozcUTNeologdList = if (mozcUTNeologd == true) getMozcUTNeologd(input) else emptyList()
        val mozcUTWebList = if (mozcUTWeb == true) getMozcUTWeb(input) else emptyList()

        // 19. 全ての候補リストを結合し、スコアと文字列でソートします。
        val resultList = resultNBestFinalDeferred +
                readingCorrectionListDeferred +
                predictiveSearchResult +
                kotowazaListDeferred +
                mozcUTPersonNames +
                mozcUTPlacesList +
                mozcUTWikiList +
                mozcUTNeologdList +
                mozcUTWebList
        val resultListFinal = resultList.sortedWith(
            compareBy<Candidate> { it.score }
                .thenBy { it.string }
        )

        // 20. 最終的な候補リストを返します。特定の順序で結合しています。
        return resultListFinal +
                numbersDeferred +
                symbolHalfWidthListDeferred +
                englishDeferred +
                (listOfDictionaryToday + emojiListDeferred + emoticonListDeferred).sortedBy { it.score } +
                symbolListDeferred +
                hirakanaAndKana +
                yomiPartListDeferred +
                singleKanjiListDeferred
    }

    /**
     * シンボルキーボード表示用に、絵文字辞書から全ての絵文字候補を取得します。
     * カテゴリ分類とソートも行います。
     * @return 絵文字のリスト。
     */
    fun getSymbolEmojiCandidates(): List<Emoji> = emojiTokenArray
