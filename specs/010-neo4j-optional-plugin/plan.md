# Implementation Plan: Neo4j como plugin opcional do IPED

**Branch**: `010-neo4j-optional-plugin` | **Date**: 2026-10-22 | **Spec**: [spec.md](spec.md)

**Input**: Feature specification from `specs/010-neo4j-optional-plugin/spec.md`

## Summary

Tornar a análise de vínculos (grafo Neo4j) um **plugin opcional**: a distribuição padrão do IPED deixa de conter qualquer componente do Neo4j e funciona integralmente sem ele; com o plugin instalado na pasta de plugins já existente, tudo se comporta como hoje. Abordagem técnica (modelo híbrido decidido em Clarifications): (1) o núcleo mantém o módulo de análise de vínculos — modelagem/CSVs (`GraphTask`, `GraphFileWriter`), consultas de vínculos e a aba "Vínculos" — reescrito contra **tipos próprios** (`GraphLabel`, `GraphNode`, `GraphRelationship`, `GraphPath`…) e um **ponto de extensão** (`GraphServiceProvider` via `ServiceLoader`, versionado, `CURRENT_SPI_VERSION = 1`); (2) um **novo módulo `iped-graph-neo4j`** empacota a implementação Neo4j do serviço (Bolt), a importação/geração e, numa subpasta isolada, o motor Neo4j 5.26 (reutilizando o zip de `iped-graph-server`), publicado como zip separado do release; (3) **degradação explícita** sem o plugin — etapa de grafo pulada com um único aviso, aba e ações ocultas na UI, aviso modeless quando o caso tem dados de vínculos, bloqueio de `--append`/`--continue`/remoção em casos com grafo salvo override `--ignore-missing-graph-plugin`; (4) **garantias de build** (enforcer banindo `org.neo4j:*`, CI verificando o release) e **documentação/licenciamento** (ThirdParty, README, notas, CLAUDE.md, avisos próprios do plugin). Detalhes em [research.md](research.md).

## Technical Context

**Language/Version**: Java 21 LTS (`maven.compiler.release = 21`), inalterado. Descoberta de plugin via `java.util.ServiceLoader` (JDK).

**Primary Dependencies**:
- **Removidas do núcleo** (`iped-engine/pom.xml`): `org.neo4j:neo4j-graphdb-api` 5.26.0 e `org.neo4j.driver:neo4j-java-driver` 5.26.0 (e, por transitividade, netty/reactor/reactive-streams em `lib/`). **Removidas do `iped-app/pom.xml`**: dependência zip `iped:iped-graph-server:neo4j` e a execução `unpack-graph-server` (fim de `lib/neo4j/`).
- **Novo módulo `iped-graph-neo4j`**: `iped-engine` (`provided`), `neo4j-java-driver` 5.26.0 + deps obrigatórias (relocadas via `maven-shade-plugin` 3.6.x), zip de `iped-graph-server` (Neo4j CE 5.26.0 + antlr 4.13.2) descompactado em `iped-graph-neo4j/neo4j/` pelo `maven-dependency-plugin`, zip final pelo `maven-assembly-plugin`.
- **Guardas de build**: `maven-enforcer-plugin` 3.5.x (`bannedDependencies` `org.neo4j:*`, `org.neo4j.driver:*`) em `iped-engine` e `iped-app`.
- `iped-graph-server`: código e POM **inalterados** (seu zip passa a ser consumido pelo plugin).

**Storage**: formato do caso inalterado — `iped/neo4j/csv/` (CSVs) e `iped/neo4j/data/` (banco Neo4j 5) continuam nos mesmos caminhos (`GraphTask.CSVS_PATH`/`DB_DATA_PATH`); índice Lucene intocado. **Novo**: cópia do plugin em `<caso>/plugins/` (irmã de `<caso>/iped/`, FR-010). Grafo multi-caso em `iped-multicases/multicase-<hash>/graph` inalterado.

