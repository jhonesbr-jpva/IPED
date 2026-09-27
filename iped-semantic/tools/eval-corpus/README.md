# eval-corpus — the evaluation corpus of semantic search

Research D18, tasks T004 and T005. The success criteria of semantic search (SC-001 to SC-003, SC-012,
SC-014, SC-018) are measured on a corpus built here, **with no real evidence**:

- **Conversations** written for the corpus, in Portuguese with slang, abbreviation and code, in a
  WhatsApp Android database (legacy schema) that IPED's `WhatsAppParser` reads, plus a `wa.db` with
  the contact names. They include the dialogue of the spec — "quanto?" / "5 mil" / "fechado, amanhã
  na portaria" — and the nickname "Baiano".
- **Documents** written for the corpus in Portuguese, English and Spanish, and a ~40-page report
  (107 KB) with a single pertinent paragraph.
- **100 Wikipedia articles** (PT, EN, ES) at pinned revisions, as distractors, so that "among the
  first ten" means something.
- **151 pictures** from Wikimedia Commons under CC0, public domain, CC BY or CC BY-SA: 101 that
  questions point at, in 36 concepts, and 50 distractors.
- **8 videos**, slideshows of 45 other Commons pictures, three ten-second scenes each.

The conversations and the documents are fiction: `content.py`. Where every other file came from,
with author and licence: [MANIFEST.md](MANIFEST.md).

## Files

| File | What |
|---|---|
| `content.py` | The written text: contacts, conversations, documents, the long report |
| `commons.json` | The pinned Commons pictures: file, concept, role (target, distractor, scene), source page, author, licence, sha256 of the bytes |
| `wikipedia.json` | The pinned articles: language, title, revision |
| `build_corpus.py` | Builds the evidence folder from the above; checks every picture against its pinned sha256 |
| `make_questions.py` | Writes the gabarito, `iped-semantic/src/test/resources/eval/questions.json` |
| `make_manifest.py` | Writes `MANIFEST.md` |
| `discover.py`, `contact_sheet.py` | How the pictures and articles were chosen: search with a licence filter, then a person looks at contact sheets and keeps what shows the concept. Run once; the result is pinned |

## Building the corpus and the eval case

```powershell
python -m venv .venv
.venv\Scripts\pip install -r ..\export-models\requirements-visual.txt imageio-ffmpeg==0.6.0
.venv\Scripts\python build_corpus.py --out <dir>\eval-ev
```

Then process `<dir>\eval-ev` with IPED, with semantic text, image and video search on (the visual
model installed), into the eval case. The quality harnesses of `iped-semantic` read that case:

```powershell
mvn -pl iped-semantic test -Diped.semantic.test.evalCase=<eval case> ...
```

## The gabarito

`questions.json` was written **before the first measurement** and its commit is recorded in
`specs/009-semantic-search/validation-log.md`. Each question has a type, a language and its
pertinent items:

| Type | Count | Measures |
|---|---|---|
| `conceptual` | 43 | SC-001 — the harness drops those whose keyword search already finds a pertinent item |
| `crosslingual` | 12 | SC-002 — pertinent items only in English or Spanish |
| `mixed` | 16 | SC-018 — a concept plus a nickname, codename, place or jargon |
| `visual-image` | 42 (6 in English) | SC-012 and FR-036 |
| `visual-video` | 24 | SC-014 |

Items are keyed so any case processed from the corpus resolves them: a file by its path in the
evidence (`{"path": "docs/pt/bilhete.txt"}`), a message by its exact body
(`{"message": "5 mil"}`; every body the gabarito cites is unique in the corpus).

Text questions carry a `subject` (version 2). SC-003 ("more like this") reads "another item
pertinent to the **same subject**": a subject's pertinent items are those of all its questions, and
from an item of one unit (a chat, or a file) another unit's item must come in the first ten.

| Version | Commit | Change |
|---|---|---|
| 1 | `acdea125` | First gabarito, closed before the first measurement |
| 2 | see `validation-log.md` | Adds `subject` to the text questions, because SC-003 speaks of subjects and version 1 had none (its SC-003 attempts used each question alone). Questions and pertinent items unchanged |

To change a question after the first measurement is to change what was measured: add a new version,
never edit silently. The same holds for the corpus:

| Corpus | Change |
|---|---|
| 1 | First corpus, built for the first measurement |
| 2 | `docs/en/laundering_memo.txt` begins with "INTERNAL MEMO" instead of "MEMO". "MEMO" at offset 0 is the magic IPED's `CustomSignatures.xml` gives Windows SuperFetch files (`application/x-superfetch`): IPED read the memo as one, and its text reached no index, keyword or semantic. The question pointing at it measured IPED's signature detection, not semantic search. No other text changed |

A text written in `content.py` must not begin with a magic IPED knows.
