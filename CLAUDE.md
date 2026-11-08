# IPED — Indexador e Processador de Evidências Digitais

> Documentação destinada a agentes de IA (Claude Code, Cursor, etc.) e desenvolvedores humanos que vão modificar este fork do projeto **IPED** ([github.com/sepinf-inc/IPED](https://github.com/sepinf-inc/IPED)). Mantenha-a coerente — atualize quando alterar a arquitetura ou as dependências.

> **Sobre esta branch (`base/agents`).** Ela é `upstream/master` (sepinf-inc) **mais o ferramental de agentes**: `.specify/` (Spec Kit), `.claude/`, este arquivo e os `CLAUDE.md` por módulo. Nenhum código do IPED é alterado aqui. Features novas da linha master nascem desta branch (`git worktree add -b NNN-nome ../NNN-nome base/agents`); branches para PR no sepinf-inc nascem de `master` (ou via `git rebase --onto master base/agents`, que descarta os commits de ferramental). As linhas do fork que mudam a árvore — Java 21, YARA-X, Tika 3, plugin de grafo, MCP — vivem nas próprias branches (seção 13); **nada disso existe nesta árvore**. Os `CLAUDE.md` por módulo foram escritos na linha Java 21 do fork: trechos sobre YARA/`--yara-only`, SPI do grafo/`ban-neo4j`/plugin `iped-graph-neo4j` e Java 21 **não se aplicam aqui**.

## 1. O que é o IPED

IPED é uma ferramenta open source de **forense digital** desenvolvida desde 2012 pela Polícia Federal Brasileira. Processa imagens forenses (E01, DD, VHD, VMDK, UFDR, AD1...) e fontes lógicas, extraindo, indexando e correlacionando evidências em larga escala (até centenas de milhões de itens por caso, 400 GB/h em hardware moderno).

Capacidades centrais: hashing (MD5, SHA-1/256/512, Edonkey, PhotoDNA), bases de hash (NSRL, ProjectVic, ICSE), detecção de assinatura, parsing recursivo de containers, expansão de imagens embarcadas, OCR (Tesseract), detecção de idioma, NER (Stanford CoreNLP), regex com validação (CPF/CNPJ/cartões/cripto), gallery imagem/vídeo, georreferenciamento, transcrição de áudio (Vosk/Whisper/Google/Azure), similaridade visual, reconhecimento facial, detecção de nudez (DIE/Yahoo NSFW), análise de vínculos (Neo4j embarcado), relatórios HTML, casos portáteis e Web API REST.

Build: **Java 11 + JavaFX**, Maven multi-módulo, distribuído com JRE embarcado + ferramentas externas (Sleuthkit, ImageMagick, Tesseract, LibreOffice, MPlayer, RegRipper, libpff, libesedb, evtxexport, rifiuti2, GraphViz, etc.).

## 2. Estrutura do repositório

```
IPED/
├── pom.xml                                 # Parent POM (versão 4.4.0-SNAPSHOT)
├── README.md, LICENSE.txt, ThirdParty.txt, ReleaseNotes.txt
├── licenses/                                # licenças de terceiros distribuídas
├── .github/workflows/maven.yml              # CI (Ubuntu 22.04, matriz Java 11 e 14)
├── .specify/, .claude/                      # ferramental de agentes (só nesta branch e nas features do fork)
│
├── iped-api/         → docs em [iped-api/CLAUDE.md](iped-api/CLAUDE.md)
├── iped-utils/       → docs em [iped-utils/CLAUDE.md](iped-utils/CLAUDE.md)
├── iped-parsers/     → docs em [iped-parsers/CLAUDE.md](iped-parsers/CLAUDE.md)
│   ├── iped-parsers-impl/
│   └── java-dbf/
├── iped-viewers/     → docs em [iped-viewers/CLAUDE.md](iped-viewers/CLAUDE.md)
│   ├── iped-viewers-api/
│   └── iped-viewers-impl/
├── iped-carvers/     → docs em [iped-carvers/CLAUDE.md](iped-carvers/CLAUDE.md)
│   ├── iped-ahocorasick/
│   ├── iped-carvers-api/
│   └── iped-carvers-impl/
├── iped-geo/         → docs em [iped-geo/CLAUDE.md](iped-geo/CLAUDE.md)
├── iped-engine/      → docs em [iped-engine/CLAUDE.md](iped-engine/CLAUDE.md)
├── iped-bfac/                               # integração com o BFAC (Banco Federal de Arquivos Conhecidos): envio de arquivos/hashes do caso (sem CLAUDE.md próprio)
└── iped-app/                                # binário final — ver [iped-app/CLAUDE.md](iped-app/CLAUDE.md)
    ├── src/main/java/iped/app/{bootstrap,config,processing,ui,graph,timelinegraph,metadata}
    └── resources/{config,scripts,localization,plugins,root,checkstyle}
```

`iped-app` consome todos os módulos e empacota o release final em `target/release/iped-${version}/`.

## 3. Pilha de tecnologia

| Camada | Stack |
|---|---|
| Linguagem | Java 11 (Liberica/BellSoft Full JDK com JavaFX) |
| Build | Maven 3+ (multi-módulo, parent pom em `pom.xml`) |
| Indexação/busca | Apache Lucene 9.2.0 |
| Parsing | Apache Tika 2.4.0 (core 2.4.0-p1) + parsers customizados |
| Imagens forenses | The Sleuthkit 4.15.0.p1 (JNI + processo isolado) |
| UI | Swing + JavaFX (`JFXPanel`) + DockingFrames 1.1.2 |
| PDF | Apache PDFBox 2.0.27, IcePDF 7.0.0 |
| SQLite | xerial-sqlite-jdbc 3.51.3.0, libfqlite (undelete) |
| Office | LibreOffice 7.2.2 via UNO bridge + NOA-Libre |
| Graph | Neo4j 4.4.4 **embarcado** no `iped-engine` (`iped.engine.graph`: `GraphTask`, `GraphFileWriter`, `GraphGenerator`, consultas `*LinksQuery`); renderização na UI via Kharon 0.1.6 |
| Web API | Jersey 2.30.1 + Grizzly + Swagger 1.6.10 |
| Storage opcional | MinIO 8.3.8, OpenSearch 2.1.0 |
| Scripting | Nashorn 15.4 (JS), JEP 4.0.3 (Python embarcado) |
| Speech | Vosk 0.3.32, Microsoft Cognitive Speech 1.19.0, Google Cloud Speech 1.22.5 |
| Logging | SLF4J 1.7.25 + Log4j 2.17.1 |
| Cache/serial | Caffeine 3.2.2, FST 2.57, Zstd-JNI 1.3.3-3 |
| OCR | Tesseract 5 (via JEP) |
| ML/IA | RandomForest (DIE), Yahoo OpenNSFW (Keras/TensorFlow), PhotoDNA (lib restrita) |

Dependências de terceiros completas: [`ThirdParty.txt`](ThirdParty.txt).

## 4. Convenções globais

- **Java 11** (`maven.compiler.source/target = 11`), `UTF-8` por todo o source.
- **Versão**: `4.4.0-SNAPSHOT` no parent; modules herdam.
- **Branch padrão**: `master` (espelho de `sepinf-inc/master`, instável, dev). Releases nas tags.
- **Locale**: detectado por `LocaleResolver` (lê system property `iped-locale`). Mensagens em PT-BR e EN; arquivos em `iped-app/resources/localization/`.
- **Idioma do código**: todo **código novo** (Java, JS, Python, XML, properties) **DEVE** ser comentado e documentado em **inglês** — Javadocs, comentários, mensagens de log, exceções técnicas. Constituição, §"Fluxo de Desenvolvimento", item 2. Javadocs/comentários PT-BR no código legado **podem permanecer**; ao reescrever substancialmente uma região, traduzir o comentário/Javadoc afetado no mesmo PR. Strings visíveis ao usuário final continuam regidas pelo Princípio III (bundles em `iped-app/resources/localization/`, PT-BR + EN).
- **Codificação**: SEMPRE explicita charset. UTF-8 para texto; ISO-8859-1 para nomes NTFS antigos.
- **Logging**: SLF4J + Log4j 2 (`Log4j2Configuration*.xml` em `conf/`). Sem `System.out`/`err`.
- **Threading**: cada `Worker` do engine roda sozinho; tasks têm instância por worker. Swing na EDT; JavaFX em `Platform.runLater`.
- **Sem chamadas diretas a `SleuthkitJNI`** — sempre via `SleuthkitClient`.

## 5. Como buildar

Pré-requisitos:
- Git, Maven 3.6+, JDK 11 com JavaFX (Liberica OpenJDK 11 Full, por exemplo — é o que o `README.md` do upstream recomenda).
- Variável `JAVA_HOME` apontando para esse JDK.
- No Linux, é necessário compilar/instalar libagdb + dependências (ver wiki "Linux").

Comandos:
```bash
git clone <este-fork>
cd IPED
mvn clean install         # gera target/release/iped-<versão>/
```

Outras receitas:
```bash
mvn -pl iped-api -am install                     # build incremental
mvn -pl iped-engine test                          # testes só do engine
mvn -pl iped-parsers/iped-parsers-impl test       # testes só de parsers
mvn -B package --file pom.xml                     # como o CI (Ubuntu)
```

O CI (`.github/workflows/maven.yml`) roda em Ubuntu 22.04 com Java 11 e 14 e instala várias dependências nativas (`libscca-utils`, `rifiuti2`, `libevtx-utils`, `pff-tools`, `libesedb-utils`, `tesseract-ocr-por`, `imagemagick`, `gsfonts`, `python3-pip` + `jep==4.0.3`, `Parse::Win32Registry`, libagdb). Replique localmente se for rodar todos os testes.

## 6. Como rodar

Estrutura do release (`target/release/`):
```
target/release/
├── iped-<version>/                   # app root (LocalConfig.txt → pluginFolder = ../plugins/)
│   ├── iped.jar                      # main class: iped.app.bootstrap.Bootstrap
│   ├── lib/                          # dependências (copy-dependencies)
│   │   ├── iped-search-app.jar       # main: iped.app.bootstrap.BootstrapUI
│   │   ├── iped-webapi.jar           # main: iped.engine.webapi.Main
│   │   └── iped-hashdb.jar           # main: iped.engine.hashdb.HashDBTool
│   ├── conf/                         # configurações editáveis
│   ├── localization/                 # bundles
│   ├── tools/                        # ferramentas externas (Sleuthkit, Tesseract, ImageMagick, LibreOffice, ...)
│   ├── scripts/                      # tasks JS/Python
│   ├── models/                       # modelos AI (DIE, NSFW, Vosk)
│   ├── jre/                          # JRE embutido (Windows)
│   └── iped.exe / bin/               # launchers
└── plugins/                          # pasta de plugins, IRMÃ do app root: JARs externos (Stanford CoreNLP,
                                      # telegram-decoder, java-dbx, ...)
```

Só os jars do **primeiro nível** de `plugins/` entram no classpath das JVMs filhas (`Bootstrap` adiciona `pluginFolder/*`). Num caso autocontido o app root é `<caso>/iped/`, logo a pasta de plugins é `<caso>/plugins/`.

CLI principais (entry points):
- `iped.app.bootstrap.Bootstrap` — processamento de caso (CLI). Flags principais: `-d`/`-data` (datasource), `-o`/`-output` (saída), `-profile` (forensic/pedo/triage/fastmode/blind), `--append`/`--continue`/`--restart`.
- `iped.app.bootstrap.BootstrapUI` — UI de busca/análise.
- `iped.engine.webapi.Main` — Web API.
- `iped.engine.hashdb.HashDBTool` — ferramenta de base de hashes.

`Bootstrap` é responsável por:
- Ler `-Xms/-Xmx` (limita ao tamanho da RAM física; default = 25% da RAM, máx 32 GB).
- Carregar configs (`Configuration.loadConfigurables`).
- Montar classpath com plugins, LibreOffice (UNO jars), `tools/sleuthkit/`, etc.
- Disparar JVM filha com `iped.app.processing.Main` (out-of-process — facilita restart e isola crashes).

## 7. Pipeline de alto nível

```
arquivo/imagem → DataSourceReader → ProcessingQueues → Worker(s)
   ↓                                                       ↓
SleuthkitClient                                       AbstractTask chain
   ↓                                                       ↓
IItem (lazy IO)  →  SignatureTask  →  HashTask  →  ParsingTask
                                                       ↓
                            ParsingTask → subitens (zip, email, ...)
                                                       ↓
                                  CarverTask (Aho-Corasick + assinaturas)
                                                       ↓
                            RegexTask / NER / LanguageDetect
                                                       ↓
                  ImageThumbTask / VideoThumbTask / DocThumbTask
                                                       ↓
                 DIETask / FaceRecognition / AudioTranscript
                                                       ↓
                                    IndexTask → Lucene IndexWriter
                                                       ↓
                              HTMLReportTask / ExportFileTask
```

Tudo configurável por `conf/IPEDConfig.txt`, `conf/TaskInstaller.xml`, `conf/*.properties|.xml|.json` e profiles (`profiles/{forensic,pedo,triage,fastmode,blind}`).

## 8. Módulos e suas docs

| Módulo | Papel | Documentação |
|---|---|---|
| `iped-api` | API base — interfaces, exceções, constantes de propriedades e MIME types. Tudo que outros módulos consomem ou implementam. | [iped-api/CLAUDE.md](iped-api/CLAUDE.md) |
| `iped-utils` | Utilitários transversais (I/O, streams, hashes, imagens, datas, ImageMagick wrapper, UTF8Properties, ícones). | [iped-utils/CLAUDE.md](iped-utils/CLAUDE.md) |
| `iped-parsers` | Camada de extração (Apache Tika estendido). ~70 categorias de parsers (chats, browsers, registry, P2P, e-mails, SQLite, ...). | [iped-parsers/CLAUDE.md](iped-parsers/CLAUDE.md) |
| `iped-viewers` | Renderização de itens na UI: PDF, HTML, imagens, áudio, hex, Office (LibreOffice UNO), e-mail, CAD, multi-viewer. | [iped-viewers/CLAUDE.md](iped-viewers/CLAUDE.md) |
| `iped-carvers` | Data carving (recuperação por assinatura) usando Aho-Corasick. | [iped-carvers/CLAUDE.md](iped-carvers/CLAUDE.md) |
| `iped-geo` | Georreferenciamento e visualização cartográfica (Leaflet/Google Maps em JavaFX WebView, parsing KML/GPX). | [iped-geo/CLAUDE.md](iped-geo/CLAUDE.md) |
| `iped-engine` | **Core**: orquestração, workers, indexação, busca, Sleuthkit out-of-process, configs, Web API, análise de vínculos (Neo4j embarcado), scripting. | [iped-engine/CLAUDE.md](iped-engine/CLAUDE.md) |
| `iped-bfac` | Integração com o BFAC (Banco Federal de Arquivos Conhecidos): envio de arquivos e hashes do caso ao backend. Módulo adicionado no upstream em dez/2025. | — |
| `iped-app` | Empacotamento final + UI Swing principal: `Bootstrap`/`BootstrapUI`, `App` (JFrame singleton), gallery, bookmarks, filtros, AppGraphAnalytics (Kharon), timeline (JFreeChart), recursos de release. | [iped-app/CLAUDE.md](iped-app/CLAUDE.md) |

## 9. Onde editar configurações (não código)

| Quero alterar… | Edite |
|---|---|
| Habilitar/desabilitar features | `iped-app/resources/config/IPEDConfig.txt` |
| Pipeline de tasks | `iped-app/resources/config/conf/TaskInstaller.xml` |
| Carvers | `iped-app/resources/config/conf/CarverConfig.xml` |
| Assinaturas customizadas | `iped-app/resources/config/conf/CustomSignatures.xml` |
| Regex e validadores | `iped-app/resources/config/conf/RegexConfig.txt` + `scripts/regex_validators/` |
| Profiles | `iped-app/resources/config/profiles/{forensic,pedo,triage,fastmode,blind}/` |
| Localização | `iped-app/resources/localization/` |
| Scripts customizados | `iped-app/resources/scripts/tasks/*.{js,py}` |
| Hash DB (NSRL/ProjectVic) | `iped-app/resources/config/conf/HashDBLookupConfig.txt` + base externa |
| Categorias | `CategoriesConfig.json`, `CategoriesToExpand.txt`, `CategoriesToExport.txt` |

## 10. Onde editar código

| Tarefa | Módulo de destino |
|---|---|
| Adicionar/alterar interface pública | `iped-api` (entender impacto antes!) |
| Helper de I/O/imagem/data | `iped-utils` |
| Suportar novo formato de arquivo | `iped-parsers/iped-parsers-impl` |
| Novo visualizador de conteúdo | `iped-viewers/iped-viewers-impl` |
| Novo formato a recuperar via carving | `iped-carvers/iped-carvers-impl` + `CarverConfig.xml` |
| Nova fonte de dados (DataSourceReader) | `iped-engine/.../datasource/` |
| Nova task no pipeline | `iped-engine/.../task/` + `TaskInstaller.xml` |
| Novo Configurable | `iped-engine/.../config/` |
| Novo endpoint REST | `iped-engine/.../webapi/` |
| Análise de vínculos (`GraphTask`, `GraphFileWriter`, `GraphGenerator`, consultas `*LinksQuery`, Neo4j embarcado) | `iped-engine/.../graph/` |
| UI principal (gallery, filtros, bookmarks, timeline) | `iped-app` |

## 11. Padrões de design recorrentes

- **Configurable** (`iped.configuration.Configurable<T>` em `iped-api`) — todo componente externamente configurável.
- **Chain of Responsibility** — `AbstractTask.nextTask` no engine.
- **Strategy** — `AbstractViewer` em viewers, `Carver` em carvers, `AbstractMapCanvas` em geo.
- **Factory** — `ISeekableInputStreamFactory`, `IItem.createChildItem()`, `MapCanvasFactory`.
- **Composite/CardLayout** — `MultiViewer`.
- **Producer/Consumer com filas prioritárias** — `ProcessingQueues` + `Worker`.
- **Out-of-process** — `SleuthkitClient`/`SleuthkitServer`, `ParsingProcess`, `Bootstrap` (UI executa em JVM filha).
- **Service Provider Interface (SPI)** — `META-INF/services/...` (Tika detectors, regex validators, graph queries).

## 12. Guia rápido para Agentes de IA

1. **Sempre comece lendo o `CLAUDE.md` do módulo afetado** antes de editar.
2. Use o `Grep` para localizar implementações/consumidores antes de mudar APIs.
3. **Não** modifique:
   - Interfaces de `iped-api` removendo/renomeando métodos.
   - Strings literais que viram chave de campo Lucene (`BasicProps`, `IndexItem`).
   - `Manager`, `Worker`, `ProcessingQueues`, `IndexWriter` sem revisar invariantes de concorrência.
   - `AppAnalyzer` (mudar quebra busca em casos antigos).
   - Aho-Corasick (`iped-ahocorasick`).
4. Para novas features, prefira:
   - **Adicionar** propriedade em `ExtraProperties`.
   - **Adicionar** task com seu `Configurable` em vez de tocar tasks existentes.
   - **Adicionar** parser/viewer/carver novo em vez de modificar comportamento existente.
5. Charset **sempre** explícito. UTF-8 por padrão.
6. EDT/JavaFX:
   - Swing → `SwingUtilities.invokeLater` ou já estar na EDT.
   - JavaFX → `Platform.runLater`.
7. Threading no engine: tasks têm uma instância por worker → use campos de instância sem locks; estado global em `caseData.objectMap` (com cleanup em `finish()`).
8. Subitens: gere via `EmbeddedDocumentExtractor.parseEmbedded(...)` (parsers) ou `IItem.createChildItem()` + `worker.processNewItem(item)` (tasks/carvers).
9. Antes de commit:
   - `mvn -pl <módulo> -am install` no módulo afetado.
   - `mvn test` se há cobertura relevante.
   - Atualize o `CLAUDE.md` do módulo se alterou contratos/dependências.

## 13. Linhas de trabalho deste fork

Esta branch não carrega features. O que o fork já produziu vive nas branches abaixo (todas em `github.com/jhonesbr-jpva/IPED`); o índice completo das specs está na branch `specs-archive`.

| Linha | Branch(es) | Base | O que muda na árvore |
|---|---|---|---|
| Features sobre o master (spec-kit) | `001-yara-rules-engine`, `003-java21-migration`, `010-neo4j-optional-plugin` | master do fork (jun/2026) | YARA-X (`YaraScanTask`, `--yara-only`), Java 21, Neo4j 5 como plugin opcional (`iped-graph-neo4j`, `iped-graph-server`, SPI `GraphServiceProvider`) |
| GUI em Eclipse RCP | `004-rcp-gui-migration`, `005-case-creation-wizard` | master do fork | módulo `iped-rcp` |
| Reimplementações limpas para PR no sepinf-inc | `java21-migration-upstream`, `yara-engine-upstream`, `tika3-upgrade`, agregadas em `integration/upstream-stack` | `upstream/master` | Java 21, YARA-X, Tika 3.3.1, `iped-mcp` |
| Linha 4.3.x (MCP/LLM) | `iped-4.3.1-mcp` (integração) + features `001-iped-llm-integration`, `006`…`009-semantic-search` | `upstream/4.3.x` | módulo `iped-mcp`, `iped-semantic` |
| Histórico | `legacy/fork-master` (master do fork até jun/2026: spec-kit + YARA + Java 21), `archive/002-eclipse-rcp-gui-migration` | — | — |

Ao abrir uma feature nova a partir daqui, registre-a nesta tabela e no índice da `specs-archive`.
