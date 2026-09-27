---
description: "Task list for 009-semantic-search"
---

# Tasks: Busca semântica sobre os artefatos interpretados pelo IPED

**Input**: Design documents from `/specs/009-semantic-search/`

**Prerequisites**: [plan.md](plan.md), [spec.md](spec.md), [research.md](research.md),
[data-model.md](data-model.md), [contracts/](contracts/), [quickstart.md](quickstart.md)

**Tests**: **incluídos**, por três motivos:
- o [plan.md](plan.md) nomeia as suítes (unitários, integração, avaliação);
- a constituição exige `mvn test` passando onde há cobertura relevante;
- boa parte dos critérios só é verificável por teste: trecho literal (SC-006), mesma ordenação
  (SC-005), cobertura que fecha a conta (E9), paridade entre interface e agente (SC-011) e as
  métricas de qualidade (SC-001 a SC-003, SC-012, SC-014, SC-018).

Sem teste, essas propriedades quebram em silêncio.

**Organization**: agrupadas por história de usuário, em ordem de prioridade.
- US1 e US2 (P1) formam o MVP.
- US3, US4, US5 e US7 (P2) vêm em seguida. US7 depende de US5 só na ferramenta de contexto.
- US6 (P3) vem por último.
- O modelo remoto não é história da spec, e sim requisito transversal (FR-024, FR-041 a FR-045).
  Fica numa fase própria, sem rótulo de história, antes do polimento.

## Format: `[ID] [P?] [Story] Description`

- **[P]**: pode rodar em paralelo (arquivo diferente, sem dependência pendente)
- **[Story]**: US1 … US7
- Caminhos relativos à raiz do repositório

## Path Conventions

- Módulo novo: `iped-semantic/src/main/java/iped/semantic/…`, testes em
  `iped-semantic/src/test/java/iped/semantic/{unit,integration,eval}/…`.
- Superfícies: `iped-app/src/main/java/iped/app/ui/semantic/…` e
  `iped-mcp/src/main/java/iped/mcp/tools/SemanticTools.java`.
- Configuração: `iped-app/resources/config/…`.
- Toque único em `iped-viewers`: `iped-viewers/iped-viewers-impl/src/main/java/iped/viewers/ATextViewer.java`.

## Ambiente — ler antes do primeiro comando

```powershell
$env:JAVA_HOME = "H:\java\LibericaJDK-11-Full"
```

Os dois defaults desta máquina estão errados (JDK 18 e Corretto 25), o estado do shell não
persiste entre chamadas, e Java 11 aqui é restrição de **runtime**. Prefixe todo comando Maven.
As suítes com parâmetro (`-Diped.semantic.test.referenceCase`, `-Diped.semantic.test.evalCase`)
**pulam** sem ele, e o resumo precisa dizer quantas pularam.

## Invariantes que nenhuma tarefa pode afrouxar

- O índice principal (`<caso>/iped/index/`) não recebe campo, documento nem mudança de análise.
  `IndexerSimilarity`, `AppAnalyzer` e `BasicProps` não são alterados (Princípio II, FR-026,
  FR-047).
- Nenhuma alteração em `Manager`, `Worker`, `ProcessingQueues`, `IndexWriter`, `SleuthkitClient`,
  Aho-Corasick ou `Configuration.java` (Princípio III). A habilitação entra por
  `AbstractTaskPropertiesConfig`/`EnableTaskProperty` devolvidos em `getConfigurables()`, como faz
  a `AudioTranscriptConfig`.
- Nenhuma credencial em arquivo distribuído, log ou `build-record.json` (FR-045).
- Nenhum texto gerado no lugar do trecho, e nenhum vetor ou pontuação bruta em saída alguma
  (FR-006, FR-025).
- SLF4J, charset explícito UTF-8, comentários em inglês, texto de interface localizado PT-BR + EN
  (Princípio V).

---

## Phase 1: Setup

**Purpose**: módulo, portão de dependências, ferramenta de exportação e conjunto de avaliação.

- [X] T001 Criar o módulo `iped-semantic`: `iped-semantic/pom.xml` com parent `iped:iped-parent:4.3.1` (como `iped-mcp/pom.xml`), dependências `iped-engine`, `iped-api`, `iped-utils`, `lucene-core` 9.2.0, `jackson-databind` e `junit` (test). Registrar `<module>iped-semantic</module>` em `pom.xml`, logo depois de `iped-engine`. Criar `iped-semantic/src/main/java/iped/semantic/package-info.java` dizendo, em inglês, que o módulo não modifica classes do engine.
- [X] T002 **Portão D2 (bloqueante)**: acrescentar `com.microsoft.onnxruntime:onnxruntime` e `com.microsoft.onnxruntime:onnxruntime-extensions` (versões candidatas) a `iped-semantic/pom.xml`. Escrever `iped-semantic/src/test/java/iped/semantic/spike/OrtBaselineSpikeTest.java`, que verifica: (a) versão de class file ≤ 55 em todas as classes dos dois jars; (b) nativos de Windows x64 e Linux x64 presentes nos jars; (c) criação de sessão e registro da biblioteca de extensões sem acesso à rede; (d) `run()` concorrente estável em 4 threads. Registrar o resultado numa seção "Resultado do spike" em `specs/009-semantic-search/research.md` (D2) e fixar as versões. Se reprovar, parar aqui e acionar o plano B de D2 antes de qualquer tarefa que dependa de `LocalOnnxEmbedder`.
- [X] T003 [P] Ferramenta de exportação reprodutível em `iped-semantic/tools/export-models/export_models.py` (mais `requirements.txt` e `README.md`). Ela exporta `intfloat/multilingual-e5-small` na revisão fixada, quantizado em int8, com o tokenizador embutido no grafo (`onnxruntime-extensions`, `gen_processing_models`), e produz `model.onnx`, `model.json` e `LICENSE`. O `model.json` traz `id` no formato `<repositório>@<revisão>+<variante>`, `sha256`, `dimensions`, `similarity: DOT_PRODUCT`, `queryPrefix: "query: "`, `passagePrefix: "passage: "` e `probesVersion`. Empacotar como zip no leiaute `models/semantic/text/multilingual-e5-small-int8/`.
- [ ] T004 [P] Gerador do conjunto de avaliação (research D18) em `iped-semantic/tools/eval-corpus/build_corpus.py`, **sem evidência real**. Ele produz:
  - conversas escritas pela equipe, em PT com gíria, abreviação e código, num banco de chat que o `WhatsAppParser` lê (partindo dos bancos de teste de `iped-parsers/iped-parsers-impl/src/test/resources`), incluindo o diálogo "quanto?" / "5 mil" / "fechado, amanhã na portaria" e mensagens que citam o apelido "Baiano";
  - documentos PT, EN e ES, entre eles um de ~40 páginas com um único parágrafo pertinente;
  - imagens e vídeos de licença aberta, listados em `iped-semantic/tools/eval-corpus/MANIFEST.md` com origem e licença.
- [ ] T005 Gabarito versionado em `iped-semantic/src/test/resources/eval/questions.json` (depende de T004). Cada entrada tem pergunta, idioma, tipo (`conceptual`, `crosslingual`, `mixed`, `visual-image`, `visual-video`) e itens pertinentes por chave estável (caminho do item + chave da mensagem). Mínimos: ≥ 30 conceituais em PT, ≥ 10 com pertinente em outro idioma, ≥ 15 `mixed`, ≥ 30 descrições de imagem (das quais ≥ 5 em inglês, para FR-036) e ≥ 15 de vídeo. Fechar **antes** da primeira medição, registrando o hash do commit do gabarito em `specs/009-semantic-search/validation-log.md`.

---

## Phase 2: Foundational (Blocking Prerequisites)

**Purpose**: configuração, fonte local de modelo, impressão digital, armazenamento no caso e
disponibilidade. Tudo que qualquer história usa.

**⚠️ CRITICAL**: nenhuma história começa antes desta fase, e T011 depende do portão T002.

