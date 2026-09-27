#!/usr/bin/env python3
"""Writes the gabarito of the evaluation corpus (T005): iped-semantic/src/test/resources/eval/questions.json.

    python make_questions.py

Questions and their pertinent items were written BEFORE the first measurement. Items are keyed so a
harness finds them in any case processed from the corpus: a file by its path in the evidence, a chat
message by its exact body (every body cited here is unique in the corpus). Messages are referenced
by position in content.py, so a body is never retyped.

Types: conceptual (SC-001; the harness drops those whose keyword search already finds a pertinent
item), crosslingual (SC-002; pertinent items only in another language), mixed (SC-018; concept plus
a nickname, codename, place or jargon), visual-image (SC-012; some in English, FR-036) and
visual-video (SC-014).
"""

import json
import os

import content

HERE = os.path.dirname(os.path.abspath(__file__))
OUT = os.path.join(HERE, "..", "..", "src", "test", "resources", "eval", "questions.json")

CHAT = {c.get("subject") or content.CONTACTS[c["jid"]]: c for c in content.CHATS}


def m(chat, *positions):
    """Message keys by chat (subject or contact name) and position."""
    msgs = CHAT[chat]["messages"]
    return [{"message": msgs[i][2]} for i in positions]


def all_of(chat):
    return m(chat, *range(len(CHAT[chat]["messages"])))


def f(*paths):
    return [{"path": p} for p in paths]


RAFAEL, JULIANA, TIAGO, MARCOS = "Rafael Souza", "Juliana Martins", "Tiago Lima", "Marcos Pereira"
SERGIO, BRUNO, PAULO, LEO = "Sérgio", "Bruno", "Paulo Henrique", "Despachante Léo"
FAMILIA, EQUIPE, PROMO, AMOR, AURORA, PELADA = "Família Silva", "Equipe Comercial", "Promoções VIP", "Amor", "Aurora", \
    "Pelada de Quinta"
LONG = content.LONG_DOC_PATH

CONCEPTUAL = [
    ("combinar a compra de algo pagando com dinheiro vivo num local de encontro",
     m(RAFAEL, 2, 3, 4, 5) + m(JULIANA, 3, 6) + f("docs/pt/bilhete.txt")),
    ("negociação de arma de fogo sem registro", m(TIAGO, 0, 1, 2, 6, 8)),
    ("carro roubado sendo desmontado para revenda de componentes", m(MARCOS, 0, 2)),
    ("trocar a identificação do automóvel para enganar a fiscalização", m(MARCOS, 4)),
    ("intimidação envolvendo os filhos de um devedor", m(SERGIO, 2, 4)),
    ("movimentar recursos ilícitos por meio de terceiros para não levantar suspeita",
     m(BRUNO, 0, 2, 3) + f("docs/pt/relatorio_auditoria.txt")),
    ("fracionar transferências em valores pequenos para escapar do controle",
     m(BRUNO, 3, 5) + f("docs/pt/relatorio_auditoria.txt")),
    ("mercadoria ilegal cruzando a divisa entre países oculta num compartimento secreto", m(PAULO, 0, 2, 3)),
    ("falsificação de documentos de identidade", m(LEO, 0, 2, 4, 6)),
    ("golpe com cobrança falsa enviada para várias pessoas", m(PROMO, 0, 1)),
    ("página na internet copiando um comércio conhecido para ludibriar clientes", m(PROMO, 4)),
    ("planejamento de um assalto a transporte de valores", m(AURORA, 0, 1, 3, 4)),
    ("divisão de tarefas entre os integrantes antes de uma ação criminosa", m(AURORA, 2, 3)),
    ("suborno a funcionário público para liberar uma autorização", f(LONG)),
    ("servidor municipal exigindo pagamento por fora", f(LONG)),
    ("orientação para evitar ir de automóvel particular a um encontro", m(RAFAEL, 7, 8)),
    ("conversa sobre guardar produtos na casa de um familiar", m(JULIANA, 1)),
    ("evitar mencionar quantias em mensagens", m(JULIANA, 5)),
    ("cartuchos incluídos na venda de uma pistola", m(TIAGO, 3, 4)),
    ("número de série apagado de uma arma", m(TIAGO, 8)),
    ("desfazer-se do localizador GPS do carro", m(MARCOS, 5, 6)),
    ("ultimato para quitar um débito sob pena de represália", m(SERGIO, 0, 4)),
    ("adiamento para juntar a quantia devida", m(SERGIO, 1)),
    ("retiradas em espécie feitas por gente diversa em casas lotéricas", m(BRUNO, 7)),
    ("papelada de produto perecível como disfarce para a viagem", m(PAULO, 5)),
    ("prazo para ficar pronta uma identidade falsificada", m(LEO, 0)),
    ("idosa enganada que efetuou o pagamento de uma fraude", m(PROMO, 2)),
    ("festa para o avô com carne assada", m(FAMILIA, 0, 3)),
    ("remarcação de um encontro de trabalho", m(EQUIPE, 0)),
    ("jantar romântico num restaurante", m(AMOR, 1, 2)),
    ("partida de futebol semanal com amigos", m(PELADA, 0, 1)),
    ("receita de sobremesa com cacau", f("docs/pt/receita_bolo.txt")),
    ("atolamento no equipamento de impressão e como resolver", f("docs/pt/manual_impressora.txt")),
    ("notificação formal exigindo quitação de débito", f("docs/pt/carta_cobranca.txt")),
    ("enchentes que desalojaram moradores", f("docs/pt/noticia_chuvas.txt")),
    ("solicitação para mandar a quantia a uma parente por estar com restrição bancária",
     f("docs/pt/email_transferencia.txt")),
    ("recado sobre deixar uma encomenda com o rapaz da entrada do prédio", f("docs/pt/bilhete.txt")),
    ("orientação para não conversar sobre o assunto por ligação", f("docs/pt/bilhete.txt") + m(AURORA, 5)),
    ("reclamações sobre o controle de acesso do edifício discutidas pelos moradores",
     f("docs/pt/ata_condominio.txt")),
    ("viagem de férias ao litoral nordestino", f("docs/pt/diario_viagem.txt")),
    ("registro policial de um carro que sumiu de um centro comercial", f("docs/pt/boletim_ocorrencia.txt")),
    ("termos para residir numa unidade alugada", f("docs/pt/contrato_aluguel.txt")),
    ("trajetória de carreira de uma candidata a emprego", f("docs/pt/curriculo.txt")),
]

