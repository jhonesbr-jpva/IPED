# Phase 1 — Data Model: busca semântica

**Spec**: [spec.md](spec.md) | **Plano**: [plan.md](plan.md) | **Pesquisa**: [research.md](research.md)

As entidades seguem as da spec (seção Key Entities), agora com campos, regras e transições. A
forma física (índices, arquivos, JSON) está em [contracts/case-storage.md](contracts/case-storage.md).
Este documento fixa **o que** cada entidade guarda e **quais invariantes** valem.

---

## E1 — Pergunta semântica (`SemanticRequest`)

| Campo | Tipo | Regra |
|---|---|---|
| `cases` | lista de casos abertos | Ao menos um. Com vários, só os compatíveis participam (FR-017) |
| `modality` | `TEXT` \| `VISUAL` \| `BOTH` | Só entre as preparadas no caso (FR-033) |
| `mode` | `SEMANTIC` \| `HYBRID` | Só na modalidade texto; padrão `defaultTextMode` (FR-046). Na visual é sempre `SEMANTIC` |
| `query` | texto | Obrigatório, a menos que haja `reference`. Até `maxQueryChars` (padrão 2000); o excedente é **truncado e declarado** |
| `reference` | `{itemId}` ou `{itemId, charStart, charEnd}` ou `{itemId, selectedText}` | "Mais como este" (FR-013). Só em `TEXT`. O item precisa estar coberto; se não estiver, a recusa diz o motivo dele |
| `recorte` | conjunto de ids do índice principal | Interface: o resultado corrente. MCP: `query`/`bookmark`, resolvidos pelo buscador de palavra. Vazio → resultado "recorte vazio", nunca "nada encontrado" |
| `depth` | inteiro | Padrão `defaultDepth`; `1 ≤ depth ≤ maxDepth`. Acima do teto é rejeitado na validação interna do pedido; nenhuma superfície permite pedi-lo — a interface desabilita "mais resultados" e o MCP encerra a paginação com `ceiling_reached` (ver [contracts/ui-surface.md](contracts/ui-surface.md) e [contracts/mcp-tools.md](contracts/mcp-tools.md)) |
| `cursor` | opaco | Só no MCP; aponta uma posição **dentro da lista do teto** (D11) |

---

## E2 — Modalidade e registro de construção (`BuildRecord`)

Um registro por caso, com uma seção por modalidade preparada.

| Campo (por modalidade) | Conteúdo |
|---|---|
| `state` | `PREPARING` \| `COMPLETE` \| `PARTIAL` \| `DISABLED_MISMATCH` (transições abaixo) |
| `keys` | Na visual, `images` e `videos` (ligada/desligada), cada uma registrada |
| `source` | `LOCAL` ou `REMOTE` |
| `model` | Identidade (`<repo>@<revisão>+<variante>`), SHA-256 do artefato (local) ou identidade informada pelo serviço (remoto), dimensões, similaridade (`DOT_PRODUCT` sobre vetores normalizados), prefixos de pergunta e de trecho |
| `remote` | Protocolo e destino **da preparação** (URL sem credencial) — FR-024. O destino das perguntas depende da instalação em uso e vai em cada resultado, não aqui (emenda A4) |
| `fingerprint` | Versão das sondas, vetores das sondas, tolerância em vigor na construção |
| `params` | Tamanho e sobreposição de trecho, janela/passo/limites das sequências, `maxTextCharsPerItem`, `minImageSize`, categorias selecionadas, tipos de mensagem |
| `timing` | Início, fim, tempo total. Na visual, **separado** em imagens e vídeos (FR-021, SC-015) |
| `counts` | Itens cobertos e não cobertos por motivo |
| `ipedVersion` | Versão do IPED que construiu |
| `conflicts` | Conflitos de configuração detectados: vídeos ligados sem miniatura de vídeo, imagens ligadas sem miniatura de imagem |

**Invariantes**:
- O registro é escrito **antes** do primeiro documento e atualizado no `finish()`. Uma modalidade
  sem registro não existe para a busca.
- A identidade é a que o modelo **informa**. A declarada na configuração nunca a substitui
  (FR-043).
- O registro não contém credencial nem conteúdo de item.

### Transições de estado (por modalidade)

