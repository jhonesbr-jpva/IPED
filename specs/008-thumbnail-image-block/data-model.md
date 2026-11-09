# Phase 1 — Data Model: miniatura como bloco de imagem

**Feature**: `008-thumbnail-image-block` | **Date**: 2026-09-18

Esta feature não cria entidade persistida nem toca o índice. O que ela modela é a **forma da
resposta** de uma chamada de ferramenta. As três entidades do spec aparecem aqui como
estruturas em memória, com suas regras de validação e transições.

---

## E1 — Resultado de ferramenta (`CallToolResult`)

O que o servidor devolve a `tools/call`. Construído em `McpDispatcher.renderResult`.

| Campo | Tipo | Antes | Depois |
|---|---|---|---|
| `content` | `ContentBlock[]` | sempre exatamente 1 bloco `text` | 1 bloco `text`, **mais** 1 bloco `image` quando E4 é emitível |
| `structuredContent` | objeto | payload da ferramenta | **inalterado** |
| `isError` | booleano | `false` | **inalterado** |

**Invariantes**:

- **R1.1** — `content[0]` é sempre o bloco de texto. A posição 0 nunca muda de tipo (D5).
- **R1.2** — `content` nunca fica vazio, mesmo quando a miniatura está ausente.
- **R1.3** — `structuredContent` é independente de `content`: nenhuma condição desta feature
  altera o que ele carrega. É o que sustenta FR-003 e FR-004.
- **R1.4** — o comprimento de `content` é 1 ou 2. Nunca mais que isso nesta feature.
- **R1.5** — **não existe resposta que carregue imagem e erro ao mesmo tempo**, e isso é estrutural,
  não convenção. O spec lista esse caso entre os Edge Cases; o desenho o torna inalcançável. Um
  `McpError` — recusa de egresso, item inexistente, falha de ferramenta — é lançado dentro de
  `callTool` e vira resposta de **erro** JSON-RPC, com `error` no lugar de `result`. `renderResult`
  nunca chega a rodar, `content` nunca é construído, e `isError` sai sempre `false` porque só é
  escrito no caminho em que a ferramenta devolveu resultado. A sinalização de erro, portanto, jamais
  depende de o cliente entender blocos de imagem: ela vive no envelope JSON-RPC, acima de `content`.
  Quem implementar esta feature **não deve** criar um caminho que misture os dois — seria inventar
  o problema que hoje não existe.

---

## E2 — Classe de conteúdo (`ContentClass`)

Enum existente em `McpServerConfig`: `metadata`, `text`, `thumbnail`, `binary`. Declarada por
ferramenta via `ToolDescriptor.returnsContent(...)`.

**Não muda de valores.** O que muda é o número de decisões que ela governa:

| Decisão | Antes | Depois |
|---|---|---|
| Política de egresso no dispatcher | sim | sim |
| Política de egresso em `ContentAccess` | sim | sim |
| **Forma do bloco de conteúdo** | — | **sim** |
| **Elisão do payload no bloco de texto** | — | **sim** |

**Invariantes**:

- **R2.1** — apenas `thumbnail` habilita bloco de imagem (FR-005). Qualquer outra classe, e a
  ausência de classe, produzem exatamente a resposta de hoje.
- **R2.2** — a decisão de forma acontece **depois** da decisão de egresso, nunca antes. Uma
  classe bloqueada não chega ao montador de blocos (FR-006).

---

## E3 — Miniatura (payload de `iped_item_thumbnail`)

O `Map<String,Object>` devolvido por `ContentAccess.thumbnail(...)`, que vira
`structuredContent`. Dois estados mutuamente exclusivos.

### Estado `available: true`

| Campo | Tipo | Regra |
|---|---|---|
| `case_id`, `item_id` | herdados de `base(...)` | inalterados |
| `available` | booleano | `true` |
| `encoding` | string | `"base64"`, inalterado |
| `media_type` | string | **detectado dos bytes** (D3); antes era a constante `"image/jpeg"` |
| `bytes` | inteiro | tamanho real em bytes, inalterado |
| `data` | string | base64 completo dos bytes, **inalterado** |

### Estado `available: false`

| Campo | Tipo | Regra |
|---|---|---|
| `available` | booleano | `false` |
| `reason` | string | não vazia; distingue os três casos de FR-009 |
| `remedy` | string | o que consultar em lugar disso |
| `data` | — | **ausente**; `AvailabilityTest` já exige isso |

**Invariantes**:

- **R3.1** — `data` presente ⟺ `available == true`. Nenhuma mudança aqui.
- **R3.2** — `media_type` presente ⟹ foi detectado dos bytes, nunca presumido (FR-002).
- **R3.3** — as três razões de ausência continuam distinguíveis entre si pelo texto de
  `reason` (FR-009, SC-006): item não visual / nada renderizado no processamento / acima do
  teto. A terceira sempre cita o tamanho real e o teto em vigor (FR-008).

