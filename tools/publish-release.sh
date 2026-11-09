#!/bin/bash
# Duo Cruise(本体)と Duo Cruise Market(更新アプリ)を、この Mac で作って署名し、
# Cloudflare の配信口(duo-releases)へ出す。車の更新アプリがそこを見て入れる。
# GitHub 停止中(2026-11-09〜)の配信手順。自動では動かさず、人が実行したときだけ動く。
#
# 使い方:  bash tools/publish-release.sh test   … テスト版(「テスト版を受け取る」がオンの端末だけ)
#          bash tools/publish-release.sh main   … 正式版(main ブランチのときだけ)
# 前提:
#   - 送り先の変更を GitLab に push 済み(手元と GitLab が一致していること)
#   - この Mac の wrangler が個人用 Cloudflare にログイン済み(npx wrangler whoami)
#   - 本番の署名鍵 ~/development/ottocast-work/keys/mx-release.jks(とパスワードの txt)
#   - Java 21 が ~/.gradle/jdks/temurin-21(更新アプリ用)、Android Studio(本体用)
#   - 任意:キーチェーン(account gitlab-runner)の duo-push-topic / duo-upload-url / duo-upload-token
#     (無ければ空で作る。即時の更新の合図・走行記録の送信はしない)
# 戻す用(prev-*.apk、1つ前の正式版の作り直し)はまだ作らない。
set -euo pipefail
cd "$(git rev-parse --show-toplevel)"

CHANNEL="${1:-}"
case "$CHANNEL" in test|main) ;; *) echo "使い方: bash tools/publish-release.sh test|main"; exit 1 ;; esac
BRANCH="$(git branch --show-current)"
[ "$CHANNEL" = test ] || [ "$BRANCH" = main ] || { echo "正式版は main で実行してください(今: $BRANCH)"; exit 1; }
[ -z "$(git status --porcelain)" ] || { echo "未コミットの変更があります"; exit 1; }
git fetch -q origin "$BRANCH"
[ "$(git rev-parse HEAD)" = "$(git rev-parse "origin/$BRANCH")" ] || { echo "GitLab の $BRANCH と手元が一致しません(push・取り込みを先に)"; exit 1; }

SDK="$HOME/Library/Android/sdk"
BT_SIGN="$SDK/build-tools/34.0.0"
AAPT2="$SDK/build-tools/37.0.0/aapt2"
JAVA21="$HOME/.gradle/jdks/temurin-21/Contents/Home"
JBR="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
KS="$HOME/development/ottocast-work/keys/mx-release.jks"
KS_PASS_FILE="$HOME/development/ottocast-work/keys/mx-release-password.txt"
BUCKET="duo-releases"
BASE="https://duo-releases.twilight-star-9d9d.workers.dev"
FLOOR_BUILD=202   # 2026-11-08 に手元で入れた 0.1.1202 より上から始める(版番号 = 1000 + 番号)
for p in "$BT_SIGN/apksigner" "$AAPT2" "$JAVA21/bin/java" "$JBR/bin/java" "$KS" "$KS_PASS_FILE"; do
  [ -e "$p" ] || { echo "見つかりません: $p"; exit 1; }
done

unset CLOUDFLARE_API_TOKEN   # 個人用 Cloudflare には、この Mac の wrangler のログインで入る
export CLOUDFLARE_ACCOUNT_ID="f95f76412f29c64f24e18e16d58f48f6" WRANGLER_SEND_METRICS=false
WR() { npx --yes wrangler@4 "$@"; }

WORK="$(pwd)/build/release"
rm -rf "$WORK" && mkdir -p "$WORK/out"

# 今の一覧を読み、次の番号を決める
if ! WR r2 object get "$BUCKET/releases.json" --remote --pipe > "$WORK/releases.json" 2>/dev/null; then echo "[]" > "$WORK/releases.json"; fi
BUILD="$(python3 - "$WORK/releases.json" "$FLOOR_BUILD" <<'PY'
import json, re, sys
nums = [int(m.group(1)) for r in json.load(open(sys.argv[1])) for m in [re.search(r"(\d+)$", r.get("tag_name", ""))] if m]
print(max(nums + [int(sys.argv[2])]) + 1)
PY
)"
VC=$((1000 + BUILD))
if [ "$CHANNEL" = main ]; then TAG="build-$BUILD"; else TAG="test-$BUILD"; fi
echo "== $TAG(版番号 $VC、$BRANCH $(git rev-parse --short HEAD))を作ります"

