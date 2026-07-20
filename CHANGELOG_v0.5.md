# v0.5 変更点

## 1. アイコン（盆栽）
アダプティブアイコンをベクタで追加。PNG を一切持たないので全解像度対応、
APK サイズ増加もほぼゼロ。

- `res/drawable/ic_launcher_foreground.xml` — 盆栽本体（S字の幹＋三段の葉冠＋鉢）
- `res/drawable/ic_launcher_background.xml` — 暗緑の背景
- `res/mipmap-anydpi-v26/ic_launcher.xml` — アダプティブアイコン定義
- `monochrome` も指定済み（Android 13+ のテーマアイコン対応）

セーフゾーン対策として foreground 全体を `scaleX/Y=0.68` で縮小してある。
minSdk 28 なので `anydpi-v26` だけで全端末をカバーできる（PNG フォールバック不要）。

## 2. 中国語混入の抑制

### 効かない方法
Unicode ブロックによるフィルタは**原理的に不可能**。日本語の漢字と
中国語の漢字は同じ CJK Unified Ideographs に同居しているため、
出力後に「中国語だけ」を機械的に取り除くことはできない。

### 採用した方法：簡体字トークンの logit bias
モデル読込時に語彙を全走査し、**日本語では使わない簡体字**を含む
トークンに `-INFINITY` のバイアスを掛けて出力候補から外す。

- 対象文字は 443 字を厳選（言偏・貝偏・糸偏・車偏・門構え・金偏・馬偏など）
- **学・国・会・体・数・画・条・干** など日本の新字体と同形の字は除外。
  これらを禁止すると日本語自体が壊れる
- 全角カンマ「，」も禁止（日本語は「、」）
- 語彙走査は数十ms。読込時間への影響は無視できる
- `LlamaBridge.load(banSimplified = false)` で無効化可能

### 併用した対策
- システムプロンプトを日本語化（日本語のみで回答するよう明示）
- temperature 0.5 → **0.35**（1bit 量子化はドリフトしやすいので低めが安全）

## 3. Markdown の平文化
`**強調**` / `### 見出し` / `---` 罫線を表示時に除去。
`<think>` ブロック除去と同じ `strip()` 内で処理している。

## 残る限界
簡体字と日本語漢字が同形の語（例: 「文法」「問題」）で構成された中国語文は
すり抜ける。完全な解決はモデル側の性能に依存するため、4B / 8B へ上げるのが
本筋の対策になる。

## 変更ファイル
```
app/src/main/res/drawable/ic_launcher_foreground.xml   新規
app/src/main/res/drawable/ic_launcher_background.xml   新規
app/src/main/res/mipmap-anydpi-v26/ic_launcher.xml     新規
app/src/main/res/mipmap-anydpi-v26/ic_launcher_round.xml 新規
app/src/main/AndroidManifest.xml   icon / roundIcon 追加
app/src/main/cpp/llama_jni.cpp     logit bias、temp 0.35
app/src/main/java/.../LlamaBridge.kt   banSimplified 引数
app/src/main/java/.../MainActivity.kt  日本語システムプロンプト、Markdown除去
app/build.gradle                   versionCode 5 / versionName 0.5
```