CROSSLINGUAL = [
    ("anúncio vendendo uma pistola sem número de série", f("docs/en/weapon_listing.txt")),
    ("memorando orientando dividir depósitos entre contas de parentes", f("docs/en/laundering_memo.txt")),
    ("carta ameaçando visitar a família caso a dívida não seja paga", f("docs/es/amenaza.txt")),
    ("carga atravessando a fronteira à noite escondida entre caixas de frutas", f("docs/es/envio_mercancia.txt")),
    ("compartimento secreto em contêiner com carne congelada", f("docs/en/shipping_manifest.txt")),
    ("oferta de passaportes falsos idênticos aos verdadeiros", f("docs/es/documentos_falsos.txt")),
    ("mensagem fraudulenta ameaçando suspender a conta do cliente", f("docs/en/phishing_template.txt")),
    ("prato espanhol de arroz com açafrão", f("docs/es/receta.txt")),
    ("resultado de vendas do trimestre abaixo da meta", f("docs/en/meeting_minutes.txt")),
    ("servidores de arquivos criptografados por sequestro de dados", f("docs/en/security_report.txt")),
    ("acordo de consultoria contábil por doze meses", f("docs/es/contrato.txt")),
    ("receita de panquecas fofas", f("docs/en/recipe.txt")),
]

MIXED = [
    ("pagamento combinado com o Baiano", all_of(RAFAEL)),
    ("entrega do material do Baiano na portaria", all_of(RAFAEL)),
    ("o que está planejado para a Aurora", all_of(AURORA)),
    ("horário do carro-forte na operação Aurora", m(AURORA, 0, 1)),
    ("travessia em Ponta Porã com mercadoria escondida", all_of(PAULO)),
    ("o Civic prata levado para o desmanche", m(MARCOS, 0, 2) + f("docs/pt/boletim_ocorrencia.txt")),
    ("pistola de nove com numeração raspada", all_of(TIAGO)),
    ("pix de 900 em contas de laranjas", m(BRUNO, 0, 3, 5)),
    ("vítima de Campinas que caiu no golpe", m(PROMO, 2, 3)),
    ("ameaça do Sérgio à família por causa da dívida", all_of(SERGIO)),
    ("RG e carteira de motorista com o despachante", all_of(LEO)),
    ("placa fria colocada em outro veículo", m(MARCOS, 3, 4)),
    ("caminhão frigorífico com fundo falso", m(PAULO, 3, 5) + f("docs/en/shipping_manifest.txt")),
    ("fiscal da prefeitura pedindo agrado", f(LONG)),
    ("lotérica para sacar o dinheiro das contas da tia", m(BRUNO, 0, 6, 7)),
    ("bilhete sobre o moleque da portaria", f("docs/pt/bilhete.txt")),
]

