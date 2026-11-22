# Duo Cruise の車への配布を Upbeater に載せる 設計書

2026-11-14 作成。利用者の指示「Duo-Cruise の車への配布を Upbeater に載せろ」。GitHub Actions は使わない（2026-11-14 に外した）。

## 1. いまの状態（2026-11-14 に確かめた事実）

- 組み立て・署名・公開は GitHub Actions（`.github/workflows/build.yml`）だけにあった。Actions が止まり、最後の正式版は build-199（2026-11-04）、最後のテスト版は test-201。
- 車の Duo Cruise Market（`apps/updater`）は、GitHub の `releases/latest` と `manifest.json` を見に行く。**GitHub のリリースは今すべて無くなっていて**（一覧が空）、車は新しい版を受け取れない。
- 署名の鍵（mx.jks とパスワード）は GitHub の秘密情報にだけあり、取り出せない。手元にも無い（利用者に確認済み）。

## 2. 方針

1. **新しい署名の鍵を作る。** 前の鍵が無いため、Android は今のアプリの上に新しい鍵の版を入れられない。**最初の1回だけ、車で今の Market と本体を消して入れ直す**（設定は初期に戻る。事前に利用者へ伝えてから行う）。以後は自動で入れ替わる。
2. **車は Upbeater から直接受け取る。** GitHub のリリースは使わない。Market に Upbeater の端末の受け口（登録・照合・報告）を足し、GitHub を見る処理は消す。
3. **組み立て・署名は自宅の Mac の実行役（Upbeater）で行う。**

## 3. 配るもの（1つのまとめ）

Upbeater の配布は「1つのリポジトリ・1つの OS に1つのファイル」。そのため Android は zip 1つにまとめて配る。

| 中身 | 内容 |
|---|---|
| `manifest.json` | 今までと同じ形（`tools/make_manifest.py`）。アプリごとの名前・パッケージ・版番号・ファイル名・SHA-256、戻す用の一覧（`rollback`） |
| `duo-updater.apk`・`duo-main.apk` | Market と本体（新しい鍵で署名） |
| `prev-*.apk` | 1つ前の版の中身を、今と同じ版番号で作り直したもの（「1つ前の版に戻す」用。Android は版番号を下げられないため） |

- 版番号（versionCode）は `2000 + Upbeater の実行番号`（`GITHUB_RUN_NUMBER`）とする。前の鍵の版 1199〜1201 より必ず大きく、実行のたびに増える（配布の通番は組み立てが成功してから決まるため、組み立て中には使えない）。
- テスト版は配らない（Upbeater は既定ブランチの版だけを配る）。Market の「テスト版を受け取る」と指示 `updater:test-channel` は消した。試すときは Upbeater の一時停止・段階配信を使う。

## 4. 組み立て（`.upbeater/android.yml`、Mac の実行役 `macos`）

- 合図：main の `apps/**`・`patches/**`・`tools/**` の変更と、手動実行。同時に1つだけ（concurrency）。
- 手順：取り出し（履歴ごと）→ Java 17/21/25・Gradle・Android SDK の確認 → Upbeater の公開鍵（`GET /v1/keys`）を取る → Market のテスト → Market と本体の組み立て → 1つ前の版の作り直し（前に配ったコミットを Mac の `~/.duo-cruise/last-release-sha` に覚えておく）→ 署名（`zipalign`・`apksigner`）と確かめ → `manifest.json` → zip → `distribute`（`platform: android`）→ 配ったコミットを覚える → ntfy で車に「check」を送る（失敗しても配布は止めない）。
- 署名の鍵：Mac の `~/.duo-cruise/signing/mx.jks`、パスワードは同じ場所の `pass`（どちらも権限 600。実行役はログインの画面の外でも動くため、鍵の束（キーチェーン）は使わない）。リポジトリ・ログ・チャットに出さない。鍵を作る手順は別の手動実行（`.upbeater/android-keygen.yml`。あれば上書きしない）。**鍵を失うと今回と同じ入れ直しが要るため、作ったら利用者が控えを取る**（Mac の Time Machine など）。
- `DUO_PUSH_TOPIC`・`DUO_UPLOAD_URL`・`DUO_UPLOAD_TOKEN` は Upbeater の秘密情報（`request_secrets` の入力ページで利用者が入れる）。

## 5. 車の Market の受け取り

- Upbeater の Hub の住所と、Upbeater の署名の公開鍵（Ed25519。世代番号つき）を、組み立て時に埋め込む。照合の返事に含まれる公開鍵は信用しない（埋め込んだものだけ）。
- 登録：利用者が Claude に頼むと、Upbeater の `create_device_code` で一回限りのコード（15分）を出す。コードは記録シートの「指示」に `updater:register <コード>` と書く（Market は30秒ごとに指示を見ている）。Market が `POST /v1/devices/register` で端末のトークンを受け取り、端末の中にだけしまう。
- 照合：15分ごと・ntfy の「check」・指示 `updater:check` のときに `POST /v1/devices/check`（今の通番を送る）。返ってきた署名を、埋め込んだ公開鍵で確かめ（Upbeater の `hub/src/manifest.ts` の verify・evaluate と同じ決まり：失効した世代・期限切れ・別のアプリ・通番の逆戻りは拒否）、zip を取り、SHA-256 を確かめ、中の `manifest.json` の各 APK の SHA-256 を確かめてから入れる。
- 入れ方は今までと同じ（確認画面なし。出たときは後ろの確認を押す。本体は入れ替えたら開き直す。自分＝Market は最後）。
- 結果は `POST /v1/devices/report` で知らせる（導入中・導入済み・失敗）。同じ版が2台で失敗すると Upbeater が配布を止める（車は1台なので、失敗は Upbeater の操作の記録で見る）。
- Ed25519 の確かめは、Android の版に頼らず、Java だけで書く（RFC 8032 の試験の値で確かめる）。
- 「1つ前の版に戻す」は今までどおり zip の中の `prev-*.apk` を入れる。

## 6. 進める順番

1. Upbeater の Hub に署名鍵（`MANIFEST_SIGNING_KEYS`）を入れる：Upbeater の `.upbeater/hub-signing-key.yml` を手動実行（秘密の部分は Mac の `~/.upbeater/hub-secrets.json` にだけ置く）。公開鍵は組み立てのたびに `GET /v1/keys` から取る。
2. 新しい Android の署名の鍵を作る：`.upbeater/android-keygen.yml` を手動実行。
3. Market に Upbeater の受け取りを足し、GitHub を見る処理を消す（済み。署名の確かめ・受け取りの決まり・まとめの取り出しはテストで確認。Android の部品の定義に対しての組み立ても確認）。
4. `.upbeater/android.yml` を手動で実行し、最初の版を Upbeater に置く（Mac の道具が足りなければ、手順の表示に従って入れる）。
5. **車で今の Market と本体を消し**、新しい Market を入れる（最初の1回だけ。入れ方は Mac から作った APK を車へ渡す。手順は利用者と決める）。
6. 記録シートの指示で登録し、Market が本体を入れることを確かめる。
7. `android.yml` を main の変更で自動に動かす。
