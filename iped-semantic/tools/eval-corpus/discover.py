#!/usr/bin/env python3
"""One-time discovery of open-license pictures and articles for the evaluation corpus (research D18).

Searches Wikimedia Commons for each concept of CONCEPTS and keeps the first candidates whose licence
allows redistribution (CC0, public domain, CC BY, CC BY-SA), writing them to candidates.json with the
licence, the author and the credit line. A person then LOOKS at every candidate (contact_sheet.py),
keeps the ones that show what the concept says, and the kept ones are pinned in commons.json - the
list build_corpus.py downloads and checks by sha256. Search results change over time; the pinned
list does not.

    python discover.py commons --out candidates.json
    python discover.py wikipedia --out wikipedia.json

This script talks to the network. build_corpus.py only downloads what is pinned.
"""

import argparse
import hashlib
import html
import json
import re
import time
import urllib.parse
import urllib.request

UA = {"User-Agent": "IPED-semantic-eval-corpus/1.0 (evaluation corpus of a forensic tool; "
                    "https://github.com/sepinf-inc/IPED)"}
ACCEPTED = re.compile(r"^(CC0|Public domain|PD|CC BY(-SA)? ?[0-9.]*)", re.I)

# concept -> (search terms, how many to keep, role). Roles: "target" (a question points at it),
# "distractor" (only there to make the top 10 mean something), "scene" (a scene of a video).
CONCEPTS = {
    "praia": ("beach sand sea waves", 3, "target"),
    "cachorro": ("dog portrait", 3, "target"),
    "gato": ("domestic cat portrait", 3, "target"),
    "cavalo": ("horse pasture", 3, "target"),
    "carro-vermelho": ("red sports car", 3, "target"),
    "motocicleta": ("motorcycle parked", 3, "target"),
    "bicicleta": ("bicycle parked", 3, "target"),
    "aviao": ("airliner airplane flying", 3, "target"),
    "barco": ("sailboat", 3, "target"),
    "trem": ("passenger train station", 3, "target"),
    "ponte": ("suspension bridge", 3, "target"),
    "igreja": ("church facade", 3, "target"),
    "cidade-noite": ("city skyline at night", 3, "target"),
    "floresta": ("forest trees path", 3, "target"),
    "montanha-neve": ("snow covered mountain peak", 3, "target"),
    "pizza": ("pizza", 3, "target"),
    "frutas": ("fruit bowl", 3, "target"),
    "flores": ("flower bouquet", 3, "target"),
    "pistola": ("pistol handgun", 4, "target"),
    "faca": ("kitchen knife", 3, "target"),
    "dinheiro": ("banknotes money", 3, "target"),
    "laptop": ("laptop computer", 3, "target"),
    "celular": ("smartphone", 3, "target"),
    "bola-futebol": ("soccer ball grass", 3, "target"),
    "estadio": ("football stadium", 3, "target"),
    "viatura-policia": ("police car", 3, "target"),
    "incendio": ("house fire firefighters", 3, "target"),
    "cerveja": ("beer bottles", 3, "target"),
    "cigarro": ("cigarette ashtray", 3, "target"),
    "comprimidos": ("pills tablets", 3, "target"),
    "seringa": ("syringe", 3, "target"),
    "maconha": ("cannabis plants", 3, "target"),
    "passaporte": ("passport", 3, "target"),
    "multidao": ("crowd protest street", 3, "target"),
    "ferramentas": ("hand tools hammer", 3, "target"),
    "cozinha": ("modern kitchen", 3, "target"),
    # distractors
    "cadeira": ("wooden chair", 2, "distractor"),
    "relogio": ("wall clock", 2, "distractor"),
    "estatua": ("marble statue", 2, "distractor"),
    "ovelha": ("sheep", 2, "distractor"),
    "vaca": ("cow", 2, "distractor"),
    "galinha": ("chicken hen", 2, "distractor"),
    "rio": ("river landscape", 2, "distractor"),
    "lago": ("lake reflection", 2, "distractor"),
    "estrada": ("country road", 2, "distractor"),
    "onibus": ("city bus", 2, "distractor"),
    "trator": ("tractor field", 2, "distractor"),
    "arranha-ceu": ("skyscraper", 2, "distractor"),
    "estante": ("bookshelf library", 2, "distractor"),
    "piano": ("grand piano", 2, "distractor"),
    "violao": ("acoustic guitar", 2, "distractor"),
    "tenis": ("tennis court", 2, "distractor"),
    "piscina": ("swimming pool", 2, "distractor"),
    "jardim": ("garden flowers", 2, "distractor"),
    "girassol": ("sunflower", 2, "distractor"),
    "borboleta": ("butterfly", 2, "distractor"),
    "pao": ("loaf of bread", 2, "distractor"),
    "cafe": ("coffee cup", 2, "distractor"),
    "vinho": ("wine glass", 2, "distractor"),
    "bolo": ("birthday cake", 2, "distractor"),
    "sushi": ("sushi", 2, "distractor"),
    "castelo": ("castle", 2, "distractor"),
    "farol": ("lighthouse", 2, "distractor"),
    "museu": ("museum interior", 2, "distractor"),
    # video scenes (not used as standalone pictures)
    "cachoeira": ("waterfall", 2, "scene"),
    "fogos": ("fireworks", 2, "scene"),
    "engarrafamento": ("traffic jam", 2, "scene"),
    "feira": ("street market stalls", 2, "scene"),
    "vulcao": ("volcano eruption", 2, "scene"),
    "deserto": ("desert sand dunes", 2, "scene"),
    "girafa": ("giraffe", 2, "scene"),
    "elefante": ("elephant", 2, "scene"),
    "leao": ("lion", 2, "scene"),
    "peixes-recife": ("coral reef fish underwater", 2, "scene"),
    "sala-aula": ("classroom students", 2, "scene"),
    "fabrica": ("factory production line", 2, "scene"),
    "porto-conteineres": ("container port cranes", 2, "scene"),
    "caminhao": ("truck highway", 2, "scene"),
    "helicoptero": ("helicopter flying", 2, "scene"),
    "raio": ("lightning storm", 2, "scene"),
    "trigo": ("wheat field", 2, "scene"),
    "esqui": ("skiing slope skiers", 2, "scene"),
    "hospital": ("hospital room bed", 2, "scene"),
    "mercado-peixe": ("fish market", 2, "scene"),
    "canteiro-obras": ("construction site crane", 2, "scene"),
    "aeroporto": ("airport terminal", 2, "scene"),
    "rodeio": ("rodeo", 2, "scene"),
    "carnaval": ("carnival parade", 2, "scene"),
}


