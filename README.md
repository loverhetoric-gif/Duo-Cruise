# Duo Cruise

MX-30 と OTTO AI BOX P3 Pro 用の、個人用アプリ一式と、車の中の本体を自動で更新する仕組み。

- `apps/updater` … 車の本体で動く更新アプリ(Duo Cruise 更新)。15分ごとに配信口(Cloudflare の duo-releases)を見て、新しければ入れる。GitHub は見に行かない
- `apps/duo` … Duo Cruise 本体。左右2画面(CarPlay と TV 版 YouTube)、コマンダーの押す回数で操作側の切り替え・左右の入れ替え
- `apps/slate` … Duo Slate。iPhone・Mac を Duo Cruise のタッチパッドにするアプリ
- `docs/` … 仕様書([Duo Cruise](docs/Duo-Cruise仕様書.md)・[Duo Slate](docs/Duo-Slate仕様書.md))
- `assets/brand` … ロゴ・アイコン・起動演出・起動音の素材と決まり([資料](assets/brand/README.md))
- `apps/keyrecorder` … コマンダーのキー記録アプリ
- `patches/airplay.patch` … AirPlay 受信アプリ(jqssun/android-airplay-server, GPL-3.0)への修正
- `tools/publish-release.sh` … Mac で作って署名し、配信口(Cloudflare の R2 `duo-releases`、`tools/release-worker`)へ出す。`test` でテスト版、`main` で正式版
- `.github/workflows/build.yml` … 手で動かしたときだけ、作れるかを確かめる。GitHub のリリースには上げない(2026-11-11〜)

署名用の鍵は、Mac の `~/development/ottocast-work/keys/` にだけある。鍵はリポジトリに入れない。
AirPlay 受信アプリは GPL-3.0 の元ソースの改変物で、修正差分を `patches/airplay.patch` として公開している。
