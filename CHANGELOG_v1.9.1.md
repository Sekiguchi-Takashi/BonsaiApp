# v1.9.1 — ビルド失敗の修正

## 症状
```
MainActivity.kt:105 Unresolved reference: toggleServer
MainActivity.kt:295 Unresolved reference: refreshServerInfo
```

## 原因
v1.9 でモデル処理を書き換えた際、`pickModel()` から `threadCount()` までを
まとめて置換した。その範囲内に

- `toggleServer()`
- `refreshServerInfo()`
- `onResume()` / `onPause()`

が含まれており、**サーバー関連の関数を巻き込んで削除**していた。

## 修正
上記4つを復元。あわせて次の2つの検査を行い、他に欠落がないことを確認した。

1. v1.8 と v1.9 の**全ファイルの関数一覧を差分照合**
   → 消えているのは意図的に撤去した3つ（`importModel` / `modelFile` /
     `pickModel`）のみ
2. 全 .kt の呼び出し先が解決できるかを走査 → 未解決なし

## 機能面の変更
なし。v1.9 の内容（モデルをフォルダから直接読む、コピー廃止、
起動時の自動読込、見つからない場合のポップアップ）はそのまま。

## 補足: ビルドログの CMake 警告について
```
IPO is not supported ... LLVMgold.so: cannot open shared object file
```
これは `-DGGML_LTO=ON` を指定しているために出る警告で、
NDK に LTO 用プラグインが無いため LTO が無効化されるだけ。
**ビルドは継続し、成果物にも影響しない**（エラーではない）。
気になる場合は build.gradle から `-DGGML_LTO=ON` を外せば消える。
