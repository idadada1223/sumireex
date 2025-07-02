package com.kazumaproject.markdownhelperkeyboard.setting_activity

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.navigation.findNavController
import androidx.navigation.ui.AppBarConfiguration
import androidx.navigation.ui.setupActionBarWithNavController
import androidx.navigation.ui.setupWithNavController
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.google.android.material.color.DynamicColors
import com.kazumaproject.markdownhelperkeyboard.R
import com.kazumaproject.markdownhelperkeyboard.databinding.ActivityMainBinding
import dagger.hilt.android.AndroidEntryPoint

/**
 * 設定画面のメインActivity。
 * BottomNavigationViewとNavigation Componentを利用して、各設定フラグメントへのナビゲーションを提供します。
 */
@AndroidEntryPoint
class MainActivity : AppCompatActivity() {

    // View Binding用の変数
    private lateinit var binding: ActivityMainBinding

    /**
     * Activityが作成されるときに呼び出されます。
     * Dynamic Colorsの適用、レイアウトの設定、Navigation Componentのセットアップを行います。
     * また、Intent経由で特定のフラグメントを開くための処理も行います。
     */
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Dynamic Colors (Material You) を利用可能であれば適用します。
        DynamicColors.applyToActivityIfAvailable(this)

        // View Bindingを使用してレイアウトをインフレートし、コンテンツビューとして設定します。
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // BottomNavigationViewとNavControllerを取得します。
        val navView: BottomNavigationView = binding.navView
        val navController = findNavController(R.id.nav_host_fragment_activity_main)

        // AppBarConfigurationを設定します。これにより、トップレベルのデスティネーション（画面）を指定し、
        // これらの画面ではActionBarに戻るボタン（Upボタン）が表示されなくなります。
        val appBarConfiguration = AppBarConfiguration(
            setOf(
                R.id.navigation_setting,          // 設定フラグメント
                R.id.navigation_learn_dictionary, // 学習辞書フラグメント
                R.id.navigation_user_dictionary,  // ユーザー辞書フラグメント
            )
        )
        // ActionBarとNavControllerを連携させ、AppBarConfigurationを適用します。
        setupActionBarWithNavController(navController, appBarConfiguration)
        // BottomNavigationViewとNavControllerを連携させます。
        navView.setupWithNavController(navController)

        // Intentに "openSettingActivity" というキーでExtraが含まれているか確認します。
        // これにより、他の箇所から特定のフラグメントを開くように指定できます。
        val extra = intent.getStringExtra("openSettingActivity")
        extra?.let { request ->
            when (request) {
                // "setting_fragment_request" の場合、設定フラグメントを開きます。
                "setting_fragment_request" -> {
                    navController.popBackStack() // バックスタックをクリア
                    navController.navigate(R.id.navigation_setting) // 設定フラグメントへ遷移
                }
                // "dictionary_fragment_request" の場合、学習辞書フラグメントを開きます。
                "dictionary_fragment_request" -> {
                    navController.navigate(R.id.navigation_learn_dictionary) // 学習辞書フラグメントへ遷移
                }
            }
        }
    }
}
