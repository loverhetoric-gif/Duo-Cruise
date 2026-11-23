#!/usr/bin/env python3
"""Duo Cruise の接続の記録(connlog)を車載機から吸い出し、1 日の流れを一覧にする。

  python3 tools/connlog.py pull                 # adb で吸い出す(~/.duo-cruise/connlog/ へ。無線 adb も探す)
  python3 tools/connlog.py show                 # 吸い出した一番新しい日の流れ
  python3 tools/connlog.py show 20261122        # その日の流れ
  python3 tools/connlog.py show 20261122 --snap # 30 秒ごとのまとめ(snap)も出す
  python3 tools/connlog.py show FILE.jsonl      # ファイルを直に
  python3 tools/connlog.py live                 # 車載機の今日の記録を吸い出さずに直に見る

一覧は「時刻 分類 出来事 中身」。最後に CarPlay のつながっていた時間帯と、その間の Wi-Fi・テザリングをまとめる。
"""
import json
import os
import subprocess
import sys
from datetime import datetime

PKG = "com.yuki.duocruise.main"
REMOTE = f"/sdcard/Android/data/{PKG}/files/connlog"
LOCAL = os.path.expanduser("~/.duo-cruise/connlog")

LABEL = {"carplay": "CarPlay", "youtube": "YouTube", "wifi": "Wi-Fi", "net": "通信の道", "ap": "ホットスポット",
         "bt": "Bluetooth", "sys": "機器", "snap": "まとめ"}


def adb(*args, serial=None, check=True):
    cmd = ["adb"] + (["-s", serial] if serial else []) + list(args)
    return subprocess.run(cmd, capture_output=True, text=True, check=check).stdout


def find_device():
    """Duo Cruise が入っている機器を探す(USB・無線。無線はポートが毎回変わるので mdns から)。"""
    def candidates():
        out = adb("devices")
        return [l.split()[0] for l in out.splitlines()[1:] if l.strip().endswith("device")]

    def has_app(s):
        return PKG in adb("shell", "pm", "list", "packages", PKG, serial=s, check=False)

    for s in candidates():
        if has_app(s):
            return s
    for line in adb("mdns", "services", check=False).splitlines():
        if "_adb-tls-connect" in line:
            adb("connect", line.split()[-1], check=False)
    for s in candidates():
        if has_app(s):
            return s
    sys.exit("Duo Cruise の入った機器が見つかりません(電源・同じネットワーク・無線デバッグ)")


def pull():
    s = find_device()
    model = adb("shell", "getprop", "ro.product.model", serial=s).strip().replace(" ", "_") or "device"
    dest = os.path.join(LOCAL, model)
    os.makedirs(dest, exist_ok=True)
    names = adb("shell", "ls", REMOTE, serial=s, check=False).split()
    names = [n for n in names if n.endswith(".jsonl")]
    if not names:
        sys.exit(f"{s}: 記録がまだありません({REMOTE})")
    for n in names:
        adb("pull", f"{REMOTE}/{n}", os.path.join(dest, n), serial=s)
    print(f"{s}({model}): {len(names)} 日分を {dest} へ吸い出しました(最新 {sorted(names)[-1]})")
    return dest


def short(v, n=90):
    s = v if isinstance(v, str) else json.dumps(v, ensure_ascii=False)
    return s if len(s) <= n else s[:n] + "…"


def describe(o):
    """1 行を人が読める中身に。"""
    c, e = o.get("cat"), o.get("ev")
    skip = {"t", "up", "cat", "ev"}
    if c == "wifi" and e == "sta":
        w = o.get("wifi", {})
        tether = " (iPhone のテザリング)" if w.get("iphoneTether") else ""
        return f"{o.get('state')} {w.get('ssid')} {w.get('bssid')} {w.get('freq')}MHz {w.get('rssi')}dBm ip={w.get('ip')}{tether}"
    if c == "carplay" and e in ("connected", "disconnected"):
        if e == "connected":
            gap = f"途切れ {o['gapS']} 秒(切れた理由: {o.get('downWhy')}) " if "gapS" in o else ""
            return f"{gap}{o.get('status')} / {o.get('path')}"
        return f"保った {o.get('keptS')} 秒 / 理由: {o.get('why')} / {o.get('path')}"
    if c == "sys" and e == "boot":
        how = "SHUTDOWN を受けてから" if o.get("prevShutdown") else "SHUTDOWN を受けずに(電源が突然切れた等)"
        return (f"起動の理由={o.get('bootReason')} 前回={o.get('bootReasonLast')} / 前回は{how}切れた"
                f"(最後に生きていた {o.get('prevLastAlive')})")
    rest = {k: v for k, v in o.items() if k not in skip}
    return " ".join(f"{k}={short(v)}" for k, v in rest.items())


