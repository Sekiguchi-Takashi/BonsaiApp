# v0.4 変更点（緊急修正）

## 症状
1行ほど生成した直後にアプリが落ちる。速度自体は出ている。

## 原因
JNI の `NewStringUTF` は **Modified UTF-8** しか受け付けない。

1. BPE トークナイザは日本語1文字を複数トークンに分割することがあり、
   1トークン分の `llama_token_to_piece` の出力が
   **UTF-8として不完全なバイト列**になる場合がある
2. その断片を `NewStringUTF` に渡すと JVM が
   `JNI DETECTED ERROR IN APPLICATION: input is not valid Modified UTF-8`
   で abort する
3. さらに絵文字などの4バイト文字は、正しいUTF-8であっても
   Modified UTF-8 としては不正（サロゲートペア表現が必要）

ASCII の間は動き、日本語に入った瞬間に落ちるという症状と一致する。

## 修正
- ネイティブ側に `pending` バッファを持ち、末尾の不完全なUTF-8
  シーケンスは次トークンまで持ち越す（`incomplete_utf8_len`）
- トークンの受け渡しを `jstring` → **`jbyteArray`** に変更。
  デコードは Kotlin 側の `String(bytes, Charsets.UTF_8)` に任せる
- `CallVoidMethod` 後に `ExceptionCheck` を追加
- EOG / 停止で抜けた際に残りをフラッシュ

## 影響範囲
- `llama_jni.cpp`: コールバックのシグネチャが `(Ljava/lang/String;)V` → `([B)V`
- `LlamaBridge.kt`: 内部に `NativeSink` を追加。
  **公開API（`TokenCallback.onToken(String)`）は変更なし**
- `MainActivity.kt`: 変更なし

## 検証方法
落ちた場合はこれでログを確認する:
```
logcat -d | grep -i -E "bonsai|DEBUG|FATAL|libc" | tail -40
```
