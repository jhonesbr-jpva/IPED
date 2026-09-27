#!/usr/bin/env python3
"""Contact sheets of the Commons candidates, for the visual review that decides what is pinned.

    python contact_sheet.py candidates.json --cache <dir> --out <dir> [--per-concept N]

Each thumbnail is labelled "<concept>#<index>"; the index is the candidate's position in
candidates.json. Search results are not trusted to show what the concept says: a person looks.
"""

import argparse
import json
import os
import time
import urllib.request

from PIL import Image, ImageDraw

UA = {"User-Agent": "IPED-semantic-eval-corpus/1.0 (evaluation corpus of a forensic tool; "
                    "https://github.com/sepinf-inc/IPED)"}
CELL = 180
COLS = 6
ROWS = 5


def fetch(url, path):
    if os.path.exists(path):
        return
    for attempt in range(4):
        try:
            req = urllib.request.Request(url, headers=UA)
            with urllib.request.urlopen(req, timeout=60) as r, open(path, "wb") as f:
                f.write(r.read())
            return
        except Exception:
            time.sleep(2 * (attempt + 1))
    raise RuntimeError("download failed: " + url)


def main():
    p = argparse.ArgumentParser()
    p.add_argument("candidates")
    p.add_argument("--cache", required=True)
    p.add_argument("--out", required=True)
    p.add_argument("--per-concept", type=int, default=0, help="0: keep + 1")
    p.add_argument("--concepts", help="comma-separated subset")
    a = p.parse_args()
    data = json.load(open(a.candidates, encoding="utf-8"))
    if a.concepts:
        wanted = a.concepts.split(",")
        data = {k: v for k, v in data.items() if k in wanted}
    os.makedirs(a.cache, exist_ok=True)
    os.makedirs(a.out, exist_ok=True)
    cells = []
    for concept, c in data.items():
        n = a.per_concept or c["keep"] + 1
        for i, cand in enumerate(c["candidates"][:n]):
            path = os.path.join(a.cache, "%s_%d.jpg" % (concept, i))
            fetch(cand["url"], path)
            cells.append(("%s#%d" % (concept, i), path))
            time.sleep(0.2)
    per_sheet = COLS * ROWS
    for s in range(0, len(cells), per_sheet):
        sheet = Image.new("RGB", (COLS * CELL, ROWS * (CELL + 16)), "white")
        d = ImageDraw.Draw(sheet)
        for k, (label, path) in enumerate(cells[s:s + per_sheet]):
            im = Image.open(path).convert("RGB")
            im.thumbnail((CELL - 4, CELL - 4))
            x, y = (k % COLS) * CELL, (k // COLS) * (CELL + 16)
            sheet.paste(im, (x + 2, y + 2))
            d.text((x + 3, y + CELL), label, fill="black")
        sheet.save(os.path.join(a.out, "sheet_%02d.jpg" % (s // per_sheet)), quality=85)
    print(len(cells), "thumbnails,", (len(cells) + per_sheet - 1) // per_sheet, "sheets")


if __name__ == "__main__":
    main()
