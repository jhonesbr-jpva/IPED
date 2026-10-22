---

description: "Task list — Neo4j como plugin opcional do IPED"
---

# Tasks: Neo4j como plugin opcional do IPED

**Input**: Design documents from `specs/010-neo4j-optional-plugin/`
**Prerequisites**: [plan.md](plan.md), [spec.md](spec.md), [research.md](research.md), [data-model.md](data-model.md), [contracts/](contracts/), [quickstart.md](quickstart.md)

**Tests**: a spec não pede TDD, mas o plano (R-12) e os contratos definem testes de unidade (registro, guard, CSV golden, provedor), um teste de integração *gated* do pacote e uma matriz de validação manual (SC-001..SC-009). Esses testes estão incluídos **dentro de cada story** como tarefas próprias; as validações manuais registram resultado em `specs/010-neo4j-optional-plugin/validation-results.md`.

**Organization**: tarefas agrupadas por user story. A **Phase 2 (Foundational)** concentra a abstração do núcleo (tipos neutros + SPI + registro + retipagem) e a migração das classes Neo4j para o novo módulo — sem isso nem o núcleo compila "sem Neo4j" (US1) nem o plugin existe (US2).

## Format: `[ID] [P?] [Story] Description`

- **[P]**: pode rodar em paralelo (arquivos diferentes, sem dependências pendentes).
- **[Story]**: US1–US4 (mapeia para as user stories da spec).
- Caminhos de arquivo exatos nas descrições. Convenções: código/Javadoc/log em inglês; charset explícito; SLF4J (constituição).

---

## Phase 1: Setup (módulo novo + baseline de paridade)

**Purpose**: criar o esqueleto do módulo do plugin e capturar o baseline **antes** de qualquer mudança de código.

- [ ] T001 Em `pom.xml` (raiz), adicionar `<module>iped-graph-neo4j</module>` logo após `<module>iped-graph-server</module>` (R-04: construído por padrão no reactor).
- [ ] T002 Criar `iped-graph-neo4j/pom.xml` (esqueleto): parent `iped:iped-parent:4.4.0-SNAPSHOT`, `artifactId iped-graph-neo4j`, `packaging jar`, descrição (plugin opcional de análise de vínculos); dependências: `iped:iped-engine:${project.version}` (`provided`), `org.neo4j.driver:neo4j-java-driver:5.26.0` (`compile`), `org.slf4j:slf4j-api` (`provided`), `iped:iped-graph-server:${project.version}` `<type>zip</type><classifier>neo4j</classifier>` (`provided`, com exclusão `*:*`) só para ordenar o reactor; sem plugins de build ainda (vêm em T044).
- [ ] T003 [P] Criar a estrutura de pastas: `iped-graph-neo4j/src/main/java/iped/graph/neo4j/`, `iped-graph-neo4j/src/main/resources/META-INF/services/`, `iped-graph-neo4j/src/plugin-home/`, `iped-graph-neo4j/src/assembly/`, `iped-graph-neo4j/src/test/java/iped/graph/neo4j/`, `iped-engine/src/test/java/iped/engine/graph/` e `iped-engine/src/test/resources/iped/engine/graph/golden/` (com `.gitkeep` onde necessário).
- [ ] T004 [P] Gerar o **baseline de paridade** com o build atual (ainda com Neo4j embarcado): `mvn -B clean package`, copiar `target/release/` para uma pasta de baseline fora do repositório, processar a evidência de referência (pequena, com e-mails/chats) com o perfil padrão para obter um caso-baseline **com** `iped/neo4j/{csv,data}`; criar `specs/010-neo4j-optional-plugin/validation-results.md` com a seção "Baseline" (caminhos, versão, contagens de nós/relacionamentos obtidas na aba Vínculos, lista dos CSVs gerados).

---

## Phase 2: Foundational (abstração do núcleo + migração das classes Neo4j)

**Purpose**: o núcleo passa a compilar **sem nenhuma referência a `org.neo4j`**; as classes dependentes do Neo4j passam a viver no módulo do plugin. Bloqueia todas as stories.

**⚠️ CRITICAL**: nenhuma story começa antes do checkpoint T030.

### Tipos neutros e SPI (`iped-engine/src/main/java/iped/engine/graph/`)

