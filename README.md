# Duo Cruise

MX-30 と OTTO AI BOX P3 Pro 用の、個人用アプリ一式と、車の中の本体を自動で更新する仕組み。

- `apps/updater` … 車の本体で動く更新アプリ(Duo Cruise Market)。15分ごとに Upbeater に新しい版を確かめ、署名を確かめてから入れる
- `apps/duo` … Duo Cruise 本体。左右2画面(CarPlay と TV 版 YouTube)、コマンダーの押す回数で操作側の切り替え・左右の入れ替え
- `apps/slate` … Duo Slate。iPhone・Mac を Duo Cruise のタッチパッドにするアプリ
- `docs/` … 仕様書([Duo Cruise](docs/Duo-Cruise仕様書.md)・[Duo Slate](docs/Duo-Slate仕様書.md))
- `assets/brand` … ロゴ・アイコン・起動演出・起動音の素材と決まり([資料](assets/brand/README.md))
- `apps/keyrecorder` … コマンダーのキー記録アプリ
- `patches/airplay.patch` … AirPlay 受信アプリ(jqssun/android-airplay-server, GPL-3.0)への修正
- `.upbeater/testflight.yml` … Duo Slate の iPhone 版を TestFlight へ送る(手動で実行)
- `.upbeater/android.yml` … Android 版(Duo Cruise Market・本体)を自宅の Mac で組み立てて署名し、Upbeater から車へ配る(いまは手動で実行)。鍵は `.upbeater/android-keygen.yml` で作る。[設計書](docs/Upbeater配布_設計書.md)

署名用の鍵は、自宅の Mac の `~/.duo-cruise/signing` にだけ置く(控えを取っておく)。鍵はリポジトリに入れない。
AirPlay 受信アプリは GPL-3.0 の元ソースの改変物で、修正差分を `patches/airplay.patch` として公開している。