- [X] T006 [P] `iped-semantic/src/main/java/iped/semantic/config/SemanticSearchConfig.java` estendendo `AbstractTaskPropertiesConfig` (padrão de `AudioTranscriptConfig`): propriedade de habilitação `enableSemanticText`, arquivo `SemanticSearchConfig.txt` e getters tipados para **todas** as chaves de [contracts/config-surface.md](contracts/config-surface.md), com os mesmos padrões (`defaultDepth = 100`, `maxDepth = 1000`, `oversampling = 4`, `exactSearchMaxItems = 50000`, `maxQueryChars = 2000`, `fingerprintTolerance = 0.9995`, `defaultTextMode = hybrid`, `rrfK = 60`, `contextMaxChars = 8000` etc.). Validações: fonte `remote` sem `…RemoteEndpoint` é erro de configuração; `defaultDepth` precisa respeitar `1 ≤ defaultDepth ≤ maxDepth`. Criar `iped-app/resources/config/conf/SemanticSearchConfig.txt` com os mesmos valores, comentados em inglês.
- [X] T007 [P] Chaves de habilitação em `iped-app/resources/config/IPEDConfig.txt`: `enableSemanticText = false`, `enableSemanticImages = false` e `enableSemanticVideos = false`, cada uma com comentário em inglês. Nenhum perfil em `iped-app/resources/config/profiles/*/IPEDConfig.txt` as liga (research D19).
- [X] T008 [P] `iped-semantic/src/main/java/iped/semantic/store/CoverageReason.java`: enum com **exatamente** os códigos de [data-model.md](data-model.md) E7 (`COVERED`, `COVERED_TRUNCATED`, `NO_TEXT`, `ANALYSIS_NOT_RUN`, `IMAGE_TOO_SMALL`, `IMAGE_UNDECODABLE`, `NO_THUMBNAIL`, `VIDEO_NO_FRAMES`, `COVERED_BY_MESSAGES`, `COVERED_BY_SOURCE_VIDEO`, `CATEGORY_NOT_SELECTED`, `REMOTE_UNAVAILABLE`, `MODEL_MISMATCH`, `PREP_FAILURE`, `MODALITY_OFF`, `BEFORE_CAPABILITY`), cada um com chave de mensagem. Acrescentar os rótulos PT-BR da tabela E7 em `iped-app/resources/localization/iped-desktop-messages_pt_BR.properties` e os equivalentes EN em `iped-desktop-messages.properties`.
- [X] T009 [P] Contratos de modelo em `iped-semantic/src/main/java/iped/semantic/model/`:
  - `Embedder.java`: `embedTexts(Role role, List<String>)`, com `Role` sendo `QUERY` ou `PASSAGE`; `embedImages(List<BufferedImage>)`; `identity()`; `dimensions()`.
  - `ModelIdentity.java`: id, sha256 opcional, dimensões, similaridade e prefixos.
  - `VectorMath.java`: normalização L2 e produto escalar.
- [X] T010 Impressão digital (research D10) em `iped-semantic/src/main/java/iped/semantic/model/Fingerprint.java` e `model/FingerprintProbes.java`:
  - sondas: `iped-semantic/src/main/resources/iped/semantic/probes/text-probes-v1.txt`, com 16 frases em PT, EN e ES;
  - `model/FingerprintProbes.java`: carrega as sondas e gera 8 imagens sintéticas determinísticas (gradientes e formas, sem aleatoriedade);
  - `model/Fingerprint.java`: calcula e compara. Regra de E10: "compatível ⇔ todas as sondas com cosseno ≥ tolerância **e** identidade informada igual à registrada".
- [X] T011 `iped-semantic/src/main/java/iped/semantic/model/LocalOnnxEmbedder.java` (depende de T002 e T009). Carrega `model.json` e `model.onnx` de `textLocalModel`, mantém uma sessão compartilhada por modelo e por JVM, aplica os prefixos de `model.json` por `Role`, trabalha em lote, normaliza a saída e extrai os nativos em `tools/onnxruntime/` (propriedade `onnxruntime.native.path`), sem acesso à rede. Identidade = `id` + `sha256` do arquivo, recalculado na carga.
- [X] T012 `iped-semantic/src/main/java/iped/semantic/model/EmbeddingService.java`: fila limitada (`queueCapacity`), lotes de `batchSize`, `inferenceThreads` threads (`0` = metade dos núcleos). `submit()` bloqueia quando a fila enche (é o controle de memória do processamento). `drain()` para o `finish()`. Callback por lote para o escritor. Tempo acumulado exposto para FR-021.
- [X] T013 [P] `iped-semantic/src/main/java/iped/semantic/store/SemanticStore.java` (caminhos `<caso>/iped/semantic/{text,visual,build-record.json,staging.mv.db}`) e `store/BuildRecord.java`, com o esquema de [contracts/case-storage.md](contracts/case-storage.md): `schema: 1`, `state` por modalidade (`PREPARING`, `COMPLETE`, `PARTIAL`, `DISABLED_MISMATCH`), `keys`, `source`, `model`, `remote` (só `phase: "preparation"`), `fingerprint`, `params`, `timing` (na visual, separado em `images`/`videos`), `counts` e `conflicts`. Escrita UTF-8 por arquivo temporário e renomeação atômica. Nenhum campo de credencial existe na classe.
- [X] T014 `iped-semantic/src/main/java/iped/semantic/store/SemanticIndexWriter.java`, `SemanticIndexReader.java` e `SemanticCodec.java`:
  - codec próprio, só nestes índices, fixando `maxConn`/`beamWidth` do HNSW a partir de `params.hnsw`;
  - `BM25Similarity` (k1 = 1,2, b = 0,75) no escritor e no leitor do índice de **texto**;
  - campos da tabela de [contracts/case-storage.md](contracts/case-storage.md): `kind`, `itemId`, `itemIdKey`, `ordinal`, `charStart`/`charEnd`, `text` armazenado **e** indexado com o `AppAnalyzer` do engine (usado, não modificado), `seqText`, `messageIds`, `lines`, `frameIndex`/`frameCount`/`instantApproxMs`, `reason`, `detail` e `v` (`KnnVectorField`, `DOT_PRODUCT`);
  - toda gravação de item por `updateDocuments(new Term("itemIdKey", id), docs)`;
  - commit a cada `commitIntervalMinutes` e sob demanda.
- [X] T015 `iped-semantic/src/main/java/iped/semantic/search/Availability.java` e `search/Compatibility.java`: implementar a tabela "Disponibilidade na hora da pergunta" de [data-model.md](data-model.md) E2, linha a linha. Isso inclui "Fonte configurada é outra que não a da preparação → **Disponível**, se a impressão digital confirmar o mesmo modelo" (emenda A4) e "não recorre a outro modelo nem troca de fonte sozinho". Cada indisponibilidade tem motivo e remédio localizados.
- [X] T016 **Verificação D15**: confirmar em `iped-engine/src/main/java/iped/engine/core/Manager.java` (só leitura) que o `init()` das tasks roda antes do primeiro `read()` da evidência, e registrar a conclusão em `specs/009-semantic-search/research.md` (D15). Implementar `iped-semantic/src/main/java/iped/semantic/task/SourceValidator.java`, a validação de início de FR-042: modelo local presente e impressão digital calculável. A verificação da fonte remota entra em T088, e o teste de ponta a ponta em T091. Se o `Manager` não garantir essa ordem, registrar o desvio e levar a validação para a checagem de configuração que o `Bootstrap` faz antes de criar a JVM filha, **sem** alterar o `Manager`.
- [X] T017 [P] Testes unitários da fundação em `iped-semantic/src/test/java/iped/semantic/unit/`:
  - `SemanticSearchConfigTest`: padrões do código iguais aos do arquivo distribuído; validação de remoto sem endpoint.
  - `BuildRecordTest`: escrita atômica; nenhum campo de credencial; ida e volta do JSON.
  - `SemanticIndexWriterTest`: `updateDocuments` idempotente por item; o índice de texto usa BM25 e o `AppAnalyzer` casa "ação" com "acao".
  - `FingerprintTest`: tolerância; identidade divergente → incompatível.
  - `EmbeddingServiceTest`: `submit` bloqueia com a fila cheia; `drain` entrega tudo.
  - `AvailabilityTest`: cada linha da tabela E2.

**Checkpoint**: fundação pronta. Com T002 aprovado, as histórias podem começar.

