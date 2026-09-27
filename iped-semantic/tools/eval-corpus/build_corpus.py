#!/usr/bin/env python3
"""Builds the evaluation corpus of semantic search (research D18, T004) - with no real evidence.

    python build_corpus.py --out <evidence folder> [--cache <download cache>]

The folder it writes is an evidence to process with IPED (-d <folder>), with semantic text and visual
search on; the processed case is the "eval case" the quality harnesses read
(-Diped.semantic.test.evalCase). What goes in:

    chats/Android/msgstore.db, wa.db   conversations written for the corpus (content.py), in the legacy
                                       WhatsApp Android schema IPED's WhatsAppParser reads
    docs/{pt,en,es}/*.txt              documents written for the corpus, including a ~40-page report with
                                       a single pertinent paragraph
    docs/wiki/{pt,en,es}/*.txt         Wikipedia articles at pinned revisions (wikipedia.json), CC BY-SA,
                                       there only as distractors
    images/*.jpg                       Wikimedia Commons pictures pinned in commons.json (targets and
                                       distractors), checked by sha256
    videos/*.mp4                       slideshows of other Commons pictures (the "scene" ones), three
                                       scenes of ten seconds each

Everything is deterministic but the network: a pinned picture whose bytes changed on the server is
reported, and --accept-changed lets the build go on with the new bytes (the gabarito does not depend
on bytes, but the MANIFEST then records the new hash).
"""

import argparse
import hashlib
import html
import json
import os
import re
import shutil
import sqlite3
import subprocess
import sys
import tempfile
import time
import urllib.parse
import urllib.request

import content

HERE = os.path.dirname(os.path.abspath(__file__))
UA = {"User-Agent": "IPED-semantic-eval-corpus/1.0 (evaluation corpus of a forensic tool; "
                    "https://github.com/sepinf-inc/IPED)"}
BASE_MS = 1709334000000  # 2024-03-01 20:00 at UTC-3

# video file -> the scene concepts it shows, in order
VIDEOS = {
    "viagem_africa.mp4": ["girafa", "elefante", "leao"],
    "natureza.mp4": ["cachoeira", "vulcao", "deserto"],
    "cidade.mp4": ["engarrafamento", "feira", "canteiro-obras"],
    "festa.mp4": ["fogos", "carnaval", "rodeio"],
    "mar.mp4": ["peixes-recife", "porto-conteineres", "mercado-peixe"],
    "trabalho.mp4": ["fabrica", "sala-aula", "hospital"],
    "transporte.mp4": ["caminhao", "helicoptero", "aeroporto"],
    "clima.mp4": ["raio", "trigo", "esqui"],
}
SCENE_SECONDS = 10


def fetch(url, retries=5):
    for attempt in range(retries):
        try:
            with urllib.request.urlopen(urllib.request.Request(url, headers=UA), timeout=60) as r:
                return r.read()
        except Exception:
            time.sleep(2 * (attempt + 1))
    raise RuntimeError("download failed: " + url)


def write_text(path, text):
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, "w", encoding="utf-8", newline="\n") as f:
        f.write(text)


# ---------------------------------------------------------------------------------------------
# WhatsApp
# ---------------------------------------------------------------------------------------------

MESSAGES_DDL = (
    "CREATE TABLE messages (_id INTEGER PRIMARY KEY AUTOINCREMENT, key_remote_jid TEXT NOT NULL, key_from_me "
    "INTEGER, key_id TEXT NOT NULL, status INTEGER, needs_push INTEGER, data TEXT, timestamp INTEGER, media_url TEXT, "
    "media_mime_type TEXT, media_wa_type TEXT, media_size INTEGER, media_name TEXT, media_caption TEXT, media_hash "
    "TEXT, media_duration INTEGER, origin INTEGER, latitude REAL, longitude REAL, thumb_image TEXT, remote_resource "
    "TEXT, received_timestamp INTEGER, send_timestamp INTEGER, receipt_server_timestamp INTEGER, "
    "receipt_device_timestamp INTEGER, read_device_timestamp INTEGER, played_device_timestamp INTEGER, raw_data BLOB, "
    "recipient_count INTEGER, participant_hash TEXT, starred INTEGER, quoted_row_id INTEGER, mentioned_jids TEXT, "
    "multicast_id TEXT, edit_version INTEGER, media_enc_hash TEXT, payment_transaction_id TEXT, forwarded INTEGER, "
    "preview_type INTEGER, send_count INTEGER, lookup_tables INTEGER)")