- [ ] T005 [P] Criar `GraphLabel.java` (final, imutável): `name()`, `static of(String)`, `equals/hashCode` por nome, `toString` = nome (contrato [graph-spi](contracts/graph-spi.contract.md)).
- [ ] T006 [P] Criar `GraphRelationshipType.java` (final, imutável): `name()`, `static of(String)`, `equals/hashCode` por nome.
- [ ] T007 [P] Criar `GraphEntity.java` (interface): `long getId()`, `String getElementId()`, `boolean hasProperty(String)`, `Object getProperty(String)` (retorna `null` se ausente — nunca lança), `Object getProperty(String, Object defaultValue)`, `Iterable<String> getPropertyKeys()`, `Map<String,Object> getAllProperties()`.
- [ ] T008 [P] Criar `GraphNode.java` (interface, estende `GraphEntity`): `Iterable<GraphLabel> getLabels()`, `boolean hasLabel(GraphLabel)`.
- [ ] T009 [P] Criar `GraphRelationship.java` (interface, estende `GraphEntity`): `GraphRelationshipType getType()`, `GraphNode getStartNode()`, `GraphNode getEndNode()`, `GraphNode getOtherNode(GraphNode)`, `GraphNode[] getNodes()`.
- [ ] T010 [P] Criar `GraphPath.java` (interface, `Iterable<GraphEntity>`): `GraphNode startNode()`, `GraphNode endNode()`, `GraphRelationship lastRelationship()`, `Iterable<GraphRelationship> relationships()`, `Iterable<GraphNode> nodes()`, `int length()`.
- [ ] T011 [P] Criar `GraphQueryException.java` (`RuntimeException`, construtores `(String)` e `(String, Throwable)`) e `GraphPluginException.java` (checked, `(String)` e `(String, Throwable)`).
- [ ] T012 [P] Criar `GraphDatabaseGenerator.java` (interface): `interface ImportListener { void output(String line); }` aninhada e `boolean generate(ImportListener listener, File dbHome, File... csvFolders) throws IOException` (mesma semântica de `GraphGenerator.generate`).
- [ ] T013 [P] Criar os enums `GraphPluginState.java` (`ABSENT`, `AVAILABLE`, `REJECTED`) e `GraphPluginRejection.java` (`MULTIPLE_COPIES`, `SPI_MISMATCH`, `INCOMPLETE`, `LOAD_ERROR`) conforme [data-model.md §1](data-model.md).
- [ ] T014 Criar `GraphServiceProvider.java` (interface): `int CURRENT_SPI_VERSION = 1`; `int getSpiVersion()`, `String getPluginVersion()`, `String getBuiltForIpedVersion()`, `String getDisplayName()`, `void validate() throws GraphPluginException`, `GraphService createGraphService()`, `GraphDatabaseGenerator createDatabaseGenerator()`; Javadoc explicando descoberta via `ServiceLoader` e a regra de versão (depende de T011, T012).
- [ ] T015 Criar `GraphPluginRegistry.java` (singleton `get()`, resolução única e sincronizada): `ServiceLoader.load(GraphServiceProvider.class)`; estados/motivos conforme a tabela do [graph-spi](contracts/graph-spi.contract.md) (0 → `ABSENT`; ≥2 → `MULTIPLE_COPIES`; `ServiceConfigurationError`/`LinkageError` → `LOAD_ERROR`; `getSpiVersion() != CURRENT_SPI_VERSION` → `SPI_MISMATCH`; `validate()` falha → `INCOMPLETE`; senão `AVAILABLE`); API `isAvailable()`, `getState()`, `getRejection()` (`Optional`), `getDetail()`, `getProvider()` (`IllegalStateException` se indisponível), `getGraphService()`/`getDatabaseGenerator()` (cacheados), `getPluginJar()`/`getPluginHome()` (via `ProtectionDomain` do provedor); log SLF4J **uma vez** com as mensagens exatas do contrato (pasta de plugins obtida de `ConfigurationManager.get().findObject(PluginConfig.class).getPluginFolder()` quando disponível); ponto de injeção para testes `static void resetForTesting(ClassLoader)` (depende de T013, T014).

### Retipagem do núcleo (`iped-engine`)

- [ ] T016 Retipar `GraphService.java`: `List<GraphNode> search(GraphLabel, Map<String,Object>)`, `void search(GraphLabel, Map<String,Object>, NodeQueryListener, String...)`; remover imports `org.neo4j.*`; manter todos os demais métodos e Javadocs (atualizar menção a Cypher como "query text in the provider's dialect").
- [ ] T017 [P] Retipar os listeners `NodeQueryListener.java` (`nodeFound(GraphNode)`), `EdgeQueryListener.java` (`edgeFound(GraphRelationship)`), `LinkQueryListener.java` (`linkFound(GraphNode, GraphNode)`) e `PathQueryListener.java` (`pathFound(GraphPath)`) em `iped-engine/src/main/java/iped/engine/graph/`.
- [ ] T018 Retipar `GraphFileWriter.java` (`Label` → `GraphLabel`, `RelationshipType` → `GraphRelationshipType` em `openNodeWriter`, `openRelationshipWriter`, `getNodeWriter`, `getRelationshipWriter`, `writeNode*`, `writeRelationship*`, `writeCreateRelationship*`, `writeNodeId`, `uniqueId`, `CSVWriter` e `join(GraphLabel...)`), preservando byte a byte os nomes de arquivo e o conteúdo dos CSVs; apagar `DynLabel.java` e `DynRelationshipType.java`.
- [ ] T019 Retipar `GraphTask.java`: `DynLabel.label(x)` → `GraphLabel.of(x)`, `DynRelationshipType.withName(x)` → `GraphRelationshipType.of(x)` (classe `NodeValues` e demais usos, ~25 ocorrências); em `finishGraphGeneration()` obter o gerador por `GraphPluginRegistry.get().getDatabaseGenerator()` e usar `GraphDatabaseGenerator.ImportListener`; remover imports `org.neo4j.*` e `GraphGenerator`.
- [ ] T020 Reescrever `GraphServiceFactoryImpl.java` como fachada: `getGraphService()` delega a `GraphPluginRegistry.get().getGraphService()` e lança `IllegalStateException("Graph plugin not available")` quando `!isAvailable()`; manter `GraphServiceFactory` e `getInstance()`.

### Migração das classes Neo4j para o plugin (`iped-graph-neo4j/src/main/java/iped/graph/neo4j/`)

- [ ] T021 [P] Mover `BoltEntity.java`, `BoltNode.java`, `BoltRelationship.java` e `BoltPath.java` de `iped-engine/.../graph/` para `iped-graph-neo4j/src/main/java/iped/graph/neo4j/` (pacote `iped.graph.neo4j`), passando a implementar `GraphEntity`/`GraphNode`/`GraphRelationship`/`GraphPath`; `getProperty` ausente → `null`; `getLabels()` → `GraphLabel.of(nome)`; remover métodos mutadores herdados da API Neo4j (`setProperty`, `delete`, `createRelationshipTo`, `addLabel`, `removeLabel`) e os de grau/relacionamentos que não fazem parte das interfaces neutras (manter o snapshot de grau se `GraphModel` o usar via propriedade).
- [ ] T022 Mover `GraphServiceImpl.java` para `iped-graph-neo4j/src/main/java/iped/graph/neo4j/Neo4jGraphService.java` (implementa `iped.engine.graph.GraphService` retipado): manter o ciclo de vida Bolt/`GraphServer` (porta, `READY_TOKEN`, `STOP`), converter resultados do driver nos adaptadores de T021, converter `org.neo4j.driver.exceptions.Neo4jException` em `GraphQueryException` em `advancedSearch`/`searchPaths`/`search` (R-14), usar `GraphLabel.name()` ao montar Cypher (depende de T021).
- [ ] T023 [P] Mover `GraphGenerator.java` → `iped-graph-neo4j/.../Neo4jDatabaseGenerator.java` (implementa `GraphDatabaseGenerator`; `POST_IMPORT_CLASS` inalterado), `GraphImportRunner.java` → `Neo4jImportRunner.java` e `Neo4jChildLauncher.java` → `iped-graph-neo4j/.../Neo4jChildLauncher.java` (por ora mantendo a resolução `lib/neo4j`; a resolução final relativa ao jar do plugin é T049); ajustar imports para `iped.engine.config.LocalConfig`/`ConfigurationManager` (continuam no engine).
- [ ] T024 Apagar de `iped-engine/src/main/java/iped/engine/graph/` os originais movidos: `GraphServiceImpl.java`, `BoltEntity.java`, `BoltNode.java`, `BoltRelationship.java`, `BoltPath.java`, `GraphGenerator.java`, `GraphImportRunner.java`, `Neo4jChildLauncher.java` (depende de T021–T023).