# concept (commons.json) -> Portuguese description
IMAGES_PT = {
    "praia": "praia com ondas do mar e areia", "cachorro": "um cachorro de coleira", "gato": "gato doméstico",
    "cavalo": "cavalo no pasto", "carro-vermelho": "carro esportivo vermelho", "motocicleta": "motocicleta estacionada",
    "bicicleta": "bicicleta encostada na rua", "aviao": "avião de passageiros voando", "barco": "barco a vela no mar",
    "trem": "trem de passageiros", "ponte": "ponte sobre um rio", "igreja": "fachada de uma igreja",
    "cidade-noite": "cidade iluminada à noite", "floresta": "árvores de uma floresta",
    "montanha-neve": "montanhas cobertas de neve", "pizza": "uma pizza", "frutas": "prato com frutas variadas",
    "flores": "flores num vaso", "pistola": "arma de fogo, uma pistola", "faca": "facas de cozinha",
    "dinheiro": "cédulas de dinheiro", "laptop": "notebook aberto", "celular": "telefone celular",
    "bola-futebol": "bola de futebol na grama", "estadio": "estádio de futebol", "viatura-policia": "viatura da polícia",
    "incendio": "bombeiros apagando um incêndio", "cerveja": "garrafas de cerveja", "cigarro": "cinzeiro com cigarros",
    "comprimidos": "comprimidos e remédios", "seringa": "uma seringa", "maconha": "planta de maconha",
    "passaporte": "passaportes", "multidao": "multidão em protesto na rua", "ferramentas": "martelo e ferramentas",
    "cozinha": "cozinha moderna",
}
IMAGES_EN = {
    "carro-vermelho": "a red sports car", "pistola": "a handgun", "maconha": "cannabis plants",
    "passaporte": "a passport", "dinheiro": "banknotes", "viatura-policia": "a police car",
}
VIDEO_OF = {}
for video, scenes in {
    "viagem_africa.mp4": ["girafa", "elefante", "leao"], "natureza.mp4": ["cachoeira", "vulcao", "deserto"],
    "cidade.mp4": ["engarrafamento", "feira", "canteiro-obras"], "festa.mp4": ["fogos", "carnaval", "rodeio"],
    "mar.mp4": ["peixes-recife", "porto-conteineres", "mercado-peixe"],
    "trabalho.mp4": ["fabrica", "sala-aula", "hospital"], "transporte.mp4": ["caminhao", "helicoptero", "aeroporto"],
    "clima.mp4": ["raio", "trigo", "esqui"]}.items():
    for s in scenes:
        VIDEO_OF[s] = video
VIDEOS_PT = {
    "girafa": "girafas na savana", "elefante": "manada de elefantes", "leao": "um leão",
    "cachoeira": "uma cachoeira", "vulcao": "vulcão em erupção", "deserto": "dunas de areia no deserto",
    "engarrafamento": "trânsito engarrafado", "feira": "feira de rua com barracas",
    "canteiro-obras": "guindaste num canteiro de obras", "fogos": "fogos de artifício",
    "carnaval": "fantasias de carnaval", "rodeio": "rodeio com cavaleiro", "peixes-recife": "peixes num recife de coral",
    "porto-conteineres": "porto com contêineres e guindastes", "mercado-peixe": "peixes à venda",
    "fabrica": "linha de montagem de carros", "sala-aula": "sala de aula", "hospital": "quarto de hospital",
    "caminhao": "caminhão na estrada", "helicoptero": "um helicóptero", "aeroporto": "terminal de aeroporto",
    "raio": "raios numa tempestade", "trigo": "plantação de trigo", "esqui": "pessoas esquiando na neve",
}


