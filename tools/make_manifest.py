#!/usr/bin/env python3
"""公開用の manifest.json を作る。使い方: make_manifest.py <出力フォルダ> <aapt2> <ビルド番号>"""
import hashlib, json, os, re, subprocess, sys

out, aapt2, build = sys.argv[1], sys.argv[2], sys.argv[3]
LABELS = {
    "duo-updater.apk": "Duo Cruise 更新",
    "duo-keys.apk": "Duo Cruise キー記録",
    "duo-airplay.apk": "Duo Cruise AirPlay",
}
apps = []
for name, label in LABELS.items():
    path = os.path.join(out, name)
    badge = subprocess.run([aapt2, "dump", "badging", path], capture_output=True, text=True, check=True).stdout
    m = re.search(r"package: name='([^']+)' versionCode='(\d+)'", badge)
    if not m:
        sys.exit("badging を読めません: " + name)
    data = open(path, "rb").read()
    apps.append({
        "package": m.group(1),
        "label": label,
        "versionCode": int(m.group(2)),
        "file": name,
        "sha256": hashlib.sha256(data).hexdigest(),
        "size": len(data),
    })
json.dump({"build": int(build), "apps": apps}, open(os.path.join(out, "manifest.json"), "w"), ensure_ascii=False, indent=2)
print("manifest.json を作りました:", [(a["package"], a["versionCode"]) for a in apps])