---

## Phase 3: User Story 1 — O perito encontra pelo significado o que a busca por palavra não acha (Priority: P1) 🎯 MVP

**Goal**: pergunta em linguagem natural na interface, contra texto de documentos, transcrições e
sequências de conversa, com trecho literal e abertura do item no trecho.

**Independent Test**: processar o conjunto de avaliação com `enableSemanticText = true`, fazer as
perguntas do gabarito pelo painel e verificar a posição dos pertinentes (quickstart, Cenários 3–5;
SC-001 e SC-002).

### Tests for User Story 1 ⚠️

- [X] T018 [P] [US1] `iped-semantic/src/test/java/iped/semantic/unit/PassageChunkerTest.java` cobre:
  - janelas de `passageChars = 1000` com `passageOverlapChars = 200` respeitando fim de frase;
  - corte do cabeçalho de metadados (`StandardParser.METADATA_HEADER`);
  - truncamento em `maxTextCharsPerItem` → `COVERED_TRUNCATED` com os caracteres excedentes em `detail`;
  - invariante de E3: "`text` é igual ao intervalo `[charStart, charEnd)` do texto do item".
- [X] T019 [P] [US1] `iped-semantic/src/test/java/iped/semantic/unit/ChatSequenceBuilderTest.java` cobre:
  - ordem por `Communication:Date`, depois `parentViewPosition`, depois id;
  - janela `chatWindowMessages = 8`, passo `chatWindowStride = 4`, limite `chatWindowMaxChars = 1500`, quebra em intervalo maior que `chatMaxGapMinutes = 720`;
  - `messageIds` na ordem da conversa;
  - texto calculado no formato `remetente: texto`, com `lines` literais;
  - mensagem curta sozinha nunca vira unidade.
- [X] T020 [P] [US1] `iped-semantic/src/test/java/iped/semantic/unit/ConsolidatorTest.java` cobre:
  - um item por ordenação, com o melhor trecho mais até 2 de contexto;
  - absorção gulosa de janelas: "nenhuma mensagem aparece em duas entradas";
  - desempate por proximidade decrescente, depois `itemId`, depois `ordinal` (FR-015).
- [X] T021 [P] [US1] `iped-semantic/src/test/java/iped/semantic/integration/TextPreparationIT.java` (`-Diped.semantic.test.referenceCase`). Processa com o texto ligado e verifica:
  - `state: COMPLETE`;
  - `counts` somam os itens considerados;
  - todo `text` de trecho é igual ao intervalo do texto do item (SC-006);
  - toda linha de sequência é igual ao `Message:Body` da mensagem apontada (SC-006);
  - o item de chat com mensagens montadas tem status `COVERED_BY_MESSAGES` e nenhum trecho;
  - o índice principal fica sem campo novo (compara `FieldInfos` antes e depois);
  - um item preparado para falhar (injeção por propriedade de teste) sai com `PREP_FAILURE`, os demais são cobertos e o processamento conclui (FR-022).
  - Implementado com `ProcessedCases`, que processa pelo release a evidência de referência duas vezes (capacidade desligada e ligada, perfil `semantic-it`): parâmetros `-Diped.semantic.test.ipedRoot=<release>` e `-Diped.semantic.test.referenceEvidence=<pasta>` em vez de `referenceCase`, porque o teste processa. A falha é injetada pela variável de ambiente `IPED_SEMANTIC_TEST_FAIL_ITEM` (o motor roda em JVM neta). SC-006 é conferido contra o arquivo da evidência: o texto do trecho é literal e o deslocamento é consistente entre os trechos do item (o texto extraído tem um prefixo antes do conteúdo). A linha de sequência guarda o valor do parser como veio; o índice principal o guarda aparado.
- [ ] T022 [P] [US1] Harness de avaliação em `iped-semantic/src/test/java/iped/semantic/eval/TextQualityEval.java` (`-Diped.semantic.test.evalCase`):
  - descarta as perguntas `conceptual` cuja busca por palavra (com `IPEDSearcher` e os termos da pergunta) encontra algum pertinente, registrando quais;
  - calcula SC-001 (≥ 80% com pertinente no top-10) e SC-002 (≥ 70%);
  - grava os números em `specs/009-semantic-search/validation-log.md`.

### Implementation for User Story 1

- [X] T023 [P] [US1] `iped-semantic/src/main/java/iped/semantic/prep/PassageChunker.java`: fatiamento de T018, com deslocamentos sobre o texto já sem cabeçalho.
- [X] T024 [P] [US1] `iped-semantic/src/main/java/iped/semantic/store/StagingStore.java`, sobre H2 MVStore (a mesma dependência dos previews), em `semantic/staging.mv.db`. Registro E11: `chatId`, `itemId`, `date`, `position` (`parentViewPosition`), `sender` (`Communication:From`) e `text` (`Message:Body`). Iteração por chat; apagar depois do commit; sobrevive a `--continue`.
- [X] T025 [US1] `iped-semantic/src/main/java/iped/semantic/prep/ChatSequenceBuilder.java` (depende de T024): janelas de T019 a partir do `StagingStore`.
- [X] T026 [US1] Enumerar os tipos de mensagem de UFDR emitidos em `iped-engine`/`iped-parsers` (só leitura) e completar o padrão de `messageMediaTypes` em `SemanticSearchConfig.java` e `SemanticSearchConfig.txt`. Hoje o padrão é `message/x-whatsapp-message`, `message/x-telegram-message`, `message/x-skype-message`, `message/x-discord-message` e `message/x-threema-message`. Registrar a lista final em `specs/009-semantic-search/research.md` (D7).
- [X] T027 [US1] `iped-semantic/src/main/java/iped/semantic/task/SemanticTextTask.java` (depende de T011–T014, T016, T023, T025):
  - `getConfigurables()` devolve `SemanticSearchConfig`; `isEnabled()` com retorno imediato quando desligada, sem carregar modelo (FR-020).
  - `init()`: `SourceValidator`; cria ou retoma o registro em `PREPARING` com impressão digital e `params`.
  - `process()`:
    - itens fora de `textCategories` → `CATEGORY_NOT_SELECTED`;
    - mensagens (`messageMediaTypes`) → `StagingStore`;
    - demais itens: `getTextReader()` sem o cabeçalho de metadados, mais a transcrição `ExtraProperties.TRANSCRIPT_ATTR` como trechos `transcript`;
    - sem texto: `ANALYSIS_NOT_RUN` quando o tipo do item seria coberto por uma análise que está desligada neste processamento — OCR (`enableOCR`) para imagem e PDF sem texto, transcrição (`enableAudioTranscription`) para áudio e vídeo —; `NO_TEXT` nos demais casos. O motivo nomeia algo verdadeiro do item, e não uma hipótese;
    - envio a `EmbeddingService`; falha → `PREP_FAILURE`, sem interromper o caso (FR-022).
  - `finish()`: drena a fila; monta as sequências; substitui os documentos do chat pelas sequências mais o status `COVERED_BY_MESSAGES`; faz o commit; grava `COMPLETE` com `counts`/`timing`; loga contagens por motivo e tempo (FR-021).
- [X] T028 [US1] Registrar `<task class="iped.semantic.task.SemanticTextTask"></task>` logo depois de `NamedEntityTask` em `iped-app/resources/config/conf/TaskInstaller.xml` e em `iped-app/resources/config/profiles/triage/conf/TaskInstaller.xml`. Acrescentar a dependência de `iped-semantic` em `iped-app/pom.xml`, para o jar ir para `lib/`.
- [X] T029 [US1] Empacotar o modelo de texto: execução `unpack-semantic-text-model` em `iped-app/pom.xml`, desempacotando o zip de T003 em `${release.dir}/models/semantic/text/`, na mesma mecânica de `unpack-vosk-model-pt`. A publicação do artefato no repositório Maven do IPED é ação do mantenedor; enquanto não houver, documentar em `iped-semantic/tools/export-models/README.md` a instalação local (`mvn install:install-file`).
- [X] T030 [P] [US1] `iped-semantic/src/main/java/iped/semantic/search/SemanticRequest.java`, `Ordering.java` e `OrderingEntry.java`, com os campos de [data-model.md](data-model.md) E1 e E8:
  - `modality`; `mode`; `query` "até `maxQueryChars`", com o excedente truncado e declarado em `notices`;
  - `reference`; `recorte`; `depth` com "`1 ≤ depth ≤ maxDepth`"; `method`; `lexicalMatches`;
  - `entries` com `rank`, `proximity` + `proximityKind`, `ranks`, `kind`, `itemId`, `excerpts`, `sequence` e `frame`;
  - `coverage`; `model`; `notices`.