**Testing**: JUnit 4.13.2 + Hamcrest + Mockito via `maven-surefire-plugin` (padrão do repositório). Novos: `GraphPluginRegistryTest`, `GraphPluginGuardTest`, `GraphFileWriterTest` (golden CSV) em `iped-engine`; `Neo4jGraphServiceProviderTest` e `Neo4jImportRunnerTest` (linhas de CSV curtas completadas antes da importação, R-15 — adicionado após a rodada de regressão de 2026-10-23) em `iped-graph-neo4j`; teste de integração **gated** (`assumeTrue(IPED_GRAPH_PLUGIN_HOME)`) que sobe o `GraphServer` a partir do pacote montado — valida a relocação do driver (R-05) —, e outro (`Neo4jImportRunnerIT`, T092) que importa CSVs com linhas completadas pelo `neo4j-admin` real. Matriz manual no [quickstart.md](quickstart.md) mapeada a SC-001..SC-009, mais a rodada com imagem grande × 4.3.1 (passo 19).

**Target Platform**: Windows x64 e Linux x64 (mesmo pacote de plugin — só Java; FR-017).

**Project Type**: aplicação forense desktop (Swing/JavaFX) + CLI de processamento; Maven multi-módulo (8 → **9 módulos**).

**Performance Goals**: sobrecarga de inicialização da detecção do plugin < 1 s (SC-006; resolução única por JVM); paridade forense 100% nos campos definidos na feature 003 ao processar sem o plugin (SC-002); paridade do grafo (contagens e 10 operações) com o plugin (SC-004).

**Constraints**:
- **Zero Neo4j no núcleo** (FR-001): nem binários, nem `import org.neo4j` no fonte de `iped-engine`/`iped-app` — garantido por enforcer + CI (R-10).
- **Isolamento de processo preservado** (FR-009, Princípio V): motor Neo4j continua em JVM filha com classpath isolado (`<home do plugin>/neo4j/*`).
- **Compatibilidade de configuração** (FR-005): `IPEDConfig.txt`, `TaskInstaller.xml` (continua listando `GraphTask`), `GraphConfig.json` e perfis **não mudam**.
- **Sem mudança em `iped-api`** (Princípio I): SPI em `iped-engine`; `CmdLineArgs` (engine) ganha um método.
- **UX inalterada com o plugin** (FR-009); sem novas funcionalidades de grafo.
- Mensagens novas localizadas PT-BR + EN (FR-016); logs técnicos em inglês.

**Scale/Scope**: `iped-engine/.../graph`: ~9 classes migram para o plugin (`GraphServiceImpl`, `Bolt*` ×4, `GraphGenerator` impl, `GraphImportRunner`, `Neo4jChildLauncher`), 2 removidas (`DynLabel`, `DynRelationshipType`), ~8 novas (SPI, tipos, registro, guard), ~10 ajustadas (interface, listeners, `GraphTask`, `GraphFileWriter`, fachada); `iped-app`: ~12 classes de `app/graph` retipadas + `App`, `MenuClass`, `MenuListener`, `UICaseDataLoader`, `CmdLineArgsImpl`, nova `GraphPluginNotice`; `Manager` (engine): guard, cópia, remoção; 4 POMs (pai, engine, app, novo módulo); CI; 2 bundles de mensagens; docs (README, ThirdParty, ReleaseNotes, 4 CLAUDE.md, README/ThirdParty/LICENSE do plugin).

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

