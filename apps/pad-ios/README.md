# Duo Pad(iPhone のタッチパッド)

車の中の Wi-Fi で OTTO の Duo Cruise に直接つなぎ、タッチパッドとして操作する iPhone アプリ。

- 1本指でなぞる＝カーソル、軽く触れる＝押す、0.4秒押したまま＝ドラッグ、2本指＝スクロール・ピンチ
- 下のボタン：戻る・ホーム・タスク・文字(iPhone のキーボード)
- 右上の設定：感度・加速・OTTO の住所(空欄なら自動で探す)

## 作って iPhone に入れる(Mac で)

```
brew install xcodegen
cd apps/pad-ios && xcodegen generate
open DuoPad.xcodeproj   # iPhone を選んで ▶
```

Xcode の 設定 → Accounts に Apple ID を入れておくこと。無料の Apple ID だと7日ごとに入れ直しが要る。

OTTO 側の受け口は apps/duo の PadServer(口 47800、Bonjour _duopad._tcp)と PadInput。
