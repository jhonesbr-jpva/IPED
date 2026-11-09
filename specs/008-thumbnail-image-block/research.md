# Phase 0 — Research: miniatura como bloco de imagem

**Feature**: `008-thumbnail-image-block` | **Date**: 2026-09-18

Este documento resolve os dois marcadores `NEEDS CLARIFICATION` do spec e mais quatro
incógnitas que a leitura do código levantou. Cada decisão registra o que foi escolhido,
por quê, e o que foi descartado.

---

## D1 — Negociação de capacidade (resolve FR-012)

**Decisão**: **emitir sempre**. O bloco de imagem acompanha o resultado da miniatura
independentemente do que o cliente anunciou no `initialize`.

**Rationale**: não existe o que negociar. O servidor declara
`protocolVersion = "2025-06-18"` (`iped-mcp/src/main/java/iped/mcp/protocol/McpDispatcher.java`,
constante `PROTOCOL_VERSION`). Nessa revisão do MCP, `CallToolResult.content` é um array de
`ContentBlock`, cuja união já inclui `TextContent`, `ImageContent`, `AudioContent`,
`ResourceLink` e `EmbeddedResource`. As capacidades que um cliente declara no handshake são
`roots`, `sampling`, `elicitation` e `experimental` — **nenhuma delas fala sobre tipos de bloco
de conteúdo**. A tolerância a blocos que o cliente não renderiza é premissa do protocolo, não
concessão do servidor.

Um cliente que quebre ao receber `type: "image"` está quebrando contra a especificação que ele
mesmo aceitou ao responder `2025-06-18`. O servidor não pode consertar isso, e tentar consertar
cria um problema maior: um segundo caminho de resposta, condicionado a um campo que o protocolo
não define — ou seja, um comportamento que só se reproduz com o cliente certo e que nenhum teste
de contrato alcança.

**Alternativas consideradas**:

- *Negociar por `capabilities.experimental`*: exigiria inventar uma chave privada e documentá-la
  para cada harness. Extensão proprietária sobre protocolo aberto — o mesmo vício que a
  [006](../006-export-allowlist-socket-transport/spec.md) já apontou no transporte socket.
  Rejeitada.
- *Configurar por instalação (`emitImageBlocks=true|false`)*: transferiria ao examinador uma
  decisão que é do protocolo, e criaria duas formas de resposta em campo, com relatos de bug
  indistinguíveis entre si. Rejeitada — o Princípio IV pede configuração para **comportamento**,
  não para conformidade com especificação.
- *Emitir só quando `clientInfo.name` for conhecido*: lista de permissão por nome de cliente.
  Frágil, envelhece mal, e nada tem a ver com capacidade real. Rejeitada.

---

## D2 — Extensão a conteúdo bruto (resolve FR-013)

**Decisão**: **fora de escopo**. Apenas `contentClass = thumbnail` emite bloco de imagem.
`iped_item_content` continua exatamente como está.

**Rationale**: três razões independentes, e a terceira sozinha já bastaria.

1. **A classe de conteúdo é diferente e a política de egresso decide por ela.**
   `iped_item_content` declara `returnsContent("binary")`
   (`iped-mcp/src/main/java/iped/mcp/tools/ItemTools.java`); a miniatura declara `thumbnail`.
   Uma instalação que permita `thumbnail` e bloqueie `binary` está dizendo algo preciso, e
   emitir imagem para `binary` atravessaria essa distinção.
2. **O propósito declarado é outro.** O conteúdo bruto existe para estabelecer *o que um
   arquivo é* — cabeçalho, número mágico, texto curto em codificação inesperada. A própria
   orientação diz "nunca para olhar uma figura".
3. **O conteúdo bruto é truncado por desenho, e imagem truncada é o que esta feature proíbe.**
   `ContentAccess.content()` devolve os primeiros `maxContentBytes` e sinaliza
   `truncated: true`. Uma imagem cortada ao meio renderiza como meia figura ou como nada — e o
   Edge Case do spec é explícito: "entregar imagem cortada seria pior que não entregar". Emitir
   bloco de imagem aqui exigiria primeiro decidir se o item cabe inteiro, o que é uma feature
   própria, com teto próprio e recusa própria.

**Alternativas consideradas**:

- *Emitir imagem quando `truncated == false` e `media_type` for `image/*`*: tecnicamente
  possível, mas produz uma ferramenta cujo formato de saída muda conforme o tamanho do item. O
  agente não teria como prever se vai ver a figura. Rejeitada — se isso for desejado, que seja
  uma feature com seu próprio teto explícito.
