# Duo Cruise

MX-30 と OTTO AI BOX P3 Pro 用の、個人用アプリ一式と、車の中の本体を自動で更新する仕組み。

- `apps/updater` … 車の本体で動く更新アプリ(Duo Cruise 更新)。15分ごとに最新リリースを見て、新しければ入れる
- `apps/duo` … Duo Cruise 本体。左右2画面(CarPlay と TV 版 YouTube)、コマンダーの押す回数で操作側の切り替え・左右の入れ替え
- `apps/slate` … Duo Slate。iPhone・Mac を Duo Cruise のタッチパッドにするアプリ
- `docs/` … 仕様書([Duo Cruise](docs/Duo-Cruise仕様書.md)・[Duo Slate](docs/Duo-Slate仕様書.md))
- `assets/brand` … ロゴ・アイコン・起動演出・起動音の素材と決まり([資料](assets/brand/README.md))
- `apps/keyrecorder` … コマンダーのキー記録アプリ
- `patches/airplay.patch` … AirPlay 受信アプリ(jqssun/android-airplay-server, GPL-3.0)への修正
- `.upbeater/testflight.yml` … Duo Slate の iPhone 版を TestFlight へ送る(手動で実行)
- Android 版(Duo Cruise Market・本体)の組み立て・署名・公開は、GitHub Actions を外したため(2026-11-14)、Upbeater への移し替えが済むまで止まっている。手順は履歴の `.github/workflows/build.yml` にある

署名用の鍵は、秘密情報(DUO_KEYSTORE_B64 / DUO_KEYSTORE_PASS)にだけ置く。鍵はリポジトリに入れない。
AirPlay 受信アプリは GPL-3.0 の元ソースの改変物で、修正差分を `patches/airplay.patch` として公開している。
