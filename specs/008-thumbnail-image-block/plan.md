# Implementation Plan: Miniatura como imagem do protocolo, não como base64 dentro de texto

**Branch**: `008-thumbnail-image-block` | **Date**: 2026-09-18 | **Spec**: [spec.md](spec.md)

**Input**: Feature specification from `/specs/008-thumbnail-image-block/spec.md`

## Summary

A miniatura que o IPED já renderizou chega ao agente como texto: uma cadeia base64 que custa
contexto proporcional ao tamanho da imagem e não mostra nada. A causa não está na ferramenta —
está no caminho que toda resposta percorre: `McpDispatcher.renderResult` monta **sempre** um
único bloco de texto, e o servidor não emite bloco de imagem em lugar nenhum.

A abordagem: passar a classe de conteúdo declarada pelo descritor até o montador de resultado e,
**apenas** para `contentClass = thumbnail`, acrescentar um segundo bloco `type: "image"` ao lado
do bloco de texto que já existe. O tipo de mídia passa a ser detectado dos próprios bytes em vez
da constante `"image/jpeg"` que hoje está errada para avatares de chat. `structuredContent` sai
intacto, os portões (egresso, auditoria, teto, modo de acesso) ficam onde estão e na mesma
ordem, e nenhuma outra ferramenta muda de forma.

Sem dependência nova, sem configuração nova, sem escrita em evidência. O trabalho concentra-se
em três arquivos de produção do `iped-mcp`, mais a orientação carregada pelo agente.

---

## D4 — a decisão que motivou emenda no spec

**D4 (research.md)**: o base64 continua íntegro em `structuredContent`, mas dentro do **bloco de
texto** o valor de `data` é substituído por uma nota apontando para o bloco de imagem.

FR-003, como estava escrito, pedia que a representação textual continuasse sendo emitida sem
ressalva; SC-002 pede que o custo de contexto caia ao de uma imagem. Se o base64 permanecesse no
bloco de texto, a figura passaria a trafegar **duas** vezes e o custo **subiria** — a feature
cobraria o dobro pelo problema que existe para resolver. Nenhuma outra leitura satisfazia SC-002,
que é critério obrigatório.

A elisão é confinada: só a chave `data`, só na classe `thumbnail`, só quando o bloco de imagem
foi de fato emitido. `structuredContent` não muda, e é por onde todo consumidor programático
deste servidor lê (`McpSessionRule.call()` e todos os testes). O bloco de texto continua
declarando `available`, `media_type`, `bytes` e `encoding`, que é literalmente o que FR-004
exige.

**Resolvido em 2026-09-20**: a tensão não ficou pendente entre plano e spec. FR-003 recebeu a
ressalva de D4, FR-004 passou a declarar-se deliberadamente distinto de FR-003, e SC-002 ganhou
limiar de aprovação (redução ≥ 90% dos tokens da chamada, em miniaturas de 100 KB ou mais),
medido por T032. O raciocínio completo e as três alternativas descartadas continuam em
[research.md](research.md), D4.

---

## Technical Context

**Language/Version**: Java 11 LTS (Liberica OpenJDK 11 Full, `H:\java\LibericaJDK-11-Full`).
Restrição de **runtime**, não só de compilação — o release embarca um JRE 11.

**Primary Dependencies**: nenhuma nova. Jackson 2.13 (`jackson-databind`) para montar os nós
JSON, já em uso; Apache Tika 2.4.0 para a detecção de tipo de mídia, já no classpath por
`iped-engine`/`iped-parsers` e já importado por `ContentAccess`.

**Storage**: N/A. Nada é persistido. A miniatura é lida do índice Lucene do caso (campo
`BasicProps.THUMB`, `StoredField`), em somente-leitura, como hoje.

**Testing**: JUnit 4 + `mvn -pl iped-mcp test`. As suítes de integração usam
`McpTestSupport.requireReferenceCase()`, que **pula** quando o caso não está configurado — e um
skip aqui não é um pass. Ver o Cenário 1 do [quickstart](quickstart.md) e a advertência sobre
`fastmode` abaixo.

**Target Platform**: servidor MCP em processo próprio, Windows e Linux, sobre os transportes
`STDIO` e `SOCKET`. Protocolo MCP `2025-06-18`, declarado em `McpDispatcher.PROTOCOL_VERSION`.

**Project Type**: servidor JSON-RPC 2.0 — módulo Maven `iped-mcp`, dentro do multi-módulo do
IPED.

