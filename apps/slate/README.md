# Duo Slate(iPhone・Mac のタッチパッド)

車の中の Wi-Fi で OTTO の Duo Cruise に直接つなぎ、タッチパッドとして操作するアプリ。iPhone と Mac の両方で動く。

- 1本指でなぞる＝カーソル、軽く触れる＝押す、0.4秒押したまま＝ドラッグ、2本指＝スクロール・ピンチ
- 下のボタン：戻る・ホーム・タスク・文字(iPhone のキーボード)
- 左上の SETTING：動きの速さ・ダークモード・タッチパッドとネオンの配色・接続先の指定(通常は不要)・再接続・Duo Slate について
- 9 言語(英・日・中簡・中繁・韓・独・仏・西・伊)。文言の原本は i18n/strings.py、`python3 -I i18n/gen.py` で書き出す

## 作って iPhone に入れる(Mac で)

```
brew install xcodegen
cd apps/slate && xcodegen generate
open DuoSlate.xcodeproj   # iPhone を選んで ▶
```

Xcode の 設定 → Accounts に Apple ID を入れておくこと。無料の Apple ID だと7日ごとに入れ直しが要る。

OTTO 側の受け口は apps/duo の PadServer(口 47800、Bonjour _duopad._tcp)と PadInput。
バンドル ID(com.yuki.duocruise.pad)と Bonjour の名前(_duopad._tcp)は、入っているアプリや OTTO との互換のため旧名のまま。