### Retipagem da UI (`iped-app/src/main/java/iped/app/graph/`)

- [ ] T025 [P] Retipar `AppGraphAnalytics.java` (todos os `org.neo4j.graphdb.Node/Relationship/Entity/Path` → `GraphNode`/`GraphRelationship`/`GraphEntity`/`GraphPath`; iteração de `path` via `Iterable<GraphEntity>` e `instanceof GraphRelationship/GraphNode`) e `GraphModel.java` (`convert(GraphNode)`, `getFullType`, `getType`, `getDefaultFieldNames`, `getLabel` sem `try/catch NotFoundException` — teste de nulo).
- [ ] T026 [P] Retipar `AddNodeWorker.java` e `ShowEvidenceNodeWorker.java` (`nodeFound(GraphNode)`), `AddRelationshipWorker.java` e `GraphSidePanel.java` (tipos dos listeners/serviço).
- [ ] T027 [P] Retipar `AdvancedSearchDialog.java` (`catch (GraphQueryException e)` nos dois pontos), `EntitySearchDialog.java`, `ExportLinksWorker.java` (remover `catch (NotFoundException)` → teste de nulo), `SearchLinksDialog.java` (`GraphLabel.of("DATASOURCE")`) e `SearchLinksWorker.java`.
- [ ] T028 [P] Ajustar `LoadGraphDatabaseWorker.java`: em `createMultiCaseGraph` usar `GraphPluginRegistry.get().getDatabaseGenerator()` e `GraphDatabaseGenerator.ImportListener`; manter `initGraphService` via `GraphServiceFactoryImpl`.

### Dependências e checkpoint

- [ ] T029 Em `iped-engine/pom.xml`, remover as dependências `org.neo4j:neo4j-graphdb-api` e `org.neo4j.driver:neo4j-java-driver` (e o comentário "Java 21 migration (003): the embedded Neo4j 5 engine runs out-of-process…", substituindo por nota apontando para `iped-graph-neo4j`).
- [ ] T030 Checkpoint da Phase 2: `mvn -B -pl iped-engine,iped-app,iped-graph-neo4j -am compile` verde e `grep -rl "import org.neo4j" iped-engine/src iped-app/src` vazio; `grep -rn "org.neo4j" iped-engine/src iped-app/src` só pode restar em comentários/strings de consulta (nenhum tipo). Registrar em `specs/010-neo4j-optional-plugin/validation-results.md` (seção "Checkpoint Phase 2").

**Checkpoint**: núcleo sem Neo4j compila; módulo do plugin compila com as classes migradas. Stories podem começar.

---

## Phase 3: User Story 1 — Usar o IPED completo sem o plugin (Priority: P1) 🎯 MVP

**Goal**: distribuição padrão sem nenhum componente Neo4j; processamento pula a etapa de grafo com um único aviso; UI abre casos sem oferecer a aba/ações de grafo; demais pontos de entrada intocados (FR-001, FR-003, FR-004, FR-005, FR-006, FR-014, FR-017).

**Independent Test**: build do release → inventário vazio de Neo4j (SC-001); processar a evidência de referência sem plugin → 1 `WARN`, 0 erros, paridade forense com o baseline (SC-002); abrir o caso na UI → sem aba "Vínculos", smoke 100% (SC-005). Ver [quickstart.md §4](quickstart.md) passos 1–3.

### Implementation for User Story 1