- [X] T031 [US1] `iped-semantic/src/main/java/iped/semantic/search/Consolidator.java` (regras de T020) e `search/SemanticSearcher.java`, o **único** buscador (depende de T014, T015 e T030). Modo semântico de texto:
  - `Availability`;
  - embedding da pergunta com `Role.QUERY`;
  - com até `exactSearchMaxItems` itens considerados, varredura **exata** sobre os vetores desses itens, `method: EXACT`; acima disso, `KnnVectorQuery` com `k = maxDepth × oversampling`, `method: APPROXIMATE` (research D5, emenda A3);
  - consolidação e ordenação determinística;
  - devolve a lista inteira do teto, e a profundidade é um prefixo dela (research D5).
- [ ] T032 [US1] Painel `iped-app/src/main/java/iped/app/ui/semantic/SemanticSearchPanel.java`, só texto nesta história:
  - campo de pergunta;
  - lista com `OrderingListRenderer.java` (trecho literal) e `SequenceRenderer.java` (mensagens com remetente e hora);
  - pergunta em `SwingWorker`, modelo carregado na primeira pergunta com aviso;
  - selecionar uma entrada seleciona o item na tabela e abre o visualizador.
  
  Registrar o painel acoplável em `iped-app/src/main/java/iped/app/ui/App.java` (mudança aditiva) e as mensagens em `iped-desktop-messages*.properties`.
- [ ] T033 [US1] **Verificação D13 + destaque do trecho**: confirmar se o visualizador de texto destaca frase recebida por `App.getHighlightTerms()` → `FileProcessor`. Se destacar, o painel publica as palavras iniciais literais do trecho como frase. Se não, implementar a rolagem até `charStart` no `iped-app`, sem alterar `iped-viewers` nesta tarefa. Registrar a conclusão em `specs/009-semantic-search/research.md` (D13).

**Checkpoint**: US1 funcional. Quickstart, Cenários 3, 4 e 5.

---

## Phase 4: User Story 2 — O resultado diz o que é, e se reproduz (Priority: P1)

**Goal**: toda ordenação declara profundidade, método, cobertura com motivos, modelo e modo; a
mesma pergunta dá a mesma lista; caso antigo e retomadas se comportam como E2 manda.

**Independent Test**: quickstart, Cenários 2, 6 e 12. SC-005, SC-006, SC-007 e FR-021.

### Tests for User Story 2 ⚠️

- [X] T034 [P] [US2] `iped-semantic/src/test/java/iped/semantic/integration/DeterminismIT.java`: mesma pergunta duas vezes, e de novo depois de fechar e reabrir o caso, dá lista idêntica, com recorte grande (`approximate`) e pequeno (`exact`) (SC-005).
- [X] T035 [P] [US2] `iped-semantic/src/test/java/iped/semantic/unit/CoverageTest.java` cobre:
  - invariante de E9: "`covered + Σ notCovered = recorteSize`";
  - motivos derivados `MODALITY_OFF` e `BEFORE_CAPABILITY` para item sem documento de estado (research D12);
  - `partial: true` quando o estado é `PARTIAL`.
- [X] T036 [P] [US2] `iped-semantic/src/test/java/iped/semantic/unit/DeclarationTextTest.java` (o `iped-app` não tem pasta de testes nem JUnit; por isso os textos são montados no `iped-semantic`, em `search/DeclarationText.java`, e o painel só os exibe): nenhum texto de declaração, em PT-BR e EN, contém "resultados encontrados", "nenhum resultado", "0 ocorrências" ou porcentagem de confiança (FR-007, FR-010); e método, profundidade, teto, modelo e modo aparecem sempre (SC-006).
- [X] T037 [P] [US2] `iped-semantic/src/test/java/iped/semantic/integration/ResumeAndAppendIT.java` cobre:
  - interrupção durante a preparação → `PARTIAL`; `--continue` → `COMPLETE` sem trecho duplicado;
  - `--append` em caso sem a capacidade → itens antigos `BEFORE_CAPABILITY`;
  - `--append` com impressão digital divergente → `DISABLED_MISMATCH`, itens do acréscimo `MODEL_MISMATCH`, e o processamento conclui (research D16).
- [X] T038 [P] [US2] `iped-semantic/src/test/java/iped/semantic/integration/MultiCaseIT.java` (FR-017). Três casos abertos juntos: dois preparados com o mesmo modelo e um com modelo diferente (ou sem a capacidade). A ordenação cobre só os dois compatíveis, com entradas de ambos intercaladas pela proximidade e o mesmo desempate determinístico. O terceiro aparece no aviso `CASES_EXCLUDED`, com o motivo dele (E2). A cobertura soma os recortes dos casos incluídos.

### Implementation for User Story 2

- [X] T039 [US2] `iped-semantic/src/main/java/iped/semantic/search/Coverage.java`: vetor de bytes por id, carregado uma vez por sessão a partir dos documentos `status` e cruzado com o bitset do recorte. Motivos derivados pelo `BuildRecord`. Integrar ao `SemanticSearcher` (campo `coverage` de toda ordenação).
- [X] T040 [US2] Declarações em `iped-semantic/src/main/java/iped/semantic/search/SemanticSearcher.java`, com os textos localizados montados em `iped-semantic/src/main/java/iped/semantic/search/DeclarationText.java` (testado por T036): `method` (`EXACT`/`APPROXIMATE`, emenda A3), `depth`/`ceiling`, `model` (identidade e fonte) e `notices`, incluindo `ordering_notice` (FR-040), `QUERY_TRUNCATED` e `IDENTIFIER_LIKE` (pergunta que, inteira, é dígitos com a pontuação usual de CPF, CNPJ, telefone ou número de conta, ou um e-mail → sugere busca por palavra; nome próprio não é detectado, como a spec registra).
- [ ] T041 [US2] Área de declaração e "Mais resultados" em `iped-app/src/main/java/iped/app/ui/semantic/SemanticSearchPanel.java`. A extensão revela mais da mesma lista, mantendo a ordem exibida. No teto, o botão desabilita, com dica que nomeia `maxDepth` sem sugerir ausência além dele. Mostrar o estado de indisponível, com motivo e remédio, para caso sem `semantic/` (FR-027) e demais linhas de E2. Os textos vêm de `DeclarationText`. Acrescentar "Detalhes da construção" (FR-014): para cada modalidade, modelo e versão, fonte e destino da preparação, data, parâmetros (`params`), estado e contagens por motivo, lidos do `BuildRecord`.
- [X] T042 [US2] Retomada e acréscimo em `iped-semantic/src/main/java/iped/semantic/task/SemanticTextTask.java`, conforme a máquina de estados de E2 e research D16: `PARTIAL` ao iniciar sobre registro `PREPARING` sem `finish`; retomada do `StagingStore`; `DISABLED_MISMATCH` sem abortar o processamento.
- [ ] T043 [US2] Ação "Marcar" em `iped-app/src/main/java/iped/app/ui/semantic/SemanticSearchPanel.java` (FR-011). Leva as entradas selecionadas a um marcador (numa sequência, só as mensagens dentro do recorte) e grava no comentário (`Bookmarks.setBookmarkComment`) a pergunta, o recorte, a profundidade, o modelo, o modo e a data.
- [X] T044 [US2] `iped-semantic/src/main/java/iped/semantic/search/MultiCaseSearch.java` (FR-017), chamado pelo `SemanticSearcher` quando o caso aberto é um `IPEDMultiSource`. Avalia `Availability` e a impressão digital **por caso**, e toma como referência o modelo em uso na instalação. Busca em cada caso compatível, com ids globais do multi-caso. Funde as listas pela proximidade, com o desempate de D5, e declara os casos excluídos em `notices` (`CASES_EXCLUDED`, com caso e motivo). A mensagem localizada fica em `DeclarationText`. Na interface, o painel mostra quais casos participaram.