### Motivo de ausência novo

D3 introduz um quarto motivo, e ele é de ausência **do bloco**, não da miniatura:

- **tipo indeterminado** — há bytes, dentro do teto, mas a detecção não produziu um tipo
  `image/*`. O resultado segue `available: true` com `data` (nada se perde), e acrescenta
  `media_type_note` explicando que o tipo não pôde ser estabelecido e que por isso a figura não
  foi entregue como imagem. **Nenhum bloco de imagem é emitido** (R4.2).

---

## E4 — Bloco de imagem (`ImageContent`)

Estrutura nova, montada por `iped.mcp.protocol.ImageBlock` a partir de E3. Nunca persistida.

| Campo | Tipo | Valor |
|---|---|---|
| `type` | string | `"image"` — literal do protocolo |
| `data` | string | o mesmo base64 de `E3.data`, sem recodificação |
| `mimeType` | string | o mesmo valor de `E3.media_type` |

**Invariantes**:

- **R4.1** — `data` e `mimeType` vêm de E3 sem transformação. Uma segunda codificação seria uma
  segunda chance de divergir do que a cópia estruturada diz.
- **R4.2** — o bloco só é emitido quando **todas** valem: classe é `thumbnail`;
  `available == true`; `data` presente e não vazio; `media_type` presente e casando com
  `image/*`. Qualquer uma falhando, a resposta é a de hoje.
- **R4.3** — o bloco nunca carrega dado truncado. Não há caminho para isso: a recusa por teto
  acontece em E3, antes, e produz `available: false`.

---

## E5 — Bloco de texto (`TextContent`)

Existente. Muda apenas na renderização do payload de miniatura.

| Campo | Regra |
|---|---|
| `type` | `"text"`, inalterado |
| `text` | JSON do payload, *pretty-printed* — inalterado, **exceto** quando E4 é emitido |

**Quando E4 é emitido** (D4): o JSON serializado no bloco de texto tem o valor de `data`
substituído por uma nota curta e fixa, em inglês, dizendo que os bytes da miniatura seguem no
bloco de imagem da mesma resposta e que `structuredContent` os carrega na íntegra.

**Invariantes**:

- **R5.1** — a substituição atinge **somente** a chave `data` de nível superior, somente na
  classe `thumbnail`, somente quando E4 foi emitido. Nenhum outro campo é tocado.
- **R5.2** — `available`, `media_type`, `bytes` e `encoding` permanecem no bloco de texto. É o
  que satisfaz FR-004 para um cliente que só lê texto.
- **R5.3** — `structuredContent` não é afetado por R5.1 (R1.3).

---

## Transições de estado

Uma chamada a `iped_item_thumbnail` percorre esta sequência. As três primeiras já existem.

```
tools/call
  │
  ├─ 1. portões do dispatcher (processamento, modo de acesso, concorrência)  [inalterado]
  ├─ 2. política de egresso por classe de conteúdo                           [inalterado, FR-006]
  ├─ 3. registro de auditoria write-ahead                                    [inalterado, FR-007]
  │
  ├─ 4. ContentAccess.thumbnail()
  │      ├─ sem bytes            → E3 ausente (motivo 1)      → 1 bloco de texto
  │      ├─ acima do teto        → E3 ausente (motivo 3)      → 1 bloco de texto   [FR-008]
  │      ├─ bytes, tipo indet.   → E3 disponível + nota       → 1 bloco de texto   [D3]
  │      └─ bytes, tipo image/*  → E3 disponível              → segue para 5
  │
  └─ 5. renderResult(payload, contentClass)
         ├─ classe != thumbnail  → 1 bloco de texto (caminho de hoje)               [FR-005]
         └─ classe == thumbnail e R4.2 satisfeita
                → bloco de texto com data elidido  +  bloco de imagem               [D4, D5]
```

Em **todos** os ramos, `structuredContent` sai idêntico ao que sai hoje, exceto pelo valor
corrigido de `media_type` (D3).

---

## O que esta feature não modela

- Nenhum campo do índice Lucene. Nenhum nome de campo é lido, escrito ou reinterpretado —
  Princípio II intacto.
- Nenhuma configuração nova. Tetos, política de egresso e modo de acesso seguem os mesmos
  (`maxThumbnailBytes`, `egressAllowedClasses`, `accessMode`), lidos de
  `conf/McpServerConfig.txt` como hoje.
- Nenhum estado entre chamadas. A montagem do bloco é função pura do payload.
- Nenhuma renderização. A miniatura é lida do que o processamento já produziu.
