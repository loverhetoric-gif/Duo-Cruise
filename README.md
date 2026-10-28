# Duo Cruise

MX-30 と OTTO AI BOX P3 Pro 用の、個人用アプリ一式と、車の中の本体を自動で更新する仕組み。

- `apps/updater` … 車の本体で動く更新アプリ(Duo Cruise 更新)。15分ごとに最新リリースを見て、新しければ入れる
- `apps/duo` … Duo Cruise 本体。左右2画面(CarPlay と TV 版 YouTube)、コマンダーの押す回数で操作側の切り替え・左右の入れ替え
- `apps/slate` … Duo Slate。iPhone・Mac を Duo Cruise のタッチパッドにするアプリ
- `docs/` … 仕様書([Duo Cruise](docs/Duo-Cruise仕様書.md)・[Duo Slate](docs/Duo-Slate仕様書.md))
- `assets/brand` … ロゴ・アイコン・起動演出・起動音の素材と決まり([資料](assets/brand/README.md))
- `apps/keyrecorder` … コマンダーのキー記録アプリ
- `patches/airplay.patch` … AirPlay 受信アプリ(jqssun/android-airplay-server, GPL-3.0)への修正
- `.github/workflows/build.yml` … main に変更が入ると、作って署名して正式版を公開する。PR のときはテスト版(prerelease)として公開する

署名用の鍵は、リポジトリの Secrets(DUO_KEYSTORE_B64 / DUO_KEYSTORE_PASS)にだけある。鍵はリポジトリに入れない。
AirPlay 受信アプリは GPL-3.0 の元ソースの改変物で、修正差分を `patches/airplay.patch` として公開している。