**Checkpoint**: MVP (US1 + US2) completo. Quickstart, Cenários 2 a 6 e 12.

---

## Phase 5: User Story 3 — A busca semântica respeita o recorte que o perito já montou (Priority: P2)

**Goal**: a pergunta roda dentro do resultado corrente da tabela, e a ordenação pode ser aplicada à
tabela.

**Independent Test**: quickstart, Cenário 7, passo 1, e Cenário 5, passo 4.

### Tests for User Story 3 ⚠️

- [X] T045 [P] [US3] `iped-semantic/src/test/java/iped/semantic/unit/RecorteFilterTest.java` cobre:
  - só itens do recorte na ordenação;
  - recorte vazio → `coverage.recorteSize = 0` e aviso `RECORTE_EMPTY`, nunca "nada encontrado";
  - recorte com até `exactSearchMaxItems` itens → varredura exata e `method: EXACT`;
  - sequência parcialmente dentro: entra, com `inRecorte = false` nas mensagens de fora.

### Implementation for User Story 3

- [X] T046 [US3] `iped-semantic/src/main/java/iped/semantic/search/ItemIdFilterQuery.java`: filtro Lucene sobre o `NumericDocValues` de `itemId` a partir de um bitset de ids (padrão do `BookmarkQuery` do `iped-mcp`), usado como filtro da `KnnVectorQuery`.
- [X] T047 [US3] Recorte em `iped-semantic/src/main/java/iped/semantic/search/SemanticSearcher.java`: o bitset do recorte passa a restringir os dois caminhos da T031 — a varredura exata percorre só os itens do recorte, e o HNSW usa o `ItemIdFilterQuery` como filtro. O limiar `exactSearchMaxItems` passa a ser avaliado sobre o tamanho do recorte. Marca `inRecorte` nas linhas de sequência.
- [ ] T048 [US3] `iped-app/src/main/java/iped/app/ui/semantic/SemanticResultFilterer.java`, implementando `IResultSetFilterer`/`IResultSetFilter` no padrão de `SimilarFacesSearchFilterer` em `App.java`: o `src` recebido é o recorte, e "Aplicar à tabela" devolve a ordenação da aba ativa com a proximidade na coluna de pontuação, uma modalidade de cada vez. Registrar em `App.java` (mudança aditiva). O painel passa a mostrar "sobre o resultado corrente: N itens".

**Checkpoint**: US3 funcional e testável sozinha sobre a fundação e US1.

---

## Phase 6: User Story 4 — "Mais como este" (Priority: P2)

**Goal**: partir de um item, ou de um trecho selecionado nele, e obter os de significado mais
próximo.

**Independent Test**: quickstart, Cenário 7, passos 2 a 4. SC-003.

### Tests for User Story 4 ⚠️

- [X] T049 [P] [US4] `iped-semantic/src/test/java/iped/semantic/unit/ReferenceSearchTest.java` cobre:
  - o item de referência nunca está na ordenação;
  - referência não coberta → recusa `REFERENCE_NOT_COVERED` com o motivo do item (E7);
  - referência por intervalo usa o trecho dado, e não o item inteiro.
- [ ] T050 [P] [US4] `iped-semantic/src/test/java/iped/semantic/eval/SimilarQualityEval.java`: SC-003 (≥ 70% com outro pertinente no top-10), gravado em `specs/009-semantic-search/validation-log.md`.

### Implementation for User Story 4

- [X] T051 [US4] Modo de referência em `iped-semantic/src/main/java/iped/semantic/search/SemanticSearcher.java`: item → vetor armazenado do melhor trecho, ou média normalizada dos trechos se não houver ordinal; intervalo ou texto selecionado → embedding com `Role.PASSAGE`. Exclui o item de referência. Só modalidade texto.
- [X] T052 [US4] Entrada "Mais como este" em `iped-app/src/main/java/iped/app/ui/MenuClass.java` (mudança aditiva) e `iped-app/src/main/java/iped/app/ui/semantic/MoreLikeThisAction.java`, desabilitada com dica do motivo quando o item não está coberto.
- [X] T053 [US4] Ação "Mais como este trecho" em `iped-viewers/iped-viewers-impl/src/main/java/iped/viewers/ATextViewer.java`: item de menu que publica o texto selecionado para um ouvinte registrado, sem efeito na renderização. Ligação do ouvinte em `iped-app/src/main/java/iped/app/ui/semantic/MoreLikeThisAction.java`. Registrar a mudança em `iped-viewers/CLAUDE.md`.

**Checkpoint**: US4 funcional.

---

## Phase 7: User Story 5 — O agente usa a mesma busca, com a mesma disciplina (Priority: P2)

**Goal**: `iped_semantic_status`, `iped_semantic_search` e `iped_semantic_similar` no MCP, com a
mesma ordenação da interface, egresso por trecho e aviso de ordenação em toda resposta.

**Independent Test**: quickstart, Cenário 10, passos 1 a 5. SC-011.

### Tests for User Story 5 ⚠️

- [X] T054 [P] [US5] `iped-mcp/src/test/java/iped/mcp/unit/SemanticToolsContractTest.java` cobre:
  - as ferramentas aparecem em `tools/list` **mesmo** sem a capacidade;
  - argumentos e resultados conforme [contracts/mcp-tools.md](contracts/mcp-tools.md);
  - argumento desconhecido é recusado;
  - `ordering_notice` em **toda** resposta;
  - nenhum `total_matches`/`total`;
  - `proximity_kind` presente;
  - nenhum vetor ou pontuação bruta.
- [X] T055 [P] [US5] `iped-mcp/src/test/java/iped/mcp/integration/SemanticEgressTest.java`: com a política sem a classe `text`, cada entrada traz `excerpt_withheld` com a regra, e nenhum trecho aparece em campo algum; o `AuditRecord` segue sem campo novo.
- [X] T056 [P] [US5] `iped-mcp/src/test/java/iped/mcp/integration/SemanticParityTest.java`: para 10 perguntas do gabarito, com o mesmo recorte, a ordenação do MCP é idêntica à do `SemanticSearcher` chamado como a interface o chama (SC-011).
  - Implementado com 10 perguntas fixas no teste, em dois recortes (`*:*` e uma expressão) e nos dois modos, até o gabarito de T005 existir; então trocar pelas perguntas dele. Roda com `-Diped.mcp.test.semanticCase=<caso preparado>` e `-Diped.mcp.ipedRoot=<release com o modelo>`; sem eles, pula.

### Implementation for User Story 5

- [X] T057 [US5] Dependência de `iped-semantic` em `iped-mcp/pom.xml`. `iped-mcp/src/main/java/iped/mcp/tools/SemanticTools.java` com `iped_semantic_status`, `iped_semantic_search` e `iped_semantic_similar`, descritores com `returnsContent("metadata")`:
  - recorte por `query`/`bookmark` resolvido por `PagedSearcher`/`BookmarkQuery`, com os mesmos `QUERY_SYNTAX`/`UNKNOWN_FIELD`;
  - cursor sobre a lista do teto;
  - recusas `SEMANTIC_UNAVAILABLE`, `MODALITY_NOT_PREPARED`, `MODEL_INCOMPATIBLE` e `REFERENCE_NOT_COVERED`;
  - paginação: a última página do teto sai sem `next_cursor` e com `ceiling_reached: true`, e o aviso diz que o teto é limite de profundidade, não de relevância (não há argumento de profundidade, então não há recusa por profundidade);
  - no status: `source`, `prepared_with` e os detalhes da construção (FR-014) — `built_at`, `params`, `state` e `counts` por modalidade.