CHAT_LIST_DDL = (
    "CREATE TABLE chat_list (_id INTEGER PRIMARY KEY AUTOINCREMENT, key_remote_jid TEXT UNIQUE, message_table_id "
    "INTEGER, subject TEXT, creation INTEGER, last_read_message_table_id INTEGER, archived INTEGER, sort_timestamp "
    "INTEGER)")
CONTACTS_DDL = (
    "CREATE TABLE wa_contacts (_id INTEGER PRIMARY KEY AUTOINCREMENT, jid TEXT NOT NULL, is_whatsapp_user BOOLEAN "
    "NOT NULL, status TEXT, status_timestamp INTEGER, number TEXT, raw_contact_id INTEGER, display_name TEXT, "
    "phone_type INTEGER, phone_label TEXT, unseen_msg_count INTEGER, photo_ts INTEGER, thumb_ts INTEGER, "
    "photo_id_timestamp INTEGER, given_name TEXT, family_name TEXT, wa_name TEXT, sort_name TEXT, nickname TEXT, "
    "company TEXT, title TEXT, status_autodownload_disabled INTEGER, keep_timestamp INTEGER, is_spam_reported "
    "INTEGER, is_sidelist_synced BOOLEAN DEFAULT 0, is_business_synced BOOLEAN DEFAULT 0)")


def build_whatsapp(folder):
    os.makedirs(folder, exist_ok=True)
    db = os.path.join(folder, "msgstore.db")
    if os.path.exists(db):
        os.remove(db)
    c = sqlite3.connect(db)
    c.execute(MESSAGES_DDL)
    c.execute(MESSAGES_DDL.replace("TABLE messages", "TABLE messages_quotes"))
    c.execute(CHAT_LIST_DDL)
    seq = 0
    for chat in content.CHATS:
        jid = chat["jid"]
        group = jid.endswith("@g.us")
        day0 = BASE_MS + chat["day"] * 86_400_000
        last = None
        for sender, minute, text in chat["messages"]:
            seq += 1
            ts = day0 + minute * 60_000
            from_me = 1 if sender == "me" else 0
            c.execute("INSERT INTO messages (key_remote_jid, key_from_me, key_id, status, data, timestamp, "
                      "media_wa_type, remote_resource, received_timestamp, media_size, forwarded, edit_version) "
                      "VALUES (?, ?, ?, ?, ?, ?, '0', ?, ?, 0, 0, 0)",
                      (jid, from_me, "EVAL%06d" % seq, 13 if from_me else 0, text, ts,
                       None if from_me or not group else sender, ts))
            last = (c.execute("SELECT last_insert_rowid()").fetchone()[0], ts)
        c.execute("INSERT INTO chat_list (key_remote_jid, message_table_id, subject, creation, sort_timestamp) "
                  "VALUES (?, ?, ?, ?, ?)", (jid, last[0], chat.get("subject"), day0 - 86_400_000, last[1]))
    c.commit()
    c.close()

    wa = os.path.join(folder, "wa.db")
    if os.path.exists(wa):
        os.remove(wa)
    c = sqlite3.connect(wa)
    c.execute(CONTACTS_DDL)
    for jid, name in content.CONTACTS.items():
        c.execute("INSERT INTO wa_contacts (jid, is_whatsapp_user, number, display_name, wa_name) "
                  "VALUES (?, 1, ?, ?, ?)", (jid, "+" + jid.split("@")[0], name, name))
    for chat in content.CHATS:
        if "subject" in chat:
            c.execute("INSERT INTO wa_contacts (jid, is_whatsapp_user, display_name) VALUES (?, 1, ?)",
                      (chat["jid"], chat["subject"]))
    c.commit()
    c.close()


# ---------------------------------------------------------------------------------------------
# Wikipedia
# ---------------------------------------------------------------------------------------------

def wiki_text(lang, revid):
    """The paragraphs of an article at a pinned revision, as plain text."""
    url = "https://%s.wikipedia.org/w/api.php?%s" % (lang, urllib.parse.urlencode(
        {"action": "parse", "oldid": revid, "prop": "text", "format": "json", "disabletoc": 1}))
    page = json.loads(fetch(url).decode("utf-8"))["parse"]["text"]["*"]
    paragraphs = []
    for p in re.findall(r"<p>(.*?)</p>", page, flags=re.S):
        t = re.sub(r"<sup[^>]*>.*?</sup>", "", p, flags=re.S)
        t = html.unescape(re.sub(r"<[^>]+>", "", t)).strip()
        if len(t) > 40:
            paragraphs.append(t)
    return "\n\n".join(paragraphs)[:20000]


def slug(title):
    s = re.sub(r"[^\w]+", "_", title, flags=re.U).strip("_")
    return s[:60]


