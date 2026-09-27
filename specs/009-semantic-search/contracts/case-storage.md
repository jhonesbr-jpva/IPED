# Contract — armazenamento da busca semântica no caso

**Feature**: [009-semantic-search](../spec.md) | **Decisão**: [research.md](../research.md), D4

Tudo fica sob `<caso>/iped/semantic/`. **Nada** é gravado no índice principal (`<caso>/iped/index/`),
nem fora da pasta do caso.

```text
<caso>/iped/
├── index/                     # índice principal — intocado
└── semantic/
    ├── build-record.json      # registro de construção (E2), UTF-8
    ├── text/                  # índice Lucene da modalidade texto
    ├── visual/                # índice Lucene da modalidade visual
    └── staging.mv.db          # montagem de sequências (E11) — existe só durante o processamento
```

Ausência de `semantic/` ⇔ caso processado sem a capacidade (FR-027). Ausência de `text/` ou
`visual/` ⇔ modalidade não preparada.

---

## Índices Lucene

Codec próprio, só nestes índices, para fixar os parâmetros do HNSW (`maxConn`, `beamWidth`)
registrados em `params`. Similaridade `BM25Similarity` (k1 = 1,2, b = 0,75) no escritor e no
buscador do índice de **texto**. O codec e a `IndexerSimilarity` do índice principal não são
tocados.

| Campo | Tipo Lucene | Em | Conteúdo |
|---|---|---|---|
| `kind` | `StringField` + `SortedDocValues` | todos | `passage`, `transcript`, `sequence`, `image`, `video_frame`, `status` |
| `itemId` | `IntPoint` + `NumericDocValues` + stored | todos | Id no índice principal. Em `sequence`, o id do chat |
| `itemIdKey` | `StringField` (não armazenado) | todos | O mesmo id como termo, para `updateDocuments`. Em sequência, a chave é o id do chat: substituir o chat troca, numa operação só, seus trechos de documento pelas sequências e pelo status `COVERED_BY_MESSAGES` |
| `ordinal` | `NumericDocValues` + stored | trecho, sequência | Desempate (FR-015) |
| `charStart`, `charEnd` | stored | trecho | Deslocamentos no texto do item |
| `text` | `TextField` (`AppAnalyzer`) | trecho, sequência | Trecho: armazenado **e indexado**, é o texto literal exibido. Sequência: o texto das linhas, só indexado (o exibido vem de `lines`). Um campo só, para a parte por palavra do modo híbrido (research D20): o BM25 calcula IDF e tamanho médio **por campo**, e com campos separados trecho e sequência seriam pontuados em escalas diferentes. Na implementação, o campo `seqText` do desenho original foi fundido neste (2026-09-27) |
| `anchorIds` | `SortedNumericDocValues` (múltiplo) | todos | Ids pelos quais o documento pertence a um recorte: o próprio item, ou as mensagens de uma sequência (entra no recorte se ao menos uma estiver nele, D7). Acrescentado na implementação para o filtro de recorte |
| `messageIds` | stored (múltiplo) + `IntPoint` | sequência | Âncoras, na ordem da conversa |
| `lines` | stored (múltiplo, JSON por linha) | sequência | `{sender, date, text}` literais |
| `frameIndex`, `frameCount`, `instantApproxMs` | stored | quadro | D8 |
| `reason` | `SortedDocValues` + stored | status | Código de E7 |
| `detail` | stored | status | Opcional |
| `v` | `KnnVectorField`, `DOT_PRODUCT` | todos menos status | Vetor normalizado |

**Substituição**: toda gravação de um item usa `updateDocuments(new Term("itemIdKey", id), docs)`.
Isso substitui trechos **e** status numa operação só, e é o que torna `--continue` idempotente
(D15).

**Commit**: a cada `commitIntervalMinutes` e no `finish()`. O `build-record.json` é regravado depois
de cada commit, por escrita em arquivo temporário e renomeação atômica.

---

## `build-record.json`

```json
{
  "schema": 1,
  "ipedVersion": "4.3.1",
  "modalities": {
    "text": {
      "state": "COMPLETE",
      "source": "local",
      "model": {
        "id": "intfloat/multilingual-e5-small@<rev>+onnx-int8-ortx",
        "reportedBy": "model",
        "sha256": "…",
        "dimensions": 384,
        "similarity": "DOT_PRODUCT",
        "queryPrefix": "query: ",
        "passagePrefix": "passage: "
      },
      "remote": null,
      "fingerprint": { "probesVersion": 1, "tolerance": 0.9995, "vectors": [[…], …] },
      "params": {
        "passageChars": 1000, "passageOverlapChars": 200, "maxTextCharsPerItem": 200000,
        "chatWindowMessages": 8, "chatWindowStride": 4, "chatWindowMaxChars": 1500, "chatMaxGapMinutes": 720,
        "categories": ["…"], "messageMediaTypes": ["message/x-whatsapp-message", "…"],
        "hnsw": { "maxConn": 16, "beamWidth": 100 }
      },
      "timing": { "startedAt": "…", "finishedAt": "…", "durationMs": 0 },
      "counts": { "COVERED": 0, "NO_TEXT": 0 },
      "conflicts": []
    },
    "visual": {
      "state": "COMPLETE",
      "keys": { "images": true, "videos": false },
      "source": "remote",
      "remote": { "protocol": "iped-embed/1", "destination": "https://gpu-lab.example/iped", "phase": "preparation" },
      "timing": { "images": { "durationMs": 0 }, "videos": { "durationMs": 0 } },
      "conflicts": ["VIDEOS_ON_WITHOUT_VIDEO_THUMBS"]
    }
  }
}
```

**Nunca aparecem aqui**: credencial, cabeçalho de autenticação, conteúdo de item.

---

## Portabilidade

`semantic/` viaja com o caso como o resto de `iped/`. Ao abrir numa estação, a disponibilidade é
decidida pela tabela "Disponibilidade na hora da pergunta" de [data-model.md](../data-model.md).
Modelo ausente ou impressão digital divergente tornam a modalidade indisponível, e a busca por
palavra não é afetada.

A exigência que o caso carrega é o **modelo**, não a fonte (emenda A4). Um caso preparado por
modelo remoto é pesquisável numa estação isolada que tenha o artefato local idêntico. O
`remote.destination` registra só por onde o conteúdo saiu na preparação.
