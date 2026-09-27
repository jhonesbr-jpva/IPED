# Implementation Plan: Busca semântica sobre os artefatos interpretados pelo IPED

**Branch**: `009-semantic-search` | **Date**: 2026-09-26 | **Spec**: [spec.md](spec.md)

**Input**: Feature specification from `/specs/009-semantic-search/spec.md`

## Summary

O IPED só encontra o que se sabe nomear. A 009 acrescenta a busca por **significado** sobre o que o
processamento já interpreta: texto extraído, OCR, transcrições, mensagens de chat em sequência de
conversa, imagens e quadros de vídeo. Ela fica disponível na interface de análise e para o agente,
com a mesma ordenação nas duas superfícies.

A abordagem, decidida em [research.md](research.md):

- **Um módulo novo, `iped-semantic`**, concentra tudo que não é superfície: duas tasks de
  preparação (texto e visual), o armazenamento no caso, as fontes de modelo (local e remota) e
  **um único buscador**. A interface e o MCP são clientes finos desse buscador, e é isso que
  garante a paridade de SC-011.
- **Inferência local com ONNX Runtime Java**, com o tokenizador embutido no grafo por ONNX Runtime
  Extensions. Roda no JRE 11 do release, sem Python e sem rede. O modelo de texto
  (`multilingual-e5-small`, int8) vai no pacote; o visual (CLIP ViT-B/32 multilíngue) vai em
  pacote separado. Qualquer modalidade pode usar modelo remoto, pelo formato OpenAI (texto) ou por
  um protocolo próprio com servidor de referência (texto e imagem).
- **Índices Lucene próprios em `<caso>/iped/semantic/`**, um por modalidade. O índice principal
  não ganha nem um campo: caso antigo abre como sempre e se declara sem busca semântica pela
  simples ausência da pasta.
- **A busca usa o Lucene 9.2 que já está na árvore** (`KnnVectorQuery` com filtro). O recorte
  corrente vira filtro. A lista é calculada uma vez até o teto e revelada aos poucos. A varredura
  é exata abaixo de um limiar e aproximada acima dele, e o método vai declarado no resultado.
- **Reprodutibilidade por impressão digital do modelo**: sondas fixas recalculadas a cada sessão
  detectam troca de modelo, inclusive de pesos sob o mesmo nome num serviço remoto.
- **Modo híbrido na modalidade texto**: BM25 sobre os trechos, **dentro do índice semântico**,
  combinado com a ordenação semântica pela posição de cada trecho nas duas listas (Reciprocal Rank
  Fusion). A busca por palavra existente continua intocada, e ela nem é BM25: pontua pela
  frequência do termo no item inteiro (D20).
- **O IPED é o recuperador; o RAG fica no harness.** O MCP entrega cada entrada com citação
  (item e intervalo literal) e ganha `iped_semantic_context`, para ampliar o contexto em volta de
  um trecho sem trazer o item inteiro. O IPED não chama modelo de linguagem (D21).

Nenhuma alteração em `Manager`, `Worker`, `ProcessingQueues`, `IndexWriter`, `SleuthkitClient`,
Aho-Corasick, `AppAnalyzer` ou interfaces de `iped-api`.

---

## Emendas ao spec — aceitas em 2026-09-26

A pesquisa encontrou quatro pontos em que o texto da spec prometia mais do que o desenho entrega,
ou ficava aquém do que ele exige. Nenhum reduz escopo. **As quatro foram aceitas pelo usuário em
2026-09-26 e já estão aplicadas à spec** (seção Clarifications, FR-007, FR-008, FR-024, FR-035,
FR-039, FR-044, SC-006).

A A4 trouxe uma consequência, aplicada junto: se as perguntas podem vir de outra fonte que não a
da preparação, o caso não tem como saber, ao ser construído, para onde elas irão. Por isso a
FR-024 passou a exigir o registro do destino **da preparação** no caso, e a declaração do destino
**das perguntas** em cada resultado, mostrada ao perito antes do envio.