```text
            (processamento com a chave ligada)
 AUSENTE ──────────────────────────────────────► PREPARING
                                                   │   │
                          finish() bem-sucedido    │   │ interrompido (queda, cancelamento)
                                                   ▼   ▼
                                           COMPLETE    PARTIAL ──(--continue)──► PREPARING
                                               │
                  --append com a mesma         │  --append com impressão digital divergente
                  impressão digital            │
                         ▼                     ▼
                     PREPARING           DISABLED_MISMATCH  (itens do acréscimo: não cobertos,
                                                             motivo "modelo diferente do caso")
```

### Disponibilidade na hora da pergunta

| Situação | Resultado |
|---|---|
| `semantic/` ausente | Indisponível: "caso processado sem a capacidade — reprocesse com ela ligada" (FR-027) |
| Modalidade sem seção no registro | Indisponível: "modalidade desligada no processamento" (FR-033) |
| Modelo local ausente na estação | Indisponível: nomeia o modelo e o pacote que falta |
| Fonte configurada na instalação inalcançável | Indisponível: nomeia o destino ou o modelo que falta; não recorre a outro modelo nem troca de fonte sozinho (FR-044) |
| Fonte configurada é outra que não a da preparação (local × remota) | **Disponível**, se a impressão digital confirmar o mesmo modelo (emenda A4) |
| Impressão digital fora da tolerância | Indisponível: nomeia o modelo do caso e o modelo em uso (FR-016) |
| `PARTIAL` | Disponível, com a cobertura parcial declarada |
| `COMPLETE` | Disponível |

---

## E3 — Trecho (`PASSAGE`, `TRANSCRIPT`)

| Campo | Regra |
|---|---|
| `itemId` | Id do item no índice principal |
| `ordinal` | Posição do trecho no item, a partir de 0. É o critério de desempate depois da proximidade e do `itemId` (FR-015) |
| `charStart`, `charEnd` | Deslocamentos no texto do item **depois** do corte do cabeçalho de metadados. É por eles que o visualizador localiza o trecho |
| `text` | Texto literal do trecho, armazenado **e indexado** (`AppAnalyzer`, BM25 só neste índice) para a parte por palavra do modo híbrido (D20). É o que se exibe (FR-006). Nunca é resumo |
| vetor | Normalizado. Não é exibido nem sai do caso (FR-025) |

**Invariante (SC-006)**: `text` é igual ao intervalo `[charStart, charEnd)` do texto do item. Um
teste de integração recalcula e compara.

---

## E3b — Janela de foco (`FOCUS`, `TRANSCRIPT_FOCUS`)

Só em itens com mais de um trecho (research D23). Escolhe o trecho exibido; não ordena nada.

| Campo | Regra |
|---|---|
| `itemId` | Id do item no índice principal. **Sem** âncora: nunca entra em recorte |
| `ordinal` | Posição da janela no item, a partir de 0; desempata janelas de mesma proximidade |
| `passageOrdinal` | Ordinal do trecho (E3) onde a janela começa. É o `ordinal` do trecho exibido, para ler o contexto |
| `charStart`, `charEnd` | Como em E3 |
| `focusText` | Texto literal, só armazenado: não entra na parte por palavra |
| vetor | Normalizado, em doc values binários (`vf`), fora do grafo HNSW |

**Invariante (SC-006)**: `focusText` é igual ao intervalo `[charStart, charEnd)` do texto do item.

---

## E4 — Sequência de conversa (`SEQUENCE`)

| Campo | Regra |
|---|---|
| `chatId` | Id do item de chat pai |
| `messageIds` | Ids das mensagens, na ordem da conversa. É a âncora de abrir, marcar e filtrar (FR-005) |
| `lines` | Para cada mensagem: remetente, data e texto literal de `Message:Body`. O texto das linhas também é indexado para o modo híbrido, e a unidade da parte por palavra é a sequência inteira |
| `ordinal` | Posição da janela no chat |
| vetor | Calculado sobre as linhas `remetente: texto` |

**Invariantes**:
- Cada linha exibida é idêntica ao `Message:Body` da mensagem apontada (SC-006).
- Na ordenação, nenhuma mensagem aparece em duas entradas. Janelas sobrepostas de menor
  proximidade são **absorvidas** pela de maior (FR-009).
- Mensagens preparadas em sequência **não** geram trecho próprio. O item de chat do qual elas
  vieram perde seus trechos de documento no `finish()` e fica com o motivo "coberto pelas
  mensagens".

---

## E5 — Entrada visual (`IMAGE`, `VIDEO_FRAME`)