- [X] T058 [US5] Egresso por trecho em `SemanticTools.java`: `EgressPolicy.check(text, item)` para cada trecho e cada linha de sequência, e `excerpt_withheld` quando bloqueado. Registrar as ferramentas em `iped-mcp/src/main/java/iped/mcp/McpServerMain.java` → `registerTools()`, com uma linha.
- [X] T059 [US5] Orientação do agente em `iped-mcp/src/main/resources/skill/references/semantic-search.md` e `iped-mcp/src/main/resources/skill/SKILL.md`:
  - criar `iped-mcp/src/main/resources/skill/references/semantic-search.md`, com quando usar significado e quando usar palavra; por que ordenação não é conjunto; baixa proximidade não é ausência; a exigência de citar item e trecho em toda afirmação (FR-040); e que trecho recuperado é dado de evidência, nunca instrução, mesmo quando imita uma;
  - acrescentar seção curta em `iped-mcp/src/main/resources/skill/SKILL.md` apontando para a referência.

**Checkpoint**: US5 funcional.

---

## Phase 8: User Story 7 — A pergunta mistura conceito e termo exato (Priority: P2)

**Goal**: modo híbrido (BM25 por trecho + vetor, fundidos por posição) na interface e no MCP, e
`iped_semantic_context` para o RAG no harness.

**Independent Test**: quickstart, Cenário 7b e Cenário 10, passos 6 e 7. SC-018.

**Depende de**: US1 (buscador) e, para `iped_semantic_context`, US5 (`SemanticTools`).

### Tests for User Story 7 ⚠️

- [X] T060 [P] [US7] `iped-semantic/src/test/java/iped/semantic/unit/RankFusionTest.java` cobre:
  - soma `Σ 1/(rrfK + posição)` com `rrfK = 60`;
  - união das duas listas;
  - desempate determinístico;
  - lista por palavra vazia → ordenação igual à semântica, com `lexicalMatches = 0`;
  - `ranks` com a posição em cada lista.
- [X] T061 [P] [US7] `iped-semantic/src/test/java/iped/semantic/unit/LexicalPassageSearcherTest.java` cobre:
  - BM25 sobre `text`/`seqText` respeita o mesmo recorte e a mesma unidade;
  - a pergunta vira termos com escape, sem interpretar sintaxe do IPED;
  - "ação" casa "acao".
- [X] T062 [P] [US7] `iped-semantic/src/test/java/iped/semantic/unit/ContextReaderTest.java` cobre:
  - texto remendado igual ao intervalo `[charStart, charEnd)` do item, sem repetir a sobreposição (E12);
  - `truncated` em `contextMaxChars`;
  - mensagens vizinhas em sequência.
- [ ] T063 [P] [US7] `iped-semantic/src/test/java/iped/semantic/eval/HybridQualityEval.java`: SC-018 (≥ 80% nas `mixed`; no máximo 5 pontos de perda nas de SC-001). Registrar no `validation-log.md` o `defaultTextMode` de fábrica que vence.
- [X] T064 [P] [US7] `iped-semantic/src/test/java/iped/semantic/integration/KeywordSearchUnchangedIT.java`: o roteiro fixo de buscas por palavra dá resultado idêntico com e sem a parte semântica preparada no caso (FR-047).
  - Roteiro em `iped-semantic/src/test/resources/eval/keyword-script.txt`, sobre o par de `ProcessedCases`. Compara por caminho e pontuação (os ids seguem a ordem dos workers) e compara também as estatísticas de termos de `content` e `name`.

### Implementation for User Story 7

- [X] T065 [P] [US7] `iped-semantic/src/main/java/iped/semantic/search/LexicalPassageSearcher.java`: BM25 no índice semântico de texto, com o `ItemIdFilterQuery` do recorte e lista truncada em `lexicalCandidates` (`0` = teto × sobreamostragem).
- [X] T066 [P] [US7] `iped-semantic/src/main/java/iped/semantic/search/RankFusion.java`: RRF por posição com o desempate de D5.
- [X] T067 [US7] Modo híbrido em `iped-semantic/src/main/java/iped/semantic/search/SemanticSearcher.java` (depende de T065 e T066): `mode` de `SemanticRequest`, com padrão `defaultTextMode`; consolidação depois da fusão; `lexicalMatches` e `ranks` no resultado; `proximityKind = combined_relative`.
- [ ] T068 [US7] Na interface, em `SemanticSearchPanel.java` e `OrderingListRenderer.java`: seletor Semântico/Híbrido; rótulo "relevância combinada relativa" com a dica "por significado: nº X · por palavra: nº Y"; e na declaração, quantas unidades a parte por palavra trouxe.
- [X] T069 [US7] No MCP, em `iped-mcp/src/main/java/iped/mcp/tools/SemanticTools.java`: parâmetro `mode` e campos `mode`, `lexical_matches`, `ranks`, `ordinal` e `char_start`/`char_end` por trecho, conforme [contracts/mcp-tools.md](contracts/mcp-tools.md).
- [X] T070 [US7] `iped-semantic/src/main/java/iped/semantic/search/ContextReader.java` (E12) e ferramenta `iped_semantic_context` em `SemanticTools.java`: `returnsContent("text")`, `before`/`after` com padrão 1, `max_chars` limitado por `contextMaxChars`, e recusas `BLOCKED_BY_POLICY`, `SEMANTIC_UNAVAILABLE` e `ENTRY_NOT_FOUND`. Acrescentar a disciplina de RAG com contexto a `iped-mcp/src/main/resources/skill/references/semantic-search.md`.

**Checkpoint**: US7 funcional. RAG ponta a ponta no quickstart, Cenário 10, passo 7.

---

## Phase 9: User Story 6 — Imagens e vídeos encontrados por descrição em palavras (Priority: P3)

**Goal**: modalidade visual com chaves independentes para imagens e vídeos, ordenação separada
da de texto, instante aproximado nos vídeos e desfoque respeitado.

**Independent Test**: quickstart, Cenário 8. SC-012 a SC-015.

### Tests for User Story 6 ⚠️

- [X] T071 [P] [US6] `iped-semantic/src/test/java/iped/semantic/unit/VideoMosaicSplitterTest.java`: leitura de `Frames=RxC` e geometria de célula igual à de `ImageUtil.getBestFramesFit` (borda de 2 px), sobre um mosaico sintético.
- [X] T072 [P] [US6] `iped-semantic/src/test/java/iped/semantic/unit/FrameInstantTest.java`: fórmula igual à de `VideoThumbsMaker` (`-ss 1`, `-sstep` com `frequency = (duração − 1000) × 0,00095 / (maxThumbs + 2)`), resultado sempre marcado como aproximado e formatado "≈ mm:ss".
- [ ] T073 [P] [US6] `iped-semantic/src/test/java/iped/semantic/integration/VisualPreparationIT.java` cobre:
  - imagens ligadas e vídeos desligados → vídeos `MODALITY_OFF`;
  - quadros-subitem com vídeos ligados → `COVERED_BY_SOURCE_VIDEO`;
  - imagem abaixo de `minImageSize = 48` → `IMAGE_TOO_SMALL`;
  - vídeos ligados sem miniatura de vídeo → `VIDEO_NO_FRAMES` e conflito `VIDEOS_ON_WITHOUT_VIDEO_THUMBS` no registro;
  - `timing` separado em `images`/`videos`.
- [ ] T074 [P] [US6] `iped-semantic/src/test/java/iped/semantic/eval/VisualQualityEval.java`: SC-012 (≥ 70%) e SC-014 (≥ 70%), e, em separado, o acerto das descrições em inglês (FR-036), tudo gravado no `validation-log.md`.

### Implementation for User Story 6

