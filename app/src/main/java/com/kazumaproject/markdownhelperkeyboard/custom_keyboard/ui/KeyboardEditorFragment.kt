package com.kazumaproject.markdownhelperkeyboard.custom_keyboard.ui

import android.os.Bundle
import android.view.Menu
import android.view.MenuInflater
import android.view.MenuItem
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.MenuHost
import androidx.core.view.MenuProvider
import androidx.core.view.isVisible
import androidx.core.widget.doAfterTextChanged
import androidx.fragment.app.Fragment
import androidx.hilt.navigation.fragment.hiltNavGraphViewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.fragment.findNavController
import androidx.navigation.fragment.navArgs
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.kazumaproject.markdownhelperkeyboard.R
import com.kazumaproject.markdownhelperkeyboard.custom_keyboard.ui.view.EditableFlickKeyboardView
import com.kazumaproject.markdownhelperkeyboard.databinding.FragmentKeyboardEditorBinding
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import timber.log.Timber

/**
 * カスタムキーボードのレイアウトを編集するためのフラグメント。
 * ユーザーはここでキーボードの行数、列数、キーボード名、各キーの設定を行うことができます。
 */
@AndroidEntryPoint
class KeyboardEditorFragment : Fragment(R.layout.fragment_keyboard_editor),
    EditableFlickKeyboardView.OnKeyEditListener {

    // Hiltを使用してViewModelを取得。navigation graphのスコープでViewModelを共有します。
    private val viewModel: KeyboardEditorViewModel by hiltNavGraphViewModels(R.id.mobile_navigation)
    // Navigation Componentを使用して画面遷移時の引数を受け取ります。
    private val args: KeyboardEditorFragmentArgs by navArgs()

    // View Bindingのインスタンス。null許容型とし、onDestroyViewでnull化します。
    private var _binding: FragmentKeyboardEditorBinding? = null
    // _bindingがnullでないことを保証するためのゲッター。
    private val binding get() = _binding!!

    /**
     * フラグメントのビューが作成された後に呼び出されます。
     * ここでView Bindingの初期化、ツールバーとメニューの設定、
     * ViewModelの初期化、UIリスナーの設定、ViewModelの監視を開始します。
     */
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        _binding = FragmentKeyboardEditorBinding.bind(view)

        setupToolbarAndMenu() // ツールバーとメニューを設定

        viewModel.start(args.layoutId) // ViewModelに編集対象のレイアウトIDを渡して初期化
        setupUIListeners() // UI要素のリスナーを設定
        observeViewModel() // ViewModelの状態変化を監視
    }

    /**
     * ツールバー（ActionBar）とオプションメニューを設定します。
     * ActionBarのタイトル設定、戻るボタンの表示、メニューアイテムの生成と選択時の処理を行います。
     */
    private fun setupToolbarAndMenu() {
        // ActivityのActionBarを取得し、タイトルと戻るボタンを設定
        (activity as? AppCompatActivity)?.supportActionBar?.apply {
            title = getString(R.string.edit_keyboard) // スクリーンタイトルを設定
            setDisplayHomeAsUpEnabled(true) // 戻る矢印を表示
        }

        // MenuProviderを使用してメニューを処理します。これはFragmentでのメニュー処理の推奨される方法です。
        val menuHost: MenuHost = requireActivity()
        menuHost.addMenuProvider(object : MenuProvider {
            /** メニューが作成されるときに呼び出されます。ここでメニューアイテムをインフレートします。 */
            override fun onCreateMenu(menu: Menu, menuInflater: MenuInflater) {
                menuInflater.inflate(R.menu.menu_keyboard_editor, menu)
            }

            /** メニューアイテムが選択されたときに呼び出されます。 */
            override fun onMenuItemSelected(menuItem: MenuItem): Boolean {
                return when (menuItem.itemId) {
                    android.R.id.home -> { // 戻るボタン（home）が押された場合
                        findNavController().popBackStack() // 前の画面に戻る
                        viewModel.onCancelEditing() // ViewModelに編集キャンセルを通知
                        true
                    }
                    R.id.action_save -> { // 保存アクションが選択された場合
                        viewModel.saveLayout() // ViewModelにレイアウト保存を指示
                        true
                    }
                    else -> false // その他のメニューアイテムは処理しない
                }
            }
        }, viewLifecycleOwner, Lifecycle.State.RESUMED) // viewLifecycleOwnerとRESUMED状態でメニュープロバイダを紐付け
    }

    /**
     * UI要素（キーボード名入力、行/列追加・削除ボタン、キーボードビュー）のリスナーを設定します。
     */
    private fun setupUIListeners() {
        // カスタムキーボードビューのキー編集リスナーを設定
        binding.flickKeyboardView.setOnKeyEditListener(this)

        // キーボード名編集テキストの変更を監視し、ViewModelに通知
        binding.keyboardNameEdittext.doAfterTextChanged { text ->
            if (text.toString() != viewModel.uiState.value.name) {
                viewModel.updateName(text.toString())
            }
        }

        // 行/列の追加・削除ボタンのクリックリスナーを設定
        binding.buttonAddRow.setOnClickListener { viewModel.addRow() }
        binding.buttonRemoveRow.setOnClickListener { viewModel.removeRow() }
        binding.buttonAddCol.setOnClickListener { viewModel.addColumn() }
        binding.buttonRemoveCol.setOnClickListener { viewModel.removeColumn() }
    }

    /**
     * ViewModelのUI状態（[EditorUiState]）とエラー状態を監視し、UIの更新や画面遷移を行います。
     */
    private fun observeViewModel() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                // UI状態の変更を監視
                launch {
                    viewModel.uiState.collect { state ->
                        updateUi(state) // UIを更新
                        if (state.navigateBack) { // 前の画面に戻るフラグが立っていれば
                            findNavController().popBackStack() // 画面遷移
                            viewModel.onDoneNavigating() // ViewModelに遷移完了を通知
                        }
                    }
                }

                // キーボード名重複エラーを監視
                launch {
                    viewModel.uiState.collect { state ->
                        if (state.duplicateNameError) {
                            showDuplicateNameDialog() // 重複エラーダイアログを表示
                            viewModel.clearDuplicateNameError() // エラー状態をクリア
                        }
                    }
                }
            }
        }
    }

    /**
     * キーボード名が重複している場合に表示するエラーダイアログ。
     */
    private fun showDuplicateNameDialog() {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(getString(R.string.duplicate_keyboard_name_title))
            .setMessage(getString(R.string.duplicate_keyboard_name_message))
            .setPositiveButton(android.R.string.ok) { dialog, _ ->
                dialog.dismiss()
            }
            .show()
    }

    /**
     * ViewModelのUI状態に基づいてUI要素を更新します。
     * @param state 現在のUI状態。
     */
    private fun updateUi(state: EditorUiState) {
        // ローディング状態に応じて編集パネルの表示/非表示を切り替え
        if (state.isLoading) {
            binding.editModePanel.isVisible = false
            return
        }
        binding.editModePanel.isVisible = true

        // キーボード名の更新（現在のテキストと異なれば）
        if (binding.keyboardNameEdittext.text.toString() != state.name) {
            binding.keyboardNameEdittext.setText(state.name)
            binding.keyboardNameEdittext.setSelection(state.name.length) // カーソルを末尾に
        }

        // キーボードレイアウトの更新
        binding.flickKeyboardView.setKeyboard(state.layout)
    }

    /**
     * [EditableFlickKeyboardView.OnKeyEditListener] の実装。
     * カスタムキーボードビュー内のキーがタップされたときに呼び出されます。
     * @param keyId タップされたキーのID。
     */
    override fun onKeySelected(keyId: String) {
        Timber.d("onKeySelected: keyId = $keyId")
        viewModel.selectKeyForEditing(keyId) // ViewModelに選択されたキーを通知
        // キー編集画面 (KeyEditorFragment) へ遷移
        findNavController().navigate(R.id.action_keyboardEditorFragment_to_keyEditorFragment)
    }

    /**
     * フラグメントのビューが破棄されるときに呼び出されます。
     * ActionBarの変更を元に戻し、View Bindingのインスタンスをクリーンアップします。
     */
    override fun onDestroyView() {
        super.onDestroyView()
        // ActionBarのタイトルと戻るボタン表示をリセット
        (activity as? AppCompatActivity)?.supportActionBar?.apply {
            title = null
            setDisplayHomeAsUpEnabled(false)
        }
        // キーボードビューのリスナーを解除
        binding.flickKeyboardView.removeOnKeyEditListener()
        // View Bindingのインスタンスをnull化してメモリリークを防ぐ
        _binding = null
    }
}
