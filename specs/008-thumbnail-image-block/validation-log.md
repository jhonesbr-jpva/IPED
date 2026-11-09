# Registro de validação — 008-thumbnail-image-block

Evidência das medições e execuções que as tasks exigem. Uma linha por verificação, com o número
medido, não com a impressão.

---

## Bancada (T003) — construída em 2026-09-20

**Origem**: `H:\iped-cases\thumbs-bench-src` — gerada de forma determinística, sem material
sensível. Dez JPEGs com uma forma geométrica grande e a legenda que a nomeia (`RED CIRCLE`,
`BLUE SQUARE`, …), de modo que uma descrição produzida por um agente possa ser conferida
mecanicamente; um JPEG grande de ruído com legenda; um PNG; um vCard com foto PNG; um `.txt` sem
conteúdo visual; e um PDF vindo de `iped-parsers/.../test-files/test_pdfImages.pdf`.

**Caso**: `H:\iped-cases\thumbs-bench` — perfil **padrão** (sem `-profile`), 16 itens em 30 s.

### Miniaturas efetivamente produzidas

Medido com uma sonda sobre `IPEDSource.getItemByID(id).getThumb()`:

| id | item | miniatura | base64 | bytes iniciais | tipo do item |
|---|---|---|---|---|---|
| 1–3, 5–6, 8, 10–13, 15 | os dez JPEGs e o PNG | 4 297 – 4 890 B | 5 732 – 6 520 chars | **JPEG** | `image/jpeg`, `image/png` |
| 9 | `photo-11-large-noise-panel.jpg` | **16 141 B** | **21 524 chars** | JPEG | `image/jpeg` |
| **4** | **`contact-ana-png-avatar.vcf`** | **4 335 B** | 5 780 chars | **PNG** | `text/x-vcard` |
| 0 | `thumbs-bench-src` (diretório) | — | — | — | `application/octet-stream` |
| 7 | `document-with-images.pdf` | — | — | — | `application/pdf` |
| 14 | `notes-no-visual-content.txt` | — | — | — | `text/plain` |

**13 itens com miniatura, 3 sem.** Os três sem são exatamente os que US3 precisa.

### Dois achados que a medição trouxe

1. **D3 confirmado contra dado real.** O item **4** tem miniatura cujos bytes iniciais são **PNG**,
   porque `VCardParser` entrega a foto crua a `THUMBNAIL_BASE64` e `ParsingTask` a grava sem
   reencodar. O código atual declara `media_type: "image/jpeg"` para ela — **o defeito que FR-002
   descreve existe nesta bancada e é reproduzível**. O item 13 é o contraste: arquivo PNG cuja
   miniatura o `ImageThumbTask` reencodou para JPEG.

2. **O limiar de 100 KB do SC-002 era inalcançável.** Com `imgThumbSize = 256` (padrão), a maior
   miniatura da bancada tem **16 KB**. O limiar foi corrigido para **4 KB ou mais**, que é o piso
   observado, e T032 passou a apontar o item 9 como alvo da medição. Correção feita sobre medição,
   não sobre estimativa.

---

## Linha de base (T004) — 2026-09-20, antes de qualquer edição de produção

```powershell
$env:JAVA_HOME = "H:\java\LibericaJDK-11-Full"
mvn -pl iped-mcp -am install -DskipTests          # EXIT 0
mvn -pl iped-mcp test `
  "-Diped.mcp.test.referenceCase=H:\iped-cases\thumbs-bench" `
  "-Diped.mcp.ipedRoot=C:\iped\iped-mcp\iped-4.3.1"
```

**Resultado**: `Tests run: 327, Failures: 4, Errors: 0, Skipped: 33`.

> **A linha de base não é verde.** Nenhuma das quatro falhas foi causada por esta feature —
> nenhum arquivo de produção havia sido tocado quando esta medição foi feita. Registradas aqui
> para que qualquer execução posterior seja comparável e para que nenhuma delas seja atribuída
> ao trabalho da 008.

