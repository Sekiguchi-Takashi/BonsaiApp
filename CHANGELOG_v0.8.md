# v0.8 — クラウド認証をアプリから外した

## 背景
Microsoft が Entra ID テナント作成を課金アカウントに紐付けたため、
無料アカウントでもクレジットカード登録なしでは Azure のアプリ登録
（クライアントID取得）ができなくなっていた。

## 方針変更
**アプリはクラウドAPIを持たない。** 端末内フォルダを読むだけにする。
OneDrive からの取得は Termux の rclone に任せる
（rclone は OneDrive用クライアントIDを内蔵しているため登録不要）。

## 削除
- `rag/OneDrive.kt` — device code flow、Graph delta API、トークン管理
  （git履歴には残っているので、将来クライアントIDを取得したら復活可能）
- `SETUP_ONEDRIVE.md`

## 追加
- `rag/FolderSync.kt` — SAF で選んだフォルダを再帰走査してインデックス
  - `DocumentsContract` を直接使用（androidx の DocumentFile は使わない）
  - **mtime + サイズ**を保存し、変化のないファイルは読み込まない
  - フォルダから消えたファイルはインデックスからも削除
  - 深さ8段までの再帰制限
- `SETUP_RCLONE.md` — 手順書

## 変更
- `RagActivity` — クライアントID入力とサインインを廃止し、
  フォルダ選択に置き換え
- `RagDb.allDocIds()` を追加（削除検出用）

## この変更で良くなったこと
- **Azure / GCP のアプリ登録が一切不要**
- OAuth関連のコードが消え、失敗ポイントが大幅に減った
- ストレージ権限の宣言が不要（SAFはユーザーが選んだ範囲のみ）
- 同期元を Google Drive / Dropbox / Syncthing に変えても
  **アプリ側は無改造**で動く
- 差分判定が mtime+サイズなので、クラウドのdeltaトークン管理が要らない

## トレードオフ
- 同期は Termux 側の責務になった。アプリ単体では完結しない
- rclone の定期実行は cron か termux-job-scheduler で別途設定が必要

## 次のフェーズ
フェーズ2: Gmail(IMAP + アプリパスワード)で受信 → キューに積む
フェーズ3: 受信 → BM25検索 → プロンプト組立 → Bonsai推論 → 履歴表示
