# v0.6 — OpenAI 互換サーバー内蔵

## 何が変わったか
アプリ自身が **OpenAI 互換の HTTP サーバー**になる。Termux 側で
llama-server を別途立てる必要はない。

```
http://127.0.0.1:8080/v1
```

他の自作アプリ（KakeiApp / MemoApp など）から、OpenAI SDK の
baseURL を差し替えるだけで Bonsai を推論エンジンにできる。

## 対応エンドポイント
| メソッド | パス | 備考 |
|---|---|---|
| GET  | `/v1/models` | モデル一覧（id は `bonsai`） |
| POST | `/v1/chat/completions` | `stream: true` で SSE |
| POST | `/v1/completions` | `prompt` を user メッセージへ変換 |
| GET  | `/health` | 稼働確認・busy 状態 |

対応パラメータ: `messages` / `max_tokens` / `temperature` / `top_p` /
`top_k` / `seed` / `stream`。`content` は文字列でもパーツ配列でも可。

## 依存ゼロを維持
外部ライブラリは一切追加していない。`ServerSocket` と、Android 標準同梱の
`org.json` だけで実装（org.json はプラットフォーム側なので依存に数えない）。

## 設計上のポイント
- **`Engine` シングルトン**でモデルをプロセス内に1つだけ保持。
  Activity の生成ボタンと HTTP リクエストが同じインスタンスを共有する
- `llama_context` は同時実行できないため、推論は `synchronized` で**直列化**。
  混んでいる時は後続リクエストが待つ（8Bなら数十秒待たされる）
- **フォアグラウンドサービス**で常駐。これが無いとアプリを閉じた瞬間に
  プロセスごと落ちてサーバーも死ぬ。常駐通知が出るのは Android の仕様
- サーバー稼働中は Activity を閉じてもモデルを解放しない
- SSE ストリーミング時も `<think>` ブロックを除去（タグを跨ぐトークンは
  バッファして判定）

## ネイティブ側の変更
- `nativeGenerate` が `messages[]`（role/content の並列配列）を受け取るように
- サンプラを**リクエスト単位で構築**。temperature 等を API から指定できる
- logit bias（簡体字禁止）は `Session` に保持し、毎回のサンプラに載せ直す

## セキュリティ
既定で **127.0.0.1 のみにバインド**。同一端末の他アプリからは到達でき、
同じ Wi-Fi の別端末からは到達できない。

`MainActivity.BIND_ALL = true` にすると LAN に公開できるが、
**認証機構は無い**。公開する場合は自己責任で。

## 追加した権限
- `INTERNET` — ServerSocket のバインドに必須（ローカル用途でも必要）
- `FOREGROUND_SERVICE` / `FOREGROUND_SERVICE_SPECIAL_USE`
- `POST_NOTIFICATIONS` — 常駐通知（Android 13+）

## 動作確認（Termux から）
```
curl http://127.0.0.1:8080/health

curl http://127.0.0.1:8080/v1/chat/completions \
  -H "Content-Type: application/json" \
  -d '{"model":"bonsai","messages":[{"role":"user","content":"自己紹介して"}]}'
```

Python の OpenAI SDK からはこう:
```python
from openai import OpenAI
client = OpenAI(base_url="http://127.0.0.1:8080/v1", api_key="dummy")
r = client.chat.completions.create(
    model="bonsai",
    messages=[{"role": "user", "content": "自己紹介して"}],
)
print(r.choices[0].message.content)
```
`api_key` は検証していないので任意の文字列でよい。

## 既知の制約
- 同時実行は1リクエストのみ（直列化）
- `usage` のトークン数は 0 固定（未計測）
- KV キャッシュは毎回クリアするため、マルチターンは毎回プロンプト全体を
  再評価する。会話が長くなるほど初回応答が遅くなる
