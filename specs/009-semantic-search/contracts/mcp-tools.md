# Contract — ferramentas MCP da busca semântica

**Feature**: [009-semantic-search](../spec.md) | **Decisão**: [research.md](../research.md), D14

Quatro ferramentas novas, nenhuma mudança nas existentes. Juntas, elas são a **camada de
recuperação** de um RAG feito no harness (research D21): acham (`_search`, `_similar`), citam
(item e intervalo literal em cada entrada) e ampliam o contexto (`_context`). Nenhuma chama
modelo de linguagem. Todas seguem os contratos já vigentes do
módulo:
- referência a item sempre carrega o caso;
- ausência ≠ vazio;
- registro precede ação (o `McpDispatcher` grava `STARTED` antes);
- a classe de conteúdo é declarada no descritor.

As quatro aparecem em `tools/list` **sempre**, mesmo em instalação sem a capacidade. Nesse caso
respondem com a indisponibilidade declarada. O agente precisa descobrir que a busca existe e por
que ela não está disponível para o caso, e não inferir isso da ausência da ferramenta.

---

## Classe de conteúdo e egresso

| Ferramenta | `returnsContent` | Conteúdo sujeito a checagem por item |
|---|---|---|
| `iped_semantic_status` | `metadata` | — |
| `iped_semantic_search` | `metadata` | cada trecho e cada linha de sequência: `EgressPolicy.check(text, item)` |
| `iped_semantic_similar` | `metadata` | idem |
| `iped_semantic_context` | `text` | a ferramenta inteira é texto de item; o portão do dispatcher decide, e depois `check(text, item)` |

**Por que `metadata` e não `text`** (ao contrário de `iped_search`, que declara `text` por causa
dos snippets): com `text` bloqueado, o portão do dispatcher recusaria a ferramenta inteira, e o
cenário 2 da História 5 exige receber **a ordenação sem os trechos**. O trecho é conferido item a
item. Quando o texto está bloqueado, sai no lugar dele:

```json
"excerpt_withheld": { "policy_rule": "allowedClasses", "reason": "content class 'text' is not among the allowed classes [metadata]" }
```

O trecho **nunca** sai por outro campo. Nenhum campo desta superfície carrega vetor, pontuação
bruta de similaridade ou representação interna (FR-025).

---

## `iped_semantic_status`

O que o caso oferece de busca semântica, sem executar busca.

**Argumentos**: `case_id` (obrigatório).

**Resultado**:

```json
{
  "case_id": "c1",
  "modalities": {
    "text":   { "available": true,  "state": "COMPLETE", "model": "intfloat/multilingual-e5-small@<rev>+onnx-int8-ortx",
                "source": "local", "prepared_with": "remote", "covered": 812344, "not_covered": { "NO_TEXT": 90211, "COVERED_BY_MESSAGES": 1120 } },
    "visual": { "available": false, "reason": "MODALITY_OFF",
                "remedy": "The case was processed with semantic image and video search off. Reprocess with enableSemanticImages/enableSemanticVideos on to use it." }
  },
  "default_depth": 100,
  "max_depth": 1000,
  "ordering_notice": "Semantic search returns a ranking, not a set. …"
}
```

`source` é a fonte **desta instalação** para as perguntas. `prepared_with` é a fonte registrada
no caso para a preparação. As duas podem diferir: o que precisa coincidir é o modelo, confirmado
pela impressão digital (emenda A4). Com fonte remota para as perguntas, vem também
`"sent_to": "<destino>"`, que é a URL sem credencial.

---

## `iped_semantic_search`

**Argumentos**

| Nome | Obrigatório | Notas |
|---|---|---|
| `case_id` | sim | |
| `question` | sim | Linguagem natural. Acima de `maxQueryChars` é truncada, e o truncamento vem em `notices` |
| `modality` | não | `text` (padrão), `visual` ou `both` |
| `mode` | não | `semantic` ou `hybrid`; padrão é o `defaultTextMode` do servidor. Só vale para texto (FR-046) |
| `query` | não | Recorte por expressão do IPED, com a mesma semântica de `iped_search` |
| `bookmark` | não | Recorte por marcador, com a mesma semântica de `iped_search`. Combina com `query` |
| `page_size` | não | Entradas por página; limitado pelo teto de página do servidor |
| `cursor` | não | `next_cursor` da página anterior. Pagina **dentro da lista do teto** |

Argumento desconhecido é recusa.

**Resultado** (uma ordenação por modalidade pedida — FR-034):

```json
{
  "case_id": "c1",
  "orderings": {
    "text": {
      "mode": "hybrid",
      "method": "approximate",
      "lexical_matches": 212,
      "ceiling": 1000,
      "returned": 20,
      "entries": [
        { "rank": 1, "proximity": 0.032, "proximity_kind": "combined_relative",
          "ranks": { "semantic": 1, "lexical": 3 },
          "kind": "sequence", "item_id": 4411,
          "sequence": { "messages": [
              { "item_id": 4412, "sender": "…", "date": "2024-03-02T21:14:00Z", "text": "quanto?",  "in_recorte": true },
              { "item_id": 4413, "sender": "…", "date": "2024-03-02T21:15:00Z", "text": "5 mil",    "in_recorte": true },
              { "item_id": 4414, "sender": "…", "date": "2024-03-02T21:15:30Z", "text": "fechado, amanhã na portaria", "in_recorte": true } ],
            "absorbed_windows": 1 } },
        { "rank": 2, "proximity": 0.031, "proximity_kind": "combined_relative",
          "ranks": { "semantic": 2 },
          "kind": "passage", "item_id": 9120, "ordinal": 18,
          "excerpts": [ { "text": "…", "char_start": 18230, "char_end": 19210 } ] }
      ],
      "coverage": { "recorte_size": 5321, "covered": 4870, "not_covered": { "NO_TEXT": 402, "CATEGORY_NOT_SELECTED": 49 }, "partial": false },
      "model": { "id": "…", "source": "local" },
      "next_cursor": "…"
    }
  },
  "notices": [],
  "ordering_notice": "Semantic search returns a ranking, not a set. Items further down or absent are not evidence that the subject does not occur in the case; low proximity is not absence. Use iped_search for identifiers and for claims of absence."
}
```