**Performance Goals**: a detecção de tipo custa a leitura dos primeiros bytes da miniatura, já
em memória. Nenhuma I/O nova, nenhuma recodificação: o base64 que vai ao bloco de imagem é o
mesmo objeto `String` que vai à cópia estruturada. O ganho medido é de contexto, não de tempo
(SC-002).

**Constraints**: teto de miniatura em `maxThumbnailBytes` (default 262144), lido de
`conf/McpServerConfig.txt`. Política de egresso por classe de conteúdo, avaliada **antes** da
montagem. Auditoria write-ahead antes da execução. Caso aberto em somente-leitura.

**Scale/Scope**: três arquivos de produção alterados, um criado, mais `SKILL.md` e o
`CLAUDE.md` do módulo. Nenhuma alteração fora do `iped-mcp`.

**Bancada**: o caso `H:\iped-cases\rockpi4-smoke` **não serve** — `fastmode` desliga
`enableImageThumbs`, `enableVideoThumbs`, `enableDocThumbs` e `enableImageSimilarity`, então
todo item cai no ramo `available: false`. É preciso um caso processado com o perfil padrão.
Receita no [quickstart](quickstart.md), Pré-requisitos.

---

## Constitution Check

*GATE: avaliado contra a constituição 1.0.0 (ratificada em 2026-08-04), antes da Phase 0 e
reavaliado após a Phase 1.*

| Princípio | Veredito | Fundamento |
|---|---|---|
| **I — Integridade da evidência** | **PASSA** | Nenhuma escrita, em nenhum modo. Não há caminho novo até a evidência: os bytes vêm do mesmo `item.getThumb()` de hoje, lidos do índice. O que muda é o invólucro da resposta, depois que os bytes já estão em memória. `ReadOnlyInvariantTest` continua valendo sem edição. |
| **II — Caso processado é contrato permanente** | **PASSA** | Nenhum nome de campo Lucene é lido, escrito ou reinterpretado; `AppAnalyzer` intocado; nenhuma assinatura de `iped-api` alterada. A mudança inteira vive acima do índice. |
| **III — Estender antes de modificar** | **PASSA COM JUSTIFICATIVA** | Ver abaixo — o spec pede essa defesa nominalmente. |
| **IV — Comportamento configurável vive em configuração** | **PASSA** | Nenhuma constante nova governando comportamento. O teto continua em `maxThumbnailBytes`, a política em `egressAllowedClasses`, o modo em `accessMode`, todos lidos de `conf/`. D1 recusa explicitamente criar uma chave `emitImageBlocks`: conformidade com o protocolo não é preferência de instalação. |
| **V — Nada implícito no que varia por ambiente** | **PASSA, e melhora** | O tipo de mídia deixa de ser presumido e passa a ser detectado (D3) — é exatamente o princípio aplicado a um valor que hoje é implícito e errado. Código novo em inglês, SLF4J, sem `System.out`. Nenhum trabalho de UI. |

### Princípio III — a defesa que o spec exige

O spec registra: *"a mudança recai sobre o caminho compartilhado por todas as ferramentas. O
plano MUST justificar por que o caso não podia ser resolvido de forma aditiva fora dele e MUST
demonstrar que a resposta anterior permanece contida na nova."*

**Por que não havia caminho aditivo fora do compartilhado.** O bloco de conteúdo vive em
`result.content[]`, e `McpDispatcher.renderResult` é o **único** lugar do servidor onde esse
array é construído. Não é um lugar entre vários: é o ponto. As três alternativas aditivas foram
examinadas e descartadas em [research.md](research.md), D5 —

- montar dentro de `ContentAccess.thumbnail()` não funciona: o que ela devolve vira
  `structuredContent`, então qualquer bloco montado ali vazaria para a cópia estruturada;
- um tipo de retorno novo (`ToolResult`) mudaria o contrato de `ToolDescriptor.Handler`, que
  todas as ferramentas implementam, para servir uma;
- uma segunda ferramenta paralela duplicaria os portões de egresso, auditoria e teto — e
  duplicar um portão é como se cria a brecha entre as duas cópias.

**Por que a modificação é contida.** O que a constituição manda justificar são alterações em
`Manager`, `Worker`, `ProcessingQueues`, `IndexWriter`, `SleuthkitClient` e no Aho-Corasick —
componentes com invariantes de concorrência. `renderResult` não é nenhum deles: é um método
estático, sem estado, sem I/O, que serializa um `Map` já pronto. Não há invariante de
concorrência a revisar porque não há estado compartilhado.