kc() { security find-generic-password -a gitlab-runner -s "$1" -w 2>/dev/null || true; }
export DUO_PUSH_TOPIC="$(kc duo-push-topic)" DUO_UPLOAD_URL="$(kc duo-upload-url)" DUO_UPLOAD_TOKEN="$(kc duo-upload-token)"
[ -n "$DUO_PUSH_TOPIC" ] || echo "(duo-push-topic が無いので、即時の更新の合図は入れません。車は15分ごとの確認で受け取ります)"

echo "sdk.dir=$SDK" > apps/updater/local.properties
echo "sdk.dir=$SDK" > apps/duo/local.properties
(cd apps/updater && JAVA_HOME="$JAVA21" ./gradlew --no-daemon -q assembleDebug -Pvc=$VC)
(cd apps/duo && JAVA_HOME="$JBR" ./gradlew --no-daemon -q assembleDebug -Pvc=$VC)

export KS_PASS="$(cat "$KS_PASS_FILE")"
sign() { # 入力 出力名
  "$BT_SIGN/zipalign" -f -p 4 "$1" "$WORK/aligned.apk"
  "$BT_SIGN/apksigner" sign --ks "$KS" --ks-key-alias mx --ks-pass env:KS_PASS --key-pass env:KS_PASS \
    --v4-signing-enabled false --out "$WORK/out/$2" "$WORK/aligned.apk"
  "$BT_SIGN/apksigner" verify "$WORK/out/$2"
}
sign apps/updater/app/build/outputs/apk/debug/app-debug.apk duo-updater.apk
sign apps/duo/app/build/outputs/apk/debug/app-debug.apk duo-main.apk
unset KS_PASS
rm -f "$WORK/aligned.apk" "$WORK"/out/*.idsig
python3 tools/make_manifest.py "$WORK/out" "$AAPT2" "$BUILD"

# ファイルを先に置き、最後に一覧を書き換える(途中で止まっても、車は前の版を見たまま)
for f in "$WORK"/out/*; do
  n="$(basename "$f")"
  case "$n" in *.apk) ct=application/vnd.android.package-archive ;; *) ct=application/json ;; esac
  WR r2 object put "$BUCKET/$TAG/$n" --file "$f" --content-type "$ct" --remote
done
python3 - "$WORK/releases.json" "$WORK/out" "$TAG" "$CHANNEL" "$(git rev-parse HEAD)" "$BRANCH" <<'PY'
import datetime, json, os, sys
idx, out, tag, channel, sha, branch = sys.argv[1:]
lst = json.load(open(idx))
pre = channel == "test"
rel = {
    "tag_name": tag,
    "name": ("test " if pre else "") + f"build {tag.split('-')[-1]}",
    "prerelease": pre,
    "draft": False,
    "published_at": datetime.datetime.now(datetime.timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ"),
    "target_commitish": sha,
    "body": f"{branch} commit {sha}(Mac から公開)",
    "assets": [{"name": n, "size": os.path.getsize(os.path.join(out, n))} for n in sorted(os.listdir(out))],
}
json.dump([rel] + [r for r in lst if r.get("tag_name") != tag], open(idx, "w"), ensure_ascii=False, indent=1)
PY
WR r2 object put "$BUCKET/releases.json" --file "$WORK/releases.json" --content-type application/json --remote

# 確かめる:配信口が新しい版を返すか
if [ "$CHANNEL" = main ]; then CHECK="$BASE/releases/latest"; else CHECK="$BASE/releases?per_page=1"; fi
curl -fsS "$CHECK" | grep -q "\"$TAG\"" || { echo "配信口に $TAG が見えません"; exit 1; }
curl -fsS -o /dev/null "$BASE/dl/$TAG/manifest.json" || { echo "manifest.json を取り出せません"; exit 1; }

if [ -n "$DUO_PUSH_TOPIC" ]; then curl -fsS -d check "https://ntfy.sh/$DUO_PUSH_TOPIC" > /dev/null && echo "車に新しい版を知らせました"; fi

# 版の控え(本人の個人ドライブ)
DRIVE="$HOME/Library/CloudStorage/GoogleDrive-wordsworth0903@gmail.com/マイドライブ/Duo Cruise 版"
if [ -d "$DRIVE" ]; then
  D="$DRIVE/0.1.$VC" && mkdir -p "$D" && cp "$WORK"/out/* "$D/"
  printf '%s\n%s %s\n%s\n' "$TAG($CHANNEL)" "$BRANCH" "$(git rev-parse HEAD)" "$(git log -1 --format=%s)" > "$D/説明.txt"
  echo "控え: $D"
fi
echo "公開しました: $TAG(版番号 $VC)"
