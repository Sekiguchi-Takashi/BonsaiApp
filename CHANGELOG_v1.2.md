# v1.2 — RAG参照ONで落ちる問題の修正

## 症状
トップ画面で RAG参照 ON にして「生成」を押すとアプリが落ちる。
OFF なら落ちない。

## 原因
**プロンプトのトークン数が `n_batch` を超えていた。**

- RAG OFF: 質問だけなので数十トークン → `n_batch`(256) に収まる
- RAG ON: 資料900〜1100文字が加わり1500〜1800トークン
  → **1回の `llama_decode` に一括で渡していたため、
    llama.cpp 側のアサートで abort**

v0.3 で低RAM対策として `n_batch` を 512→256 に下げたことが、
RAGを入れるまで表面化していなかった。

## 修正（`llama_jni.cpp`）

### 1. プリフィルの分割
プロンプトを `n_batch` 単位に区切って `llama_decode` に渡す。

```cpp
for (size_t off = 0; off < tokens.size(); off += n_batch) {
    int n = min(n_batch, tokens.size() - off);
    llama_batch pb = llama_batch_get_one(tokens.data() + off, n);
    if (llama_decode(ctx, pb) != 0) { /* 中断 */ }
}
```

### 2. n_ctx を超えるプロンプトの切り詰め
`n_ctx - maxTokens - 8` を上限とし、超える場合は**先頭を捨てて末尾を残す**。
質問はプロンプト末尾にあるため、頭の資料から削るのが安全。

### 3. 生成ループの整理
プリフィルで最後のトークンまで評価済みなので、
生成ループの初回は `llama_decode` を飛ばして直接サンプリングする。
（従来は最終バッチを二重に評価していた）

## 併せて調整
文脈の予算に余裕を持たせた。

| 項目 | v1.1 | v1.2 |
|---|---|---|
| 参考資料 | 1100文字 | **900文字** |
| メール本文・質問 | 600文字 | **500文字** |
| 回答生成 | 400トークン | 400トークン |

n_ctx=2048 に対して日本語1文字≒1トークン前後なので、
これで切り詰めが発動しない範囲に収まる。

## 確認方法
```
logcat -d | grep -i bonsaijni | tail -20
```
`prompt_tokens=` と `n_ctx=` が出る。
`truncating head` が出る場合は資料が予算を超えている。