**Que a resposta anterior permanece contida na nova**, demonstrado item a item:

| Elemento da resposta de hoje | Na resposta nova |
|---|---|
| `content[0].type == "text"` | idêntico, na mesma posição |
| `content[0].text` (JSON do payload) | idêntico, **exceto** o valor de `data` na classe `thumbnail` (D4) |
| `structuredContent` | **byte a byte idêntico**, salvo `media_type` passar a trazer o tipo correto (D3) |
| `isError` | idêntico |
| resposta de toda ferramenta que não seja miniatura | **idêntica em tudo** |

O gatilho é a classe de conteúdo que o descritor já declara — a mesma marcação que a política de
egresso usa. Nenhuma ferramenta adquire imagem por efeito colateral (FR-005), e o dia em que uma
declarar `thumbnail`, terá declarado de propósito.

**Complexity Tracking**: nada a preencher. Não há violação a justificar.

### Reavaliação após a Phase 1

Os artefatos de desenho não introduziram entidade persistida, configuração, dependência nem
componente concorrente. Os cinco vereditos continuam valendo, e o veredito de III fica mais
forte, não mais fraco: o desenho final concentra o código novo em uma classe própria
(`ImageBlock`), deixando em `renderResult` apenas a passagem da classe de conteúdo e a
delegação. **Portão aprovado.**

---

## Project Structure

### Documentation (this feature)

```text
specs/008-thumbnail-image-block/
├── plan.md              # este arquivo
├── spec.md              # entrada
├── research.md          # Phase 0 — D1 a D6; resolve FR-012 e FR-013
├── data-model.md        # Phase 1 — E1 a E5, invariantes, transições
├── quickstart.md        # Phase 1 — nove cenários de validação
├── contracts/
│   └── tool-result-image-block.md   # forma do resultado de tools/call
├── checklists/
└── tasks.md             # Phase 2 — gerado por /speckit-tasks, NÃO por este comando
```

### Source Code (repository root)

Tudo dentro do `iped-mcp`. Nenhum outro módulo é tocado.

```text
iped-mcp/
├── src/main/java/iped/mcp/
│   ├── protocol/
│   │   ├── McpDispatcher.java          # ALTERADO — renderResult recebe a classe de conteúdo
│   │   ├── ImageBlock.java             # NOVO — monta o bloco de imagem e elide o payload no texto
│   │   └── ToolDescriptor.java         # inalterado — getContentClass() já existe
│   ├── item/
│   │   └── ContentAccess.java          # ALTERADO — thumbnail() detecta o tipo em vez de presumir
│   ├── tools/
│   │   └── ItemTools.java              # ALTERADO — apenas a description da ferramenta
│   ├── egress/EgressPolicy.java        # inalterado
│   └── config/McpServerConfig.java     # inalterado — nenhuma chave nova
│
├── src/main/resources/skill/
│   └── SKILL.md                        # ALTERADO — seção "Bytes and pictures" (FR-010)
│
├── src/test/java/iped/mcp/
│   ├── contract/
│   │   ├── ImageBlockContractTest.java # NOVO — forma da resposta, C1 a C7
│   │   └── TransportParityTest.java    # ALTERADO — estende paridade a um tools/call (D6)
│   ├── integration/
│   │   └── ThumbnailImageBlockTest.java # NOVO — presença, ausência, teto, egresso
│   └── unit/
│       └── MediaTypeDetectionTest.java # NOVO — detecção vs. presunção (D3)
│
└── CLAUDE.md                           # ALTERADO — a forma da resposta é contrato do módulo
```

**Structure Decision**: a feature não justifica módulo novo. A capacidade é a forma de resposta
de um servidor que já existe, não escopo próprio, e não traz dependência a isolar — os dois
critérios que a seção *Restrições da plataforma* usa para recomendar um módulo. O código novo
nasce como classe própria dentro do pacote que já é o dono do protocolo
(`iped.mcp.protocol`), o que mantém a fronteira explícita sem multiplicar artefatos Maven.

---

## Complexity Tracking

> Preencher **apenas** se o Constitution Check tiver violações a justificar.

Nada a registrar. Nenhum dos cinco princípios é violado; a única passagem que exige defesa
explícita — Princípio III, sobre tocar o caminho compartilhado — está fundamentada acima com as
alternativas aditivas que foram examinadas e o motivo de cada descarte, e a constituição trata
isso como conformidade, não como exceção.
