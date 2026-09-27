# Contract — superfície de configuração

**Feature**: [009-semantic-search](../spec.md) | **Princípio IV** da constituição

Duas fontes de configuração, no padrão das demais tasks: as chaves de habilitação ficam em
`IPEDConfig.txt` (e nos perfis), e o resto fica em `conf/SemanticSearchConfig.txt`, lido por
`SemanticSearchConfig implements Configurable<UTF8Properties>`. O arquivo distribuído é a
autoridade. Os valores no código são fallback de último recurso, iguais aos do arquivo, como no
`McpServerConfig`.

---

## `IPEDConfig.txt` — habilitação (FR-018, FR-032)

| Chave | Padrão | Efeito |
|---|---|---|
| `enableSemanticText` | `false` | Liga a `SemanticTextTask` |
| `enableSemanticImages` | `false` | Liga a `SemanticVisualTask` para imagens |
| `enableSemanticVideos` | `false` | Liga a `SemanticVisualTask` para vídeos |

Nenhum perfil distribuído liga qualquer uma delas nesta entrega (research D19).

---

## `conf/SemanticSearchConfig.txt`

### Fonte de modelo (FR-024, FR-041 a FR-045)

| Chave | Padrão | Notas |
|---|---|---|
| `textModelSource` | `local` | `local` ou `remote` |
| `textLocalModel` | `models/semantic/text/multilingual-e5-small-int8` | Relativo à instalação |
| `textRemoteProtocol` | *(vazio)* | `openai-embeddings` ou `iped-embed/1` |
| `textRemoteEndpoint` | *(vazio)* | URL base. **Sem padrão**: não existe destino de fábrica |
| `textRemoteModel` | *(vazio)* | Nome do modelo pedido ao serviço |
| `textRemoteCredentialEnv` | `IPED_SEMANTIC_TEXT_CREDENTIAL` | Nome da variável de ambiente |
| `textRemoteCredentialFile` | *(vazio)* | Caminho de arquivo com a credencial. Nunca a credencial em si |
| `visualModelSource` / `visualLocalModel` / `visualRemote*` | análogos | O modelo local visual vem em pacote separado |
| `remoteAllowPlainHttp` | `false` | HTTP sem TLS só com esta chave explícita, ou em loopback |
| `remoteTimeoutSeconds` | `60` | Por requisição |
| `remoteMaxRetries` | `5` | Com espera crescente; esgotadas, o item fica `REMOTE_UNAVAILABLE` |

**Validação ao iniciar** (FR-042, SC-017): se uma chave de habilitação está ligada e a modalidade
não tem fonte utilizável, o processamento não começa. Fonte `remote` sem `…RemoteEndpoint` e fonte
`local` sem o modelo no caminho são erros de configuração, e não permissão para escolher outra
fonte.

### O que entra (FR-019)

| Chave | Padrão | Notas |
|---|---|---|
| `textCategories` | *(vazio = todas com texto)* | Lista separada por `;` |
| `visualCategories` | *(vazio = todas as imagens e vídeos)* | idem |
| `messageMediaTypes` | tipos de mensagem de WhatsApp, Telegram, Skype, Discord e Threema | Tipos preparados como sequência de conversa (D7) |
| `maxTextCharsPerItem` | `200000` | Excedente declarado (`COVERED_TRUNCATED`) |
| `passageChars` / `passageOverlapChars` | `1000` / `200` | |
| `chatWindowMessages` / `chatWindowStride` | `8` / `4` | |
| `chatWindowMaxChars` / `chatMaxGapMinutes` | `1500` / `720` | |
| `minImageSize` | `48` | Pixels no maior lado da imagem original |

### Busca (FR-007, D5, D10, D11)

| Chave | Padrão | Notas |
|---|---|---|
| `defaultDepth` | `100` | |
| `maxDepth` | `1000` | Teto |
| `oversampling` | `4` | Sobreamostragem de trechos por entrada, antes da consolidação |
| `exactSearchMaxItems` | `50000` | Recorte até este tamanho → varredura exata |
| `maxQueryChars` | `2000` | Excedente truncado e declarado |
| `fingerprintTolerance` | `0.9995` | A calibrar na bancada (D10) |
| `defaultTextMode` | `hybrid` | `hybrid` ou `semantic` (FR-046). O de fábrica é confirmado ou trocado pela medição de SC-018 (D20) |
| `rrfK` | `60` | Constante da fusão por posição |
| `lexicalCandidates` | `0` (= teto × sobreamostragem) | Tamanho da lista por palavra antes da fusão |
| `contextMaxChars` | `8000` | Teto de `iped_semantic_context` (FR-048) |

### Custo (D15)

| Chave | Padrão | Notas |
|---|---|---|
| `inferenceThreads` | `0` (= metade dos núcleos) | |
| `batchSize` | `32` | |
| `queueCapacity` | `1024` | Fila cheia → worker espera |
| `commitIntervalMinutes` | `10` | |

---

## O que **não** é configurável, de propósito

- **Piso de proximidade**: não existe, por decisão da clarificação (FR-007).
- **Misturar modalidades numa ordenação**: proibido por FR-034.
- **Recorrer a outro modelo** quando a fonte registrada falha: proibido por FR-044.
- **Credencial em arquivo distribuído**: a configuração diz onde, nunca qual (FR-045).