- [ ] T031 [US1] Em `iped-engine/src/main/java/iped/engine/graph/GraphTask.java` (`init`): `enabled = config.isEnabled() && GraphPluginRegistry.get().isAvailable()`; quando `config.isEnabled()` e o plugin não está disponível, emitir **uma única vez** (flag `static AtomicBoolean`) o `WARN` `Graph generation is enabled but the graph plugin is not installed (<estado/detalhe>): link analysis will be skipped for this case.`; não criar `GraphFileWriter` nem a pasta `neo4j/csv` quando desabilitado (contrato [cli-missing-plugin §A](contracts/cli-missing-plugin.contract.md)).
- [ ] T032 [P] [US1] Em `iped-app/src/main/java/iped/app/processing/Main.java`, chamar `GraphPluginRegistry.get()` logo após `Configuration.getInstance().loadConfigurables(...)` do processo filho (onde a `PluginConfig` já está carregada), garantindo a linha de log do estado antes do `Manager` (FR-003).
- [ ] T033 [P] [US1] Em `iped-app/src/main/java/iped/app/ui/AppMain.java`, chamar `GraphPluginRegistry.get()` logo após `Configuration.getInstance().loadConfigurables(libDir.getParentFile()..., true)` (FR-003).
- [ ] T034 [US1] Em `iped-app/src/main/java/iped/app/ui/App.java`: criar `appGraphAnalytics` (~l.638), registrar `FilterSelectedEdges` (~l.834) e criar `graphDock` (~l.981) **apenas** se `GraphPluginRegistry.get().isAvailable()`; garantir `null`-safety nos usos de `graphDock` (~l.1094, 1268, 1361, 1439) e de `appGraphAnalytics` (contrato [ui-degradation](contracts/ui-degradation.contract.md)).
- [ ] T035 [P] [US1] Em `iped-app/src/main/java/iped/app/ui/MenuClass.java` (~l.330) adicionar o item "adicionar ao grafo" só quando `App.get().appGraphAnalytics != null`; em `iped-app/src/main/java/iped/app/ui/MenuListener.java` (~l.461) guardar `addEvidenceFilesToGraph` contra `null`.
- [ ] T036 [P] [US1] Em `iped-app/src/main/java/iped/app/ui/UICaseDataLoader.java` (~l.115) chamar `initGraphService()` só quando `App.get().appGraphAnalytics != null` (o aviso FR-007 entra em T065).
- [ ] T037 [P] [US1] Em `iped-app/pom.xml`: remover a dependência `iped:iped-graph-server` (`zip`, classifier `neo4j`, ~l.29–45) e a execução `unpack-graph-server` (~l.137–162); atualizar o comentário de `excludeTypes` (~l.632–634) mantendo `<excludeTypes>zip</excludeTypes>`.
- [ ] T038 [P] [US1] Adicionar `maven-enforcer-plugin` 3.5.0 (goal `enforce`, fase `validate`) com `bannedDependencies` (`org.neo4j:*`, `org.neo4j.driver:*`, `searchTransitive=true`) em `iped-engine/pom.xml` e `iped-app/pom.xml` (contrato [release-inventory](contracts/release-inventory.contract.md)).
- [ ] T039 [P] [US1] Criar `iped-engine/src/test/java/iped/engine/graph/GraphPluginRegistryTest.java` com os casos **ABSENT** (classloader de teste sem provedores → `isAvailable()==false`, `getProvider()` lança `IllegalStateException`) e "log emitido uma vez" (usar `resetForTesting`); a matriz completa é estendida em T051.
- [ ] T040 [P] [US1] Criar `iped-engine/src/test/java/iped/engine/graph/GraphFileWriterTest.java`: escrever um conjunto fixo de nós/relacionamentos (`GraphLabel`/`GraphRelationshipType`, propriedades com acentos e vírgulas) em pasta temporária e comparar nomes de arquivo e conteúdo com os golden em `iped-engine/src/test/resources/iped/engine/graph/golden/` (capturados do baseline T004 com o `GraphFileWriter` antigo).
- [ ] T041 [US1] Executar `mvn -B clean package`; rodar o inventário do contrato [release-inventory](contracts/release-inventory.contract.md) (`find target/release -iname '*neo4j*'`, `ls lib | grep -i -E 'netty|reactor|reactive-streams'`, grep de imports) e registrar em `specs/010-neo4j-optional-plugin/validation-results.md` (SC-001).
- [ ] T042 [US1] Validar processamento sem plugin ([quickstart §4](quickstart.md) passo 2): processar a evidência de referência com o release de T041 (perfil padrão), conferir 1 `WARN`, 0 erros, ausência de `iped/neo4j/`, e paridade forense com o caso-baseline (hashes, MIME, texto, categorias, carved, YARA, timeline — método da feature 003); registrar (SC-002, FR-004).
- [ ] T043 [US1] Validar UI e demais pontos de entrada sem plugin ([quickstart §4](quickstart.md) passo 3): smoke da UI no caso de T042 (sem aba/ações de grafo, 0 diálogos de erro, 0 stack traces), `--yara-only`, `-nogui`, relatório a partir do caso, `iped.engine.webapi.Main`, `HashDBTool`; registrar (SC-005, FR-006, FR-017).

**Checkpoint**: MVP — distribuição sem Neo4j, totalmente funcional sem o plugin.

---

## Phase 4: User Story 2 — Instalar o plugin e recuperar a análise de vínculos (Priority: P2)

**Goal**: pacote do plugin instalável em 3 passos; com ele, geração do grafo, aba "Vínculos", append/remoção e cópia autocontida funcionam como antes; remoção devolve o comportamento de US1 (FR-002, FR-009, FR-010, FR-011, FR-012, FR-015).

**Independent Test**: instalar o zip em `<dist>/plugins/`, reiniciar → log "Graph plugin loaded"; processar a evidência de referência → contagens de nós/relacionamentos iguais ao baseline; roteiro de 10 operações na aba; `<caso>/plugins/` presente; remover o plugin → US1. Ver [quickstart.md §3–§4](quickstart.md) passos 7–13.

### Implementation for User Story 2

