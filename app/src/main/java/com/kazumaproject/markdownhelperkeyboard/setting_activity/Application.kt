package com.kazumaproject.markdownhelperkeyboard.setting_activity

import android.app.Application
import com.kazumaproject.markdownhelperkeyboard.BuildConfig
import dagger.hilt.android.HiltAndroidApp
import timber.log.Timber
import java.io.File

/**
 * アプリケーションクラス。
 * Hiltによる依存性注入のルートとなり、アプリケーション全体の初期化処理を行います。
 */
@HiltAndroidApp
class Application : Application() {
    /**
     * アプリケーションが作成されるときに呼び出されます。
     * Timber（ロギングライブラリ）の初期化や、dexOutputDirの読み取り専用設定などを行います。
     */
    override fun onCreate() {
        super.onCreate()
        // デバッグビルドの場合にTimberのDebugTreeを初期化し、ログ出力を有効にします。
        if (BuildConfig.DEBUG) {
            Timber.plant(Timber.DebugTree())
        }
        // dexOutputDir (コンパイルされたDEXファイルが格納されるディレクトリ) を取得します。
        // このディレクトリを読み取り専用に設定しています。
        // これにより、ランタイムでのDEXファイルの変更を防ぎ、セキュリティを向上させる意図がある可能性があります。
        // ただし、特定の状況下では影響が出る可能性もあるため、注意が必要です。
        val dexOutputDir: File = codeCacheDir
        dexOutputDir.setReadOnly()
    }
}
