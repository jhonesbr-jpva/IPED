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
| **7 — agente descreve a imagem** | ⚠️ **não executado** | Exige harness com modelo de visão conectado ao servidor. **Não rodado nesta sessão.** O servidor faz sua parte — o bloco de imagem sai correto, conferido no Cenário 2 — mas SC-001, SC-003 e SC-005 dependem de uma execução que não aconteceu aqui |
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
