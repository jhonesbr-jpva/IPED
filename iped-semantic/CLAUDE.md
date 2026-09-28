# Módulo `iped-semantic`

> **Busca semântica** sobre os artefatos que o IPED já interpretou (feature 009): texto e
> transcrições por trecho, conversas por sequência de mensagens, imagens e quadros de vídeo. A
> preparação roda no processamento, como tarefa do pipeline; a busca é uma classe só
> (`SemanticSearcher`), chamada igualmente pela interface (`iped-app`) e pelo MCP (`iped-mcp`). O
> resultado é sempre uma **ordenação declarada**, nunca um conjunto.

Especificação e decisões: [specs/009-semantic-search/](../specs/009-semantic-search/) —
`spec.md`, `research.md` (D1–D23), `data-model.md`, `contracts/`, `validation-log.md`.

## 1. Propósito

- Achar por significado o que a busca por palavra não acha (pergunta em linguagem natural, outro
  idioma, gíria de conversa), sem trocar a busca por palavra: o índice principal não muda.
- Dizer sempre o que a ordenação é: profundidade, teto, método (exato/aproximado), cobertura com
  motivos, modelo e modo. Uma ordenação **não** afirma ausência.
- Funcionar com modelo local (ONNX Runtime, sem rede) ou remoto (opt-in, com destino declarado).

## 2. Estrutura

```
iped-semantic/
├── src/main/java/iped/semantic/
│   ├── SemanticPaths.java     # raiz da instalação, pasta dos nativos do ONNX Runtime
│   ├── config/                # SemanticSearchConfig (conf/SemanticSearchConfig.txt), validação
│   ├── model/                 # Embedder local (LocalOnnxEmbedder) e remotos, fábrica, fila de inferência
│   │                          # (EmbeddingService), impressão digital (Fingerprint), credenciais
│   ├── prep/                  # fatiamento: PassageChunker, ChatSequenceBuilder, VideoMosaicSplitter, FrameInstant
│   ├── store/                 # índices semânticos (Lucene 9.2), BuildRecord, StagingStore, campos
│   ├── task/                  # SemanticTextTask, SemanticVisualTask (pipeline), TextPreparation,
│   │                          # VisualPreparation, SourceValidator, CaseModels
│   └── search/                # SemanticSearcher, SearchRun, Consolidator, RankFusion, Coverage,
│                              # Availability, Compatibility, ContextReader, DeclarationText, MultiCaseSearch
├── src/test/java/iped/semantic/
│   ├── unit/ task/ spike/     # sem caso: FakeEmbedder e SyntheticCase
│   ├── integration/ (*IT)     # processam pelo release (ProcessedCases)
│   └── eval/ (*Eval)          # harnesses de qualidade sobre o caso de avaliação
└── tools/
    ├── export-models/         # exporta os modelos (texto e visual) para ONNX, reprodutível
    ├── semantic-server/       # servidor de referência do protocolo iped-embed/1 (fora do release)
    └── eval-corpus/           # constrói o corpus de avaliação (sem evidência real) e o gabarito
```

No caso: `<caso>/iped/semantic/` com `build-record.json`, `text/` e `visual/` (índices Lucene
próprios, codec HNSW) e `staging/` (mensagens à espera de virar sequência). Contrato em
[contracts/case-storage.md](../specs/009-semantic-search/contracts/case-storage.md).

## 3. Decisões que condicionam o desenho

| Decisão | Onde | Resumo |
|---|---|---|
| Módulo próprio, índice próprio | D1, D4 | Nada no índice principal; o caso continua autocontido |
| ONNX Runtime 1.20 + extensions 0.13 | D2, D17 | Tokenizador no grafo; nativos extraídos em `tools/onnxruntime/`; uma entrada por `run()` (quantização dinâmica int8) |
| `multilingual-e5-small` int8 (texto), CLIP multilíngue (visual) | D3, D8 | Visual é pacote separado, fora do release |
| Busca exata até `exactSearchMaxItems`, HNSW acima | D5 | Declarado em `method` |
| Trechos de 1000/200 ordenam; janelas de foco de 400/100 escolhem o trecho exibido | D6, D23 | Janelas só em itens com mais de um trecho, vetor em doc values fora do grafo |
| Conversas por sequência de mensagens | D7 | O vetor leva `remetente: texto`; bancos de chat decodificados não viram trecho |
| Impressão digital do modelo | D10 | Sondas fixas; compatível = cosseno ≥ `fingerprintTolerance` |
| Modo híbrido por RRF ponderado | D20 | `rrfLexicalWeight` 0,3, calibrado no caso de avaliação |
| Referência de mensagem sem remetentes | D22 | "Mais como este" pede significado, não interlocutor |
| Modelo copiado para o caso | D17 | O app do próprio caso precisa dele na hora da busca (`CaseModels`) |
| Texto de binários fora do padrão | D6 (revisão de 2026-09-27) | Cadeia de parsers com o `RawStringParser`, espaço não alocado e slack ficam `BINARY_TEXT`; `prepareBinaryText = true` inclui. Era 45% do texto de uma imagem Linux medida. Também o texto ilegível (≥ 10% de caracteres de substituição, controle, uso privado ou não atribuídos): binário que um parser de texto decodificou, como BLOB de banco |
| Mensagem sem texto próprio | D7 (revisão de 2026-09-28) | Marcadores do parser não são texto (`! IMAGE_MESSAGE`, `! Attachment: image/jpeg`); mensagem de tipo listado sem `Message-Body` fica `NO_TEXT`, antes da regra de binários (o conteúdo vazio passa pelo `RawStringParser`) |
| Conversa renderizada só no fim | D6 (revisão de 2026-09-28) | O item de chat (`chatMediaTypes`) vai para a área de montagem; no `finish()` é preparado como documento só se o chat não ganhou sequência. Antes era embutido e descartado: metade da inferência numa bancada de chats |