| # | Requisito | Antes dizia | Emenda (aplicada) | Por quê |
|---|---|---|---|---|
| **A1** | FR-008 | Enumera os motivos de não cobertura | Acrescentar "categoria não selecionada", "coberto pelas mensagens", "coberto pelo vídeo de origem", "sem miniatura" e "modelo remoto indisponível" | O desenho produz esses motivos (research D6, D8, D9, D12). Sem eles, o item cairia num motivo que não é verdade sobre ele, e isso é o "motivo derivado do item, não escolhido de uma lista" que a 001 já teve de corrigir |
| **A2** | FR-035, História 6 | "com o instante em que ele aparece no vídeo" | "com o instante **aproximado**, declarado como tal" | O IPED não grava o instante dos quadros. Ele é derivável da duração e do passo de extração, com erro da ordem de um passo (D8). Apresentar como exato seria afirmar precisão que o dado não tem |
| **A3** | FR-007 | Ordenação com profundidade declarada | Acrescentar que o **método** (exato ou aproximado) é declarado junto | Acima de `exactSearchMaxItems` a busca é aproximada (D5): reproduzível, mas pode omitir um item que a varredura exaustiva incluiria. O perito precisa saber qual das duas coisas está vendo |
| **A4** | FR-044 | Pergunta exige "o mesmo serviço, com o mesmo modelo" | Exigir "o mesmo modelo, confirmado pela impressão digital", **qualquer que seja a fonte** | Um caso preparado no servidor de GPU do laboratório poderia ser pesquisado na estação isolada com o artefato local idêntico. A impressão digital (D10) garante que é o mesmo modelo |

---

## Technical Context

**Language/Version**: Java 11 LTS (Liberica OpenJDK 11 Full, `H:\java\LibericaJDK-11-Full`).
Restrição de **runtime**: o release embarca JRE 11. Python aparece só **fora** do IPED: no script
que exporta os modelos para ONNX e no servidor remoto de referência.

**Primary Dependencies**:
- Já na árvore: Lucene 9.2.0 (`KnnVectorField`, `KnnVectorQuery`), Jackson 2.13, H2 MVStore,
  `java.net.http` (JDK 11).
- **Novas**: `com.microsoft.onnxruntime:onnxruntime` (CPU) e
  `com.microsoft.onnxruntime:onnxruntime-extensions`. Versões fixadas depois do spike de D2, que
  verifica class file ≤ 55, nativos presentes e carga sem rede.
- **Modelos**: `intfloat/multilingual-e5-small` (texto, MIT) no pacote;
  `sentence-transformers/clip-ViT-B-32-multilingual-v1` (visual, Apache-2.0/MIT) em pacote
  separado. Os alternativos estão em D3.

**Storage**: por caso, em `<caso>/iped/semantic/`: um índice Lucene por modalidade (trechos,
sequências, imagens, quadros, documentos de estado), `build-record.json` e a área de montagem
temporária `staging.mv.db`. Leiaute em [contracts/case-storage.md](contracts/case-storage.md).

**Testing**: JUnit 4 (o padrão da árvore).
- Unitários: fatiamento, montagem de sequências, consolidação, cobertura, impressão digital,
  configuração, protocolo remoto contra servidor falso.
- Integração: caso de referência, ativada por `-Diped.semantic.test.referenceCase`.
- Avaliação: conjunto de avaliação, ativada por `-Diped.semantic.test.evalCase`, que calcula
  SC-001 a SC-003, SC-012, SC-014, SC-018 (híbrido contra semântico) e a revocação do aproximado.
- MCP: as suítes existentes do `iped-mcp`, com as ferramentas novas.

**Target Platform**: Windows x64 (principal) e Linux x64 (CI Ubuntu 22.04). Só CPU; GPU não é
requisito.

**Project Type**: aplicação desktop (Swing + JavaFX) com motor de processamento em JVM filha e
servidor MCP.

**Performance Goals**:
- SC-004: até 5 s por pergunta em 1 M itens cobertos; até 2 s em recorte de até 10 mil itens.
- SC-010: processamento com texto ≤ 2× o tempo sem a capacidade.
- SC-013: processamento com texto e imagens ≤ 3×.

