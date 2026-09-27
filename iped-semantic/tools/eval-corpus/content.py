# -*- coding: utf-8 -*-
"""Text written for the evaluation corpus (research D18). Fiction, written by the team for this
purpose: no real person, case or evidence. The subjects are the ones an examiner looks for, told the
way people write in chats - slang, abbreviation, code - so that the words of a question and the words
of the answer differ, which is what semantic search has to bridge (SC-001).

Chats become a WhatsApp Android database (legacy schema) that IPED's WhatsAppParser reads; documents
become .txt files. questions.json (in iped-semantic/src/test/resources/eval) points at these texts
by path, or by the exact body of a message.
"""

# jid -> display name (wa.db). The phone owner writes as "me".
CONTACTS = {
    "5561981110001@s.whatsapp.net": "Rafael Souza",
    "5561981110002@s.whatsapp.net": "Juliana Martins",
    "5561981110003@s.whatsapp.net": "Tiago Lima",
    "5561981110004@s.whatsapp.net": "Marcos Pereira",
    "5561981110005@s.whatsapp.net": "Dona Célia",
    "5561981110006@s.whatsapp.net": "Aline",
    "5561981110007@s.whatsapp.net": "Tio Zé",
    "5561981110008@s.whatsapp.net": "Fernanda Rocha",
    "5561981110009@s.whatsapp.net": "Paulo Henrique",
    "5561981110010@s.whatsapp.net": "Sérgio",
    "5561981110011@s.whatsapp.net": "Bruno",
    "5561981110012@s.whatsapp.net": "Despachante Léo",
    "5561981110013@s.whatsapp.net": "Neguinho",
    "5561981110014@s.whatsapp.net": "Amor",
}

R, J, T, M, CE, AL, ZE, FE, PH, SE, BR, LEO, NG, AM = (
    "5561981110001@s.whatsapp.net", "5561981110002@s.whatsapp.net", "5561981110003@s.whatsapp.net",
    "5561981110004@s.whatsapp.net", "5561981110005@s.whatsapp.net", "5561981110006@s.whatsapp.net",
    "5561981110007@s.whatsapp.net", "5561981110008@s.whatsapp.net", "5561981110009@s.whatsapp.net",
    "5561981110010@s.whatsapp.net", "5561981110011@s.whatsapp.net", "5561981110012@s.whatsapp.net",
    "5561981110013@s.whatsapp.net", "5561981110014@s.whatsapp.net")