| Suíte | Falha | Natureza |
|---|---|---|
| `export/ArtifactIntegrityTest` | `aDestinationThatKeepsNothingIsRefusedEvenThoughItIsInsideTheRoot` — esperava `ALLOWED`, veio `UNRESOLVABLE` | **Pré-existente e independente de caso**: falha também na execução sem caso algum. Vem da 006 (confinamento de escrita de artefato) |
| `integration/InvestigationBatteryTest` | taxa de acerto 27%, abaixo da barra de 90% | **Conteúdo da bancada**: a bateria interroga o caso de referência documentado (contratos, pagamentos, e-mails, chats, GPS, itens apagados, carving). A bancada da 008 é de miniaturas e não tem nada disso |
| `integration/VocabularyTest` | `aFieldThatExistsIsNeverRejected` — `Unparseable number: "*"` | **Conteúdo da bancada**: depende do vocabulário de campos do caso |
| `integration/VocabularyTest` | `anUnknownFieldInAQueryComesBackWithTheRightNameAttached` — veio `QUERY_SYNTAX` no lugar de `UNKNOWN_FIELD` | idem |

### As sete suítes protegidas na linha de base

Todas verdes, **zero skips** — a bancada as exercita de verdade:

| Suíte | Resultado |
|---|---|
| `contract/ToolSchemaTest` | 6/6, 0 skip |
| `contract/TransportParityTest` | 1/1, 0 skip |
| `unit/AvailabilityTest` | 6/6, 0 skip |
| `unit/AuditChainTest` | 8/8, 0 skip |
| `integration/EgressPolicyTest` | 6/6, 0 skip |
| `integration/AuditDurabilityTest` | 1/1, 0 skip |
| `integration/ReadOnlyInvariantTest` | 3/3, 0 skip |

**Alvo para as execuções seguintes**: `Failures: 4` exatamente, com estes quatro nomes. Uma
quinta falha, ou um nome diferente, é regressão desta feature.

---

## Encanamento (T006) — 2026-09-20

`renderResult` passou a receber a classe de conteúdo do descritor, sem mudar a saída.

**Resultado**: `Tests run: 327, Failures: 4, Errors: 0, Skipped: 33` — **idêntico** à linha de base,
mesmos quatro nomes. O encanamento não mexeu em nada, que era o ponto de separá-lo em fase própria.

---

## User Stories 1 a 3 (T016, T020, T026) — 2026-09-20

**Execução final**: `mvn -pl iped-mcp -am clean install`

```
Tests run: 347, Failures: 4, Errors: 0, Skipped: 33
```

As quatro falhas são **as mesmas quatro da linha de base**, com os mesmos nomes. Vinte testes
novos, todos verdes:

| Suíte | Testes | Cobre |
|---|---|---|
| `contract/ImageBlockContractTest` | 4 | C1–C7 do contrato; FR-005 (nenhuma outra ferramenta ganhou imagem) |
| `unit/MediaTypeDetectionTest` | 6 | FR-002 / D3 — tipo lido dos bytes, e ausência de tipo em vez de palpite |
| `integration/ThumbnailImageBlockTest` | 9 | caminho feliz, FR-003, FR-004, FR-006, FR-008, FR-009, R1.5, SC-002 |
| `contract/TransportParityTest` | +1 | FR-011 — `content[]` idêntico sobre STDIO e SOCKET |

### As sete suítes protegidas (T021)

Todas verdes, **zero skips**, e **nenhuma editada** — `git status` mostra apenas arquivos novos em
`src/test/java`:

`ToolSchemaTest` 8/8 · `TransportParityTest` 2/2 · `AvailabilityTest` 6/6 · `AuditChainTest` 8/8 ·
`EgressPolicyTest` 6/6 · `AuditDurabilityTest` 1/1 · `ReadOnlyInvariantTest` 3/3

### Um percalço que não era defeito