| Princípio | Avaliação | Status |
|---|---|---|
| **I. Estabilidade da API Pública** | Nenhuma interface de `iped-api` é tocada; o SPI nasce em `iped-engine` (R-02). Chaves Lucene (`BasicProps`/`IndexItem`), `AppAnalyzer` e o formato do caso (`iped/neo4j/*`) permanecem inalterados. `iped.engine.CmdLineArgs` (engine, não API) ganha `isIgnoreMissingGraphPlugin()`. | ✅ PASS |
| **II. Extensão Modular em vez de Modificação** | A feature é majoritariamente **extensão**: novo módulo, novo SPI, novo registro/guard/aviso. Modificações inevitáveis em componentes núcleo: `Manager` (guard antes de escrever, cópia do plugin para o caso, remoção de evidência sem plugin) e `App`/`MenuClass`/`UICaseDataLoader` (montagem condicional da UI), além da troca de tipos em `GraphTask`/`GraphFileWriter`/listeners. Sem mudança de lógica em `Worker`/`ProcessingQueues`/`IndexWriter`. Justificado em Complexity Tracking. | ⚠️ PASS c/ justificativa |
| **III. Configuração antes de Código** | Nenhum caminho/limite hardcoded: pasta de plugins vem de `PluginConfig` (`LocalConfig.txt`); a chave `enableGraphGeneration` mantém arquivo e default, com semântica "gerar quando disponível"; override exposto como flag de CLI; mensagens de UI/console em bundles PT-BR + EN. Perfis não precisam de edição (FR-005). | ✅ PASS |
| **IV. Integridade Forense e Determinismo** | Charset explícito (UTF-8) ao ler manifesto/`plugin.properties`; logging SLF4J; CSVs gravados pelo `GraphFileWriter` byte-a-byte iguais aos atuais (teste golden) — o plugin só completa com campos vazios, antes da importação, as linhas mais curtas que o cabeçalho, sem alterar valores (R-15, pós-implementação); processamento sem o plugin produz os mesmos campos forenses (SC-002). Bloqueio de modificação sem o plugin evita grafo silenciosamente desatualizado; override é explícito e logado. `SleuthkitClient` intocado. | ✅ PASS |
| **V. Concorrência e Isolamento de Processo** | `GraphTask` continua uma instância por worker (writer estático compartilhado como hoje); o motor Neo4j segue **fora do processo** (JVM filha com classpath isolado), agora lançado pelo plugin; aviso da UI na EDT (`invokeLater`); registro resolvido uma vez (sem contenção). | ✅ PASS |

**Restrições de Build/Distribuição**: as dependências do módulo do plugin continuam registradas no `ThirdParty.txt` raiz (seção "Optional plugin … NOT bundled", exigência da constituição para toda dependência do repositório) **e** no `ThirdParty.txt` + `LICENSE.txt` **do plugin**; `licenses/GPL 3.txt` já existe e `licenses/MIT-0.txt` (reactive-streams) é adicionado; `.github/workflows/maven.yml` atualizado no mesmo PR (verificação do release + publicação do zip do plugin); novo módulo no reactor do POM pai (construído por padrão; `iped-app` não depende dele — FR-014).

**Resultado do gate**: PASS, condicionado a (a) enforcer + verificação de CI verdes (SC-001), (b) teste gated de integração do pacote (relocação do driver, R-05), (c) matriz do quickstart executada. Sem violação que exija abandonar princípio.

## Project Structure

### Documentation (this feature)

```text
specs/010-neo4j-optional-plugin/
├── plan.md                                   # Este arquivo (/speckit-plan)
├── research.md                               # Fase 0: R-01..R-14 (divisão núcleo×plugin, SPI, registro, pacote, shade,
│                                             #   pasta de plugins/cópia no caso, degradação, guard/flag, UI, enforcer, docs, testes; R-15: linhas CSV curtas no import do Neo4j 5)
├── data-model.md                             # Fase 1: GraphPlugin (estados), SPI, pacote, pasta de plugins, dados do caso,
│                                             #   tipos de modelo, config/CLI, mensagens
├── quickstart.md                             # Fase 1: build, instalação, matriz de validação SC-001..SC-009
├── contracts/
│   ├── graph-spi.contract.md                 # Ponto de extensão: interfaces, tipos, estados do registro, versionamento
│   ├── plugin-package.contract.md            # Layout do zip, manifesto, instalação/remoção/atualização, cópia no caso
│   ├── cli-missing-plugin.contract.md        # Degradação no processamento, matriz de bloqueio, flag, mensagens
│   ├── ui-degradation.contract.md            # UI com/sem plugin, aviso modeless único
│   └── release-inventory.contract.md         # Invariante "zero Neo4j" e seu enforcement (enforcer, CI, manual)
├── checklists/
│   └── requirements.md                       # (de /speckit-specify)
└── tasks.md                                  # Fase 2 (/speckit-tasks — NÃO criado aqui)
```