- [X] T075 [US6] Estender `iped-semantic/tools/export-models/export_models.py` para `sentence-transformers/clip-ViT-B-32-multilingual-v1`, com dois grafos (codificador de texto com tokenizador embutido e codificador de imagem) e o pré-processamento (224 px, recorte central, normalização) declarado em `model.json`. Empacotar como zip separado, **fora do release**, no leiaute `models/semantic/visual/<id>/`, com instruções de instalação no `README.md` da ferramenta.
- [X] T076 [US6] Caminho de imagem em `iped-semantic/src/main/java/iped/semantic/model/LocalOnnxEmbedder.java`: `embedImages` com o pré-processamento de `model.json` em Java2D, e `embedTexts` pelo codificador de texto do modelo visual.
- [X] T077 [P] [US6] `iped-semantic/src/main/java/iped/semantic/prep/VideoMosaicSplitter.java` e `prep/FrameInstant.java` (regras de T071 e T072). A leitura do mosaico segue o `VideoThumbTask` (preview e `ImageUtil.readJpegWithMetaData`), só leitura.
- [ ] T078 [US6] `iped-semantic/src/main/java/iped/semantic/task/SemanticVisualTask.java`:
  - habilitação e config: `getConfigurables()` devolve `SemanticSearchConfig` mais `new EnableTaskProperty("enableSemanticImages")` e `new EnableTaskProperty("enableSemanticVideos")`; `isEnabled()` = imagens ∨ vídeos;
  - imagens: pela miniatura do item (`getThumb()`); `minImageSize` sobre as dimensões originais; `NO_THUMBNAIL` e `IMAGE_UNDECODABLE`;
  - vídeos: pelo mosaico, com o melhor quadro por vídeo, `frameIndex`/`frameCount`/`instantApproxMs`, e `VIDEO_NO_FRAMES`;
  - quadros-subitem excluídos com vídeos ligados;
  - registro: conflitos, e `timing` separado em `images`/`videos` (FR-021, SC-015);
  - `SourceValidator` para a fonte visual (FR-042).
- [ ] T079 [US6] Registrar `<task class="iped.semantic.task.SemanticVisualTask"></task>` logo depois de `ImageSimilarityTask` em `iped-app/resources/config/conf/TaskInstaller.xml` e em `iped-app/resources/config/profiles/triage/conf/TaskInstaller.xml`.
- [ ] T080 [US6] Modalidade visual em `iped-semantic/src/main/java/iped/semantic/search/SemanticSearcher.java`: pergunta pelo codificador de texto do modelo visual (PT e EN, FR-036); um vídeo por entrada, pelo melhor quadro; `BOTH` → **duas** ordenações, nunca mescladas (FR-034); sempre `mode: SEMANTIC`.
- [ ] T081 [US6] Aba visual no painel: `iped-app/src/main/java/iped/app/ui/semantic/VisualEntryRenderer.java`, com a miniatura desenhada respeitando o desfoque global (`GalleryModel.getBlurFilter()` e `ImageUtil.blur`, como a galeria, FR-037) e "≈ mm:ss" nos vídeos; escolha Texto/Visual/Ambas em `SemanticSearchPanel.java`, com as modalidades não preparadas desabilitadas e com dica.
- [ ] T082 [US6] Entradas visuais em `iped-mcp/src/main/java/iped/mcp/tools/SemanticTools.java`: `kind` `image`/`video`, `frame.frame_index`, `frame.instant_approx`, `instant_is_approximate: true`, **sem** imagem (FR-039). Acrescentar a `semantic-search.md` que o exame é por `iped_item_thumbnail`.

**Checkpoint**: US6 funcional, com cada chave afetando só o que governa.

---

## Phase 10: Cross-cutting — modelo remoto (FR-024, FR-041 a FR-045, SC-016, SC-017)

**Purpose**: qualquer modalidade pode usar modelo remoto. A exigência é o **modelo**, confirmado
pela impressão digital, e não a fonte (emenda A4). Credenciais nunca em arquivo distribuído.

- [ ] T083 [P] `iped-semantic/src/test/java/iped/semantic/unit/RemoteEmbedderTest.java`, contra servidor HTTP falso em processo (`com.sun.net.httpserver`), com os dois protocolos de [contracts/remote-model-protocol.md](contracts/remote-model-protocol.md):
  - resposta sem identidade → recusada;
  - `data[].index` incompleto → falha do lote;
  - 5xx/timeout → novas tentativas até `remoteMaxRetries`;
  - 429 respeita `Retry-After`;
  - 401/403 → falha de configuração;
  - HTTP puro recusado salvo em loopback ou com `remoteAllowPlainHttp=true`.
- [ ] T084 [P] `iped-semantic/src/test/java/iped/semantic/unit/CredentialResolverTest.java`: credencial por variável de ambiente ou arquivo; nunca aparece em log (verificado com appender de teste), nem no `BuildRecord`, nem em `toString()`.
- [ ] T085 [P] `iped-semantic/src/test/java/iped/semantic/integration/SourceSwapIT.java` (emenda A4): caso preparado por fonte remota (servidor de referência em loopback) e pesquisado com fonte local do mesmo artefato → disponível, `source: local`, `prepared_with: remote`; artefato diferente → `MODEL_INCOMPATIBLE`.
- [ ] T086 [P] `iped-semantic/src/main/java/iped/semantic/model/CredentialResolver.java`: `…RemoteCredentialEnv` ou `…RemoteCredentialFile`; nunca o valor na configuração.
- [ ] T087 `iped-semantic/src/main/java/iped/semantic/model/RemoteOpenAiEmbedder.java` (`POST {endpoint}/v1/embeddings`, só texto, identidade = `model`) e `model/RemoteIpedEmbedder.java` (`POST {endpoint}/iped-embed/1/embed` e `GET …/model`, texto e imagem). Ambos com `java.net.http`, UTF-8, `Authorization: Bearer` a partir do `CredentialResolver`, e as regras de falha do contrato. Falha de lote no processamento → itens `REMOTE_UNAVAILABLE`.
- [ ] T088 Seleção de fonte por modalidade (`textModelSource`/`visualModelSource`) em `iped-semantic/src/main/java/iped/semantic/task/SemanticTextTask.java`, `task/SemanticVisualTask.java`, `task/SourceValidator.java` e `search/SemanticSearcher.java`:
  - o registro grava o destino **da preparação** (`remote.phase: "preparation"`);
  - o resultado traz `model.sentTo` quando a fonte das perguntas é remota;
  - sem troca automática de fonte (FR-044);
  - `SourceValidator` verifica o serviço remoto alcançável com identidade informada (FR-042).
- [ ] T089 `iped-app/src/main/java/iped/app/ui/semantic/RemoteDestinationNotice.java`: faixa permanente "Perguntas desta modalidade são enviadas a `<destino>`" e confirmação na primeira pergunta da sessão; recusar não envia nada (FR-044). Ligar em `SemanticSearchPanel.java`. `sent_to` no MCP em `SemanticTools.java`.
- [ ] T090 [P] Servidor de referência `iped-semantic/tools/semantic-server/semantic_server.py` (mais `requirements.txt` e `README.md`): implementa `iped-embed/1` servindo os **mesmos** artefatos ONNX de T003/T075, com a mesma identidade, e exige credencial. Não faz parte do release.
- [ ] T091 Teste de falha rápida em `iped-semantic/src/test/java/iped/semantic/integration/FailFastIT.java` (SC-017). Com `enableSemanticImages = true`, sem modelo visual local e sem remoto, o processamento recusa começar, nomeando o que falta, e a evidência não é aberta. Verificação: no log do processamento, a recusa vem antes de qualquer marcador de início do leitor da fonte de dados ou de carga do Sleuthkit, e nenhum desses marcadores aparece. O mesmo com fonte remota inalcançável.

---

## Phase 11: Polish & Cross-Cutting Concerns

**Purpose**: documentação, terceiros, medições na bancada e validação final.

- [ ] T092 [P] Documentar a dependência nativa, como a constituição exige (Restrições da plataforma: "documentada ali e no ThirdParty.txt"): criar `iped-app/resources/root/tools/onnxruntime/README.txt` (a pasta `resources/root` é copiada para a raiz do release, `iped-app/pom.xml`), com o jar de origem e a versão fixada em T002, as plataformas, a licença e como a extração em `tools/onnxruntime/` acontece. Depois, `ThirdParty.txt` e `licenses/`: ONNX Runtime (MIT), ONNX Runtime Extensions (MIT), `intfloat/multilingual-e5-small` (MIT) e `sentence-transformers/clip-ViT-B-32-multilingual-v1` (Apache-2.0, com a parte de imagem do CLIP sob MIT), com as versões fixadas em T002.
- [ ] T093 [P] Criar `iped-semantic/CLAUDE.md`: propósito, estrutura, decisões de [research.md](research.md), invariantes da seção acima, configuração e como rodar as suítes. Atualizar:
  - `CLAUDE.md` da raiz: módulo na árvore e na tabela da seção 8; ONNX Runtime na pilha da seção 3 e em `tools/` na estrutura do release (seção 6); `SemanticSearchConfig.txt` na tabela da seção 9;
  - `iped-mcp/CLAUDE.md`: ferramentas novas e as invariantes de egresso por trecho e de `ordering_notice`;
  - `iped-app/CLAUDE.md`: painel e filtro semânticos.
