# tv/ — TV 版 YouTube の枠(担当 D)

## 部品
- `TvPane` … 約束の API。`TvPane(Activity)`、`view()`、`onKey(KeyEvent)`、`onResume()`、`onPause()`、`destroy()`、`status()`。
  追加で `command(String)`(記録シートの指示 `com.yuki.duocruise.main:tv-…` 用)。
- `DialServer` … iPhone の YouTube の送信ボタンに「Duo Cruise」を出す DIAL サーバー(SSDP＋dd.xml＋/apps/YouTube)。
  TvPane が起動・停止する。つながっている全ての回線(wlan0・p2p 等)で応え、10秒ごとに新しい回線を拾う。
- `TvLog` … 記録。C の `com.yuki.duocruise.main.DuoLog.send(Context, String kind, String text)` があればそれを使い、
  無ければ約束のブロードキャスト(`com.yuki.duocruise.updater.LOG`、宛先 `com.yuki.duocruise.updater`)を直接送る。

## C にお願いしたいこと(C の持ち場なので D は触っていない)
1. DuoActivity で `new TvPane(this)` を作り、`view()` を右(入れ替え時は左)の枠に置く。
   `onResume/onPause/onDestroy` で `onResume()/onPause()/destroy()` を呼ぶ。操作側が YouTube のとき、キーを `onKey(e)` へ。
   - 再生は onPause で止めない(音を続ける)。左右の入れ替えは view を付け替えるだけで良い(作り直さない)。
2. AndroidManifest.xml に権限を追加:
   - `android.permission.CHANGE_WIFI_MULTICAST_STATE`(DIAL の探索を受けるため。無いと自動で見つからない恐れ)
   - `android.permission.ACCESS_WIFI_STATE`
   - `com.yuki.duocruise.permission.LOG`(記録の送信。更新アプリが宣言)
   - INTERNET は既にある
3. 旧 `TvActivity` の削除と、マニフェストからの登録削除、`PaneService`/`Layout`/`MainActivity` からの呼び出しの付け替え。
4. 指示のブロードキャスト `com.yuki.duocruise.COMMAND` の `command` が `tv-` で始まるとき `tvPane.command(cmd)` へ渡す。
   - `tv-status` / `tv-ua`(一覧) / `tv-ua:0〜2`(名乗りを変えて読み直す) / `tv-reload` / `tv-home` / `tv-probe` / `tv-dial-restart`

## 記録に出るもの
- 名乗り(Tizen / Cobalt / AndroidTV)と読み込み先 URL(pairingCode 等の値は伏せる)。TV 版から外されたら自動で次の名乗りを試す
- 端末の再生能力(VP9/AV1/H264 のハード有無、Widevine のレベル、WebView の版)とページから見た再生能力(MSE・EME)
- 再生の状態・解像度・落ちたコマ(1分ごと)、音のフォーカス
- DIAL:加わった回線、探された回線と相手、起動・停止の依頼

## DialServer の単体テスト(Mac で、JUnit なし)
app/build.gradle は B の持ち場なので JUnit を足さず、Java だけで動くテストにした。
```
JH="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
SRC=apps/duo/app/src
"$JH/bin/javac" -encoding UTF-8 --release 17 -d /tmp/dialtest \
  $SRC/main/java/com/yuki/duocruise/main/tv/DialServer.java \
  $SRC/test/java/com/yuki/duocruise/main/tv/DialServerTest.java
"$JH/bin/java" -cp /tmp/dialtest com.yuki.duocruise.main.tv.DialServerTest   # 「すべて合格」で成功
```