# A chat: jid (a person, or a group ending in @g.us with a subject), the day it happened (days after
# 2024-03-01) and its messages as (sender, minutes after 20:00 of that day, text). In a group the
# sender is the participant; "me" is the owner.
CHATS = [
    {"jid": R, "day": 0, "messages": [
        ("me", 0, "e aí, conseguiu falar com o Baiano?"),
        (R, 2, "falei, ele tem o material"),
        ("me", 3, "quanto?"),
        (R, 4, "5 mil"),
        ("me", 5, "fechado, amanhã na portaria"),
        (R, 7, "leva em espécie, nada de transferência"),
        ("me", 8, "beleza, 8h tô lá"),
        (R, 11, "o Baiano pediu pra não vir de carro próprio"),
        ("me", 12, "vou de uber"),
        (R, 14, "chegando lá manda um áudio que ele desce"),
    ]},
    {"jid": J, "day": 1, "messages": [
        (J, 0, "a mercadoria chegou?"),
        ("me", 3, "chegou, tá guardada no depósito do meu primo"),
        (J, 5, "quando eu te acerto?"),
        ("me", 6, "sexta, pode ser em notas mesmo"),
        (J, 9, "trago 3 pacotes de 1000"),
        ("me", 10, "melhor não falar valor por aqui"),
        (J, 12, "tá bom, sexta no estacionamento do mercado"),
    ]},
    {"jid": T, "day": 2, "messages": [
        (T, 0, "consegui a peça que tu pediu"),
        ("me", 2, "é a de nove?"),
        (T, 3, "isso, com dois pentes"),
        ("me", 5, "e a caixinha de bala?"),
        (T, 6, "50 unidades, vai junto"),
        ("me", 8, "quanto sai tudo?"),
        (T, 9, "7 e meio, sem nota né"),
        ("me", 11, "tá limpa? sem histórico?"),
        (T, 13, "numeração raspada, pode ficar tranquilo"),
    ]},
    {"jid": M, "day": 3, "messages": [
        (M, 0, "o Civic prata de ontem já foi pro desmanche"),
        ("me", 4, "e o que deu pra aproveitar?"),
        (M, 6, "motor e câmbio vendidos, lataria vai pro ferro-velho"),
        ("me", 8, "e a placa?"),
        (M, 9, "já botei a placa fria no outro"),
        ("me", 12, "cuidado com o rastreador"),
        (M, 14, "arranquei na hora, joguei no rio"),
    ]},
    {"jid": "556199990001-1700000001@g.us", "subject": "Família Silva", "day": 4, "messages": [
        (CE, 0, "domingo é o aniversário do vô, todo mundo vem?"),
        (AL, 3, "levo o bolo de chocolate"),
        ("me", 5, "eu levo refrigerante e carvão"),
        (ZE, 8, "vou fazer o churrasco, compra picanha"),
        (CE, 10, "começa ao meio-dia, não se atrasem"),
        (AL, 14, "a vó pediu pra trazer o álbum de fotos antigas"),
    ]},
    {"jid": "556199990002-1700000002@g.us", "subject": "Equipe Comercial", "day": 5, "messages": [
        (FE, 0, "reunião de resultados remarcada pra quinta às 10h"),
        (PH, 3, "vou apresentar o fechamento do trimestre"),
        ("me", 5, "preciso dos números da filial sul"),
        (FE, 7, "mando a planilha até amanhã"),
        (PH, 9, "lembrem de atualizar o CRM antes"),
    ]},
    {"jid": SE, "day": 6, "messages": [
        (SE, 0, "o prazo acabou, cadê o dinheiro?"),
        ("me", 4, "tô juntando, me dá mais uma semana"),
        (SE, 5, "a gente sabe onde tua filha estuda"),
        ("me", 6, "não mexe com a minha família"),
        (SE, 8, "então paga até sexta. senão a gente faz uma visita"),
    ]},
    {"jid": BR, "day": 7, "messages": [
        (BR, 0, "abre uma conta no nome da tua tia"),
        ("me", 2, "ela não vai desconfiar?"),
        (BR, 3, "fala que é pra receber aposentadoria"),
        (BR, 4, "aí a gente passa pix de 900 pra não chamar atenção"),
        ("me", 6, "quantas contas precisa?"),
        (BR, 7, "umas cinco, cada uma recebe um pouco por dia"),
        ("me", 9, "e depois tira como?"),
        (BR, 10, "saque no caixa de lotérica, sempre pessoa diferente"),
    ]},
    {"jid": PH, "day": 8, "messages": [
        (PH, 0, "a encomenda sai de Pedro Juan na terça"),
        ("me", 3, "atravessa por onde?"),
        (PH, 4, "pela linha seca em Ponta Porã, de madrugada"),
        (PH, 5, "vai no fundo falso do caminhão frigorífico"),
        ("me", 8, "e se a PRF parar?"),
        (PH, 9, "o motorista tem nota de carne, tá tudo certo"),
    ]},
    {"jid": LEO, "day": 9, "messages": [
        (LEO, 0, "o RG fica pronto em três dias"),
        ("me", 2, "precisa de quê?"),
        (LEO, 3, "foto 3x4 e o nome que vai no documento"),
        ("me", 5, "e a carteira de motorista?"),
        (LEO, 6, "essa demora uma semana, cobra à parte"),
        ("me", 8, "sai com o carimbo do Detran?"),
        (LEO, 9, "igualzinho o original, ninguém percebe"),
    ]},
    {"jid": "556199990003-1700000003@g.us", "subject": "Promoções VIP", "day": 10, "messages": [
        (NG, 0, "manda o link do boleto pro pessoal da lista"),
        ("me", 3, "já disparei pra 300 números"),
        (NG, 20, "a senhora de Campinas caiu, pagou 2.400"),
        ("me", 21, "saca logo antes do banco bloquear"),
        (NG, 24, "o site clonado da loja tá no ar até amanhã"),
    ]},
    {"jid": AM, "day": 11, "messages": [
        (AM, 0, "saudade de você"),
        ("me", 2, "também, amanhã a gente janta?"),
        (AM, 3, "aquele italiano da praça"),
        ("me", 4, "reservo pra 20h"),
        (AM, 6, "leva aquele vinho que eu gosto"),
    ]},
    {"jid": "556199990004-1700000004@g.us", "subject": "Aurora", "day": 12, "messages": [
        (T, 0, "a Aurora começa segunda às 4h"),
        (M, 2, "o carro-forte passa na avenida às 5h10"),
        ("me", 3, "quem fica com o rádio?"),
        (T, 4, "eu fico, vocês dois no carro de apoio"),
        (M, 6, "as ferramentas já tão no galpão"),
        (T, 7, "ninguém fala Aurora por telefone, só aqui"),
    ]},
    {"jid": "556199990005-1700000005@g.us", "subject": "Pelada de Quinta", "day": 13, "messages": [
        (BR, 0, "quinta tem jogo às 21h"),
        (PH, 2, "quem não pagou a quadra acerta comigo"),
        ("me", 3, "levo a bola nova"),
        (BR, 6, "o time de colete perdeu de novo kkk"),
    ]},
]