- [ ] T044 [US2] Completar `iped-graph-neo4j/pom.xml` (build): `maven-dependency-plugin` `unpack` do zip `iped-graph-server` em `${project.build.directory}/plugin-home/iped-graph-neo4j` (o zip tem prefixo `neo4j/`); `maven-resources-plugin` copiando `src/plugin-home/` para `${project.build.directory}/plugin-home/iped-graph-neo4j` com `filtering=true` só para `plugin.properties`; `maven-shade-plugin` 3.6.0 (relocações `io.netty`→`iped.graph.neo4j.shaded.io.netty`, `reactor`→`iped.graph.neo4j.shaded.reactor`, `org.reactivestreams`→`iped.graph.neo4j.shaded.org.reactivestreams`; `ServicesResourceTransformer`; `ManifestResourceTransformer` com `IPED-Graph-SPI-Version=1`, `Implementation-Version=${project.version}`, `IPED-Target-Version=${project.version}`, `Implementation-Title=iped-graph-neo4j`; filtro excluindo `META-INF/*.SF|*.DSA|*.RSA`; `createDependencyReducedPom=false`); `maven-assembly-plugin` com `src/assembly/plugin.xml`, `appendAssemblyId=false`, `finalName iped-graph-neo4j-${project.version}` (contrato [plugin-package](contracts/plugin-package.contract.md), R-04/R-05).
- [ ] T045 [P] [US2] Criar `iped-graph-neo4j/src/assembly/plugin.xml`: formato `zip`, `includeBaseDirectory=false`; `fileSet` do jar shaded (`${project.build.directory}/${project.build.finalName}.jar` → raiz) e `fileSet` de `${project.build.directory}/plugin-home/` → raiz (gera `iped-graph-neo4j/…`).
- [ ] T046 [P] [US2] Criar `iped-graph-neo4j/src/plugin-home/plugin.properties` (`plugin.version=${project.version}`, `spi.version=1`, `iped.version=${project.version}`, `built.on=${maven.build.timestamp}`) e `iped-graph-neo4j/src/plugin-home/README.md` (instalação em 3 passos, verificação no log, remoção, regra de compatibilidade por versão do SPI, plano B de jars soltos — conteúdo do contrato [plugin-package](contracts/plugin-package.contract.md)).
- [ ] T047 [US2] Criar `iped-graph-neo4j/src/main/java/iped/graph/neo4j/Neo4jGraphServiceProvider.java`: ler o manifesto do próprio jar (`ProtectionDomain` → `JarFile`, UTF-8) para `getSpiVersion()`/`getPluginVersion()`/`getBuiltForIpedVersion()`; `getDisplayName()="iped-graph-neo4j"`; `validate()` exige `<pasta do jar>/iped-graph-neo4j/neo4j/iped-graph-server-*.jar` e ≥1 `neo4j-*.jar` (mensagens com o caminho esperado); `createGraphService()` → `Neo4jGraphService`; `createDatabaseGenerator()` → `Neo4jDatabaseGenerator`; expor `static Path pluginHome()` para o lançador (depende de T022, T023).
- [ ] T048 [P] [US2] Criar `iped-graph-neo4j/src/main/resources/META-INF/services/iped.engine.graph.GraphServiceProvider` com a linha `iped.graph.neo4j.Neo4jGraphServiceProvider`.
- [ ] T049 [US2] Em `iped-graph-neo4j/src/main/java/iped/graph/neo4j/Neo4jChildLauncher.java`, substituir `getNeo4jLibDir()` (que resolvia `lib/neo4j` relativo ao jar do engine) por resolução `<pasta do jar do plugin>/iped-graph-neo4j/neo4j/` (via `Neo4jGraphServiceProvider.pluginHome()`); manter `ADD_OPENS` e `-XX:+IgnoreUnrecognizedVMOptions`; `IOException` descritiva se a pasta não existir (depende de T047).
- [ ] T050 [US2] Em `iped-engine/src/main/java/iped/engine/core/Manager.java`, no bloco que copia `lib/`, `scripts/`, `jre/`, `tools/` para o caso novo (~l.862), adicionar: se `GraphPluginRegistry.get().isAvailable()`, copiar `getPluginJar()` e a pasta `getPluginHome()` para `new File(output.getParentFile(), "plugins")` (= `<caso>/plugins/`, irmã de `<caso>/iped/`), criando a pasta, com log `INFO`; nada copiado quando ausente (FR-010, R-06).
- [ ] T051 [US2] Estender `iped-engine/src/test/java/iped/engine/graph/GraphPluginRegistryTest.java` com provedores de teste (fixtures em `iped-engine/src/test/java/iped/engine/graph/testproviders/` + arquivos `META-INF/services` em pastas de recursos de teste carregadas por `URLClassLoader` isolado): `AVAILABLE`, `MULTIPLE_COPIES` (dois provedores), `SPI_MISMATCH` (`getSpiVersion()==2`), `INCOMPLETE` (`validate()` lança), `LOAD_ERROR` (classe do serviço inexistente) — asserções sobre `getState()`, `getRejection()`, `getDetail()` e `getProvider()`.
- [ ] T052 [P] [US2] Criar `iped-graph-neo4j/src/test/java/iped/graph/neo4j/Neo4jGraphServiceProviderTest.java`: `getSpiVersion() == GraphServiceProvider.CURRENT_SPI_VERSION`; `validate()` aceita layout completo e rejeita (mensagem com caminho) pasta `neo4j/` ausente ou sem `iped-graph-server-*.jar`, usando pastas temporárias e um `pluginHome()` injetável.
- [ ] T053 [US2] Criar `iped-graph-neo4j/src/test/java/iped/graph/neo4j/Neo4jPluginPackageIT.java` (perfil Maven `graph-it` em `iped-graph-neo4j/pom.xml`, `maven-failsafe-plugin`): `assumeTrue(System.getenv("IPED_GRAPH_PLUGIN_HOME") != null)`; a partir do pacote extraído, iniciar o `GraphServer` pelo `Neo4jChildLauncher` em pasta temporária, executar `RETURN 1` com o driver relocado, parar o processo — valida a relocação (R-05); documentar o plano B (jars soltos) em `research.md` se falhar.
- [ ] T054 [US2] Validar instalação ([quickstart §3](quickstart.md)): `mvn -pl iped-graph-neo4j -am package`, extrair o zip em `<dist>/plugins/` do release de T041 cronometrando os 3 passos, reiniciar e conferir `Graph plugin loaded: iped-graph-neo4j … (SPI v1 …)` nos logs de processamento e de UI; registrar em `validation-results.md` (SC-003, FR-002, FR-003).
- [ ] T055 [US2] Validar processamento com plugin ([quickstart §4](quickstart.md) passo 8): processar a evidência de referência; conferir `iped/neo4j/csv` e `data`; comparar contagens de nós/relacionamentos e os CSVs com o baseline de T004; registrar (SC-004, FR-009).
- [ ] T056 [US2] Validar UI com plugin ([quickstart §4](quickstart.md) passos 9, 10, 12): roteiro fixo de 10 operações na aba "Vínculos" vs baseline; `--append` e `-remove` atualizando o grafo; multi-caso com 2 casos (grafo combinado); registrar (SC-004, FR-009).
- [ ] T057 [US2] Validar cópia autocontida e remoção ([quickstart §4](quickstart.md) passos 11 e 13): conferir `<caso>/plugins/iped-graph-neo4j-*.jar` + pasta; abrir o caso pelo `IPED-SearchApp` do próprio caso em pasta/máquina sem o plugin instalado → aba disponível; remover o plugin da distribuição e repetir T042/T043 → comportamento de US1 sem erros residuais; registrar (FR-010, FR-015, SC-008).

**Checkpoint**: US1 e US2 funcionam de forma independente (com e sem plugin).

---

## Phase 5: User Story 3 — Compatibilidade com casos e configurações existentes (Priority: P3)

**Goal**: casos antigos com dados de vínculos abrem sem o plugin com aviso único não bloqueante; modificar esses casos sem o plugin é recusado salvo `--ignore-missing-graph-plugin`; `--restart` sem plugin apaga `neo4j/`; perfis/configs antigos carregam sem edição (FR-005, FR-007, FR-008).