Entrada visual:

```json
{ "rank": 1, "proximity": 0.31, "proximity_kind": "relative", "kind": "video", "item_id": 7702,
  "frame": { "frame_index": 5, "instant_approx": "≈ 01:12", "instant_is_approximate": true } }
```

A entrada visual **não traz imagem**. Para examiná-la, o agente chama `iped_item_thumbnail`, que
já entrega bloco de imagem (008) e passa pela política de egresso da classe `thumbnail` (FR-039).

**Regras**
- `proximity` vai sempre acompanhado de `proximity_kind`: `relative` no modo semântico,
  `combined_relative` no híbrido (FR-010, FR-047). No híbrido, `ranks` mostra de qual lista a
  entrada veio. Uma entrada sem `lexical` subiu só por significado.
- `mode` é declarado em toda ordenação de texto. Híbrido com `lexical_matches: 0` é, de fato, a
  ordenação semântica, e é declarado assim.
- Toda entrada de texto é **citável**: `item_id`, `ordinal` e `char_start`/`char_end` de cada
  trecho, ou o `item_id` de cada mensagem. É com isso que `iped_semantic_context` amplia o contexto
  e que uma resposta gerada fora liga cada afirmação a um intervalo literal.
- Não existe `total_matches`, nem `total`, nem resultado vazio com sentido de "nada encontrado"
  (FR-007). Recorte vazio → `coverage.recorte_size: 0` e `notices: ["RECORTE_EMPTY"]`.
- `ordering_notice` vem em **toda** resposta, não só quando a proximidade é baixa (FR-040).
- `method` é `exact` ou `approximate` (emenda A3).
- Com modelo remoto, `model.sent_to` informa para onde a pergunta foi enviada.
- Página parcial não existe: a lista do teto já está calculada, então paginar não reexecuta a
  busca e não depende de tempo.

**Recusas**: `SEMANTIC_UNAVAILABLE` (com o motivo de [data-model.md](../data-model.md), E2), a
modalidade e o remédio), `MODALITY_NOT_PREPARED`, `DEPTH_ABOVE_CEILING` (com o teto e a chave
`maxDepth`), `QUERY_SYNTAX`/`UNKNOWN_FIELD` no recorte (os mesmos de `iped_search`, com a correção
já verificada) e `MODEL_INCOMPATIBLE` (FR-016, com os dois modelos nomeados).

---

## `iped_semantic_similar`

"Mais como este" (FR-013). Só modalidade texto.

**Argumentos**: `case_id`, `item_id` (obrigatórios). Opcionais: `char_start` e `char_end` (trecho
de referência dentro do item), `query`, `bookmark` (recorte), `page_size`, `cursor`.

**Resultado**: o mesmo formato de `iped_semantic_search`, sem `question`. O item de referência
não aparece na ordenação.

**Recusas**: as de `iped_semantic_search`, mais `REFERENCE_NOT_COVERED`, com o motivo do item de
referência (E7) — por exemplo, "o item não tem texto".

---

## `iped_semantic_context`

Texto literal em volta de uma entrada, sem trazer o item inteiro (FR-048). Serve para o RAG
ampliar o contexto antes de responder. Lê do índice semântico, sem reextrair o item.

**Argumentos**

| Nome | Obrigatório | Notas |
|---|---|---|
| `case_id`, `item_id` | sim | Item de trecho ou transcrição, ou o chat de uma sequência |
| `ordinal` | sim | O `ordinal` da entrada |
| `before`, `after` | não | Trechos (ou mensagens) antes e depois; padrão 1 e 1 |
| `max_chars` | não | Limitado por `contextMaxChars` |

**Resultado** (texto):

```json
{ "item_id": 9120, "kind": "passage", "char_start": 17230, "char_end": 20210,
  "text": "…", "truncated": false }
```

**Resultado** (sequência):

```json
{ "item_id": 4411, "kind": "sequence",
  "messages": [ { "item_id": 4409, "sender": "…", "date": "…", "text": "…" }, … ],
  "truncated": false }
```

Os trechos vizinhos se sobrepõem por construção (`passageOverlapChars`). O `text` devolvido é
**remendado pelos deslocamentos**: é exatamente o intervalo `[char_start, char_end)` do texto do
item, sem repetição.

**Recusas**: `BLOCKED_BY_POLICY` (classe `text` ou item restrito), `SEMANTIC_UNAVAILABLE`,
`ENTRY_NOT_FOUND` (item e ordinal sem entrada no índice semântico).

---

## Auditoria

Sem campo novo em `AuditRecord`. Os parâmetros gravados no `STARTED` incluem `question` porque é
argumento da chamada. A trilha já é a fonte de verdade sobre o que o agente perguntou, e
continua sendo.