# Version 2 adds the subject of each text question (SC-003 speaks of "another item pertinent to the
# same subject"; version 1 had no subjects, so its SC-003 attempts used each question alone). Questions
# and pertinent items are those of version 1, unchanged. A question with no subject here is its own.
SUBJECTS = {
    "pagamento-e-entrega": ["conceptual-01", "conceptual-16", "conceptual-17", "conceptual-18", "conceptual-37",
                            "mixed-01", "mixed-02", "mixed-16"],
    "arma-de-fogo": ["conceptual-02", "conceptual-19", "conceptual-20", "crosslingual-01", "mixed-07"],
    "veiculo-furtado": ["conceptual-03", "conceptual-04", "conceptual-21", "conceptual-41", "mixed-06", "mixed-12"],
    "ameaca-por-divida": ["conceptual-05", "conceptual-22", "conceptual-23", "crosslingual-03", "mixed-10"],
    "lavagem-com-parentes": ["conceptual-06", "conceptual-07", "conceptual-24", "crosslingual-02", "mixed-08",
                             "mixed-15"],
    "contrabando-na-fronteira": ["conceptual-08", "conceptual-25", "crosslingual-04", "crosslingual-05", "mixed-05",
                                 "mixed-13"],
    "documentos-falsos": ["conceptual-09", "conceptual-26", "crosslingual-06", "mixed-11"],
    "golpe-na-internet": ["conceptual-10", "conceptual-11", "conceptual-27", "crosslingual-07", "mixed-09"],
    "assalto-planejado": ["conceptual-12", "conceptual-13", "mixed-03", "mixed-04"],
    "suborno": ["conceptual-14", "conceptual-15", "mixed-14"],
    "receitas": ["conceptual-32", "crosslingual-08", "crosslingual-12"],
}

# Version 3 adds, to the questions whose pertinent item is the long report, the literal words the
# excerpt shown for it must contain: acceptance scenario 2 of User Story 1 ("the excerpt shown is
# that paragraph, not the beginning of the document"), which versions 1 and 2 did not measure. The
# words have no comma, because IPED reads the report as delimited text and turns commas into tabs.
EXCERPTS = {qid: "agrado de dez mil reais" for qid in ("conceptual-14", "conceptual-15", "mixed-14")}


def main():
    commons = json.load(open(os.path.join(HERE, "commons.json"), encoding="utf-8"))
    images_of = {}
    for e in commons:
        if e["role"] == "target":
            images_of.setdefault(e["concept"], []).append({"path": "images/" + e["file"]})
    questions = []

    def add(kind, lang, text, relevant):
        questions.append({"id": "%s-%02d" % (kind, sum(1 for q in questions if q["type"] == kind) + 1),
                          "type": kind, "lang": lang, "question": text, "relevant": relevant})

    for text, rel in CONCEPTUAL:
        add("conceptual", "pt", text, rel)
    for text, rel in CROSSLINGUAL:
        add("crosslingual", "pt", text, rel)
    for text, rel in MIXED:
        add("mixed", "pt", text, rel)
    for concept, text in IMAGES_PT.items():
        add("visual-image", "pt", text, images_of[concept])
    for concept, text in IMAGES_EN.items():
        add("visual-image", "en", text, images_of[concept])
    for concept, text in VIDEOS_PT.items():
        add("visual-video", "pt", text, [{"path": "videos/" + VIDEO_OF[concept]}])

    subject_of = {qid: s for s, ids in SUBJECTS.items() for qid in ids}
    for q in questions:
        if q["type"] in ("conceptual", "crosslingual", "mixed"):
            q["subject"] = subject_of.get(q["id"], q["id"])
        if q["id"] in EXCERPTS:
            q["excerpt"] = EXCERPTS[q["id"]]
    known = {q["id"] for q in questions}
    unknown = [qid for qid in list(subject_of) + list(EXCERPTS) if qid not in known]
    if unknown:
        raise SystemExit("SUBJECTS or EXCERPTS name questions that do not exist: %s" % unknown)

    doc = {"version": 3, "corpus": "iped-semantic/tools/eval-corpus", "questions": questions}
    os.makedirs(os.path.dirname(OUT), exist_ok=True)
    with open(OUT, "w", encoding="utf-8", newline="\n") as fh:
        json.dump(doc, fh, indent=1, ensure_ascii=False)
        fh.write("\n")
    from collections import Counter
    print(os.path.normpath(OUT), Counter(q["type"] for q in questions),
          Counter(q["lang"] for q in questions if q["type"] == "visual-image"))


if __name__ == "__main__":
    main()
