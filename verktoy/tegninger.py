#!/usr/bin/env python3
"""Figurer tegnet på nytt i en chat (uten API): pakk ut en .bokhylle-figurer-fil og pakk tegningene tilbake.

  python3 verktoy/tegninger.py pakk-ut  Bok.bokhylle-figurer  mappe/
      Pakker ut figurer/, kontekst/ og figurer.json, og lister figurene (nøkkel, side, beskrivelse).
  python3 verktoy/tegninger.py pakk     mappe/  Bok.bokhylle-tegninger
      Lager tegningsfilen av mappe/tegninger/<nøkkel>.svg (figurer uten SVG hoppes over, f.eks. fotografier).

Filformatet er beskrevet i app/src/main/java/no/oystein/bokhylle/backup/FigureExchange.kt.
"""
import json
import pathlib
import sys
import zipfile


def pakk_ut(src, dst):
    dst = pathlib.Path(dst)
    dst.mkdir(parents=True, exist_ok=True)
    with zipfile.ZipFile(src) as z:
        for name in z.namelist():
            if ".." in name or name.startswith("/"):
                raise SystemExit(f"ugyldig filnavn i arkivet: {name}")
        z.extractall(dst)
    meta = json.loads((dst / "figurer.json").read_text())
    if meta.get("format") != "bokhylle-figurer":
        raise SystemExit("ikke en .bokhylle-figurer-fil")
    print(f"«{meta.get('title')}»: {len(meta['figures'])} figurer")
    for f in meta["figures"]:
        print(f"  {f['key']}  side {f.get('pageNumber') or '?'}  {f.get('description') or ''}"
              + (f"  [bildetekst: {f['caption']}]" if f.get("caption") else ""))


def pakk(src, dst):
    src = pathlib.Path(src)
    meta = json.loads((src / "figurer.json").read_text())
    drawings = []
    with zipfile.ZipFile(dst, "w", zipfile.ZIP_DEFLATED) as z:
        for f in meta["figures"]:
            svg = src / "tegninger" / f"{f['key']}.svg"
            if not svg.exists():
                continue
            text = svg.read_text()
            if "<svg" not in text or "</svg>" not in text:
                raise SystemExit(f"{svg} er ikke en SVG")
            z.writestr(f"tegninger/{f['key']}.svg", text)
            drawings.append({"key": f["key"], "svg": f"tegninger/{f['key']}.svg"})
        z.writestr("tegninger.json", json.dumps(
            {"format": "bokhylle-tegninger", "version": 1, "book": meta["book"], "drawings": drawings}, ensure_ascii=False))
    print(f"{dst}: {len(drawings)} av {len(meta['figures'])} figurer tegnet")


if __name__ == "__main__":
    if len(sys.argv) != 4 or sys.argv[1] not in ("pakk-ut", "pakk"):
        raise SystemExit(__doc__)
    (pakk_ut if sys.argv[1] == "pakk-ut" else pakk)(sys.argv[2], sys.argv[3])
