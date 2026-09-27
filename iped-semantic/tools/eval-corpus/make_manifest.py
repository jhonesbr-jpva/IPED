#!/usr/bin/env python3
"""Writes MANIFEST.md: origin, author and licence of every picture, video scene and article of the corpus."""

import json
import os

import build_corpus

HERE = os.path.dirname(os.path.abspath(__file__))


def esc(s):
    return (s or "").replace("|", "\\|").replace("\n", " ").strip()


def main():
    commons = json.load(open(os.path.join(HERE, "commons.json"), encoding="utf-8"))
    wiki = json.load(open(os.path.join(HERE, "wikipedia.json"), encoding="utf-8"))
    lines = ["# MANIFEST - evaluation corpus of semantic search", "",
             "Everything in the corpus that was not written for it, with where it came from and under which "
             "licence. Written by `make_manifest.py` from `commons.json` and `wikipedia.json`; do not edit by hand.",
             "", "The conversations and the documents under `docs/pt`, `docs/en` and `docs/es` are fiction written "
             "for this corpus (`content.py`), with no real person, case or evidence.", ""]
    for role, title in (("target", "Pictures that questions point at (`images/`)"),
                        ("distractor", "Distractor pictures (`images/`)"),
                        ("scene", "Pictures shown as scenes of the videos (`videos/`)")):
        lines += ["## " + title, "", "| File | Concept | Source | Author | Licence | sha256 (first 12) |",
                  "|---|---|---|---|---|---|"]
        for e in commons:
            if e["role"] != role:
                continue
            lines.append("| %s | %s | [%s](%s) | %s | %s | `%s` |" % (
                e["file"], e["concept"], esc(e["title"]), e["page"], esc(e["author"])[:80], esc(e["license"]),
                e["sha256"][:12]))
        lines.append("")
    lines += ["## Videos (`videos/`)", "",
              "Slideshows made by `build_corpus.py`: each scene is its pictures (above) for %d seconds, 640x360, "
              "25 fps, H.264. The licence of a video is that of its pictures." % build_corpus.SCENE_SECONDS, "",
              "| Video | Scenes, in order |", "|---|---|"]
    for video, scenes in build_corpus.VIDEOS.items():
        lines.append("| %s | %s |" % (video, ", ".join(scenes)))
    lines += ["", "## Wikipedia articles (`docs/wiki/`) - distractors", "",
              "Paragraphs of each article at the pinned revision, up to 20 000 characters. Licence CC BY-SA 4.0; "
              "authors in each article's history.", "", "| Language | Article | Revision |", "|---|---|---|"]
    for w in wiki:
        lines.append("| %s | [%s](%s) | %d |" % (w["lang"], esc(w["title"]), w["url"], w["revid"]))
    with open(os.path.join(HERE, "MANIFEST.md"), "w", encoding="utf-8", newline="\n") as f:
        f.write("\n".join(lines) + "\n")
    print(len(commons), "pictures,", len(wiki), "articles")


if __name__ == "__main__":
    main()
