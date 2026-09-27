# Contract — protocolo de modelo remoto

**Feature**: [009-semantic-search](../spec.md) | **Decisão**: [research.md](../research.md), D9

O IPED é **cliente** nos dois protocolos. O cliente HTTP é o do JDK 11 (`java.net.http`). Toda
requisição vai para o destino declarado em configuração, e só para ele (SC-016).

Regras comuns:
- HTTPS obrigatório, salvo em loopback ou com `remoteAllowPlainHttp=true`.
- Credencial resolvida por `CredentialResolver` (variável de ambiente ou arquivo). Enviada em
  `Authorization: Bearer <credencial>` e nunca registrada em log nem no caso.
- Corpo em UTF-8 explícito.
- Resposta sem identidade de modelo é **recusada** (FR-043), mesmo que traga vetores válidos.
- A identidade recebida é comparada com a registrada no caso, e a impressão digital (D10) é
  conferida na abertura de cada sessão de busca.

---

## `openai-embeddings` (só texto)

**Requisição**

```http
POST {endpoint}/v1/embeddings
Content-Type: application/json; charset=utf-8

{ "model": "{textRemoteModel}", "input": ["passage: …", "passage: …"], "encoding_format": "float" }
```

Os prefixos de trecho e de pergunta vão no texto quando o modelo os exige (vêm de `model.json` ou da
configuração). O serviço não sabe nada de prefixo.

**Resposta aceita**

```json
{ "model": "text-embedding-…", "data": [ { "index": 0, "embedding": [ … ] } ] }
```

- Identidade = `model`. O nome sozinho não detecta pesos trocados sob o mesmo nome; a impressão
  digital detecta.
- `data[].index` precisa cobrir exatamente as entradas enviadas; qualquer falta é falha da
  requisição inteira.
- Vetores são normalizados no cliente antes de ir ao índice.

---

## `iped-embed/1` (texto e imagem)

**Requisição**

```http
POST {endpoint}/iped-embed/1/embed
Content-Type: application/json; charset=utf-8

{
  "model": "{visualRemoteModel}",
  "inputs": [
    { "type": "text",  "role": "query",   "text": "arma de fogo" },
    { "type": "image", "media_type": "image/jpeg", "data": "<base64>" }
  ]
}
```

`role` é `query` ou `passage` para texto, e o servidor aplica o prefixo do modelo. Para imagem,
o IPED envia a **miniatura** ou o **quadro recortado do mosaico**, nunca o arquivo original.

**Resposta**

```json
{
  "model": { "id": "sentence-transformers/clip-ViT-B-32-multilingual-v1@<rev>+onnx-fp32-ortx", "sha256": "…" },
  "embeddings": [ [ … ], [ … ] ]
}
```

- `model.id` e, quando presente, `model.sha256` compõem a identidade.
- `embeddings` na mesma ordem e na mesma quantidade de `inputs`.

**Descoberta** (opcional, usado na validação de início):

```http
GET {endpoint}/iped-embed/1/model  →  { "id": "…", "sha256": "…", "dimensions": 512, "modalities": ["text", "image"] }
```

---

## Falhas

| Situação | No processamento | Na pergunta |
|---|---|---|
| Conexão recusada, tempo esgotado, 5xx | Novas tentativas até `remoteMaxRetries`; depois `REMOTE_UNAVAILABLE` nos itens do lote, e o processamento segue | Modalidade indisponível, com o destino nomeado |
| 429 (limite de requisições) | Respeita `Retry-After` quando presente | idem |
| 401/403 | Falha de configuração: interrompe a modalidade e registra | Indisponível: "credencial recusada pelo destino" |
| Resposta sem identidade | Recusada; lote falha | Indisponível |
| Identidade diferente da registrada | Acréscimo: `DISABLED_MISMATCH` (D16) | `MODEL_INCOMPATIBLE` |

---

## Notas da implementação (2026-09-27)

- **Imagem vai em PNG** (`"media_type": "image/png"`), não JPEG: sem perda, o serviço vê os
  pixels que o IPED tem. O `media_type` já era campo do pedido; o exemplo acima mostra JPEG.
- **Redirecionamento não é seguido.** Uma resposta 3xx é recusada como erro de configuração: a
  requisição vai só ao destino declarado (SC-016), e seguir um `Location` a levaria a outro.
- **Outros 4xx** também são erro de configuração, sem nova tentativa. Nenhuma mensagem de erro
  carrega o corpo da resposta, que pode ecoar conteúdo de evidência.
- **URL com credencial embutida** (`https://user:senha@…`) é recusada: a credencial fica na
  variável ou no arquivo, nunca na configuração.
- **Abertura conversa com o serviço uma vez**: `GET …/model` no `iped-embed/1` (sem ele, um texto),
  e um texto no `openai-embeddings`. Assim destino inalcançável ou credencial recusada aparecem
  antes de qualquer item ser lido (FR-042), e a identidade é a que o serviço informa. Uma resposta
  posterior com outra identidade é recusada como erro de configuração.
- **401/403 no meio do processamento**: o lote falha, a modalidade para de enviar, os itens
  seguintes ficam `REMOTE_UNAVAILABLE` com o motivo, e o registro fecha `PARTIAL` com
  `disabledReason`. O processamento segue.
- **Pilha com o visualizador HTML**: a política de segurança do IPED nega rede a qualquer pilha que
  passe pelo jar dos visualizadores, e o "Mais como este" pode partir do visualizador de texto. O
  envio roda em `AccessController.doPrivileged`, com o privilégio do `iped-semantic`.

## Servidor de referência

`iped-semantic/tools/semantic-server/` implementa `iped-embed/1` servindo os **mesmos artefatos
ONNX** do pacote do IPED, com a mesma identidade. Não faz parte do release. Serve para:
1. o laboratório hospedar o modelo em máquina com GPU sem escrever código;
2. o teste do caminho remoto (SC-016) sem depender de serviço de terceiros.
