"""strings.py(原本)から、app と carplay の strings.xml を言語ごとに書き出す。

  python3 apps/duo/i18n/gen.py

既定(values/)は英語。対応していない言語の端末では英語になる。香港は繁体字(台湾と同じ)を使う。
"""
import os
import re
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
from strings import APP, CARPLAY, LANGS  # noqa: E402

DUO = os.path.dirname(HERE)
EXTRA_DIRS = {"zh-rTW": ["zh-rHK"]}


def fix_zh(s):
    # 中国語は全角の句読点と括弧に揃える(書式 %1$s などはそのまま)
    s = re.sub(r",(?=\S)", "，", s)
    s = s.replace(",", "，").replace("(", "（").replace(")", "）")
    return s


def esc(s):
    s = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
    s = s.replace("\\", "\\\\").replace("'", "\\'").replace('"', '\\"')
    s = s.replace("\n", "\\n").replace("\t", "\\t")
    if s.startswith(" ") or s.endswith(" "):
        s = '"' + s + '"'  # 前後の空白を残す
    return s


def write(module, table, extra=None):
    for i, lang in enumerate(LANGS):
        d = "values" if lang == "en" else "values-" + lang
        lines = ['<?xml version="1.0" encoding="utf-8"?>',
                 "<!-- apps/duo/i18n/gen.py が strings.py から書き出す。直接は直さない -->",
                 "<resources>"]
        if extra:
            for k, v in extra.items():
                lines.append('    <string name="%s" translatable="false">%s</string>' % (k, esc(v)))
        for key, vals in table.items():
            v = vals[i]
            if lang.startswith("zh"):
                v = fix_zh(v)
            fmt = "" if re.search(r"%(\d\$)?[sd]", v) else ""
            lines.append('    <string name="%s"%s>%s</string>' % (key, fmt, esc(v)))
        lines.append("</resources>")
        text = "\n".join(lines) + "\n"
        for dd in [d] + ["values-" + x for x in EXTRA_DIRS.get(lang, [])]:
            path = os.path.join(DUO, module, "src/main/res", dd, "strings.xml")
            os.makedirs(os.path.dirname(path), exist_ok=True)
            with open(path, "w", encoding="utf-8") as f:
                f.write(text)
            print("wrote", os.path.relpath(path, DUO))


write("app", APP, extra={"app_name": "Duo Cruise"})
write("carplay", CARPLAY, extra=None)