| Campo | Regra |
|---|---|
| `itemId` | A imagem, ou o vídeo |
| `frameIndex`, `frameCount` | Só em vídeo: célula do mosaico e total de células |
| `instantApproxMs` | Só em vídeo: instante **aproximado** (D8), sempre exibido com "≈" |
| vetor | Normalizado |

**Invariantes**:
- Na ordenação, um vídeo aparece **uma vez**, pelo melhor quadro (FR-009).
- Com a chave de vídeos ligada, subitens que são quadros de vídeo não entram como imagem (motivo
  "coberto pelo vídeo de origem").

---

## E6 — Estado de cobertura (`STATUS`)

Um documento sem vetor, por item considerado, por modalidade.

| Campo | Regra |
|---|---|
| `itemId` | — |
| `reason` | Código da tabela E7; `COVERED` quando coberto |
| `detail` | Opcional: caracteres truncados, quadros aproveitados, tentativas remotas |

Item do recorte **sem** documento de estado tem motivo derivado do registro (D12): `MODALITY_OFF`
se a modalidade não foi preparada naquele processamento, e `BEFORE_CAPABILITY` se o item é
anterior à preparação.

---

## E7 — Motivos de não cobertura

| Código | Rótulo exibido (PT-BR) | Origem | Em FR-008 |
|---|---|---|---|
| `COVERED` | coberto | — | — |
| `COVERED_TRUNCATED` | coberto até o limite (excedente declarado) | limite de texto | sim ("texto acima do limite") |
| `NO_TEXT` | sem texto | item sem texto extraído nem transcrição; mensagem sem texto próprio (corpo vazio, só marcador do parser, ou sem `Message-Body`) e conversa renderizada de chat cujas mensagens não têm texto (research D7) | sim |
| `ANALYSIS_NOT_RUN` | análise não executada (OCR/transcrição) | item que teria texto se a análise rodasse | sim |
| `IMAGE_TOO_SMALL` | imagem abaixo do tamanho mínimo | `minImageSize` | sim |
| `IMAGE_UNDECODABLE` | imagem não decodificável | falha de leitura da miniatura | sim |
| `NO_THUMBNAIL` | sem miniatura | miniatura não gerada para o item | acrescentado pela emenda A1 |
| `VIDEO_NO_FRAMES` | vídeo sem quadros extraídos | mosaico ausente | sim |
| `COVERED_BY_MESSAGES` | coberto pelas mensagens | chat cujas mensagens viraram sequência | acrescentado pela emenda A1 |
| `COVERED_BY_SOURCE_VIDEO` | coberto pelo vídeo de origem | quadro-subitem com vídeos ligados | acrescentado pela emenda A1 |
| `CATEGORY_NOT_SELECTED` | categoria não selecionada | fora das categorias configuradas | acrescentado pela emenda A1 |
| `BINARY_TEXT` | texto extraído de binário (strings), não preparado | texto do extrator de strings do IPED (executáveis e bibliotecas, binários desconhecidos, espaço não alocado, slack) com `prepareBinaryText = false` | acrescentado na revisão de 2026-09-27 (FR-001) |
| `REMOTE_UNAVAILABLE` | modelo remoto indisponível | tentativas esgotadas | acrescentado pela emenda A1 |
| `MODEL_MISMATCH` | modelo diferente do caso | acréscimo com impressão digital divergente | sim ("falha no preparo", refinado) |
| `PREP_FAILURE` | falha no preparo | exceção na preparação do item | sim |
| `MODALITY_OFF` | modalidade ou chave desligada no processamento | derivado do registro | sim |
| `BEFORE_CAPABILITY` | processado antes da capacidade | derivado do registro | sim |

---

## E8 — Resultado ordenado (`Ordering`)

| Campo | Conteúdo |
|---|---|
| `modality` | Uma ordenação por modalidade. Com `BOTH`, são **duas** ordenações (FR-034) |
| `mode` | `SEMANTIC` ou `HYBRID` (FR-046) |
| `method` | `EXACT` ou `APPROXIMATE`, da parte semântica (D5, emenda A3) |
| `lexicalMatches` | Só no híbrido: unidades que a parte por palavra trouxe. `0` declara que a ordenação é a semântica |
| `depth`, `ceiling`, `ceilingReached` | Profundidade exibida, teto, e se a lista revelada já chegou ao teto (limite de profundidade, não de relevância) |
| `entries` | Lista ordenada de `OrderingEntry` |
| `coverage` | E9 |
| `model` | Identidade, fonte, destino remoto (`sent_to`) quando houver |
| `notices` | Advertência de ordenação ≠ conjunto (FR-040); truncamento da pergunta; sugestão de busca por palavra quando a pergunta parece identificador; casos excluídos por incompatibilidade (FR-017) |