### Source Code (repository root)

```text
pom.xml                                                   # <module>iped-graph-neo4j</module> (após iped-graph-server)

iped-engine/pom.xml                                       # remover neo4j-graphdb-api + neo4j-java-driver; maven-enforcer bannedDependencies
iped-engine/src/main/java/iped/engine/graph/
├── GraphServiceProvider.java        (novo)               # SPI (ServiceLoader), CURRENT_SPI_VERSION = 1
├── GraphDatabaseGenerator.java      (novo)               # interface (absorve GraphGenerator.generate + ImportListener)
├── GraphLabel.java, GraphRelationshipType.java (novos)   # substituem DynLabel / DynRelationshipType (Label/RelationshipType)
├── GraphEntity.java, GraphNode.java, GraphRelationship.java, GraphPath.java (novos)  # modelo neutro (GraphNode inclui getDegree())
├── GraphQueryException.java, GraphPluginException.java (novos)
├── GraphPluginRegistry.java         (novo)               # descoberta, validação, estados ABSENT/AVAILABLE/REJECTED, log único
├── GraphPluginGuard.java            (novo)               # bloqueio append/continue/remove sem plugin; --restart apaga neo4j/
├── GraphService.java                (mod.)               # tipos neutros
├── {Node,Edge,Link,Path,NodeEdge,Connection,Label,Free}QueryListener.java (mod.)     # tipos neutros
├── GraphServiceFactoryImpl.java     (mod.)               # fachada → registro (IllegalStateException se indisponível)
├── GraphTask.java                   (mod.)               # GraphLabel/GraphRelationshipType; enabled = config && plugin; 1 WARN; finish via SPI
├── GraphFileWriter.java             (mod.)               # assinaturas com GraphLabel/GraphRelationshipType (CSV idêntico)
├── GraphServiceImpl.java, BoltEntity/BoltNode/BoltRelationship/BoltPath.java,
│   GraphGenerator.java (impl), GraphImportRunner.java, Neo4jChildLauncher.java   → MOVIDOS para iped-graph-neo4j
└── DynLabel.java, DynRelationshipType.java               → REMOVIDOS
iped-engine/src/main/java/iped/engine/CmdLineArgs.java    # + isIgnoreMissingGraphPlugin()
iped-engine/src/main/java/iped/engine/core/Manager.java   # guard no início de process(); cópia do plugin → <caso>/plugins/;
                                                          #   removeEvidence: pular exclusão no grafo sem plugin (CSV continua)
iped-engine/src/test/java/iped/engine/graph/              # GraphPluginRegistryTest, GraphPluginGuardTest, GraphFileWriterTest (+ golden)

iped-graph-neo4j/                                         # NOVO MÓDULO (plugin)
├── pom.xml                                               # iped-engine provided; driver 5.26.0; shade (relocação); unpack do zip
│                                                         #   iped-graph-server em target/plugin-home/iped-graph-neo4j/neo4j; assembly zip
├── src/assembly/plugin.xml                               # layout: jar na raiz + iped-graph-neo4j/{neo4j,plugin.properties,LICENSE.txt,ThirdParty.txt,README.md}
├── src/main/java/iped/graph/neo4j/
│   ├── Neo4jGraphServiceProvider.java                    # SPI impl; lê manifesto; validate() (home relativa ao jar)
│   ├── Neo4jGraphService.java                            # ex-GraphServiceImpl (Bolt), exceções → GraphQueryException
│   ├── BoltEntity/BoltNode/BoltRelationship/BoltPath.java  # implementam GraphEntity/GraphNode/GraphRelationship/GraphPath
│   ├── Neo4jDatabaseGenerator.java                       # ex-GraphGenerator (importação + pós-importação)
│   ├── Neo4jImportRunner.java, Neo4jChildLauncher.java   # -cp <home>/neo4j/* ; add-opens preservados; padShortRows antes do neo4j-admin (R-15)
├── src/main/resources/META-INF/services/iped.engine.graph.GraphServiceProvider
├── src/plugin-home/{plugin.properties,LICENSE.txt,ThirdParty.txt,README.md}   # fora do jar; copiado para o zip
├── src/test/java/iped/graph/neo4j/                        # Neo4jGraphServiceProviderTest; Neo4jImportRunnerTest; Neo4jPluginPackageIT e Neo4jImportRunnerIT (gated)
└── CLAUDE.md                                             # docs do módulo

iped-graph-server/                                        # inalterado (zip consumido pelo plugin)

iped-app/pom.xml                                          # remover dep zip + unpack-graph-server + comentário excludeTypes; enforcer
iped-app/src/main/java/iped/app/graph/*.java              # retipar: AppGraphAnalytics, GraphModel, AddNodeWorker, AddRelationshipWorker,
                                                          #   AdvancedSearchDialog, EntitySearchDialog, ExportLinksWorker, SearchLinksDialog,
                                                          #   SearchLinksWorker, GraphSidePanel, ShowEvidenceNodeWorker, LoadGraphDatabaseWorker
iped-app/src/main/java/iped/app/ui/App.java               # criar appGraphAnalytics/graphDock/FilterSelectedEdges só se plugin disponível
iped-app/src/main/java/iped/app/ui/MenuClass.java         # item "adicionar ao grafo" condicional
iped-app/src/main/java/iped/app/ui/MenuListener.java      # guarda null
iped-app/src/main/java/iped/app/ui/UICaseDataLoader.java  # initGraphService() só com plugin; GraphPluginNotice.showIfNeeded()
iped-app/src/main/java/iped/app/ui/GraphPluginNotice.java (novo)   # JDialog modeless, uma vez por sessão
iped-app/src/main/java/iped/app/ui/AppMain.java           # GraphPluginRegistry.get() após loadConfigurables
iped-app/src/main/java/iped/app/processing/Main.java      # idem (processamento)
iped-app/src/main/java/iped/app/processing/CmdLineArgsImpl.java   # --ignore-missing-graph-plugin
iped-app/resources/localization/iped-engine-messages*.properties   # GraphPlugin.MissingForCaseModification, GraphPlugin.ProceedingWithoutUpdate
iped-app/resources/localization/iped-desktop-messages*.properties  # GraphPlugin.Notice.Title, GraphPlugin.Notice.Text
iped-app/resources/plugins/Readme.txt                     # mencionar o plugin de análise de vínculos

.github/workflows/maven.yml                               # passo "no neo4j in release"; grep de imports; upload do zip do plugin
ThirdParty.txt                                            # remover Neo4j CE / Neo4j Java Driver / antlr 4.13 em lib/neo4j; nota do plugin
README.md, ReleaseNotes.txt                               # análise de vínculos via plugin opcional
CLAUDE.md (raiz: §2 §3 §6 §8 §10 §13 + pasta de plugins irmã), iped-engine/CLAUDE.md (graph), iped-app/CLAUDE.md (graph/plugins)
```