def snap_line(o):
    car, yt, w, ap, bt = (o.get(k, {}) for k in ("car", "yt", "wifi", "ap", "bt"))
    cp = f"CarPlay {'接続' if car.get('conn') else '未接続'}{'・表示' if car.get('shown') else ''} {car.get('status')}"
    if car.get("iphone"):
        cp += f" iPhone={car['iphone']} 口={car.get('iface')}"
    ytt = f"YouTube {yt.get('state')} {yt.get('res', '')} 音量{yt.get('duck')}%"
    wf = f"Wi-Fi {w.get('ssid')} {w.get('ip')}{'(テザリング)' if w.get('iphoneTether') else ''} validated={w.get('validated')}"
    hs = f"AP {ap.get('state')} 台数={ap.get('count', '?')}"
    btc = ",".join(f"{k}:{len(v)}" for k, v in (bt.get("connected") or {}).items()) or "なし"
    return f"{cp} | {ytt} | {wf} | {hs} | BT {bt.get('state')} 接続={btc}"


def load(path):
    rows = []
    with open(path, encoding="utf-8") as f:
        for n, line in enumerate(f, 1):
            line = line.strip()
            if not line:
                continue
            try:
                rows.append(json.loads(line))
            except json.JSONDecodeError:
                rows.append({"t": "?", "cat": "sys", "ev": "壊れた行", "line": n})  # 電源断の直後の書きかけ
    return rows


def show(arg=None, with_snap=False, rows=None, title=None):
    if rows is None:
        if arg and os.path.isfile(arg):
            path = arg
        else:
            files = sorted(p for p in (os.path.join(r, f) for r, _, fs in os.walk(LOCAL) for f in fs) if p.endswith(".jsonl"))
            if arg:
                files = [p for p in files if os.path.basename(p).startswith(arg)]
            if not files:
                sys.exit("記録がありません。先に python3 tools/connlog.py pull")
            path = files[-1]
        rows, title = load(path), path
    print(f"== {title}({len(rows)} 行)")
    car_up, periods = None, []
    for o in rows:
        t = str(o.get("t", "?"))[11:19]
        c, e = o.get("cat"), o.get("ev")
        if c == "carplay" and e == "connected":
            car_up = o
        if c == "carplay" and e == "disconnected" and car_up:
            periods.append((car_up, o)); car_up = None
        # 切断の行が書かれずにアプリ・機器が終わったとき(入れ替え・落ちた・電源断)も、そこで区切る
        if c == "sys" and e in ("screen-close", "shutdown", "boot", "app-updated", "crash", "service-start") and car_up:
            periods.append((car_up, dict(o, cut=e))); car_up = None
        if c == "snap":
            if with_snap:
                print(f"{t}  {'まとめ':<8} {snap_line(o)}")
            continue
        print(f"{t}  {LABEL.get(c, c):<8} {e:<14} {describe(o)}")
    if car_up:
        periods.append((car_up, None))
    if periods:
        print("\n== CarPlay がつながっていた時間帯")
        for a, b in periods:
            end = (str(b.get("t"))[11:19] + (f"(アプリ・機器の {b['cut']} で区切り)" if b.get("cut") else "")) if b else "(記録の終わりまで)"
            print(f"  {str(a.get('t'))[11:19]} 〜 {end}  {a.get('path')}")
    snaps = [o for o in rows if o.get("cat") == "snap"]
    if snaps:
        print(f"\n== 最後のまとめ({str(snaps[-1].get('t'))[11:19]})\n  {snap_line(snaps[-1])}")


def live(with_snap=False):
    s = find_device()
    day = datetime.now().strftime("%Y%m%d")
    text = adb("shell", "cat", f"{REMOTE}/{day}.jsonl", serial=s)
    rows = []
    for line in text.splitlines():
        try:
            rows.append(json.loads(line))
        except json.JSONDecodeError:
            pass
    show(rows=rows, with_snap=with_snap, title=f"{s} {day}(直に)")


def main():
    a = [x for x in sys.argv[1:] if not x.startswith("--")]
    snap = "--snap" in sys.argv
    if not a or a[0] in ("-h", "help"):
        print(__doc__)
    elif a[0] == "pull":
        pull()
    elif a[0] == "show":
        show(a[1] if len(a) > 1 else None, snap)
    elif a[0] == "live":
        live(snap)
    else:
        print(__doc__)


if __name__ == "__main__":
    main()