def get(url, params):
    req = urllib.request.Request(url + "?" + urllib.parse.urlencode(params), headers=UA)
    for attempt in range(4):
        try:
            with urllib.request.urlopen(req, timeout=60) as r:
                return json.load(r)
        except Exception:
            time.sleep(2 * (attempt + 1))
    raise RuntimeError("failed: " + url)


def strip_html(s):
    return html.unescape(re.sub(r"<[^>]+>", "", s or "")).strip()


def commons(out, only=None):
    result = {}
    if only:
        # refresh some concepts of an existing candidates file
        with open(out, encoding="utf-8") as f:
            result = json.load(f)
    for concept, (terms, keep, role) in CONCEPTS.items():
        if only and concept not in only:
            continue
        r = get("https://commons.wikimedia.org/w/api.php", {
            "action": "query", "generator": "search", "gsrnamespace": 6,
            "gsrsearch": terms + " filetype:bitmap", "gsrlimit": 25, "prop": "imageinfo",
            "iiprop": "url|sha1|size|extmetadata|mime", "iiurlwidth": 800, "format": "json"})
        pages = sorted(r.get("query", {}).get("pages", {}).values(), key=lambda p: p.get("index", 0))
        chosen = []
        for p in pages:
            ii = p["imageinfo"][0]
            md = ii.get("extmetadata", {})
            lic = md.get("LicenseShortName", {}).get("value", "")
            if ii.get("mime") != "image/jpeg" or ii.get("width", 0) < 800 or not ACCEPTED.match(lic):
                continue
            chosen.append({"title": p["title"], "page": ii["descriptionurl"], "url": ii["thumburl"],
                           "license": lic, "licenseUrl": md.get("LicenseUrl", {}).get("value", ""),
                           "author": strip_html(md.get("Artist", {}).get("value", "")),
                           "credit": strip_html(md.get("Credit", {}).get("value", ""))})
            if len(chosen) >= keep + 3:  # a few spares for the visual review
                break
        result[concept] = {"terms": terms, "keep": keep, "role": role, "candidates": chosen}
        print(concept, len(chosen))
        time.sleep(0.5)
    with open(out, "w", encoding="utf-8", newline="\n") as f:
        json.dump(result, f, indent=1, ensure_ascii=False)