**Structure Decision**: Maven multi-módulo existente + **um módulo novo** (`iped-graph-neo4j`) com pacote raiz próprio `iped.graph.neo4j` (evita split package com `iped.engine.graph`). O SPI e os tipos neutros ficam em `iped.engine.graph` (mesmo pacote de `GraphService`, menor churn). `iped-graph-server` permanece como módulo da pilha isolada. A UI continua em `iped-app`.

## Complexity Tracking

| Violation | Why Needed | Simpler Alternative Rejected Because |
|-----------|------------|-------------------------------------|
| Modificar `Manager` (componente núcleo, Princípio II) | O bloqueio FR-008 precisa ocorrer **antes de qualquer escrita** no caso (append/continue/remoção); a cópia FR-010 e a remoção de evidência já vivem no `Manager`. Mudança pequena (3 pontos), sem tocar invariantes de concorrência. | Fazer o guard em `GraphTask` — tarde demais (itens já em processamento). Fazer em `CmdLineArgsImpl` — não tem a pasta de saída resolvida nem o registro do plugin carregado. |
| Modificar `App`/`MenuClass`/`UICaseDataLoader` | FR-006 exige que nenhum elemento de grafo seja oferecido sem o plugin; a montagem do painel é feita nessas classes. `App` já trata `graphDock == null`. | Manter o painel e desabilitá-lo — descartado na spec (premissa "aba oculta"). |
| Fat jar com relocação (shade) no plugin | Mantém o "soltar na pasta" com um jar + uma pasta, detecção simples de cópias duplicadas e imunidade a conflitos de netty/reactor no classpath principal. | Jars soltos do driver em `plugins/` — funciona, mas espalha ~10 arquivos na pasta de plugins e complica a regra de unicidade; mantido como plano B (R-05). |