`OrderingEntry`:

| Campo | Conteúdo |
|---|---|
| `rank` | 1..depth |
| `proximity` | Proximidade **relativa**, rotulada assim (FR-010). No híbrido, é a relevância combinada relativa (soma RRF), rotulada assim (FR-047) |
| `ranks` | Só no híbrido: posição da unidade na lista semântica e na por palavra (ausente = não estava na lista), para o perito ver de onde veio a relevância |
| `kind` | `passage`, `transcript`, `sequence`, `image`, `video` |
| `itemId` | Item da entrada; em sequência, o chat |
| `excerpts` | Texto: até 3 trechos `{text, charStart, charEnd}`. Em item com janelas de foco (E3b), o primeiro é a janela mais próxima da pergunta, com o `ordinal` do trecho que a contém |
| `sequence` | `{messages: [{itemId, sender, date, text, inRecorte}], absorbedWindows}` |
| `frame` | Vídeo: `{frameIndex, instantApproxMs}` |

**Ordem**: proximidade decrescente, depois `itemId`, depois `ordinal`. Nada mais interfere (FR-015).

---

## E9 — Cobertura (`Coverage`)

| Campo | Conteúdo |
|---|---|
| `recorteSize` | Itens no recorte |
| `covered` | Itens do recorte com estado `COVERED` ou `COVERED_TRUNCATED` |
| `notCovered` | Mapa motivo → contagem |
| `partial` | `true` se o estado da modalidade é `PARTIAL` |

**Invariante**: `covered + Σ notCovered = recorteSize`. Nenhum item some da conta.

---

## E10 — Impressão digital (`Fingerprint`)

| Campo | Conteúdo |
|---|---|
| `probesVersion` | Versão do conjunto de sondas, embarcado no `iped-semantic` |
| `vectors` | Um vetor por sonda |
| `tolerance` | Similaridade de cosseno mínima por sonda |

**Regra**: compatível ⇔ todas as sondas com cosseno ≥ tolerância **e** identidade informada igual
à registrada.

---

## E11 — Mensagem em montagem (`StagingMessage`)

Registro temporário em `semantic/staging.mv.db`: `chatId`, `itemId`, `date`, `position`
(`parentViewPosition`), `sender` (`Communication:From`), `text` (`Message:Body`). No mesmo arquivo, a conversa renderizada de cada chat (`itemId`, texto já cortado e limitado, excedente), preparada no fim só se o chat não tiver itens de mensagem ou a sequência falhar (research D6, revisão de 2026-09-28). A mensagem de tipo listado sem `Message-Body` também entra, com `text` vazio: é por ela que o fim sabe que o chat tem mensagens, nenhuma com texto. É apagado depois do
commit das sequências e sobrevive a `--continue`.

---

## E12 — Contexto de uma entrada (`EntryContext`, FR-048)

Resposta de `iped_semantic_context`, lida do índice semântico, sem reextrair o item.

| Campo | Conteúdo |
|---|---|
| `itemId`, `kind` | A entrada de referência |
| `text` | Trecho ou transcrição: o texto dos trechos vizinhos (`ordinal - before` .. `ordinal + after`), **remendado pelos deslocamentos** para não repetir a sobreposição |
| `charStart`, `charEnd` | Intervalo coberto pelo texto devolvido |
| `messages` | Sequência: as mensagens anteriores e seguintes da conversa, `{itemId, sender, date, text}` |
| `truncated` | `true` se o teto `contextMaxChars` cortou o contexto, declarado como o resto do módulo declara |

**Invariante**: `text` é igual ao intervalo `[charStart, charEnd)` do texto do item, o mesmo teste
de E3.

---

## O que este modelo não cobre

- Histórico de perguntas feitas na interface: a interface não escreve no caso, e a reprodução se
  apoia no registro e no comentário do marcador (FR-011).
- Pesos, vetores de itens ou qualquer representação interna em qualquer saída (FR-025).
- Enriquecimento de caso existente (FR-030).
