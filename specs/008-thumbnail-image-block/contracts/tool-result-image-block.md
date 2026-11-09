# Contrato — resultado de `tools/call` com bloco de imagem

**Feature**: `008-thumbnail-image-block` | **Protocolo**: MCP `2025-06-18`

Este é o contrato externo que a feature altera: a forma do resultado de `tools/call`. Vale
igualmente sobre `STDIO` e `SOCKET` (FR-011) — o bloco nasce acima do transporte.

---

## 1. Resultado hoje (todas as ferramentas)

```json
{
  "content": [
    { "type": "text", "text": "{\n  \"case_id\": \"c1\",\n  …\n}" }
  ],
  "structuredContent": { "case_id": "c1", "…": "…" },
  "isError": false
}
```

---

## 2. Resultado depois — miniatura presente e legível

Emitido quando, e somente quando, **todas** as condições valem:

1. a ferramenta declara `returnsContent("thumbnail")`;
2. a política de egresso permitiu a classe `thumbnail`;
3. o payload traz `available: true`;
4. `data` está presente e não vazio;
5. `media_type` foi detectado dos bytes e casa com `image/*`.

```json
{
  "content": [
    {
      "type": "text",
      "text": "{\n  \"case_id\": \"c1\",\n  \"item_id\": 4711,\n  \"available\": true,\n  \"encoding\": \"base64\",\n  \"media_type\": \"image/png\",\n  \"bytes\": 18422,\n  \"data\": \"<delivered as an image block in this same response; structuredContent carries the full base64>\"\n}"
    },
    {
      "type": "image",
      "data": "iVBORw0KGgoAAAANSUhEUg…",
      "mimeType": "image/png"
    }
  ],
  "structuredContent": {
    "case_id": "c1",
    "item_id": 4711,
    "available": true,
    "encoding": "base64",
    "media_type": "image/png",
    "bytes": 18422,
    "data": "iVBORw0KGgoAAAANSUhEUg…"
  },
  "isError": false
}
```

**Garantias**:

| # | Garantia | Requisito |
|---|---|---|
| C1 | `content[0].type == "text"` — sempre, em toda resposta do servidor | FR-003 |
| C2 | o bloco de imagem, quando existe, está em `content[1]` | D5 |
| C3 | `content[1].data == structuredContent.data`, byte a byte | R4.1 |
| C4 | `content[1].mimeType == structuredContent.media_type` | FR-002 |
| C5 | `structuredContent` carrega o base64 íntegro | FR-003, FR-004 |
| C6 | o bloco de texto declara `available`, `media_type`, `bytes` e `encoding` | FR-004 |
| C7 | `isError == false` | — |

---

## 3. Resultado depois — miniatura ausente

Idêntico ao de hoje. **Nenhum bloco de imagem** (FR-009).

```json
{
  "content": [
    {
      "type": "text",
      "text": "{\n  \"case_id\": \"c1\",\n  \"item_id\": 91,\n  \"available\": false,\n  \"reason\": \"this item has no thumbnail; …\",\n  \"remedy\": \"Check content_type through iped_item_metadata; …\"\n}"
    }
  ],
  "structuredContent": {
    "case_id": "c1", "item_id": 91,
    "available": false, "reason": "…", "remedy": "…"
  },
  "isError": false
}
```

Vale para os três motivos de FR-009, que continuam distinguíveis pelo texto de `reason`:

| Motivo | `reason` cita |
|---|---|
| item não visual | que nada há para renderizar; aponta `content_type` via `iped_item_metadata` |
| nada renderizado no processamento | que a miniatura não foi produzida |
| acima do teto | o **tamanho real**, o **teto em vigor** e `maxThumbnailBytes` em `conf/McpServerConfig.txt` (FR-008) |

Em nenhum deles há `data`, e em nenhum deles há bloco de imagem.

---

## 4. Resultado depois — bytes presentes, tipo indeterminado

Caso novo, introduzido por D3. Há miniatura, dentro do teto, mas a detecção não devolveu um
tipo `image/*`. Nada se perde: o base64 continua entregue; o que não acontece é a emissão da
imagem, porque declará-la com um tipo inventado é exatamente o que FR-002 proíbe.

```json
{
  "content": [
    { "type": "text", "text": "{\n  …\n  \"available\": true,\n  \"bytes\": 3110,\n  \"media_type_note\": \"The media type of these bytes could not be established, so they are not delivered as an image. The base64 above is the thumbnail exactly as stored.\",\n  \"data\": \"…\"\n}" }
  ],
  "structuredContent": { "…": "…", "available": true, "media_type_note": "…", "data": "…" },
  "isError": false
}
```

Sem `media_type`, sem bloco de imagem — e, por consequência de R5.1, **sem elisão** do base64
no bloco de texto, já que não há bloco de imagem para onde apontar.

---

## 5. Resultado depois — bloqueado pela política de egresso

Inalterado. A recusa vem do dispatcher, **antes** de qualquer bloco ser montado (FR-006), como
erro JSON-RPC com `McpError.BLOCKED_BY_POLICY`. Nenhum `content` é construído, e portanto a
forma da resposta não é caminho alternativo por onde conteúdo saia.

```json
{
  "jsonrpc": "2.0",
  "id": 7,
  "error": {
    "code": -32602,
    "message": "The egress policy in force does not allow content of class 'thumbnail', …",
    "data": { "code": "BLOCKED_BY_POLICY", "rule": "allowedClasses", "contentClass": "thumbnail", "…": "…" }
  }
}
```

O mesmo vale para **qualquer** erro, não só o de egresso: um item inexistente, uma falha de
ferramenta, um argumento inválido. O erro vive no envelope JSON-RPC, em `error`, e nessa resposta
não há `result` — logo não há `content`, logo não há bloco de imagem. **Nenhuma resposta deste
servidor carrega imagem e erro ao mesmo tempo** (R1.5 de [data-model.md](../data-model.md)), e a
legibilidade da falha nunca depende de o cliente entender blocos de imagem.

---

## 6. Todas as demais ferramentas

**Sem alteração de nenhuma espécie** (FR-005). Uma ferramenta que declara `metadata`, `text` ou
`binary`, ou que não declara classe, produz exatamente a resposta da seção 1. Isto é verificável
de fora: só `iped_item_thumbnail` declara `returnsContent("thumbnail")`.

---

## 7. Superfície de `tools/list`

**Sem alteração de schema.** `inputSchema` de `iped_item_thumbnail` continua exigindo `case_id`
e `item_id` e nada mais. `ToolSchemaTest` e `TransportParityTest` seguem passando sem edição.

A `description` da ferramenta muda de texto — hoje diz "base64-encoded", que passa a ser uma
meia verdade que orienta mal. Passa a dizer que a miniatura chega como imagem quando o cliente
a apresenta, e que a ausência continua declarada com o motivo.

---

## 8. Compatibilidade

| Consumidor | Efeito |
|---|---|
| Cliente que renderiza imagem | passa a ver a figura |
| Cliente que ignora blocos desconhecidos | resposta válida; continua sabendo que a miniatura existe, com tipo e tamanho (C6) |
| Consumidor que lê `structuredContent` | **nada muda**, exceto `media_type` passar a trazer o tipo correto |
| Consumidor que lê `content[0].text` | continua lendo texto na posição 0 (C1); para miniatura, encontra a nota no lugar do base64 |
| Consumidor que decodifica base64 a partir de `content[0].text` | **único afetado**: precisa passar a ler `structuredContent.data` ou o bloco de imagem. Ver D4 |
