# v0.3 変更点

## 目的
Bonsai 8B (Q1_0 / 約1.15GB) を空きRAM 1.1GB の端末で動かす。
あわせて体感速度と出力品質を改善する。

## メモリ対策（8B を通すための本丸）
- `use_mmap = true` / `use_mlock = false` を明示。重みをファイルから
  随時ページインさせ、常駐RSSを抑える。**mlock は絶対に有効化しない**
- KVキャッシュを `q8_0` 化（`type_k` / `type_v`）。f16 比でほぼ半分
- `n_ctx` 2048 → **1024**
- `n_batch` / `n_ubatch` 512 → **256**（プリフィル時のピークRAM削減）
- マニフェストに `android:largeHeap="true"`
- 空きRAMをステータスに常時表示

## 品質
- **`llama_chat_apply_template` を適用**。GGUF 埋め込みテンプレートを使い、
  取得できなければ ChatML でフォールバック
- トークン化を `add_special=false` / `parse_special=true` に変更
  （テンプレートが特殊トークンを含むため。ここを直さないとBOS二重付与）
- 生成のたびに KV をクリアし、前回の文脈汚染を防止
- Qwen3 系が出す `<think>…</think>` を表示から除去

## 体感速度
- UI更新を **60ms 間引き**。1トークンごとの全文字列再設定をやめた
- **停止ボタン**を追加（2〜3 tok/s だと必須）
- `-DGGML_LTO=ON` を追加
- `MAX_TOKENS` を 512 に（停止できるので上限は緩めてよい）

## 既知のリスク
1. `llama_model_chat_template` / `llama_memory_clear` は比較的新しいAPI。
   prism ブランチのバージョン次第でコンパイルエラーの可能性あり。
   その場合は `llama.cpp/include/llama.h` を確認して合わせる
2. 8B は速度が 1.7B の 1/4 前後。2〜3 tok/s を想定しておくこと
3. swap を使い切っている状態では、読込中に OOM Killer に殺される可能性が残る。
   落ちる場合は他アプリを終了してから起動する

## 8B への差し替え手順
```
cd /sdcard/Download
rm -f model.gguf
curl -L -o model.gguf https://huggingface.co/prism-ml/Bonsai-8B-gguf/resolve/main/Bonsai-8B-Q1_0.gguf
```
アプリの「モデルを再選択」から取り込む。内部ストレージへコピーするため
**空き容量は3GB以上**必要（一時ファイルと二重に存在するタイミングがある）。

## ダメだった場合の退避先
8B が乗らなければ 4B (Q1_0 / 約0.57GB) が現実的な上限。
モデル差し替えだけで対応可能、アプリ側の変更は不要。
