# carplay モジュール（自作 CarPlay・無線のみ）

Duo Cruise の左枠に iPhone の CarPlay を映す部品。担当 B。

## 元のソースと著作権（GPL-3.0）

- 元：xcertplay — https://github.com/shilapi/xcertplay
  - 作者：shilapi と xcertplay の貢献者（元のリポジトリには、著作権者名と年を書いた行が無い。README に「GPL v3.0 で公開」とだけある）
  - GNU General Public License v3.0（全文はリポジトリ直下の `LICENSE`。元の LICENSE と同じ文）
- 取り込んだ版：1.4.2（commit `1298fb35e29ee30e7bd849cb0e751b52aa22a13f`）の `shared/src/main`
- このため Duo Cruise 本体（apps/duo）も GPL-3.0 で公開する

### 元から変えたところ
- `shared/MyCarApp*.kt`（Car App Library の入口）と res・assets を外した。マニフェストからも外した
- `orchestration/CarPlayController.kt`：`/dev/i2c-N` の時も、0x10/0x11 のうち証明書を持つ番地を選ぶ（CH341 と同じ方法）。`config.mfiI2cAddress` を指定した時は、その番地だけを使う
- `orchestration/CarPlayRuntimeConfig.kt`：`mfiI2cAddress` を追加
- `AirPlayPersistence.kt`：xcertplay の `common` から、識別情報とペアリング情報の保存だけを抜き出した
- 新しく追加：`com.yuki.duocruise.carplay`（CarPlayPane・CarPlayKeyMap・MfiProbe・DuoRecord・CarPlayNetworkCheck・RouteGuidance）
- ナビの案内(Duo Cruise Unlimited Edition だけ。`CarPlayPane` の `routeGuidance` が true のとき):
  - `transport/Iap2IdentificationClient.kt`：識別情報に RouteGuidanceDisplayComponent(項目 30)を足す。断られたら外して名乗り直す
  - `iap2/catalog/Iap2Endpoint.kt`：識別情報の項目 30 の形を足す
  - `iap2/message/Iap2Messages.kt`：StartRouteGuidanceUpdates に受け口 0 と申し込みの項目を入れる
  - `orchestration/CarPlayController.kt`：0x5201/0x5202 を `onRouteGuidance` へ渡す
  - 項目の番号は LIVI(https://github.com/f-io/LIVI, GPL-3.0)の iap2-csm に合わせた

## 使い方（C の DuoActivity から）

```java
CarPlayPane carplay = new CarPlayPane(this, new CarPlayPane.Listener() {
    @Override public void onStatus(String status) { /* 枠の上に出す等 */ }
    @Override public void onRecord(String kind, String text) { /* 画面表示用。更新アプリへは送信済み */ }
});
leftFrame.addView(carplay.view());           // 740×416 の枠
requestPermissions(CarPlayPane.requiredPermissions(), 1); // 初回だけ
carplay.start();                              // 接続開始(無線)
// キー：操作側が CarPlay の時だけ渡す。true なら使った
if (carplay.onKey(event)) return true;
// ダイヤルがスクロールとして届く機種なら onGenericMotionEvent からも
carplay.onGenericMotion(motionEvent);
carplay.stop();                               // Wi-Fi・Bluetooth・チップを手放す(MultiPlay に返す)
String s = carplay.status();
```

- 映像は 740×416 で iPhone に頼む。枠の大きさが違っても縦横比を保って描く
- 左右の入れ替えで View を付け替えても止まらない（描き直しの時は完全な1枚を頼み直す）
- `onKey` は CarPlay がつながっていない時と、対応表に無いキーの時は false を返す
- 長押し（操作側の切り替え）の判定は C の持ち場。CarPlay へは短押しとして渡す

## キーの対応表

`CarPlayKeyMap.kt` の `DEFAULTS` の1か所だけで決める。車での実測（調査A）が出たら、その表を差し替える。
試すだけなら `CarPlayKeyMap.set(keyCode, Action)` で一時的に上書きできる。

| 操作 | 今の既定（推測） |
|---|---|
| 回す | NAVIGATE_NEXT / NAVIGATE_PREVIOUS、TAB（Shift で逆） |
| 押す（決定） | DPAD_CENTER / ENTER |
| 戻る | BACK / ESCAPE |
| ホーム | HOME / MOVE_HOME |
| 倒す | DPAD_UP / DOWN / LEFT / RIGHT |
| 再生系 | MEDIA_PLAY_PAUSE / PLAY / PAUSE / NEXT / PREVIOUS |

## 指示 `com.yuki.duocruise.main:mfi-probe`（MFi チップの読み取り確認）

受け口（`com.yuki.duocruise.COMMAND` のレシーバー）は C の持ち場。`command` が `mfi-probe` の時に、次の1行を呼ぶ。

```java
if (MfiProbe.COMMAND.equals(intent.getStringExtra("command"))) {
    MfiProbe.runAndReport(context);   // 別スレッドで読み、結果を kind=result で更新アプリへ送る
}
```

- `/dev/i2c-0` の 0x10・0x11 だけを読む。書き込みは「読む場所の番号 1 バイト」だけ
- 0x10・0x20・0x21・0x40 は番号として指定しない（コードで止めている）。ほかの番地には触らない
- 読むもの：版 0x00、FW 0x01、プロトコル 0x02/0x03、ID 0x04（4バイト）、エラー 0x05、証明書の長さ 0x30、証明書 0x31〜
- 2回読んで比べる。値が変わったら「取り合いの疑い」と書く
- 自作 CarPlay が動いている間は読まない
- MultiPlay が iPhone とつながっていない時に実行すること

結果の例：`mfi-probe:` に続けて番地ごとの値と「判断: 本物は 0x11 …」。

## 記録（更新アプリへ送るもの）

CarPlayPane が自分で `com.yuki.duocruise.updater.LOG` を送る。

- state：開始・停止・状態の変化・回線の様子
- error：失敗、取り合いの検出（Wi-Fi の ch・iPhone テザリング・Bluetooth・チップ）
- log：キーの対応表
- result：mfi-probe の結果

## MultiPlay との住み分け

止めない・無効化しない。`start()` から `stop()` の間だけ、チップ・Wi-Fi（P2P）・Bluetooth を使う。
取り合いらしい失敗は error として記録する（推測を含む）。

## ビルド

`apps/duo` で `./gradlew assembleDebug`。必要なもの：
- Gradle を起動する JDK 17 以上
- Java 25（Gradle の daemon toolchain。無ければ foojay から自動で取る）
- Android SDK Platform 37、NDK 28.2.13676358
