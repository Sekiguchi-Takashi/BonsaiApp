package com.appathy.bonsai

import android.app.Activity
import android.graphics.Color
import android.os.Bundle
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/**
 * アプリ内マニュアル（v1.8）。
 * 外部ファイルを持たず、本文は定数として埋め込む（ゼロ依存方針のため）。
 */
class ManualActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val d = resources.displayMetrics.density
        val pad = (16 * d).toInt()

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
            setBackgroundColor(Color.parseColor("#101014"))
        }

        val body = TextView(this).apply {
            text = MANUAL
            setTextColor(Color.parseColor("#E8EAED"))
            textSize = 14f
            setLineSpacing(0f, 1.25f)
            setTextIsSelectable(true)
        }
        root.addView(body, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))

        setContentView(ScrollView(this).apply { addView(root) })
    }

    companion object {
        private const val MANUAL = """Bonsai 使い方マニュアル

■ このアプリは何か

端末の中だけで動くローカルLLM（Bonsai）に、自分の資料を読ませて
質問に答えさせるアプリです。通信は一切しません。
機内モードでも動き、入力した内容が外部に出ることはありません。


■ 最初にやること（3ステップ）

1. モデルフォルダを決める
   .gguf ファイルを置くフォルダを1つ決めます（例: 内部ストレージ/Models）。
   上段の「モデル」を押し、そのフォルダを選びます。

   ファイルはコピーされません。その場所のまま読み込むので、
   容量が二重に必要になることはありません。
   フォルダに .gguf が1つだけなら自動で読み込み、
   複数あれば選択画面が出ます。

   起動するたびに、このフォルダを見にいって自動で読み込みます。
   ファイルが無い場合はエラーのポップアップが出ます。

2. 資料フォルダを選ぶ
   「RAG」を押し、資料を置いたフォルダを選びます。
   Termux の rclone で OneDrive から同期したフォルダを想定しています。
   ※Termux のホーム配下は選べません。内部ストレージに置いてください。

3. インデックスを作る
   同じ画面で「インデックス更新」を押します。
   「文書 N / チャンク M / 語彙 L」と出れば成功です。


■ 質問する

画面下の入力欄に質問を書き、「生成」を押します。

・RAG参照 ON  … 資料を検索して、その内容をもとに答えます
・RAG参照 OFF … 資料を見ず、モデルの知識だけで答えます

RAG参照 ON のときは、右側でタグ（資料の対象）を選びます。
タグを選ばないと生成できません。40本以上の資料が入ると、
アプリ名を指定しないと別アプリの資料が混ざるためです。

生成中は「生成」が「停止」に変わります。押せば途中で止められます。
回答の末尾に、参照した資料の名前が出ます。


■ タグについて

タグは資料の先頭に書いた app_name（またはキャラ名）です。
資料を追加してインデックスを更新すると、自動で選択肢に増えます。

タグが空欄のままの資料は選択肢に出ません。
資料の先頭に次の形式を書いてください。

---
type: app_doc
app_name: MemoApp
app_label: メモ帳
doc_part: 機能
---


■ 資料エディタ

「エディタ」から、アプリの中で資料を書けます。

・テンプレートは3種類（アプリ資料 / キャラ設定 / フリー）
・「開く」で既存ファイルを読み込んで編集できます
・「保存して反映」を押すと、保存と同時にインデックスも更新されます

保存先は RAG で選んだフォルダです。


■ サーバー（他アプリ連携）

「サーバー」を押すと、端末内に OpenAI 互換の API が立ちます。

  http://127.0.0.1:8080/v1

他の自作アプリから、OpenAI SDK の接続先をここに向けるだけで
Bonsai を推論エンジンとして使えます。
リクエストに "rag": true を足すと資料検索を挟みます。

・画面から使うだけなら起動不要です
・起動中はモデルが常駐するのでメモリを使い続けます
・同じWi-Fiの別端末からは接続できません（意図した制限です）


■ メール連携

「メール」から Gmail を監視し、届いたメールに資料をもとにした
回答案を自動で作れます。

・Gmail のアプリパスワード（16桁）が必要です
・2段階認証が有効な個人アカウントのみ対応します
・既読にはしません。自動送信もしません（画面に出るだけです）

監視を開始すると常駐通知が出ます。停止すると通知も消え、
バックグラウンドの動作も完全に止まります。


■ よくある質問

Q. 資料を更新したのに答えが変わらない
A. インデックス更新を押してください。ファイルを置くだけでは
   反映されません。更新時に「変更なし」と出る場合は、
   ファイルの更新日時が変わっていない可能性があります。

Q. 回答が遅い
A. RAG参照 ON のときは資料を読み込む時間がかかるため、
   最初の1文字が出るまで10〜20秒かかることがあります。
   モデルが大きいほど遅くなります。

Q. 中国語が混ざる
A. 小さいモデルで起きやすい現象です。日本語では使わない
   簡体字は出力できないようにしてありますが、完全ではありません。
   大きいモデルに変えると減ります。

Q. 資料にないことを聞くとどうなる
A. 「参考資料に該当する記載がありません」と答えるよう指示して
   いますが、モデルが小さいと推測で答えることがあります。
   回答末尾の参照資料を確認してください。

Q. モデルを変えたい
A. モデルフォルダに別の .gguf を置き、「モデル」を押して選びます。
   フォルダ自体を変えるときは、選択画面の「フォルダを変更」を押します。
   サーバーやメール監視は先に停止してください。

Q. 起動時に「モデルが見つかりません」と出る
A. フォルダの中の .gguf が移動・削除されたか、フォルダを選び直す必要が
   あります。「フォルダを選ぶ」から設定し直してください。
   モデルはアプリ内にコピーしていないため、元ファイルを消すと読めません。


■ 注意

・アプリを消しても、モデルと資料のファイル自体は残ります
  （消えるのは資料インデックスとアプリの設定だけです）
・Gmail のアプリパスワードは端末内に保存されます
・回答の正しさは保証されません。重要な判断には使わないでください
"""
    }
}