## Phase 0 — Research (concluída)

[research.md](research.md): R-01 divisão híbrida; R-02 SPI em `iped-engine` + tipos neutros; R-03 registro/estados/log; R-04 módulo e layout do pacote; R-05 shade/relocação do driver (com plano B); R-06 pasta de plugins irmã do app root e cópia para `<caso>/plugins/`; R-07 degradação da task e `--restart`; R-08 guard e `--ignore-missing-graph-plugin`; R-09 UI condicional e aviso modeless; R-10 enforcer + CI; R-11 licenciamento/docs; R-12 testes; R-13 Cypher opaco no núcleo; R-14 exceções/propriedades ausentes; R-15 (pós-implementação, rodada de regressão de 2026-10-23) completamento, no plugin, das linhas de CSV mais curtas que o cabeçalho antes da importação do Neo4j 5. Nenhum `NEEDS CLARIFICATION` remanescente.

## Phase 1 — Design & Contracts (concluída)

- [data-model.md](data-model.md): `GraphPlugin` (máquina de estados), `GraphServiceProvider`, `PluginPackage`, `PluginFolder`, `CaseLinkData`, tipos de modelo (mapeamento Neo4j → núcleo), configuração/CLI, mensagens.
- Contratos: [graph-spi](contracts/graph-spi.contract.md), [plugin-package](contracts/plugin-package.contract.md), [cli-missing-plugin](contracts/cli-missing-plugin.contract.md), [ui-degradation](contracts/ui-degradation.contract.md), [release-inventory](contracts/release-inventory.contract.md).
- [quickstart.md](quickstart.md): build, instalação em 3 passos, matriz de 18 verificações mapeadas a SC/FR.
- Contexto de agente: `CLAUDE.md` raiz aponta para este plano (bloco `SPECKIT`).

**Re-check pós-design (Constitution)**: resultado idêntico ao gate inicial — I ✅, II ⚠️ (justificado acima), III ✅, IV ✅ (teste golden dos CSVs incluído), V ✅ (isolamento preservado pelo lançador do plugin). Nenhuma nova violação introduzida pelo design.

## Phase 2 — Tasks (próximo comando)

`/speckit-tasks` gera `tasks.md`. Ordem sugerida: (1) tipos neutros + SPI + registro + testes unitários (engine); (2) retipar `GraphTask`/`GraphFileWriter`/listeners/UI (compila sem Neo4j); (3) módulo `iped-graph-neo4j` (mover classes, shade, assembly) + teste de provedor; (4) validação gated do pacote (R-05); (5) degradação: task, guard/flag, `Manager`, UI/aviso, mensagens; (6) POMs/enforcer/CI/inventário; (7) docs e licenciamento; (8) matriz do quickstart (Windows + Linux).
