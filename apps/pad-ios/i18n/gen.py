# strings.py から DuoPad/Localizable.xcstrings と DuoPad/InfoPlist.xcstrings を書き出す。
# 使い方: python3 -I apps/pad-ios/i18n/gen.py
import json, os, sys

here = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, here)
from strings import LANGS, S, INFO

def catalog(table):
    out = {"sourceLanguage": "ja", "strings": {}, "version": "1.0"}
    for key, vals in table.items():
        assert len(vals) == len(LANGS), key
        out["strings"][key] = {"localizations": {
            lang: {"stringUnit": {"state": "translated", "value": v}} for lang, v in zip(LANGS, vals)}}
    return out

dst = os.path.join(here, "..", "DuoPad")
for name, table in (("Localizable", S), ("InfoPlist", INFO)):
    with open(os.path.join(dst, name + ".xcstrings"), "w", encoding="utf-8") as f:
        json.dump(catalog(table), f, ensure_ascii=False, indent=2, sort_keys=True)
        f.write("\n")
print("ok", len(S), "strings,", len(INFO), "Info.plist strings")