As três são **metas de partida, não medidas** (D15).

**Constraints**:
- Sem rede por padrão (FR-023, SC-009).
- Evidência somente leitura (Princípio I).
- Índice principal intocado (Princípio II).
- Nada carregado quando desligado (FR-020, SC-008).
- Mesma ordenação na mesma instalação (SC-005).

**Scale/Scope**: até alguns milhões de itens cobertos por modalidade, algo como 3 M documentos de
trecho no texto.

---

## Constitution Check

*GATE: avaliado antes da Phase 0 e reavaliado após a Phase 1.*

| Princípio | Avaliação | Situação |
|---|---|---|
| **I. Integridade da evidência** | Nenhuma escrita na evidência. As imagens usam a miniatura já gerada, e os vídeos usam o mosaico do preview. A leitura de texto usa o cache que o parsing já produziu, e nenhum caminho novo lê a evidência. Toda escrita vai para `<caso>/iped/semantic/`, e só durante o processamento, que já é a operação que escreve o caso. Não há operação nova de escrita sobre caso existente (FR-030) | **Passa** |
| **II. Caso processado é contrato permanente** | O índice principal não recebe campo, documento nem mudança de análise. `AppAnalyzer` e `BasicProps` intocados: o `AppAnalyzer` é **usado**, sem modificação, para indexar os trechos no índice semântico. O BM25 do modo híbrido é configurado só no índice semântico, e a `IndexerSimilarity` da busca por palavra continua a mesma (FR-047). Nenhuma interface de `iped-api` alterada. Caso antigo não tem `semantic/` e abre como sempre (FR-026, FR-027, SC-007) | **Passa** |
| **III. Estender antes de modificar** | Capacidade nova em módulo novo, com duas tasks novas e `Configurable` próprio. **Nenhuma** das classes da lista de revisão obrigatória é tocada. As modificações em arquivos existentes são aditivas e estão listadas abaixo | **Passa, com justificativas** |
| **IV. Configurável vive em configuração** | Três chaves de habilitação em `IPEDConfig.txt`. Todo o resto em `conf/SemanticSearchConfig.txt`: fonte por modalidade, destino remoto, onde ficam as credenciais, categorias, limites, janelas, tamanho mínimo, profundidade, teto, limiar exato e tolerância da impressão digital. Contrato em [contracts/config-surface.md](contracts/config-surface.md) | **Passa** |
| **V. Nada implícito no que varia por ambiente** | UTF-8 explícito no registro e no HTTP. SLF4J em todo o código novo, sem `System.out`. Consultas fora da EDT, com a atualização do painel na EDT. Mensagens em `iped-desktop-messages*.properties`, no mínimo PT-BR e EN. Comentários em inglês. Variação numérica entre CPUs tratada pela tolerância da impressão digital (D10) | **Passa** |
| **Plataforma: Java 11 é runtime** | ONNX Runtime Java exige Java 8+. As extensões ainda **não verificadas**: o spike de D2 é portão, e reprovar aciona o plano B | **Passa condicionado ao spike** |
| **Plataforma: dependência nativa documentada** | Nativos do ONNX Runtime extraídos em `tools/onnxruntime/`, registrados em `ThirdParty.txt` e `licenses/` (D17) | **Passa** |
| **Fluxo: task nova** | `SemanticTextTask` e `SemanticVisualTask` têm `Configurable`, estão registradas em `TaskInstaller.xml` (depois de `NamedEntityTask` e depois de `ImageSimilarityTask`, respectivamente) e retornam cedo em `isEnabled()` | **Passa** |

### Princípio III — o que muda em arquivo existente, e por quê

Todas as mudanças são **aditivas**. Nenhuma altera comportamento existente quando a capacidade
está desligada.