# Written documents: path -> text. Paths are relative to the evidence root.
DOCS = {
    "docs/pt/bilhete.txt": "Deixa o pacote com o moleque da portaria, o acerto é amanhã. Não fala nada no telefone.",
    "docs/pt/contrato_aluguel.txt": (
        "CONTRATO DE LOCAÇÃO RESIDENCIAL\n\n"
        "Pelo presente instrumento, o LOCADOR cede ao LOCATÁRIO o apartamento 302 do Edifício Ipê, pelo prazo "
        "de trinta meses, mediante aluguel mensal de dois mil e duzentos reais, reajustado anualmente pelo IGP-M. "
        "O imóvel destina-se exclusivamente a moradia. As despesas ordinárias de condomínio, água e energia correm "
        "por conta do LOCATÁRIO. Fica vedada a sublocação sem anuência escrita do LOCADOR. Em caso de rescisão "
        "antecipada, será devida multa proporcional ao tempo restante. Elegem as partes o foro de Brasília."),
    "docs/pt/relatorio_auditoria.txt": (
        "RELATÓRIO DE AUDITORIA INTERNA - EXERCÍCIO 2023\n\n"
        "No exame dos extratos da empresa e dos seus sócios, identificamos um padrão: depósitos fracionados, "
        "todos logo abaixo do limite que obriga a comunicação ao órgão de inteligência financeira, espalhados por "
        "contas abertas em nome de cunhados, sogros e primos do sócio majoritário. Os mesmos montantes eram "
        "reunidos dias depois numa conta de uma empresa sem empregados registrados. Recomendamos o reporte do fato "
        "ao conselho e a suspensão imediata das operações com as pessoas envolvidas."),
    "docs/pt/boletim_ocorrencia.txt": (
        "BOLETIM DE OCORRÊNCIA Nº 4471/2024\n\n"
        "Natureza: subtração de automóvel. O comunicante relata que estacionou seu Honda Civic prata no pátio do "
        "shopping às 19h e, ao retornar às 22h30, o automóvel não se encontrava no local. Não houve testemunhas. "
        "O bem possuía dispositivo de localização por satélite, que deixou de transmitir às 20h15 nas proximidades "
        "da marginal. Foram solicitadas as imagens das câmeras do estacionamento."),
    "docs/pt/receita_bolo.txt": (
        "BOLO DE CHOCOLATE DA VÓ\n\n"
        "Ingredientes: 3 ovos, 2 xícaras de açúcar, 2 xícaras de farinha de trigo, 1 xícara de chocolate em pó, "
        "1 xícara de leite morno, meia xícara de óleo, 1 colher de fermento.\n"
        "Modo de preparo: bata os ovos com o açúcar até clarear, junte o óleo e o leite, depois a farinha e o "
        "chocolate peneirados. Por último, o fermento. Asse em forno médio por 40 minutos. Cubra com brigadeiro "
        "mole ainda quente."),
    "docs/pt/manual_impressora.txt": (
        "GUIA RÁPIDO - MODELO LX-200\n\n"
        "Se o papel ficar enroscado no rolo, desligue o aparelho e abra a tampa traseira. Puxe a folha devagar, "
        "no sentido da saída, sem rasgá-la. Verifique se não restaram pedaços presos nas engrenagens. Feche a "
        "tampa, ligue novamente e aguarde a luz verde parar de piscar. Use apenas folhas A4 de 75 g/m², bem "
        "alinhadas na bandeja."),
    "docs/pt/carta_cobranca.txt": (
        "Prezado Sr. Antônio,\n\n"
        "Consta em nossos registros que a parcela 7 do seu financiamento, vencida em 10 de fevereiro, permanece em "
        "aberto. Pedimos a gentileza de regularizar a situação em até cinco dias úteis, a fim de evitar a inclusão "
        "do seu nome nos cadastros de proteção ao crédito e a incidência de juros e multa contratual. Caso o "
        "acerto já tenha sido feito, desconsidere esta mensagem.\n\nAtenciosamente,\nSetor de Recuperação"),
    "docs/pt/ata_condominio.txt": (
        "ATA DA ASSEMBLEIA ORDINÁRIA - RESIDENCIAL JARDIM DAS ACÁCIAS\n\n"
        "Aos quinze dias de março, reuniram-se os condôminos no salão de festas. Diversos presentes relataram que "
        "a guarita tem liberado visitantes sem interfonar para as unidades e que entregadores circulam pelos "
        "andares sem acompanhamento. Deliberou-se pela instalação de câmeras no hall, pela exigência de cadastro "
        "de prestadores de serviço e pela troca da empresa terceirizada responsável pelo turno da noite."),
    "docs/pt/email_transferencia.txt": (
        "De: Márcio\nPara: Financeiro\nAssunto: pagamento da nota 1182\n\n"
        "Bom dia. Peço que o valor da nota 1182 seja transferido para a conta da minha cunhada, Rosana, no Banco "
        "do Cerrado, agência 0412, porque a minha está bloqueada por uma pendência que ainda não consegui "
        "resolver. Assim que normalizar, volto a receber normalmente. Obrigado."),
    "docs/pt/noticia_chuvas.txt": (
        "TEMPORAL DEIXA 300 FAMÍLIAS FORA DE CASA NA ZONA NORTE\n\n"
        "A chuva de ontem à noite fez o córrego transbordar e a água chegou a um metro e meio dentro das casas da "
        "Vila Esperança. A Defesa Civil abriu duas escolas como abrigo e pede doações de colchões, roupas e "
        "alimentos não perecíveis. Não houve vítimas, mas três pontes de madeira foram levadas pela correnteza."),
    "docs/pt/curriculo.txt": (
        "CURRÍCULO - ANA PAULA REZENDE\n\n"
        "Formação: Administração de Empresas, Universidade Federal de Goiás (2015). Experiência: analista de "
        "compras na Distribuidora Planalto (2016-2021), coordenadora de logística na Transportes Serra Azul "
        "(2021-atual). Idiomas: inglês avançado, espanhol intermediário. Conhecimentos: SAP, Excel avançado, "
        "gestão de estoque e negociação com fornecedores."),
    "docs/pt/diario_viagem.txt": (
        "Querido diário, dia 3 em Porto Seguro. Acordamos cedo para pegar a balsa até Arraial d'Ajuda. A água "
        "do mar estava morna e verde, e passamos a tarde toda debaixo do guarda-sol comendo acarajé e tomando "
        "água de coco. À noite fomos à passarela do álcool. Amanhã vamos de buggy até Trancoso."),
    "docs/en/shipping_manifest.txt": (
        "SHIPPING MANIFEST - CONTAINER MSKU 448120-4\n\n"
        "Declared cargo: frozen beef, 22 pallets, reefer set at -18 C. Note for the receiving team: container 4 "
        "has a false bottom welded under the floor panels; do not open it at the port. The meat stays on top as "
        "cover. Unload only at the warehouse, after the inspection window has closed."),
    "docs/en/phishing_template.txt": (
        "Subject: Your account will be suspended in 24 hours\n\n"
        "Dear customer, we detected a pending invoice on your account. To avoid the suspension of your services "
        "and additional fees, click the link below and complete the payment today. This is an automatic message; "
        "please do not reply. Customer Care Team"),
    "docs/en/meeting_minutes.txt": (
        "MINUTES - REGIONAL SALES MEETING\n\n"
        "Attendees reviewed the third quarter results. Revenue grew 4% against the same period last year, below "
        "the 7% target. The team agreed to focus on the southern accounts, to hire two new representatives and to "
        "revisit the discount policy before the holiday season. Next meeting in two weeks."),
    "docs/en/weapon_listing.txt": (
        "FOR SALE: 9mm handgun, like new, two magazines included, serial number filed off. Cash only, no questions, "
        "pickup in person. A box of 50 rounds available for an extra charge."),
    "docs/en/recipe.txt": (
        "FLUFFY PANCAKES\n\n"
        "Whisk 2 cups of flour, 2 tablespoons of sugar, 2 teaspoons of baking powder and a pinch of salt. In another "
        "bowl beat 2 eggs with 1.5 cups of milk and 3 tablespoons of melted butter. Combine, rest for 10 minutes and "
        "cook on a hot griddle until bubbles form. Serve with maple syrup and berries."),
    "docs/en/security_report.txt": (
        "INCIDENT REPORT - RANSOMWARE\n\n"
        "On Monday at 02:14 the file servers of the accounting department were encrypted. A ransom note demanded "
        "payment in bitcoin within 72 hours. The attackers entered through a VPN account without two-factor "
        "authentication. Backups from Sunday were intact and restoration completed on Wednesday."),
    # Corpus version 2: this memo began with "MEMO", the magic IPED's CustomSignatures.xml gives
    # Windows SuperFetch files (application/x-superfetch); IPED read it as one and its text never
    # reached any index. No text written here may begin with a magic IPED knows.
    "docs/en/laundering_memo.txt": (
        "INTERNAL MEMO - HANDLE WITH CARE\n\n"
        "From now on, split the deposits into amounts under ten thousand and spread them across the accounts of "
        "relatives: the aunt, the two cousins and your brother-in-law. Never more than two deposits a day at the "
        "same branch. We consolidate at the end of the month through the consulting company's invoices."),
    "docs/es/amenaza.txt": (
        "Ya te avisamos dos veces. Si no pagas antes del viernes, vamos a visitar a tu familia. Sabemos a qué hora "
        "sale tu hija del colegio y dónde trabaja tu esposa. No llames a nadie. Esta es la última advertencia."),
    "docs/es/envio_mercancia.txt": (
        "INFORME DE ENVÍO\n\n"
        "La carga cruzará la frontera durante la noche, escondida entre cajas de fruta, en el camión de la "
        "cooperativa. El chofer lleva la factura de las naranjas. Si hay control en el puesto, se sigue por el "
        "camino de tierra que bordea el río."),
    "docs/es/receta.txt": (
        "PAELLA VALENCIANA\n\n"
        "Sofríe el pollo y el conejo en aceite de oliva, añade judías verdes y garrofón, luego tomate rallado y "
        "pimentón. Vierte el caldo con azafrán, echa el arroz en cruz y deja cocer 18 minutos sin remover. Deja "
        "reposar cinco minutos antes de servir."),
    "docs/es/contrato.txt": (
        "CONTRATO DE PRESTACIÓN DE SERVICIOS\n\n"
        "La empresa Consultoría Andina S.A. se obliga a prestar servicios de asesoría contable al cliente durante "
        "doce meses, a cambio de una retribución mensual fija. El contrato podrá rescindirse con aviso previo de "
        "treinta días. Cualquier controversia se someterá a los tribunales de Lima."),
    "docs/es/documentos_falsos.txt": (
        "Ofrecemos pasaportes, cédulas y licencias de conducir idénticos a los originales, con holograma y chip. "
        "Entrega en cinco días hábiles. Solo necesitamos una foto y los datos que quieras que aparezcan. Pago "
        "por adelantado."),
}