- [ ] T094 [P] Completar as mensagens novas em `iped-app/resources/localization/iped-desktop-messages.properties` (EN) e `iped-desktop-messages_pt_BR.properties`. Os demais idiomas caem no fallback EN, como hoje.
- [ ] T095 Escala e revocação (quickstart, Cenário 9) em `iped-semantic/src/test/java/iped/semantic/eval/ScaleAndRecallEval.java`:
  - índice sintético com 1 M itens cobertos: pergunta sem recorte ≤ 5 s e recorte de 10 mil ≤ 2 s, frio e quente (SC-004);
  - revocação do aproximado contra o exato no conjunto de avaliação; ajustar `oversampling`/`beamWidth` se necessário;
  - calibrar `fingerprintTolerance` entre duas CPUs diferentes.
  
  Registrar tudo no `validation-log.md`.
- [ ] T096 Medições na bancada (quickstart, Cenários 2, 3 e 8), registradas no `validation-log.md`:
  - SC-008 (±5% desligada);
  - SC-010 (≤ 2× só texto);
  - SC-013 (≤ 3× texto + imagens);
  - SC-015 (tempo de vídeos em separado; desligar volta ±5%);
  - SC-009 e SC-016 (observação de tráfego).
  
  **Se SC-010 ou SC-013 reprovar só com CPU, parar e levar a revisão para a spec (Assumptions), sem relaxar no plano.**
- [ ] T097 Decisão de fábrica (research D19 e D20), com os números de T063 e T096: `defaultTextMode` em `SemanticSearchConfig.java`/`SemanticSearchConfig.txt`, e quais perfis em `iped-app/resources/config/profiles/*/IPEDConfig.txt` ligam o quê. Registrar a justificativa no `validation-log.md`.
- [ ] T098 Regressão, com o resultado registrado em `specs/009-semantic-search/validation-log.md`:
  - `mvn -pl iped-semantic -am install`;
  - `mvn -pl iped-mcp test` com `-Diped.mcp.ipedRoot=<release> -Djvm=<release>/jre/bin/java.exe -Diped.mcp.test.referenceCase=<caso>` (todas as suítes existentes, mais as novas);
  - `mvn -pl iped-engine test`;
  - roteiro de buscas por palavra do SC-007 contra o release anterior;
  - SHA-256 da evidência antes e depois.
- [ ] T099 Rodar o [quickstart.md](quickstart.md) inteiro e preencher o checklist de encerramento em `specs/009-semantic-search/validation-log.md`. Atualizar o **Status** de `specs/009-semantic-search/spec.md` com a evidência.

---

## Dependencies & Execution Order

### Phase Dependencies

- **Setup (Phase 1)**: T001 primeiro. **T002 é portão**: sem ele aprovado, T011 e tudo que carrega
  modelo local ficam bloqueados. T003, T004 e T005 correm em paralelo ao resto do Setup.
- **Foundational (Phase 2)**: depende de T001. T011 depende de T002. Bloqueia todas as histórias.
- **US1 e US2 (P1)**: US2 depende de US1 (declara o que US1 produz), e as duas juntas são o MVP.
- **US3 e US4 (P2)**: dependem de US1; independentes entre si.
- **US5 (P2)**: depende de US1 (e de US2 para `coverage`/declarações). Independente de US3 e US4 no
  código.
- **US7 (P2)**: depende de US1; `iped_semantic_context` (T070) e T069 dependem de US5.
- **US6 (P3)**: depende da fundação e de US1 (buscador); T082 depende de US5.
- **Modelo remoto (Phase 10)**: depende da fundação; T088–T089 tocam as tasks e o painel de US1/US6.
- **Polish (Phase 11)**: depende das histórias desejadas. T096 precisa de US1 e US6; T097 precisa de
  T063 e T096.

### User Story Dependencies

| História | Depende de | Testável sozinha por |
|---|---|---|
| US1 (P1) | Fundação | Cenários 3–5 |
| US2 (P1) | US1 | Cenários 2, 6, 12 |
| US3 (P2) | US1 | Cenário 7, passo 1 |
| US4 (P2) | US1 | Cenário 7, passos 2–4 |
| US5 (P2) | US1, US2 | Cenário 10, passos 1–5 |
| US7 (P2) | US1; US5 para contexto/MCP | Cenário 7b; Cenário 10, passos 6–7 |
| US6 (P3) | Fundação, US1; US5 para T082 | Cenário 8 |

### Within Each User Story

- Testes escritos antes, falhando antes da implementação.
- Modelo de dados (T030) antes do buscador; buscador antes das superfícies.
- Toda mudança em arquivo existente é aditiva e vem na tarefa que a justifica (tabela do Princípio
  III no [plan.md](plan.md)).

### Parallel Opportunities

- Setup: T003, T004 e T005 em paralelo com T001/T002.
- Fundação: T006, T007, T008, T009 e T013 em paralelo; T017 depois de cada alvo.
- US1: testes T018–T022 em paralelo; T023, T024 e T030 em paralelo.
- Depois do MVP: US3, US4 e US5 em paralelo (arquivos distintos, exceto `SemanticSearcher.java`,
  onde US3 e US4 tocam trechos diferentes — serializar os commits).
- Fase 10: T083, T084, T085, T086 e T090 em paralelo.
- Polish: T092, T093 e T094 em paralelo.

---

## Parallel Example: User Story 1

```bash
# Testes de US1 juntos:
Task: "PassageChunkerTest em iped-semantic/src/test/java/iped/semantic/unit/PassageChunkerTest.java"
Task: "ChatSequenceBuilderTest em iped-semantic/src/test/java/iped/semantic/unit/ChatSequenceBuilderTest.java"
Task: "ConsolidatorTest em iped-semantic/src/test/java/iped/semantic/unit/ConsolidatorTest.java"

# Peças independentes de US1 juntas:
Task: "PassageChunker em iped-semantic/src/main/java/iped/semantic/prep/PassageChunker.java"
Task: "StagingStore em iped-semantic/src/main/java/iped/semantic/store/StagingStore.java"
Task: "SemanticRequest/Ordering/OrderingEntry em iped-semantic/src/main/java/iped/semantic/search/"
```

---

## Implementation Strategy

### MVP First (US1 + US2)

1. Setup, com **T002 aprovado** (sem ele, parar e acionar o plano B).
2. Fundação.
3. US1: texto semântico no painel, com sequências de conversa.
4. US2: declarações, cobertura, reprodutibilidade, retomada.
5. **Parar e validar**: quickstart, Cenários 2 a 6 e 12; SC-001, SC-002, SC-005, SC-006, SC-007 e
   SC-010 (primeira medição de custo — se reprovar, voltar à spec).

### Incremental Delivery

1. MVP (US1 + US2).
2. US5, o agente — é a base do RAG no harness.
3. US7, híbrido e contexto — melhora o que o RAG consome.
4. US3 e US4, recorte e "mais como este".
5. US6, visual — maior custo, chaves próprias.
6. Modelo remoto; depois o polimento, com as medições e a decisão de fábrica.

### Parallel Team Strategy

1. Todos: Setup e fundação (T002 primeiro).
2. Depois do MVP: pessoa A em US5 e US7 (MCP/RAG); pessoa B em US3 e US4 (interface); pessoa C em
   US6 (visual) e na Fase 10.

---

## Notes

- [P] = arquivo diferente, sem dependência pendente.
- Testes com parâmetro pulam sem ele; o resumo tem de dizer quantos pularam.
- Commit por tarefa ou grupo lógico, no estilo `feat(iped-semantic): …` / `docs(009): …`.
- Nenhuma tarefa toca o índice principal, as classes da lista de revisão obrigatória ou
  `Configuration.java`. Se uma tarefa parecer exigir isso, parar e revisar o plano.
- Todo número medido vai para `specs/009-semantic-search/validation-log.md`, com a data e a
  máquina.