| Arquivo | Mudança | Por que não havia alternativa aditiva fora dele |
|---|---|---|
| `pom.xml` (raiz) | `<module>iped-semantic</module>` depois de `iped-engine` | Registro de módulo |
| `iped-app/pom.xml` | Dependência de `iped-semantic`; desempacotar o modelo de texto em `models/semantic/text/` | É onde o release é montado (D17) |
| `iped-mcp/pom.xml`, `McpServerMain.registerTools()` | Dependência; uma linha `tools.addAll(new SemanticTools(...).descriptors())` | É o registro de ferramentas; não há descoberta por SPI |
| `iped-app/.../ui/App.java` | Registrar o `SemanticResultFilterer` e o painel acoplável | É onde os filtros e painéis são registrados ("faces semelhantes" usa o mesmo ponto) |
| `iped-app/.../ui/MenuClass.java` | Entrada "Mais como este" | Menu de contexto da tabela |
| `iped-viewers/.../ATextViewer.java` | Ação de menu "Mais como este trecho" que publica o texto selecionado para um ouvinte | FR-013 exige partir de trecho **selecionado no item**, e a seleção só existe no visualizador. É a única mudança fora de `iped-app`/`iped-mcp`/`iped-semantic`, e é uma ação de menu sem efeito sobre a renderização |
| `resources/config/IPEDConfig.txt`, `conf/TaskInstaller.xml` | Três chaves (desligadas) e duas linhas de task | Configuração, não código (Princípio IV) |
| `resources/localization/iped-desktop-messages*.properties` | Chaves novas | Princípio V |
| `iped-mcp/src/main/resources/skill/` | `references/semantic-search.md`, seção em `SKILL.md` | FR-040 |
| `ThirdParty.txt`, `licenses/` | ONNX Runtime, extensões, modelos | Constituição, Restrições da plataforma |

### Reavaliação após a Phase 1

O desenho da Phase 1 ([data-model.md](data-model.md) e [contracts/](contracts/)) não introduziu
violação:

- O leiaute de armazenamento confirma que o índice principal não é tocado.
- Os contratos do MCP não acrescentam campo ao `AuditRecord`, e a classe de conteúdo continua
  sendo a fronteira da política de egresso.
- A superfície de configuração não tem valor fixo em código: os padrões no código são fallback de
  último recurso, com o mesmo padrão do `McpServerConfig`.

Continua **condicionado** o spike de D2 (baseline Java das extensões). As emendas A1–A4, que não
eram violação constitucional mas mudavam o que se testa, foram aceitas e aplicadas em 2026-09-26.

---

## Project Structure

### Documentation (this feature)

```text
specs/009-semantic-search/
├── spec.md
├── plan.md                      # este arquivo
├── research.md                  # Phase 0 — D1 a D21
├── data-model.md                # Phase 1
├── quickstart.md                # Phase 1 — cenários de validação
├── contracts/
│   ├── case-storage.md          # leiaute de <caso>/iped/semantic/ e registro de construção
│   ├── config-surface.md        # IPEDConfig.txt + conf/SemanticSearchConfig.txt
│   ├── mcp-tools.md             # iped_semantic_status / _search / _similar / _context
│   ├── remote-model-protocol.md # openai-embeddings e iped-embed/1
│   └── ui-surface.md            # painel, filtro, menus, declarações
├── checklists/requirements.md
└── tasks.md                     # /speckit-tasks — ainda não criado
```

### Source Code (repository root)