# The long report: forty pages of routine, and one paragraph that matters.
LONG_DOC_PATH = "docs/pt/relatorio_manutencao_2023.txt"
LONG_DOC_KEY_PARAGRAPH = (
    "Registre-se, por fim, que o engenheiro responsável informou à administração que o fiscal da prefeitura só "
    "assinaria o laudo de conclusão da obra mediante um agrado de dez mil reais, entregue em envelope, depois do "
    "expediente, sem recibo.")
LONG_DOC_TOPICS = [
    ("elevadores", "Os elevadores sociais e de serviço passaram pela revisão trimestral prevista em contrato. "
                   "Foram substituídos os cabos de tração do elevador B e ajustado o nivelamento das portas no "
                   "térreo. O tempo médio de parada caiu de 14 para 9 minutos."),
    ("hidráulica", "Na parte hidráulica, trocaram-se as boias das caixas d'água superiores e o registro geral da "
                   "garagem. Foi detectado um vazamento na coluna de água fria do bloco 2, corrigido em duas "
                   "etapas para não interromper o abastecimento."),
    ("elétrica", "O quadro de distribuição do subsolo recebeu novos disjuntores e barramentos. As lâmpadas das "
                 "áreas comuns foram substituídas por modelos de LED, com economia estimada de 31% no consumo "
                 "mensal das áreas comuns."),
    ("jardinagem", "A equipe de jardinagem fez a poda das palmeiras, a adubação dos canteiros e a reposição da "
                   "grama no parquinho. O sistema de irrigação automática foi reprogramado para o período seco."),
    ("limpeza", "O cronograma de limpeza das fachadas foi cumprido em outubro, com lavagem a alta pressão e "
                "aplicação de hidrofugante nas juntas. As caixas de gordura foram esgotadas a cada sessenta dias."),
    ("segurança", "Foram testados os extintores, as mangueiras e o sistema de alarme de incêndio. A brigada "
                  "interna realizou dois simulados de evacuação, com tempo médio de saída de seis minutos."),
    ("piscina", "A piscina teve o filtro de areia trocado e o revestimento das bordas refeito. O tratamento da "
                "água passou a ser feito com cloro estabilizado, com medições diárias de pH registradas em planilha."),
    ("telhado", "No telhado, substituíram-se 42 telhas danificadas pelo granizo de agosto e as calhas foram "
                "limpas e vedadas. O para-raios recebeu medição de aterramento dentro dos valores da norma."),
]


