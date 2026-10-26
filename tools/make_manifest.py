#!/usr/bin/env python3
"""公開用の manifest.json を作る。

使い方: make_manifest.py <出力フォルダ> <aapt2> <ビルド番号> [1つ前の正式版のタグ]

- apps     … このビルドのアプリ(ファイルが無いものは省く。例: main に apps/duo が無いときの本体)
- rollback … 1つ前の正式版の中身を、今と同じ版番号で作り直したもの(prev-*.apk。中身が変わったアプリだけ)
              更新アプリの「1つ前の版に戻す」が使う。古い更新アプリはこの項目を読まない
"""
import hashlib, json, os, re, subprocess, sys

out, aapt2, build = sys.argv[1], sys.argv[2], sys.argv[3]
prev_tag = sys.argv[4] if len(sys.argv) > 4 else ""
LABELS = {
    "duo-updater.apk": "Duo Cruise 更新",
    "duo-keys.apk": "Duo Cruise キー記録",
    "duo-main.apk": "Duo Cruise",
    "duo-airplay.apk": "Duo Cruise AirPlay",
}
REQUIRED = {"duo-updater.apk", "duo-keys.apk", "duo-airplay.apk"}


def entry(name, label):
    path = os.path.join(out, name)
    badge = subprocess.run([aapt2, "dump", "badging", path], capture_output=True, text=True, check=True).stdout
    m = re.search(r"package: name='([^']+)' versionCode='(\d+)'", badge)
    if not m:
        sys.exit("badging を読めません: " + name)
    data = open(path, "rb").read()
    return {
        "package": m.group(1),
        "label": label,
        "versionCode": int(m.group(2)),
        "file": name,
        "sha256": hashlib.sha256(data).hexdigest(),
        "size": len(data),
    }


apps, rollback = [], []
for name, label in LABELS.items():
    if os.path.exists(os.path.join(out, name)):
        apps.append(entry(name, label))
    elif name in REQUIRED:
        sys.exit("見つかりません: " + name)
    prev = "prev-" + name
    if os.path.exists(os.path.join(out, prev)):
        rollback.append(entry(prev, label + "(1つ前)"))

# 戻す用は、今の版と同じ版番号でなければ意味がない(下げられない・上げると自動で入ってしまう)
now = {a["package"]: a["versionCode"] for a in apps}
for r in rollback:
    if now.get(r["package"]) != r["versionCode"]:
        sys.exit("戻す用の版番号が今と違います: " + r["file"])

result = {"build": int(build), "apps": apps}
if rollback:
    result["rollback"] = rollback
    result["rollbackOf"] = prev_tag
json.dump(result, open(os.path.join(out, "manifest.json"), "w"), ensure_ascii=False, indent=2)
print("manifest.json を作りました:", [(a["package"], a["versionCode"]) for a in apps],
      "戻す用:", [(a["package"], a["versionCode"]) for a in rollback])