**Independent Test**: abrir o caso-baseline (com grafo) sem o plugin → diálogo modeless uma vez, sem erros; `--append` recusado com mensagem e exit ≠ 0; com a flag prossegue com avisos; perfis da versão anterior carregam. Ver [quickstart.md §4](quickstart.md) passos 4–6, 14, 16–17.

### Implementation for User Story 3

- [ ] T058 [US3] Em `iped-engine/src/main/java/iped/engine/CmdLineArgs.java`, adicionar `boolean isIgnoreMissingGraphPlugin();` (Javadoc em inglês).
- [ ] T059 [US3] Em `iped-app/src/main/java/iped/app/processing/CmdLineArgsImpl.java`, adicionar `@Parameter(names = "--ignore-missing-graph-plugin", description = "proceed with --append/--continue/-remove on a case that has graph data even if the graph plugin is not installed (the graph will not be updated)")` + getter; incluir em `saveIntoCaseData` como os demais flags booleanos (contrato [cli-missing-plugin §C](contracts/cli-missing-plugin.contract.md)).
- [ ] T060 [US3] Criar `iped-engine/src/main/java/iped/engine/graph/GraphPluginGuard.java`: `static boolean hasLinkData(File output)` (`neo4j/data` ou `neo4j/csv`); `static void checkCaseModification(CmdLineArgs args, File output)` — se `(isAppendIndex() || isContinue() || getEvidenceToRemove()!=null) && hasLinkData && !registry.isAvailable()`: sem flag → `throw new IPEDException(Messages.getString("GraphPlugin.MissingForCaseModification"))`; com flag → `WARN` no log e `LOGGER.log(CONSOLE, Messages.getString("GraphPlugin.ProceedingWithoutUpdate"))`; `static void handleRestart(CmdLineArgs args, File output)` — se `isRestart() && !registry.isAvailable()` apagar `output/neo4j` (`IOUtil.deleteDirectory`) com log `INFO`; tabela B do contrato (depende de T058).
- [ ] T061 [US3] Em `iped-engine/src/main/java/iped/engine/core/Manager.java`: chamar `GraphPluginGuard.checkCaseModification(args, output)` e `GraphPluginGuard.handleRestart(args, output)` no início de `process()`, **antes** do bloco `if (args.isContinue() || args.isRestart())` (~l.251) e de `removeEvidence` (~l.259/277); em `removeEvidence` (~l.486–502), só abrir o `GraphService` quando `GraphPluginRegistry.get().isAvailable()`, senão `WARN` "graph not updated" e seguir para a limpeza dos CSVs (`GraphFileWriter.removeDeletedRelationships`), que permanece (depende de T060).
- [ ] T062 [P] [US3] Adicionar `GraphPlugin.MissingForCaseModification` e `GraphPlugin.ProceedingWithoutUpdate` (textos do contrato [cli-missing-plugin §B](contracts/cli-missing-plugin.contract.md)) em `iped-app/resources/localization/iped-engine-messages.properties` (EN) e `iped-engine-messages_pt_BR.properties` (PT-BR), com tradução best-effort em `_de_DE`, `_es_AR`, `_fr_FR`, `_it_IT`.
- [ ] T063 [P] [US3] Adicionar `GraphPlugin.Notice.Title` e `GraphPlugin.Notice.Text` (com `{0}` para o detalhe do registro; textos do contrato [ui-degradation](contracts/ui-degradation.contract.md)) em `iped-app/resources/localization/iped-desktop-messages.properties` e `_pt_BR.properties`, com tradução best-effort em `_de_DE`, `_es_AR`, `_fr_FR`, `_it_IT`.
- [ ] T064 [US3] Criar `iped-app/src/main/java/iped/app/ui/GraphPluginNotice.java`: `static boolean shouldShow(List<IPEDSource> sources, GraphPluginState state)` (lógica pura: `state != AVAILABLE` e algum `new File(source.getModuleDir(), GraphTask.DB_DATA_PATH).exists()`); `static void showIfNeeded(IPEDMultiSource appCase)` — uma vez por sessão (`static AtomicBoolean`), `JDialog` `ModalityType.MODELESS` com ícone informativo, texto `MessageFormat` com `GraphPluginRegistry.get().getDetail()` e botão OK, via `SwingUtilities.invokeLater`; log `INFO` (depende de T063).
- [ ] T065 [US3] Em `iped-app/src/main/java/iped/app/ui/UICaseDataLoader.java` (ponto de T036), quando `App.get().appGraphAnalytics == null`, chamar `GraphPluginNotice.showIfNeeded(App.get().appCase)` (depende de T064).
- [ ] T066 [P] [US3] Criar `iped-engine/src/test/java/iped/engine/graph/GraphPluginGuardTest.java`: matriz da tabela B do contrato (append/continue/remove × com/sem `neo4j/data` ou `csv` × plugin AVAILABLE/ABSENT via `resetForTesting` × flag) usando um `CmdLineArgs` de teste e pastas temporárias; `handleRestart` apaga `neo4j/` só sem plugin; `yaraOnly`/caso novo sem efeito.
- [ ] T067 [P] [US3] Criar `iped-app/src/test/java/iped/app/ui/GraphPluginNoticeTest.java` para `shouldShow` (fontes com/sem `neo4j/data`, estados `ABSENT`/`REJECTED`/`AVAILABLE`, multi-fonte) — sem Swing.
- [ ] T068 [US3] Validar aviso ([quickstart §4](quickstart.md) passos 4 e 12-sem-plugin): abrir o caso-baseline (com grafo) sem o plugin → diálogo modeless exibido uma vez, UI utilizável, 0 erros; abrir multi-caso com um caso com grafo → aviso único; registrar (FR-007).
- [ ] T069 [US3] Validar bloqueio e override ([quickstart §4](quickstart.md) passos 5, 6, 16, 17): `--append` no caso-baseline sem plugin → mensagem e exit ≠ 0, caso intocado; com `--ignore-missing-graph-plugin` → prossegue com `WARN` em log e console; `-remove` idem; `--restart` sem plugin → `iped/neo4j/` removido; plugin corrompido / duas cópias / `IPED-Graph-SPI-Version: 2` → rejeitado com motivo no log e IPED segue; registrar (FR-008, FR-011, FR-012).
- [ ] T070 [US3] Validar configurações legadas ([quickstart §4](quickstart.md) passo 14): copiar `IPEDConfig.txt`, `conf/TaskInstaller.xml` (com `iped.engine.graph.GraphTask`), `conf/GraphConfig.json` e um perfil customizado do release-baseline para o release novo; processar com e sem o plugin → sem edição e sem erro; registrar (SC-007, FR-005).