def long_document():
    """Forty pages of routine maintenance, deterministic, with the one paragraph in the middle."""
    parts = ["RELATÓRIO ANUAL DE MANUTENÇÃO PREDIAL - CONDOMÍNIO EDIFÍCIO SOLAR DO LAGO - 2023\n"]
    months = ["janeiro", "fevereiro", "março", "abril", "maio", "junho", "julho", "agosto", "setembro",
              "outubro", "novembro", "dezembro"]
    n = 0
    for month in months:
        parts.append("\n%s\n" % month.upper())
        for k, (topic, text) in enumerate(LONG_DOC_TOPICS):
            n += 1
            parts.append("%d. Em %s, item %s: %s Ordem de serviço %04d, custo registrado no centro de despesas %d. "
                         "Nenhuma pendência técnica ficou em aberto ao fim do período." % (
                             n, month, topic, text, 1000 + n * 7, 300 + (n * 13) % 97))
            parts.append("Observações complementares do mês de %s sobre %s: o fornecedor apresentou nota fiscal, "
                         "termo de garantia e relatório fotográfico, conferidos pela administração e arquivados na "
                         "pasta física da gestão, conforme o regimento interno do condomínio." % (month, topic))
        if month == "junho":
            parts.append(LONG_DOC_KEY_PARAGRAPH)
    for extra in range(1, 41):
        parts.append("\nANEXO %d - INVENTÁRIO DE MATERIAIS\n" % extra)
        for k, (topic, _) in enumerate(LONG_DOC_TOPICS):
            parts.append("Material de %s, lote %d-%d: estoque inicial %d unidades, consumo %d, saldo %d. Conferência "
                         "feita pelo zelador e visada pelo síndico." % (
                             topic, extra, k, 40 + extra, 5 + k, 35 + extra - k))
    return "\n".join(parts) + "\n"
