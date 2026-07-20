# BonsaiApp — HANDOFF

ローカルLLM（PrismML Bonsai / GGUF）を llama.cpp + JNI で APK に組み込む最小構成。
v0.1 = CIが緑になって APK が出るところまでが目標。

## 固定バージョン（変更禁止）

| 項目 | 値 |
|---|---|
| AGP | 8.5.2 |
| Kotlin | 1.9.24 |
| Gradle | 8.9（wrapperなし、CIで指定） |
| JDK | 17 |
| NDK | 26.3.11579264 |
| CMake | 3.22.1 |
| compileSdk / targetSdk | 34 |
| minSdk | 28（llama.cpp の Android 要件） |
| ABI | arm64-v8a のみ |

## 従来方針との差分

これまでの「外部依存ゼロ」は Java/Kotlin 側では維持（androidx すら入れない）。
ただし **ネイティブ側だけは llama.cpp に依存する**。ここは避けられない。

- llama.cpp は **リポジトリにコミットしない**。CI が clone する（`.gitignore` 済み）
- 依存の固定は `build.yml` の `LLAMA_REPO` / `LLAMA_REF` で行う
- ビルド時間は従来の1〜2分 → **初回15〜25分**。`app/.cxx` をキャッシュしているので2回目以降は数分

## llama.cpp のピン留め（最重要）

```yaml
LLAMA_REPO: https://github.com/PrismML-Eng/llama.cpp
LLAMA_REF:  prism
```

- **ternary (Q2_0)** を使う → PrismML fork の `prism` ブランチが必須
- **1bit (Q1_0)** だけ → upstream `ggml-org/llama.cpp` でも動く

`prism` はブランチなので中身が動く。初回ビルドが通ったら、ワークフローログの
`PIN THIS SHA -> xxxxxxx` を `LLAMA_REF` に貼って **必ず固定する**。

## 既知の壊れやすいポイント

1. **llama.h の API ドリフト** — `llama_jni.cpp` は 2025年前後の sampler chain API 前提。
   コンパイルエラーが出たら、直すのは基本このファイルだけ。該当タグの
   `llama.cpp/include/llama.h` を読んで関数名を合わせる。
2. **`git init` をホームで打つ事故** — 従来どおり厳禁。必ずプロジェクトフォルダ内で。
3. **debug.keystore** — このZIPには入っていない。既存プロジェクトからコピーして
   リポジトリ直下（`app/build.gradle` から見て `../debug.keystore`）に置くこと。
4. **ABI を増やさない** — x86_64 を足すとビルド時間がほぼ倍。エミュレータ検証が
   必要になるまで arm64-v8a 単独で通す。

## モデルの配置

APK には同梱しない（サイズ的に不可）。端末側に手で置く。

```
/sdcard/Android/data/com.appathy.bonsai/files/model.gguf
```

Termux から:

```
mkdir -p /sdcard/Android/data/com.appathy.bonsai/files
cp /sdcard/Download/Bonsai-1.7B-Q1_0.gguf /sdcard/Android/data/com.appathy.bonsai/files/model.gguf
```

推奨は 1.7B から。動いたら 4B（GGUF 約0.57GB）へ。8B（約1.15GB）はメモリ次第。

## ファイル構成

```
.github/workflows/build.yml   CI（NDK導入 → llama.cpp clone → gradle）
app/build.gradle              externalNativeBuild の cmake 引数
app/src/main/cpp/CMakeLists.txt   add_subdirectory(llama.cpp) + JNI .so
app/src/main/cpp/llama_jni.cpp    JNIブリッジ（load / generate / free）
app/src/main/java/.../LlamaBridge.kt  Kotlin側ラッパ
app/src/main/java/.../MainActivity.kt プログラマティックUI（XMLなし）
```

## 次の一手（v0.2 以降の候補）

- 生成のキャンセル（`llama_sampler` ループに中断フラグ）
- チャットテンプレート適用（`llama_chat_apply_template`）
- 初回起動時のモデルDL＋SHA検証
- KVキャッシュ保持でマルチターン化
