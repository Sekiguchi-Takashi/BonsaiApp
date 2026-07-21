# v0.9 — フェーズ2（Gmail受信）とサービス整理

## 1. サービスのライフサイクルを作り直した

### 要望
「サーバー停止したらバックグラウンドも起動し続けないように」

### 対応
`ServerService` を、サーバーとメール監視の**両方を抱える単一サービス**に統合し、
ON/OFF 状態を SharedPreferences で管理するようにした。

- 機能が1つでもONならサービス生存・常駐通知あり
- **すべてOFFになった時点でサービスは完全終了する**
  - 通知が消える
  - `stopForeground(STOP_FOREGROUND_REMOVE)` + `stopSelf()`
  - 画面が閉じていればモデルも解放してRAMを返す
  - `START_NOT_STICKY` を返すのでシステムも再起動しない
- OFF状態でシステムに再生成された場合は、即座に自殺する

### ついでに直した既知の穴
- **システムに殺されて再生成された時、モデルが読み込まれていなかった問題**
  → サービスが自前で `model.gguf` を読み込むようにした（`ensureModelLoaded`）
- **端末再起動後に復帰しなかった問題**
  → `BootReceiver` を追加。ただし**ユーザーが停止した機能は復帰させない**。
    保存されたON/OFF状態を見て、ONだったものだけ起動する

## 2. フェーズ2: Gmail 受信

OAuth も Google Cloud プロジェクトも使わない。
**アプリパスワード + IMAP**。トークン期限切れが原理的に発生しない。

### 追加ファイル
- `mail/ImapClient.kt` — SSLSocket で書いた最小 IMAP。
  LOGIN / SELECT / UID SEARCH / UID FETCH / **IDLE** / LOGOUT
- `mail/MimeParser.kt` — 最小 MIME パーサ
  - RFC2047 ヘッダデコード（`=?UTF-8?B?...?=` / `=?ISO-2022-JP?B?...?=`）
  - multipart から text/plain を選択、無ければ text/html をタグ除去
  - base64 / quoted-printable
  - **ISO-2022-JP / Shift_JIS 対応**（日本語メールで実際に必要）
- `mail/MailQueue.kt` — SQLite のキュー（pending / done / error）
- `mail/MailWatcher.kt` — IDLE ループ、指数バックオフ再接続、差出人フィルタ
- `MailActivity.kt` — 設定とキュー確認

### 設計上のポイント
- **ポーリングしない。** IMAP IDLE で新着を待つので通信量と電池消費が小さい
- Gmail は29分でIDLEを切るので24分ごとに張り直す
- **`BODY.PEEK[]` で取得するため既読フラグが立たない。**
  ユーザーのGmailの未読状態を汚さない
- 取得済みUIDを記録し、二重取り込みを防ぐ
- 再接続は5秒→最大5分の指数バックオフ

## セットアップ
`SETUP_GMAIL.md` を参照。要点は3つ:
- 個人の gmail.com であること
- 2段階認証が有効であること
- https://myaccount.google.com/apppasswords で16桁を発行（このURLからしか入れない）

## セキュリティ上の注意
アプリパスワードは SharedPreferences に**平文**で保存される。
`EncryptedSharedPreferences` は androidx 依存になるため今回も見送った。

## 次
**フェーズ3**: pending を拾う → BM25検索 → プロンプト組立 → 推論 → answer に書き戻し