## 4. Configuração

`iped-app/resources/config/conf/SemanticSearchConfig.txt`, com todas as chaves comentadas; tabela
em [contracts/config-surface.md](../specs/009-semantic-search/contracts/config-surface.md). A
habilitação fica no `IPEDConfig.txt` (`enableSemanticText`, `enableSemanticImages`,
`enableSemanticVideos`), desligada por padrão. Fonte remota por modalidade
(`textModelSource`/`visualModelSource = remote`), com a credencial só em variável de ambiente ou
arquivo apontado.

## 5. Invariantes que não podem ser afrouxadas

- O índice principal não recebe campo, documento nem mudança de análise; `IndexerSimilarity`,
  `AppAnalyzer` e `BasicProps` não mudam. `AppAnalyzer` é **usado** (parte por palavra do híbrido),
  nunca alterado.
- Nenhuma alteração em `Manager`, `Worker`, `ProcessingQueues`, `IndexWriter`, `SleuthkitClient`,
  Aho-Corasick ou `Configuration.java`. A habilitação entra por `EnableTaskProperty`.
- Nenhuma credencial em arquivo distribuído, log ou `build-record.json`.
- Nenhum texto gerado no lugar do trecho; nenhum vetor ou pontuação bruta em saída alguma. Todo
  trecho exibido é intervalo literal do texto do item (SC-006), inclusive as janelas de foco.
- Uma ordenação nunca é apresentada como conjunto: nada de "N resultados", "nenhum resultado" ou
  porcentagem de confiança (FR-007, FR-010). Os textos saem de `DeclarationText`.
- Interface e MCP pelo mesmo `SemanticSearcher`/`SearchRun`: não criar segundo caminho (SC-011).
- SLF4J, UTF-8 explícito, comentários em inglês, texto de interface localizado PT-BR + EN.

## 6. Testes

```powershell
# unitários (sem caso nem modelo; os demais pulam por Assume)
mvn -pl iped-semantic test

# integração: processam uma evidência pelo release
mvn -pl iped-semantic test "-Dtest=*IT" `
  "-Djvm=<release>\jre\bin\java.exe" `
  "-Diped.semantic.test.ipedRoot=<release>" `
  "-Diped.semantic.test.referenceEvidence=<pasta de evidência>" `
  "-Diped.semantic.test.textModel=<release>\models\semantic\text\multilingual-e5-small-int8" `
  "-Diped.semantic.test.visualModel=<release>\models\semantic\visual\clip-vit-b-32-multilingual-v1" `
  "-Diped.semantic.test.referenceServerPython=<python com requirements do semantic-server>"

# qualidade: sobre o caso de avaliação (tools/eval-corpus), gravam em validation-log.md
mvn -pl iped-semantic test "-Dtest=*QualityEval" "-Diped.semantic.test.evalCase=<caso>" ...
```

`-Diped.semantic.test.config.<chave>=<valor>` sobrepõe uma chave nas rodadas de calibração, e o
log registra a sobreposição. JDK de build: Liberica 11 Full (o runtime do release é Java 11).

## 7. ⚠️ Áreas sensíveis

- **Gabarito e corpus são versionados.** Mudar pergunta, pertinente ou texto do corpus depois de
  medir é mudar o que foi medido: nova versão, registrada no `validation-log.md` e no README do
  corpus. Nenhum texto do corpus pode começar com uma assinatura que o IPED conheça ("MEMO" é a do
  SuperFetch).
- **Escolhas feitas olhando o caso de avaliação** (peso do híbrido, referência de mensagem, janelas
  de foco) estão marcadas como tal; a bancada com evidência real (T096) é quem as confirma.
- **Campos do índice semântico são contrato de caso**: renomear um quebra todo caso já preparado.
- **`SecurityManager` da interface**: a política do IPED nega arquivo e rede a pilhas com certos
  códigos (visualizador HTML; scripts). O acesso remoto usa `AccessController.doPrivileged`.

## 8. Limitações conhecidas

- O `e5-small` separa pouco paráfrases distantes; no cenário 2 da História 1, 4 de 6 perguntas
  exibem o parágrafo certo do relatório longo.
- As janelas de foco quase dobram a inferência de texto nos itens longos (no corpus de avaliação,
  preparação de 23 s para 38 s).
- O custo segue o volume de texto: ~113 mil caracteres por segundo em 14 núcleos, sem janelas de
  foco. Uma imagem de sistema rica em texto (código, documentação, configurações) leva horas mesmo
  sem o texto de binários; SC-010 (≤ 3×) e SC-013 (≤ 4×) são medidos no caso de avaliação.
- O vetor de uma sequência leva os remetentes; entre conversas, os nomes ainda pesam do lado dos
  candidatos (D22).