Uma execução intermediária acusou
`ThumbnailImageBlockTest.initializationError — NoClassDefFoundError: LMcpSessionRule;`. O descritor
do campo saiu como `LMcpSessionRule;` em vez de `Liped/mcp/integration/McpSessionRule;`, enquanto
`CaseOpenTest`, do mesmo pacote, estava correto — resíduo de compilação incremental do
`maven-compiler-plugin`. `mvn clean test` resolveu e não reapareceu em nenhuma execução posterior.
Registrado porque o sintoma sugere erro de código e não é.

---

## Cenários do quickstart (T031) — 2026-09-20

| Cenário | Veredito | Evidência |
|---|---|---|
| 1 — suíte automatizada | ✅ | 347 testes, 4 falhas de linha de base, 33 skips; nenhum skip nas suítes de miniatura |
| 2 — resposta na mão | ✅ | item 15: `content blocks=2  types=[text, image]  isError=false`; `data == structuredContent.data : true` |
| 3 — tipo detectado | ✅ | **item 4 (vCard) responde `media_type: "image/png"`** e `mimeType: image/png`. Antes desta feature diria `image/jpeg` |
| 4 — três ausências | ✅ | item 0: `content blocks=1  has data=false`, com motivo declarado; coberto por `everyKindOfAbsenceAnswersWithoutAnImageBlock` |
| 5 — egresso à frente da forma | ✅ | `theEgressPolicyRefusesBeforeAnyBlockIsBuilt`: erro `BLOCKED_BY_POLICY`, `details.contentClass = thumbnail`, sem `content` e sem `structuredContent` |
| 6 — paridade de transportes | ✅ | `bothTransportsAnswerAThumbnailWithTheSameBlocks`: `content[]` idêntico sobre STDIO e SOCKET |
| **7 — agente descreve a imagem** | ✅ **2026-09-21** | 13 de 13 descrições corretas; 13 chamadas de miniatura, exatamente nos 13 itens descritos; zero exportações; a imagem conferida **nos anexos do harness**, byte a byte. Ver [a seção própria](#cenário-7-t031--2026-09-21), mais abaixo. Em 2026-09-20 ficou pendente por falta de harness com modelo de visão |
| 8 — custo de contexto | ✅ | ver abaixo |
| 9 — orientação | ✅ | `SkillParityTest` 3/3, 0 skips: os wrappers dos três harnesses foram regerados e batem com o canônico |

**FR-005 conferido na resposta crua**: `iped_item_metadata`, `iped_item_text` e `iped_item_content`
respondem `blocks=1  types=[text]` cada. Nenhuma ferramenta fora da classe `thumbnail` mudou.

---

## Medição do SC-002 (T032) — 2026-09-20

Item 9 (`photo-11-large-noise-panel.jpg`), a maior miniatura da bancada.

| Medida | Valor |
|---|---|
| miniatura | 16 141 bytes |
| base64 | 21 524 caracteres |
| bloco de texto **antes** (payload inteiro) | 21 697 caracteres |
| bloco de texto **depois** | **267 caracteres** |
| **redução** | **98,8%** |
| base64 ainda transcrito no texto | **não** |

**Critério: ≥ 90%. Medido: 98,8%. Aprovado.**

O bloco de texto passa a caber em 267 caracteres e continua declarando `available`, `encoding`,
`media_type` e `bytes` — tudo que FR-004 exige. A figura viaja uma vez, no bloco que a mostra.

---

## Cenário 7 (T031) — 2026-09-21

**Harness**: opencode 1.18.31 numa VM Lima (`iped-agent`), com bridge até o servidor em
`127.0.0.1:8737` e modelo `glm-5.3-flash`, que tem visão, servido pelo OpenWebUI local. O
workspace multi-agente `C:\pericia\agents\iped-008-thumbs` usa a skill do release, que já traz a
orientação da 008. O orquestrador `forensic` despachou o `survey` para o panorama do caso e depois
dois `inspect` em paralelo, um com 10 itens e outro com 3. Só o `inspect` tem `iped_item_thumbnail`.

**Execução**: 17:42:59–17:59:21 UTC. A sessão de auditoria é `a62bf342-fccf-4423-a5fe-66b340949f10`
e a sessão raiz do opencode é `ses_f3aefa249ffe1DWBXl09JYHSmS`.

**Pergunta**: a pergunta do procedimento, *"descreva o que há nos itens 1 a 10"*, não foi usada.
Ela se responde pelo nome do arquivo (`photo-07-black-circle.jpg`) e passaria com a feature
desligada. A usada foi esta:

> Abra o caso em H:\iped-cases\thumbs-bench. Para cada item que tenha miniatura, responda três
> coisas: (1) qual é a cor de fundo da imagem; (2) que texto aparece escrito dentro dela, transcrito
> literalmente; (3) quantos lados tem a figura geométrica, se houver uma. Se não conseguir ver
> alguma imagem, diga isso em vez de deduzir do nome do arquivo.

### Respostas contra o gabarito

Conferido contra as imagens-fonte de `thumbs-bench-src`, das quais o caso foi processado, e, no
item 9, contra a miniatura efetivamente entregue ao modelo. **Não** foi conferido abrindo os itens
na interface do IPED, como o texto de SC-001 prevê.

| id | fundo | texto | figura | resposta do agente | |
|---|---|---|---|---|---|
| 1 | branco | `BLACK CIRCLE` | círculo | branco · `BLACK CIRCLE` · círculo, sem lados | ✅ |
| 2 | branco | `YELLOW CIRCLE` | círculo | branco · `YELLOW CIRCLE` · círculo, sem lados | ✅ |
| 3 | branco | `PURPLE SQUARE` | quadrado | branco · `PURPLE SQUARE` · quadrado, 4 lados | ✅ |
| 4 | branco | `AVATAR PNG` | círculo | branco · `AVATAR PNG` · círculo, sem lados | ✅ |
| 5 | branco | `CYAN SQUARE` | quadrado | branco · `CYAN SQUARE` · quadrado, 4 lados | ✅ |
| 6 | branco | `ORANGE TRIANGLE` | triângulo | branco · `ORANGE TRIANGLE` · triângulo, 3 lados | ✅ |
| 8 | branco | `MAGENTA TRIANGLE` | triângulo | branco · `MAGENTA TRIANGLE` · triângulo, 3 lados | ✅ |
| 9 | ruído **cinza** na miniatura (ver abaixo) | `LARGE NOISE PANEL` em faixa branca | nenhuma | cinza ruidoso · `LARGE NOISE PANEL` em retângulo branco central · nenhuma | ✅ |
| 10 | branco | `RED CIRCLE` | círculo | branco · `RED CIRCLE` · círculo, sem lados | ✅ |
| 11 | branco | `BLUE SQUARE` | quadrado | branco · `BLUE SQUARE` · quadrado, 4 lados | ✅ |
| 12 | branco | `GRAY CIRCLE` | círculo | branco · `GRAY CIRCLE` · círculo, sem lados | ✅ |
| 13 | azul-marinho | `WHITE STAR ON NAVY` | estrela | azul-marinho · `WHITE STAR ON NAVY` · estrela de 5 pontas, "10 lados" | ✅ |
| 15 | branco | `GREEN TRIANGLE` | triângulo | branco · `GREEN TRIANGLE` · triângulo, 3 lados | ✅ |

Para os itens **0, 7 e 14**, que não têm miniatura, a resposta final diz que "não há imagem a
descrever". Não houve descrição visual inventada.

### Os três critérios

| Critério | Medido | Veredito |
|---|---|---|
| **SC-001** — ≥ 9 de 10 | as dez imagens de forma (1, 2, 3, 5, 6, 8, 10, 11, 12, 15): **10 de 10**; somados o avatar (4), o ruído (9) e o fundo escuro (13): **13 de 13** | ✅ |
| **SC-003** — sem exportação para ver | 34 operações na trilha, todas `STARTED` → `OK`: `iped_session_info` 1, `iped_open_case` 3, `iped_case_overview` 1, `iped_list_fields` 1, `iped_check_field` 3, `iped_aggregate` 2, `iped_search` 8, `iped_get_items` 2, `iped_item_thumbnail` 13. **`iped_export_item` 0, `iped_export_artifact` 0** | ✅ |
| **SC-005** — toda afirmação visual tem miniatura na trilha | 13 chamadas `iped_item_thumbnail` entre 17:52:56 e 17:54:00 UTC, nos ids 1–6, 8–13 e 15, **exatamente os 13 descritos**. Nenhuma nos itens 0, 7 e 14, que também não receberam descrição | ✅ |

### A imagem chegou ao modelo: conferido no harness

As Assumptions do spec deixam em aberto um risco: o harness descartar o bloco de imagem, e o modelo
responder por outro caminho. Isso foi conferido no armazenamento do opencode
(`~/.local/share/opencode/opencode.db` na VM, tabela `part`). Cada uma das 13 chamadas de
miniatura traz em `state.attachments` uma imagem, e os bytes decodificados são **exatamente** as
miniaturas medidas em T003:

| item | anexo | bytes | base64 | assinatura |
|---|---|---|---|---|
| **4** | **`image/png`** | 4 335 | 5 780 | `89 50 4E 47` — PNG |
| 9 | `image/jpeg` | 16 141 | 21 524 | `FF D8 FF E0` |
| os outros 11 | `image/jpeg` | 4 297 – 4 890 | 5 732 – 6 520 | `FF D8 FF E0` |

No mesmo resultado, o bloco de texto tem 265 a 267 caracteres, e o campo `data` traz só um aviso de
que a imagem veio como bloco próprio. O base64 não se repete no texto. O **D3** fica confirmado de
ponta a ponta: o item 4 chegou ao modelo declarado como PNG.

### Item 9: o gabarito estava errado, e o agente não

O gabarito do workspace dizia "ruído colorido". O agente respondeu "cinza ruidoso, ruído
preto-e-branco". A medição foi feita no quarto superior da imagem, que é só ruído:

| | dispersão RGB média (máx − mín) | pixels com dispersão > 60 |
|---|---|---|
| original, 2400 × 1800 | 43,3 | 21,7% |
| miniatura entregue, 256 × 192 | **4,3** | **0,0%** |

Ao reduzir a imagem para 256 px, o ruído RGB se compensa na média e a cor desaparece. A miniatura é
ruído cinza, e o agente descreveu o que recebeu. O gabarito descrevia o arquivo original.

### O que a pergunta não protegia

A execução expôs dois pontos fracos na bancada:

- **O texto dentro de cada imagem é o nome do arquivo em maiúsculas.** A resposta (2) se deduz do
  nome.
- **O item 13 se chama `photo-12-white-star-on-navy.png`.** "Azul-marinho" está no nome.

Portanto, a prova de que o modelo olhou as imagens não está nas respostas que o nome permite. Ela
está nos **anexos conferidos no harness** e em detalhes que o nome não dá:

- no item 4, `AVATAR PNG` na ordem em que aparece na imagem, enquanto o nome diz `png-avatar`, e a
  figura identificada como círculo;
- no item 9, a faixa branca central e o fundo cinza;
- nos itens 12, 13 e 15, a legenda "na parte inferior da imagem";
- no item 13, as cinco pontas da estrela.

Para uma bancada futura, a legenda não deve repetir o nome, e os nomes devem ser neutros, como
`img-01.jpg`.

### Imprecisões do agente que não alteram o veredito

- O agente atribuiu ao "servidor" a conversão da miniatura do item 13 de PNG para JPEG. Quem faz
  isso é o `ImageThumbTask`, no processamento, como registrado em T003.
- Para a estrela de 5 pontas, ele contou "10 lados" e explicou a contagem. O gabarito não pede
  número de lados para a estrela.
