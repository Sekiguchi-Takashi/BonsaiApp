# v0.2 変更点

## 背景
Android 11 以降（検証端末は Android 17）のスコープドストレージにより、
`/sdcard/Android/data/<pkg>/files/` へ Termux からもファイルマネージャからも
書き込めない。v0.1 のモデル配置手順は成立しない。

## 対応
モデルの受け渡しを **SAF（ACTION_OPEN_DOCUMENT）** に変更。

- 「モデルを選択 (.gguf)」ボタンを追加
- 選択したファイルをアプリ内部ストレージ `filesDir/model.gguf` へコピー
- コピーは `.tmp` へ書いてから rename（中断時に壊れたモデルを掴まない）
- 8MBごとに進捗を表示
- **権限宣言ゼロ**。マニフェストに storage 系パーミッションは不要

## その他
- サンプリングを Bonsai 公式推奨値へ: temp 0.7→0.5、top-p 0.95→0.9（top-k 20 は据置）
- `llama_jni.cpp` の `static llama_token` をローカル変数に変更
- `android:extractNativeLibs` を削除（AGP の警告解消）
- 出力を選択可能に（`setTextIsSelectable`）
- versionCode 2 / versionName 0.2

## 注意
内部ストレージへコピーするため、取込時は **モデルサイズ分の空き容量**が必要
（1.7B Q1_0 で約237MB）。Download 側の元ファイルは取込後に削除して構わない。

## 使い方
1. APK を更新インストール
2. 「モデルを選択 (.gguf)」→ Download の `model.gguf` を選ぶ
3. コピー完了後、自動で読込 → 生成ボタンが有効化
