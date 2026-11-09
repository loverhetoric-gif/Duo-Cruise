#!/bin/bash
# 配信口(duo-releases)の一番新しい版を、無線 adb で OTTO に直接入れる。
# 使うのは主に「更新アプリの見に行く先を変えたとき」(古い更新アプリは新しい配信口を見ないため)。
# 使い方: OTTO の電源を入れ、Mac と同じネットワークにしてから  bash tools/install-to-otto.sh
set -euo pipefail
BASE="https://duo-releases.twilight-star-9d9d.workers.dev"

# 無線 adb(ポートは毎回変わる)を探してつなぐ
if ! adb devices | grep -q -w device; then
  for t in $(adb mdns services | awk '/_adb-tls-connect/ {print $NF}'); do adb connect "$t" || true; done
fi
SERIAL="$(adb devices | awk '$2=="device" {print $1; exit}')"
[ -n "$SERIAL" ] || { echo "OTTO が見つかりません(電源・ネットワーク・無線デバッグを確認)"; exit 1; }
echo "接続: $SERIAL"

TAG="$(curl -fsS "$BASE/releases?per_page=1" | python3 -c 'import json,sys; print(json.load(sys.stdin)[0]["tag_name"])')"
W="$(mktemp -d)"; trap 'rm -rf "$W"' EXIT
curl -fsS -o "$W/manifest.json" "$BASE/dl/$TAG/manifest.json"
for f in duo-main.apk duo-updater.apk; do   # 更新アプリは最後(自分自身の入れ替え)
  curl -fsS -o "$W/$f" "$BASE/dl/$TAG/$f"
  want="$(python3 -c 'import json,sys; print([a["sha256"] for a in json.load(open(sys.argv[1]))["apps"] if a["file"]==sys.argv[2]][0])' "$W/manifest.json" "$f")"
  [ "$(shasum -a 256 "$W/$f" | cut -d' ' -f1)" = "$want" ] || { echo "$f: ハッシュ不一致"; exit 1; }
  adb -s "$SERIAL" install -r "$W/$f"
done
for p in com.yuki.duocruise.main com.yuki.duocruise.updater; do
  echo "$p: $(adb -s "$SERIAL" shell dumpsys package $p | grep -m1 versionCode | tr -s ' ')"
done
echo "入れました: $TAG"