**Checkpoint**: todas as stories funcionais e independentes.

---

## Phase 6: User Story 4 — Distribuir o IPED e o plugin com licenciamento claro (Priority: P4)

**Goal**: avisos de terceiros e licença coerentes (núcleo sem Neo4j; plugin com os seus), documentação do plugin opcional, CI verificando o release e publicando o zip (FR-013, FR-014).

**Independent Test**: `mvn -B clean package` em ambiente sem o plugin passa; inventário vazio; `ThirdParty.txt` do núcleo sem Neo4j; zip do plugin contém `LICENSE.txt` + `ThirdParty.txt`; README/notas descrevem o plugin; CI verde na branch. Ver [quickstart.md §4](quickstart.md) passo 1 e [release-inventory](contracts/release-inventory.contract.md).

### Implementation for User Story 4

- [ ] T071 [P] [US4] Em `ThirdParty.txt` (raiz): remover as entradas "Neo4j Community Edition 5.26.0" e "Neo4j Java Driver 5.26.0"; na entrada "ANTLR 4 Runtime" remover a menção a `lib/neo4j/`/4.13.2; adicionar nota "Link analysis (graph) is provided by the optional plugin iped-graph-neo4j, distributed separately with its own LICENSE.txt and ThirdParty.txt".
- [ ] T072 [P] [US4] Criar `iped-graph-neo4j/src/plugin-home/LICENSE.txt` (cópia de `licenses/GPL 3.txt`) e `iped-graph-neo4j/src/plugin-home/ThirdParty.txt` (Neo4j CE 5.26.0 GPLv3; neo4j-java-driver 5.26.0 Apache-2.0; Netty Apache-2.0; reactor-core Apache-2.0; reactive-streams MIT-0; antlr4-runtime 4.13.x BSD-3; demais transitivas listadas por `mvn -pl iped-graph-server dependency:list`), no formato do `ThirdParty.txt` do IPED.
- [ ] T073 [P] [US4] Em `README.md` (raiz, ~l.85): "Graph analysis for communications (calls, emails, instant messages...) — via the optional iped-graph-neo4j plugin"; acrescentar parágrafo curto "Optional plugins" apontando para `plugins/` e para o README do plugin.
- [ ] T074 [P] [US4] Em `ReleaseNotes.txt`, adicionar no topo a entrada `#spec/010-neo4j-optional-plugin:` (formato da entrada 003) resumindo: núcleo sem Neo4j, SPI v1, plugin separado, comportamento sem plugin (aviso, aba oculta, bloqueio + `--ignore-missing-graph-plugin`), cópia no caso, compatibilidade por versão do SPI.
- [ ] T075 [P] [US4] Em `iped-app/resources/plugins/Readme.txt`, adicionar a linha "- iped-graph-neo4j-<version>.jar + iped-graph-neo4j/ (GPL license): enables link analysis (graph) — download the plugin zip published with each IPED release and extract it in this folder".
- [ ] T076 [US4] Em `.github/workflows/maven.yml`, após "Build with Maven": passo "Verify no Neo4j in standard release" (`find target/release -iname '*neo4j*'` deve ser vazio; `grep -rl "import org.neo4j" iped-engine/src iped-app/src` deve ser vazio; falhar caso contrário) e passo "Upload graph plugin" (`actions/upload-artifact@v4`, `name: iped-graph-neo4j-${{ github.sha }}`, `path: iped-graph-neo4j/target/iped-graph-neo4j-*.zip`).
- [ ] T077 [P] [US4] Atualizar `CLAUDE.md` (raiz): §2 (módulo `iped-graph-neo4j`), §3 (linha Graph: SPI em `iped-engine`, plugin separado), §6 (layout: `plugins/` é **irmã** de `iped-<versão>/`; `lib/neo4j/` não existe mais), §8 (tabela de módulos), §10 (onde editar: SPI/registro/guard no engine, implementação no plugin), §13 (histórico: spec 010).
- [ ] T078 [P] [US4] Atualizar `iped-engine/CLAUDE.md` (seção graph: tipos neutros, `GraphServiceProvider`, `GraphPluginRegistry`, `GraphPluginGuard`, `GraphTask` gating; remover a linha de dependência `neo4j-graphdb-api`/`neo4j-java-driver`), `iped-app/CLAUDE.md` (seção `graph/` retipada; `App` condicional; `GraphPluginNotice`; pasta de plugins irmã; `copy-plugin-resources`) e criar `iped-graph-neo4j/CLAUDE.md` (papel, layout do pacote, build/shade, lançador, testes, plano B).
- [ ] T079 [US4] Validar distribuição ([quickstart §4](quickstart.md) passo 1): em ambiente limpo sem o plugin instalado, `mvn -B clean package` verde (enforcer + testes), inventário vazio, zip do plugin com `LICENSE.txt`/`ThirdParty.txt`/`README.md`/`plugin.properties`, CI da branch verde com o artefato do plugin publicado; registrar (SC-009, FR-013, FR-014).

**Checkpoint**: release e plugin publicáveis com licenciamento e documentação coerentes.

---

## Phase 7: Polish & Cross-Cutting Concerns

**Purpose**: limpeza, medições e verificação final transversal.

