# rclone で OneDrive を端末に同期する（Azure登録不要・無料）

## なぜこの方式か

Microsoft は Entra ID テナントの作成を課金アカウントに紐付けるようになり、
無料でもクレジットカードの本人確認が要る。
**rclone は OneDrive 用のクライアントIDを内蔵している**ので、
アプリ登録なしで認証できる。

副次的な利点として、アプリからOAuthコードが丸ごと消える。
同期元を Google Drive や Dropbox に変えても、アプリ側は無改造で動く。

## 1. Termux 側の準備

```
pkg install rclone
termux-setup-storage
```

`termux-setup-storage` は共有ストレージへのアクセス許可を出す。
これをやらないと `~/storage/shared` が作られない。

## 2. rclone の設定

```
rclone config
```

対話で以下を選ぶ:

- `n` (New remote)
- name: `od`
- Storage: 一覧から `onedrive` の番号
- **client_id: 空のままEnter**（rclone内蔵のIDが使われる。ここが肝）
- client_secret: 空のままEnter
- region: `1` (Microsoft Cloud Global)
- Edit advanced config: `n`
- Use web browser to automatically authenticate: **`y`**
  - ブラウザが開くのでMicrosoftアカウントでサインインして承認
- config type: `1` (OneDrive Personal or Business)
- 表示されたドライブでよければ `y`
- `y` で保存 → `q` で終了

## 3. 同期

```
mkdir -p ~/storage/shared/RAG
rclone sync od:RAG ~/storage/shared/RAG --progress
```

`od:RAG` は OneDrive のルート直下の `RAG` フォルダ。
別の場所なら `od:Documents/RAG` のように書く。

**`sync` は削除も反映する**（OneDrive側で消したファイルはローカルからも消える）。
消えるのが怖い場合は `copy` にする。

## 4. アプリ側

1. Bonsai → RAG設定 → **フォルダを選択**
2. ピッカーで `RAG` フォルダ（内部ストレージ直下）を選ぶ
3. **インデックス更新**

2回目以降は mtime とサイズを比較するので、
変更のないファイルは読み込まれない（「変更なし N」に出る）。

## 5. 自動化（任意）

Termux から定期実行する場合:

```
pkg install termux-api cronie
crontab -e
```

```
*/30 * * * * rclone sync od:RAG ~/storage/shared/RAG
```

`crond` を起動しておくこと。電池最適化の除外も必要。

## 詰まりどころ

- **フォルダ選択で Termux のホーム (`/data/data/com.termux/...`) は選べない。**
  SAF からは見えないので、必ず `~/storage/shared/` 配下（= 内部ストレージ）へ同期する
- rclone の認証でブラウザが開かない場合、`rclone authorize "onedrive"` を
  別途実行して出力を貼る方式でも通る
- 対象は `.txt` / `.md` / `.markdown` / `.text` のみ、2MB上限