# ---------------------------------------------------------------------------------------------
# Pictures and videos
# ---------------------------------------------------------------------------------------------

def picture(entry, cache, accept_changed, changed):
    path = os.path.join(cache, entry["file"])
    if not os.path.exists(path):
        data = fetch(entry["url"])
        with open(path, "wb") as f:
            f.write(data)
        time.sleep(0.2)
    data = open(path, "rb").read()
    sha = hashlib.sha256(data).hexdigest()
    if sha != entry["sha256"]:
        changed.append(entry["file"])
        if not accept_changed:
            raise SystemExit("%s changed on the server (sha256 %s, pinned %s); rerun with --accept-changed to "
                             "use the new bytes" % (entry["file"], sha, entry["sha256"]))
    return path


def build_video(out, scene_pictures, ffmpeg):
    """Each scene is its pictures, shown for SCENE_SECONDS in total, 640x360 letterboxed, 25 fps."""
    tmp = tempfile.mkdtemp()
    try:
        listing = os.path.join(tmp, "list.txt")
        with open(listing, "w", encoding="utf-8") as f:
            last = None
            for pictures in scene_pictures:
                each = SCENE_SECONDS / len(pictures)
                for p in pictures:
                    f.write("file '%s'\nduration %s\n" % (p.replace("\\", "/"), each))
                    last = p
            f.write("file '%s'\n" % last.replace("\\", "/"))
        subprocess.run([ffmpeg, "-y", "-loglevel", "error", "-f", "concat", "-safe", "0", "-i", listing,
                        "-vf", "scale=640:360:force_original_aspect_ratio=decrease,pad=640:360:(ow-iw)/2:(oh-ih)/2,"
                               "format=yuv420p",
                        "-r", "25", "-c:v", "libx264", "-preset", "medium", "-crf", "23", "-bitexact",
                        "-fflags", "+bitexact", out], check=True)
    finally:
        shutil.rmtree(tmp, ignore_errors=True)


# ---------------------------------------------------------------------------------------------

def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--out", required=True, help="evidence folder to write (emptied first)")
    parser.add_argument("--cache", help="download cache (default: <out>-cache)")
    parser.add_argument("--accept-changed", action="store_true")
    args = parser.parse_args()
    out = os.path.abspath(args.out)
    cache = os.path.abspath(args.cache or out + "-cache")
    if os.path.exists(out):
        shutil.rmtree(out)
    os.makedirs(out)
    os.makedirs(cache, exist_ok=True)

    build_whatsapp(os.path.join(out, "chats", "Android"))
    for path, text in content.DOCS.items():
        write_text(os.path.join(out, path), text + "\n")
    write_text(os.path.join(out, content.LONG_DOC_PATH), content.long_document())

    wiki = json.load(open(os.path.join(HERE, "wikipedia.json"), encoding="utf-8"))
    for w in wiki:
        cached = os.path.join(cache, "wiki_%s_%d.txt" % (w["lang"], w["revid"]))
        if not os.path.exists(cached):
            write_text(cached, wiki_text(w["lang"], w["revid"]))
            time.sleep(0.2)
        dest = os.path.join(out, "docs", "wiki", w["lang"])
        os.makedirs(dest, exist_ok=True)
        shutil.copyfile(cached, os.path.join(dest, slug(w["title"]) + ".txt"))

    commons = json.load(open(os.path.join(HERE, "commons.json"), encoding="utf-8"))
    changed = []
    os.makedirs(os.path.join(out, "images"))
    scenes = {}
    for e in commons:
        path = picture(e, cache, args.accept_changed, changed)
        if e["role"] == "scene":
            scenes.setdefault(e["concept"], []).append(path)
        else:
            shutil.copyfile(path, os.path.join(out, "images", e["file"]))

    import imageio_ffmpeg
    ffmpeg = imageio_ffmpeg.get_ffmpeg_exe()
    os.makedirs(os.path.join(out, "videos"))
    for name, concepts in VIDEOS.items():
        build_video(os.path.join(out, "videos", name), [scenes[c] for c in concepts], ffmpeg)

    counts = {}
    for base, _, files in os.walk(out):
        for f in files:
            top = os.path.relpath(base, out).split(os.sep)[0]
            counts[top] = counts.get(top, 0) + 1
    print(json.dumps({"out": out, "files": counts, "changedPictures": changed}, indent=1))
    if changed:
        print("WARNING: %d pictures changed on the server; the MANIFEST hashes are stale" % len(changed),
              file=sys.stderr)


if __name__ == "__main__":
    main()