```text
iped-semantic/                                   # NOVO módulo
├── pom.xml
├── CLAUDE.md
├── src/main/java/iped/semantic/
│   ├── config/      SemanticSearchConfig        # Configurable<UTF8Properties>, conf/SemanticSearchConfig.txt
│   │                SemanticEnableProperties    # enableSemanticText / Images / Videos
│   ├── model/       Embedder                    # contrato comum: texto→vetor, imagem→vetor, identidade
│   │                LocalOnnxEmbedder           # ONNX Runtime + extensões, sessão compartilhada
│   │                RemoteOpenAiEmbedder        # openai-embeddings (texto)
│   │                RemoteIpedEmbedder          # iped-embed/1 (texto e imagem)
│   │                ModelIdentity, Fingerprint, FingerprintProbes
│   │                EmbeddingService            # fila limitada + lotes, por modelo e por JVM
│   │                CredentialResolver          # variável de ambiente ou arquivo, nunca valor em conf
│   ├── prep/        PassageChunker, ChatSequenceBuilder, VideoMosaicSplitter, FrameInstant
│   ├── store/       SemanticStore               # caminhos em <caso>/iped/semantic
│   │                SemanticIndexWriter/Reader, StatusRecord, BuildRecord, StagingStore
│   ├── search/      SemanticSearcher            # o ÚNICO buscador — interface e MCP chamam este
│   │                SemanticRequest, Ordering, OrderingEntry, Coverage, Consolidator,
│   │                ItemIdFilterQuery, Compatibility
│   │                LexicalPassageSearcher      # BM25 sobre os trechos, no índice semântico (D20)
│   │                RankFusion                  # RRF por posição, desempate determinístico
│   │                ContextReader               # texto literal em volta de uma entrada (D21)
│   └── task/        SemanticTextTask, SemanticVisualTask
├── src/main/resources/iped/semantic/probes/     # sondas da impressão digital (texto e imagens sintéticas)
├── src/test/java/iped/semantic/{unit,integration,eval}/
└── tools/
    ├── export-models/                           # exportação reprodutível para ONNX (Python, fora do release)
    └── semantic-server/                         # servidor remoto de referência (iped-embed/1)

iped-app/
├── src/main/java/iped/app/ui/semantic/          # NOVO pacote
│   ├── SemanticSearchPanel                      # painel acoplável
│   ├── SemanticResultFilterer                   # IResultSetFilterer — aplica a ordenação à tabela
│   ├── OrderingListRenderer, SequenceRenderer, VisualEntryRenderer
│   ├── MoreLikeThisAction, RemoteDestinationNotice
├── src/main/java/iped/app/ui/App.java           # registro (aditivo)
├── src/main/java/iped/app/ui/MenuClass.java     # "Mais como este" (aditivo)
├── resources/config/IPEDConfig.txt              # +3 chaves, desligadas
├── resources/config/conf/SemanticSearchConfig.txt  # NOVO
├── resources/config/conf/TaskInstaller.xml      # +2 tasks
├── resources/localization/iped-desktop-messages*.properties
└── pom.xml

iped-mcp/
├── src/main/java/iped/mcp/tools/SemanticTools.java   # NOVO
├── src/main/java/iped/mcp/McpServerMain.java         # uma linha de registro
├── src/main/resources/skill/references/semantic-search.md  # NOVO
└── pom.xml

iped-viewers/iped-viewers-impl/src/main/java/iped/viewers/ATextViewer.java  # ação de menu (aditivo)
pom.xml, ThirdParty.txt, licenses/
```

**Structure Decision**: módulo novo `iped-semantic` como núcleo da capacidade. Superfícies em
pacote próprio no `iped-app` (`ui/semantic`) e numa classe de ferramentas no `iped-mcp`.
Mudanças em arquivos existentes limitadas ao registro, e todas aditivas (tabela do Princípio III).

---

## Complexity Tracking

Nenhuma violação constitucional a justificar. Registro aqui as duas escolhas de complexidade que
um revisor pode questionar:

| Escolha | Por que é necessária | Alternativa simples rejeitada, e por quê |
|---|---|---|
| Dois protocolos remotos (`openai-embeddings` e `iped-embed/1`) | O usuário pediu modelo remoto, e o formato OpenAI não tem imagem | Só OpenAI: sem remoto para a modalidade visual. Só protocolo próprio: exclui os serviços que o formato OpenAI cobre sem código |
| Impressão digital por sondas, além da identidade declarada | Detecta pesos trocados sob o mesmo nome e absorve a variação numérica entre CPUs | Só identidade: não detecta troca silenciosa num serviço remoto, que é exatamente o risco que FR-043 nomeia |
| Segunda relevância por palavra (BM25 por trecho), ao lado da existente | O modo híbrido precisa de relevância por trecho; a existente é por item e só por frequência | Reaproveitar a existente: não tem unidade de trecho, e trocá-la mudaria a busca por palavra em todo caso (Princípio II) |