# Distractor articles: everyday and encyclopaedic subjects far from the questions.
WIKIPEDIA = {
    "pt": ["Brasília", "Rio Amazonas", "Futebol", "Samba", "Café", "Mata Atlântica", "Pantanal", "Machado de Assis",
           "Carnaval do Brasil", "Feijoada", "Capoeira", "Cerrado", "Floresta Amazônica", "Salvador (Bahia)",
           "Ouro Preto", "Pão de Açúcar", "Cristo Redentor", "Bossa nova", "Tom Jobim", "Pelé", "Ayrton Senna",
           "Energia solar", "Fotossíntese", "Sistema Solar", "Lua", "Vulcão", "Terremoto", "Oceano Atlântico",
           "Revolução Industrial", "Império Romano", "Idade Média", "Renascimento", "Segunda Guerra Mundial",
           "Independência do Brasil", "Proclamação da República do Brasil", "Abolição da escravatura no Brasil",
           "Língua portuguesa", "Música popular brasileira", "Cinema", "Teatro", "Xadrez", "Voleibol", "Basquetebol",
           "Natação", "Olimpíadas", "Arroz", "Mandioca", "Cana-de-açúcar", "Soja", "Gado bovino"],
    "en": ["Photosynthesis", "Solar System", "Volcano", "Earthquake", "Roman Empire", "Renaissance",
           "Industrial Revolution", "Chess", "Basketball", "Tennis", "Coffee", "Tea", "Bread", "Cheese",
           "Honey bee", "Elephant", "Whale", "Penguin", "Rainforest", "Desert", "Glacier", "Mount Everest",
           "Amazon River", "Nile", "Pacific Ocean", "Printing press", "Telephone", "Internet", "Railway",
           "Bicycle", "Olympic Games", "Jazz", "Opera", "Impressionism", "Architecture"],
    "es": ["Madrid", "Buenos Aires", "Cordillera de los Andes", "Tango", "Flamenco", "Paella", "Café",
           "Fútbol", "Río de la Plata", "Machu Picchu", "Don Quijote de la Mancha", "Pablo Picasso",
           "Gabriel García Márquez", "Volcán", "Sistema solar"],
}


def wikipedia(out):
    result = []
    for lang, titles in WIKIPEDIA.items():
        for title in titles:
            r = get("https://%s.wikipedia.org/w/api.php" % lang, {
                "action": "query", "prop": "extracts|revisions|info", "explaintext": 1, "titles": title,
                "rvprop": "ids", "inprop": "url", "redirects": 1, "format": "json"})
            page = next(iter(r["query"]["pages"].values()))
            if "extract" not in page:
                print("missing", lang, title)
                continue
            text = page["extract"][:20000]
            result.append({"lang": lang, "title": page["title"], "revid": page["revisions"][0]["revid"],
                           "url": page["fullurl"], "license": "CC BY-SA 4.0",
                           "sha256": hashlib.sha256(text.encode("utf-8")).hexdigest(), "chars": len(text)})
            print(lang, page["title"], len(text))
            time.sleep(0.3)
    with open(out, "w", encoding="utf-8", newline="\n") as f:
        json.dump(result, f, indent=1, ensure_ascii=False)


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("what", choices=["commons", "wikipedia"])
    parser.add_argument("--out", required=True)
    parser.add_argument("--only", help="commons: comma-separated concepts to refresh in an existing --out")
    args = parser.parse_args()
    if args.what == "commons":
        commons(args.out, set(args.only.split(",")) if args.only else None)
    else:
        wikipedia(args.out)


if __name__ == "__main__":
    main()