- [ ] T080 [P] Procurar referências obsoletas (`grep -rn "lib/neo4j\|GraphGenerator\b\|DynLabel\|DynRelationshipType\|neo4j-graphdb-api\|GraphServiceImpl" --include=*.java --include=*.md --include=*.xml --include=*.txt .` fora de `target/` e `specs/`) e corrigir comentários/docs em `iped-engine/`, `iped-app/`, `iped-graph-server/`, `ThirdParty.txt`.
- [ ] T081 [P] Medir tempo de inicialização (CLI `-nogui` e UI) com e sem plugin vs. release-baseline, 3 execuções cada; registrar em `specs/010-neo4j-optional-plugin/validation-results.md` (SC-006, < 1 s).
- [ ] T082 [P] Smoke Linux (WSL Ubuntu): build, instalação do plugin em `<dist>/plugins/`, processamento com/sem plugin, UI com/sem plugin ([quickstart §4](quickstart.md) passo 18); registrar (FR-017).
- [ ] T083 Revisão de conformidade com a constituição no código novo/movido (`iped-engine/.../graph/*`, `iped-graph-neo4j/**`, `iped-app/.../ui/GraphPluginNotice.java`, `GraphPluginGuard`): comentários/Javadoc em inglês, charset explícito ao ler manifesto/`plugin.properties`, somente SLF4J, EDT via `invokeLater`; rodar `mvn -B -pl iped-engine,iped-app,iped-graph-neo4j test` e anexar o resumo em `validation-results.md`.
- [ ] T084 Consolidar `specs/010-neo4j-optional-plugin/validation-results.md` com a matriz completa do [quickstart §4](quickstart.md) (18 passos, status/evidência) e atualizar as notas de `specs/010-neo4j-optional-plugin/checklists/requirements.md` com o resultado final.

---

## Dependencies & Execution Order

### Phase Dependencies

- **Setup (Phase 1)**: sem dependências; T004 (baseline) deve rodar **antes** de qualquer mudança de código (usa o build atual).
- **Foundational (Phase 2)**: depende de T001–T003 — **bloqueia todas as stories**; termina no checkpoint T030.
- **US1 (Phase 3)**: depende de T030. É o MVP.
- **US2 (Phase 4)**: depende de T030; T054–T057 pressupõem o release de T041 (US1) para instalar o plugin.
- **US3 (Phase 5)**: depende de T030; T065 toca o mesmo ponto de T036 (US1) e T061 o `Manager` já tocado por T050 (US2) — coordenar se feitas em paralelo.
- **US4 (Phase 6)**: depende de T030; T079 pressupõe T041 e T044 (release + zip).
- **Polish (Phase 7)**: depende das stories desejadas.

### User Story Dependencies

- **US1**: independente (só Foundational).
- **US2**: independente de US1 em código; a validação usa o release de US1.
- **US3**: independente em código; validação usa o caso-baseline (T004) e, para o override, o release de US1.
- **US4**: majoritariamente docs/build; validação final depende de US1 (release) e US2 (zip).

### Within Each Story

- Modelo/SPI antes de serviço; serviço antes de integração (`Manager`, `App`); testes de unidade junto da implementação; validações manuais ao final de cada story com registro em `validation-results.md`.

### Parallel Opportunities

- Phase 2: T005–T013 (9 arquivos novos) em paralelo; T017, T021, T023 em paralelo; T025–T028 (UI) em paralelo após T016/T017.
- US1: T032, T033, T035, T036, T037, T038, T039, T040 em paralelo após T031/T034.
- US2: T045, T046, T048 em paralelo com T044/T047; T052 em paralelo com T051.
- US3: T062, T063 em paralelo com T058–T061; T066, T067 em paralelo.
- US4: T071–T075, T077, T078 todos em paralelo; T076 isolado.

---

## Parallel Example: Phase 2 (tipos neutros)

```bash
# Nove arquivos independentes em iped-engine/src/main/java/iped/engine/graph/:
Task: "T005 GraphLabel.java"
Task: "T006 GraphRelationshipType.java"
Task: "T007 GraphEntity.java"
Task: "T008 GraphNode.java"
Task: "T009 GraphRelationship.java"
Task: "T010 GraphPath.java"
Task: "T011 GraphQueryException.java + GraphPluginException.java"
Task: "T012 GraphDatabaseGenerator.java"
Task: "T013 GraphPluginState.java + GraphPluginRejection.java"
```

## Parallel Example: User Story 1

```bash
# Após T031 (GraphTask) e T034 (App):
Task: "T032 Main.java — resolver registro"
Task: "T033 AppMain.java — resolver registro"
Task: "T035 MenuClass/MenuListener — guards"
Task: "T036 UICaseDataLoader — guard"
Task: "T037 iped-app/pom.xml — remover zip/unpack"
Task: "T038 enforcer em iped-engine/iped-app"
Task: "T039 GraphPluginRegistryTest (ABSENT)"
Task: "T040 GraphFileWriterTest (golden)"
```

---

## Implementation Strategy

### MVP First (User Story 1)

1. Phase 1 (T001–T004) — inclusive o **baseline** antes de tocar código.
2. Phase 2 (T005–T030) — núcleo sem Neo4j compilando; plugin compilando.
3. Phase 3 (T031–T043) — release sem Neo4j, funcional sem o plugin.
4. **STOP e validar** US1 (inventário, paridade, smoke).

### Incremental Delivery

1. US2 (T044–T057): o plugin devolve a análise de vínculos → validar com e sem plugin.
2. US3 (T058–T070): compatibilidade/bloqueio/aviso → validar com o caso-baseline.
3. US4 (T071–T079): docs, licenças, CI → validar build limpo + CI.
4. Polish (T080–T084).

### Riscos a vigiar

- **R-05 (relocação do driver)**: validar cedo com T053; se falhar, adotar o plano B (jars soltos em `plugins/`) ajustando T044/T045 e os contratos.
- **Paridade dos CSVs**: T040 (golden) protege o determinismo (Princípio IV) antes de qualquer validação manual.
- **Toques em `Manager`/`App`**: T050/T061 e T034/T065 editam os mesmos arquivos — fazer em sequência ou rebase cuidadoso.

---

## Notes

- [P] = arquivos diferentes, sem dependências pendentes.
- Todo resultado de validação manual vai para `specs/010-neo4j-optional-plugin/validation-results.md`.
- Commit por tarefa ou grupo lógico; parar em cada checkpoint para validar a story de forma independente.
- Evitar: tarefas vagas, conflitos no mesmo arquivo, dependências cruzadas que quebrem a independência das stories.