- *Nova ferramenta `iped_item_image`*: seria o caminho aditivo correto para essa capacidade, e
  fica registrado aqui como sucessora natural. Fora do escopo desta entrega.

---

## D3 — Tipo de mídia: detectado, não presumido (FR-002)

**Decisão**: detectar o tipo a partir dos próprios bytes da miniatura. Quando a detecção não
produzir um tipo `image/*`, **nenhum bloco de imagem é emitido** e o resultado declara que o
tipo não pôde ser estabelecido.

**Rationale**: hoje o campo é constante — `result.put("media_type", "image/jpeg")` em
`iped-mcp/src/main/java/iped/mcp/item/ContentAccess.java` — e a constante está errada para uma
parte real do acervo. Miniaturas chegam ao índice por dois caminhos distintos:

| Origem | Formato |
|---|---|
| `ImageThumbTask` (`PREVIEW_EXT = "jpg"`), `DocThumbTask` (`ImageIO.write(img, "jpg", …)`), `VideoThumbTask` (`.jpg`) | JPEG, sempre |
| `ParsingTask`, a partir de `ExtraProperties.THUMBNAIL_BASE64` | **o que o parser tiver posto lá** |

O segundo caminho é alimentado por `WhatsAppParser`, `TelegramParser`, `DiscordParser`,
`SkypeParser`, `ThreemaParser`, `VCardParser` e `PDFTextParser`, e na maioria desses casos os
bytes vêm **direto do banco da fonte** — avatares de contato, fotos de vCard. Nada garante JPEG
ali; PNG e GIF ocorrem. Declarar `image/jpeg` sobre bytes PNG faz o cliente tentar decodificar
como JPEG e falhar, e escreve um tipo falso na cópia estruturada, que é material que um perito
pode citar.

**Ferramenta**: Apache Tika, via `MimeTypes`/`TikaConfig`, já presente no classpath por
`iped-engine`/`iped-parsers` — `ContentAccess` já importa `org.apache.tika.*`. **Nenhuma
dependência nova** (Restrições da plataforma; Assumptions do spec).

**Alternativas consideradas**:

- *Tabela de assinaturas própria (JPEG/PNG/GIF/BMP/WEBP)*: ~20 linhas, sem custo de
  inicialização. Rejeitada por reimplementar o que o Tika já faz e o IPED já confia — mas
  registrada como saída caso o custo de detecção apareça na medição.
- *Manter `image/jpeg` e apenas acrescentar o bloco*: propaga o defeito para dentro do bloco
  novo, agora com consequência visível. Rejeitada; FR-002 é explícito.
- *Presumir `image/jpeg` quando a detecção falhar*: é exatamente "presumir por omissão", que
  FR-002 proíbe. Rejeitada.

**Efeito colateral aceito**: a cópia estruturada passa a poder trazer `media_type: "image/png"`
onde antes trazia `"image/jpeg"`. É correção de um valor errado, não regressão: FR-004 exige
que o cliente continue sabendo o tipo, e ele passa a saber o tipo **certo**.

---

## D4 — O payload base64 dentro do bloco de texto

**Decisão**: manter a **cópia estruturada byte a byte idêntica** (`data` continua lá, inteiro)
e, **apenas** no bloco de texto e **apenas** para a classe `thumbnail`, substituir o valor de
`data` por uma nota curta dizendo que os bytes seguem no bloco de imagem.

> **Esta decisão motivou emenda no spec.** FR-003 recebeu a ressalva correspondente em
> 2026-09-20, e SC-002 ganhou limiar de aprovação. O que segue é o raciocínio que levou a ela,
> registrado como estava no momento da decisão.

**Rationale**: FR-003 e SC-002 não podem ser satisfeitos ao mesmo tempo na leitura literal.

- FR-003 pede que a representação textual e a cópia estruturada continuem sendo emitidas.
- SC-002 pede que "examinar uma imagem deixe de transcrever a codificação da figura no
  diálogo", com o custo de contexto caindo ao de uma imagem.

Se o bloco de texto continuar carregando o base64 inteiro, o custo de contexto **sobe**: a
figura passa a ser transmitida duas vezes, uma ilegível e outra legível. A feature cujo motivo
declarado é parar de pagar caro por cegueira passaria a cobrar o dobro. SC-002 é critério
obrigatório do spec e nenhuma outra leitura o satisfaz.

A elisão é segura porque a informação não se perde em lugar nenhum que alguém realmente leia:

- `structuredContent` fica intacto — é o caminho documentado e é o que todo consumidor
  programático deste servidor usa. `McpSessionRule.call()` devolve
  `response.path("result").path("structuredContent")`, e **todos** os testes leem por ali.
- O bloco de texto continua declarando `available`, `media_type`, `bytes` e `encoding` — que é
  literalmente o que FR-004 exige ("continuar sabendo que a miniatura existe, com tipo e
  tamanho"). FR-004 não exige que os bytes estejam no texto.
- A própria Assumption do spec explica que a preocupação com remover texto é "perda de
  informação para quem lê dados estruturados". Esses leem `structuredContent`, que não muda.

O que um cliente perde: a possibilidade de decodificar a miniatura a partir do bloco de texto
serializado, ignorando tanto a cópia estruturada quanto o bloco de imagem. Esse cliente é
hipotético e não existe nos testes nem nos guias de instalação.

**Alternativas consideradas**:

- *Bloco de texto byte a byte idêntico (conservadora)*: satisfazia FR-003 ao pé da letra e
  **falhava SC-002**, porque o base64 continuaria no diálogo — agora acompanhado da imagem.
  Rejeitada, e a rejeição foi levada ao spec em vez de ficar só aqui.
- *Remover `data` também de `structuredContent`*: aí sim quebra FR-003 e FR-004 de verdade, e
  quebra teste (`AvailabilityTest` lê `data` pela cópia estruturada). Rejeitada.
- *Elidir para toda classe de conteúdo*: atingiria `iped_item_content`, que não emite imagem e
  cujo base64 é a única entrega. Rejeitada — a elisão fica presa à mesma condição que emite a
  imagem (D5).

---

## D5 — Onde o bloco é montado, e como FR-005 é garantido

**Decisão**: montar no caminho de renderização de resultado do `McpDispatcher`
(`renderResult`), com o gatilho amarrado à **classe de conteúdo declarada pelo descritor**
(`ToolDescriptor.getContentClass()` igual a `thumbnail`), e a extração dos bytes isolada em uma
classe nova, `iped.mcp.protocol.ImageBlock`.

**Rationale**: `renderResult` é o único lugar do servidor onde `content[]` é construído — é
onde o bloco tem de nascer, e não há alternativa aditiva fora dele (o spec pede essa
justificativa explicitamente; ver `plan.md`, Constitution Check).

Amarrar ao `contentClass` — e não ao nome da ferramenta — é o que dá FR-005 de graça: a mesma
marcação que a política de egresso já usa para decidir passa a decidir também a forma. Nenhuma
outra ferramenta declara `thumbnail`, então nenhuma outra adquire imagem como efeito colateral,
e o dia em que uma declarar, terá declarado de propósito.

**Ordem no array `content`**: texto primeiro, imagem depois. Mantém o índice 0 estável para
qualquer cliente que leia `content[0].text` — e existem clientes assim.

**Alternativas consideradas**:

- *Marcador próprio no descritor (`ToolDescriptor.rendersImage()`)*: um segundo eixo de
  classificação que pode divergir do primeiro. Duas marcações para o mesmo fato é como se cria
  a inconsistência que FR-005 quer impedir. Rejeitada.
- *Tipo de retorno novo (`ToolResult` com payload + anexo)*: mudaria o contrato de
  `ToolDescriptor.Handler`, que todas as ferramentas implementam, para servir uma. Rejeitada
  por desproporção.
- *Montar dentro de `ContentAccess.thumbnail()`*: ela devolve `Map<String,Object>`, que vira
  `structuredContent`. Qualquer bloco montado ali vazaria para a cópia estruturada. Rejeitada.

---

## D6 — Paridade entre transportes (FR-011)

**Decisão**: nada específico a implementar; cobrir com teste.

**Rationale**: `StdioTransport` e `SocketTransport` transportam JSON-RPC já serializado pelo
`JsonRpcCodec`; nenhum dos dois inspeciona `result.content`. O bloco de imagem nasce acima dos
dois, em `McpDispatcher`, portanto a paridade é estrutural, não construída. O que falta é
**prová-la**: `TransportParityTest` hoje compara `tools/list`, que não exercita resultado.

**Ação**: estender a cobertura de paridade a um `tools/call` de miniatura, comparando o
`content[]` dos dois transportes.

---

## Incógnitas remanescentes

Nenhuma. Os dois marcadores do spec estão resolvidos (D1, D2). D4 é uma decisão de projeto que
reinterpreta FR-003 e está sinalizada para revisão no `plan.md`.
