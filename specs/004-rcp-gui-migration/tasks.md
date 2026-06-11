# Tasks: Migração da GUI do IPED para Eclipse RCP

**Input**: Design documents from `/specs/004-rcp-gui-migration/`

**Prerequisites**: [plan.md](plan.md), [spec.md](spec.md), [research.md](research.md), [data-model.md](data-model.md), [contracts/](contracts/), [quickstart.md](quickstart.md)

**Tests**: INCLUÍDOS — o plano (R11) e os critérios de sucesso (SC-001/002/007) exigem SWTBot, harness de paridade e inventário versionado.

**Organization**: Tarefas agrupadas por user story (US1–US6 da spec), para implementação e validação independentes. Convenção de idioma: código e comentários novos em inglês (constituição); estes documentos em PT-BR.

## Format: `[ID] [P?] [Story] Description`

- **[P]**: Pode rodar em paralelo (arquivos diferentes, sem dependência pendente)
- **[Story]**: US1–US6 (fases de user story apenas)
- Caminhos exatos nas descrições

## Path Conventions

Estrutura definida no [plan.md](plan.md) §Project Structure: novo reactor Tycho em `iped-rcp/` (perfil Maven `-P rcp`); toques mínimos em `iped-engine`, `iped-app` e CI.

---

## Phase 1: Setup (Shared Infrastructure)

**Purpose**: Reactor Tycho, target platform, CI e baseline de paridade

- [X] T001 Criar reactor Tycho: `iped-rcp/pom.xml` (parent Tycho 4.x, herda versão `4.4.0-SNAPSHOT`) + módulo no `pom.xml` raiz sob perfil `rcp` (build atual intocado)
- [X] T002 [P] Definir target platform em `iped-rcp/target-platform/iped-rcp.target` (Eclipse Platform ≥ 4.32 e4/SWT/JFace, Nebula Gallery e Nebula NatTable — fallback R12 já disponível na target) + `target-platform-configuration` com environments `win32.win32.x86_64` e `gtk.linux.x86_64` (research R1/R2)
- [X] T003 [P] Registrar dependências EPL-2.0 (Platform, SWT, JFace, e4, Nebula) em `ThirdParty.txt` e anexar licenças em `licenses/` (research R13)
- [X] T004 [P] Adicionar job `rcp` ao CI (perfil `-P rcp`, GTK3 + Xvfb para SWTBot), sem alterar o job existente (research R13) — implementado como workflow dedicado `.github/workflows/rcp.yml` com filtro de paths `iped-rcp/**` (mantém `maven.yml` intocado e evita falhas no CI principal enquanto o reactor evolui)
- [X] T005 Extrair e congelar o inventário de paridade em `specs/004-rcp-gui-migration/parity-inventory.md` a partir da UI atual (`iped-app/src/main/java/iped/app/ui/App.java`, menus, `ProgressFrame`): áreas busca, tabela(s), galeria, árvores, metadados/facetas, filtros, similaridade, viewers, mapa, grafo, timeline, bookmarks, exportação/relatório, atalhos, progresso; incluir seção dedicada "interoperabilidade de bookmarks" (SC-009 — consumidores: UI atual, gerador de relatório, `--append`/`--continue`, multicase, casos portáteis), seção "aparência nativa" com o inventário de telas para inspeção visual por SO (SC-010) e o procedimento de captura do baseline das contagens (SC-001; re-baseline só por marco — Clarifications)

**Checkpoint**: `mvn -P rcp clean verify` compila reactor vazio; inventário congelado

---

## Phase 2: Foundational (Blocking Prerequisites)

**Purpose**: Bundles-base, serviços e4, produto bootável e infra de teste

**⚠️ CRITICAL**: Nenhuma user story começa antes desta fase completa

- [X] T006 Criar bundle wrapper `iped-rcp/bundles/iped.rcp.libs/` embutindo `iped-engine` + árvore de dependências via `Bundle-ClassPath`, com `Export-Package` restrito aos pacotes consumidos pela UI (research R3; engine intocado — FR-028) — implementado com felix maven-bundle-plugin (Embed-Dependency transitivo, ~300 jars, 195 MB) + `pomDependencies=consider` no parent; exports iniciais = `iped.*`; follow-ups documentados no pom (jars assinados/BouncyCastle, exports adicionais sob demanda)
- [X] T007 [P] Criar bundle `iped-rcp/bundles/iped.rcp.api/` (PROVISÓRIA): `ICaseSessionService`, `IItemAccessService`, `ISearchService`, `IBookmarkService`, `SelectionContext`, `ModelAnchors`, constantes de tópicos `iped/rcp/*`, com `@apiNote` provisório em todo Javadoc (contrato [ui-extension-api](contracts/ui-extension-api.contract.md)) — bundle deliberadamente sem dependências (tipos próprios `ItemId`/`SelectionContext`; accessors tipados do engine chegam com T008 via exports do wrapper); build Tycho resolveu a target platform, validando os IUs do `.target`
- [X] T008 Implementar `CaseSessionService` (DS) em `iped-rcp/bundles/iped.rcp.core/`: ciclo `OPENING→READY→CLOSING→CLOSED` sobre `IPEDMultiSource`, suporte single/multicase (FR-001/002), detecção de mídia somente leitura (data-model `CaseSession`) — DS via `tycho-ds-plugin` (XML e header `Service-Component` gerados); abre sempre com `askImagePathIfNotFound=false` (sem diálogo Swing do engine; falha vira `CaseOpenException` com rollback para CLOSED); read-only espelha o probe de `BitmapBookmarks.saveState` (`IOUtil.canWrite` no `bookmarks.iped`); `interactive` = heurística `EvidenceStatus` em disco (refinar com T062/T063); wrapper `iped.rcp.libs` passou a exportar `org.apache.lucene.*`, `org.sleuthkit.datamodel` e `org.slf4j`; fix OSGi×Python: `appendCaseLibsToJavaClassPath` anexa `<caso>/iped/lib/*.jar` à property `java.class.path` antes do open — o `JEPClassFinder` (ClassEnquirer do jep) só enumera pacotes Java pelo classpath plano, e sem isso `from iped.engine... import` nos scripts Python falhava com `ModuleNotFoundError` (só enumeração de nomes; as classes carregam pelo wrapper)
- [X] T009 [P] Adaptador i18n em `iped-rcp/bundles/iped.rcp.core/`: `Messages` ponte para `iped-app/resources/localization/*.properties`, locale via `iped-locale`/`-nl` (research R7, FR-020) — `iped.rcp.core.i18n.Messages`: pasta resolvida por `-Diped.rcp.localization.dir` → `osgi.install.area` (e diretório pai, p/ produto em `<root>/ui/`) → walk-up dev; locale `iped-locale` → `osgi.nl` → default, sincronizado de volta no `LocaleResolver` (engine/viewers na mesma língua); chave ausente nunca lança: log 1x + `!key!` (detectável por testes — SC-006)
- [X] T010 [P] Utilitário de bridge em `iped-rcp/bundles/iped.rcp.viewers/`: `SwtAwtBridgeHost` (composite único por part, workarounds de foco/z-order GTK) + helpers de marshaling SWT↔EDT↔JavaFX (research R4; Princípio V) — `iped.rcp.viewers.bridge`: sandwich `Frame>Panel>JRootPane`, foco SWT→AWT, `sun.awt.xembedserver` no GTK, dispose na EDT; `UiThreads` com FX via reflexão pelo system classloader (JavaFX vem do JRE Liberica Full, não de bundle; bootdelegation no produto)
- [X] T011 Criar aplicação e4 em `iped-rcp/bundles/iped.rcp.app/`: `Application.e4xmi` (perspectiva padrão, part stacks nos anchors de `ModelAnchors`), `LifeCycle` (abertura de caso por argumento, diálogo nativo de seleção e `-multicases`), produto `iped-rcp/products/iped.rcp.product/` (.product, launcher `iped-ui`, splash nativo) e feature `iped-rcp/features/iped.rcp.feature/` — modelo com os 4 PartStacks de `ModelAnchors` + addons e4 padrão; `LifeCycle` abre via `ICaseSessionManager` bloqueando sob o splash (FR-027), erro em `MessageBox` i18n (chaves novas `AppLifeCycle.*` em EN+PT-BR; demais locales no T047), `@PreSave` fecha a sessão; produto com `--add-opens` do engine (mesmo set do `Bootstrap`), bootdelegation `javafx.*`, `-data @user.home/.iped/ui-workspaces/default` (provisório até T043); **smoke Windows OK**: boot OSGi+SCR+e4 com launcher nativo + splash → diálogo de erro PT-BR para caso inexistente; **caso real OK** (2026-06-11, `F:\test_yara_java21`): sessão READY, janela com título do caso e perspectiva vazia com os 4 part stacks renderizada (screenshot do usuário), shutdown gracioso via `@PreSave`; exigiu `-Djava.security.manager=allow` no vmArgs do produto (`Configuration.loadConfigurables:239` instala SecurityManager p/ bloquear rede dos HTML viewers — mesma flag dos exes legados da migração 003); Linux fica para o checkpoint da fase
- [X] T012 Publicar seleção e eventos: integração `ESelectionService` (chave `iped.rcp.selection`) + tópicos `IEventBroker` (`case/OPENED|CLOSED`, `results/CHANGED`, `bookmarks/CHANGED`) em `iped.rcp.core` (data-model `SelectionContext`) — `iped.rcp.core.events`: `IUiEventPublisher`/`UiEventPublisher` (DS sobre `EventAdmin` opcional, mesmo wire format do `EventBroker` e4 — property `org.eclipse.e4.data` — então `@UIEventTopic` funciona; headless = no-op); `CaseSessionService` publica `case/OPENED|CLOSED` (payload `List<String>` de paths); `UiEventsAddon` (registrado no `Application.e4xmi`) espelha toda `SelectionContext` do `ESelectionService` na chave `iped.rcp.selection` do contexto da aplicação (injeção `@Named` em qualquer part, incl. terceiros) e limpa a seleção em `case/CLOSED`; `results/CHANGED` e `bookmarks/CHANGED` passam a ser disparáveis pelo publisher (emissores reais chegam com T016+/T021/T063)
- [X] T013 [P] Infra de testes: `iped-rcp/tests/iped.rcp.tests.swtbot/` (tycho-surefire + SWTBot sob Xvfb) e `iped-rcp/tests/iped.rcp.tests.parity/` (harness headless, parâmetro `-Dcase.dir`) (research R11) — parity: jar plano + surefire com a linha JVM dos launchers legados (`--add-opens` + `-Djava.security.manager=allow`), exclui `iped.rcp.libs` (jars aninhados invisíveis em classpath plano; engine vem de `iped:iped-engine` — mesmo caminho do gerador de relatório); `CaseSessionHeadlessTest` **verde contra `F:\test_yara_java21`** (2026-06-11: open→READY→itens>0→listeners→close em ~13 s; open inválido roda sem caso e cobre o rollback); swtbot: eclipse-test-plugin com tycho-surefire configurado para lançar o produto real (`E4Application` + product + feature no runtime, `useUIThread=false`), **desligado por padrão** (`-DskipUiTests=false` + `-Dcase.dir`) até o T014; CI `rcp.yml` corrigido para instalar o reactor raiz antes do build RCP (lacuna desde T006). Follow-up perf: abertura do caso no produto OSGi levou minutos vs ~13 s headless — investigar (cache de extração dos jars aninhados do wrapper?) junto de SC-003/T056
- [X] T062 [P] Spike (timeboxed) do modo quase-ao-vivo (research R14): protótipo de leitor read-only concorrente a um processamento real do caso de referência — medir impacto nos commits do índice e contenção SQLite (`storage-*.db`; precedente: busy-wait do `--yara-only` com `AppMain` aberto), validar recarga por geração do índice; registrar GO/ajustes em `specs/004-rcp-gui-migration/research.md` §R14 (gate FR-030; pré-requisito de T063 — ID fora de ordem: adicionado em remediação pós-análise) — **GO registrado (2026-06-11)**: protótipo `NearLiveReaderSpike` (parity module) concorrente ao processamento de `RockPi4.E01` (perfil triage-spike, commits a 45 s); live 340,4 s vs baselines 365,2/358,3 s (−5,9% — gate ≤5% com folga), 7 gerações recarregadas na cadência exata (zero stall), 1302 leituras SQLite com 0×BUSY/máx 16 ms; **zero mudanças no engine** (WAL desnecessário); disciplinas para T063 (tabelas invisíveis pré-commit, leituras curtas autocommit, gen vazia, cadência = commitIntervalSeconds) documentadas no §R14

**Checkpoint**: produto abre, carrega caso de referência e mostra perspectiva vazia nos 2 SOs; spike T062 com decisão GO registrada — **COMPLETO**: Windows ✅ (2026-06-11, caso `F:\test_yara_java21`); **Linux ✅ (2026-06-11, WSLg Ubuntu/GTK + Temurin 21**, caso de pasta sem `sleuth.db` — caso com `sleuth.db` no Linux requer TSK compilado com bindings Java, pré-requisito ambiental padrão do IPED-Linux, não-RCP); **T062 GO ✅**. Achados da perna Linux: (1) bug latente do resolver i18n legado → T065; (2) `GraphConfig.json` `phone-region="auto"` falha em locale sem país (`C`) — sensibilidade ambiental do engine ao abrir caso, documentar no inventário; (3) fallback EN do diálogo de erro confirmado em locale `C` (SC-006)

---

## Phase 3: User Story 1 - Triage essencial em caso processado (Priority: P1) 🎯 MVP

**Goal**: Fluxo central: abrir caso → buscar → ordenar → visualizar → bookmark → exportar/relatório

**Independent Test**: Cenários 1–5 da US1 na spec; contagens e saídas idênticas à UI atual no mesmo caso

### Tests for User Story 1

- [X] T014 [P] [US1] Teste SWTBot do fluxo de triage (abrir → buscar → ordenar → visualizar → bookmark → exportar) em `iped-rcp/tests/iped.rcp.tests.swtbot/src/.../TriageFlowTest.java` (escrever antes; deve falhar) — **escrito primeiro** contra o contrato de widget-ids (`iped.rcp.views.searchbar.{query,run}`, `iped.rcp.views.results.table`); exportação usa o hook `-Diped.rcp.export.dir` (SWTBot não dirige diálogos nativos); o passo de bookmark exercita o `IBookmarkService` dentro do produto OSGi real (perna OSGi do SC-009); compila no harness tycho-surefire (ECJ `-err:-forbidden` só no módulo de teste p/ lookup OSGi); execução: `mvn -f iped-rcp/pom.xml -pl tests/iped.rcp.tests.swtbot verify -DskipUiTests=false -Dcase.dir=<caso>` — **VERDE no produto real (Windows, 2026-06-11, caso `F:\test_yara_java21`, 26 s)**: fluxo completo busca→tabela→histórico→ordenação→seleção→bookmark(OSGi)→exportação; ajustes de harness exigidos: (a) `org.eclipse.ui.ide.application` adicionado ao `.target` SÓ para o runtime de teste (o UI harness do tycho-surefire o provisiona incondicionalmente; produto continua e4 puro — não está na feature), (b) `LifeCycle.parseCaseArgs` endurecido para aceitar apenas diretórios/listas `.txt` (o harness passa o caminho do `surefire.properties` como argumento de programa); perna Linux/Xvfb fica com o CI (`rcp.yml`)
- [X] T015 [P] [US1] Harness de paridade: contagens de busca vs baseline para conjunto de consultas em `iped-rcp/tests/iped.rcp.tests.parity/src/.../SearchParityTest.java`; incluir cenário multicase (2+ casos, contagens vs UI atual — FR-002) — **VERDE contra `F:\test_yara_java21`** (2026-06-11): 8 consultas congeladas (match-all, termo, campo, range, booleana, frase) com contagens idênticas ao caminho `IPEDSearcher.multiSearch` da UI atual; paginação reconstrói o resultado completo; `sortBy` preserva o conjunto e publica generation nova (descendente = inverso exato — nota no inventário); erro de sintaxe vira `IllegalArgumentException` apresentável; **aprendizado registrado**: baseline deve usar o texto verbatim (`""`≠`"*"` no parser — 2 itens de diferença no caso de referência); multicase roda com `-Dcase.dir2` (skip sem ele)
- [X] T064 [P] [US1] Teste round-trip de bookmarks (SC-009/FR-005) em `iped-rcp/tests/iped.rcp.tests.parity/src/.../BookmarkRoundTripTest.java` + `BookmarkStateDump.java` (JVM filha em classpath plano — mesmo caminho do gerador de relatório) — **VERDE contra `F:\test_yara_java21`** (2026-06-11): ida (RCP escreve com acentos/cor/comentário/checked + bookmark de 100 mil itens `BitmapBookmarks` → JVM plana lê dump canônico com CRC de pertencimento idêntico), volta (engine plano escreve → pilha RCP lê após `loadState`), escrita concorrente em 2 threads com flush final `saveState(true)` (disciplina `SaveStateThread`); caso de referência restaurado no cleanup (delete + `clearChecked`); a perna "contexto OSGi real" da ida é coberta pelo passo de bookmark do T014 no produto; **fixes de ambiente**: classpath da JVM filha via env `CLASSPATH` (limite de linha de comando do Windows, error=206) e estado checked é global do caso → reset entre pernas; multicase com `-Dcase.dir2` (skip sem ele)

### Implementation for User Story 1

> Serviços headless adicionados em `iped.rcp.core` como base da US1 (consumidos por parts e harness): `SearchService` (DS, `ISearchService` + result set ativo com `ResultSet` imutável e generation, publica `results/CHANGED`), `ResultSorter` (porte do `RowComparator` por DocValues, sem `App`/Swing; descendente = inverso exato do ascendente — divergência de desempate registrada no inventário), `BookmarkService` (DS, `IBookmarkService` + superfície interna checked/counts/flush; toda mutação salva via `SaveStateThread` e publica `bookmarks/CHANGED`), `ItemAccessService` (DS, `IItemAccessService` + `resolve()` interno p/ viewers). Wrapper `iped.rcp.libs` passou a exportar `org.apache.tika.*` (metadados de item).

- [X] T016 [P] [US1] Part de busca em `iped-rcp/bundles/iped.rcp.views/src/.../SearchBarPart.java`: campo de consulta com sintaxe atual + histórico (paridade `QueryComboBox`), executa via `ISearchService` e publica `results/CHANGED` (FR-006) — Combo com histórico persistido (InstanceScope, 50 entradas, mais recente primeiro), Enter/botão, busca em Job (Princípio V), erro de sintaxe → `MessageDialog` com chaves legadas `UISearcher.Error.*`; ids SWTBot `iped.rcp.views.searchbar.{query,run}` (contrato do T014)
- [X] T017 [US1] Tabela de resultados em `iped-rcp/bundles/iped.rcp.views/src/.../ResultsTablePart.java`: tabela `SWT.VIRTUAL` sobre `MultiSearchResult` (fetch preguiçoso por `SWT.SetData` lendo stored fields do Lucene, ordenação no engine fora da UI thread via `SearchService.sortBy`/`ResultSorter`, checkbox de marcação = `isChecked`/`setChecked` do modelo de bookmarks como hoje, seleção → `ESelectionService` com `SelectionContext`) (FR-007, research R12, data-model `ResultSetModel`) — assina `@UIEventTopic(results/CHANGED)` com guarda de generation; colunas: nº de linha fixo + campos visíveis (score/bookmark pseudo-campos, CATEGORY localizada, LENGTH formatado); menu de contexto: exportar itens/propriedades, gerenciar bookmarks, colunas, relatório
- [X] T018 [P] [US1] Configuração de colunas em `iped-rcp/bundles/iped.rcp.views/src/.../ColumnsConfigDialog.java`: colunas visíveis/ordem persistidas em preferências do usuário (paridade `ColumnsManager`) — `ResultColumns` espelha `defaultFields`/`defaultWidths` legados; persistência em InstanceScope (área de workspace R5; divergência do `visibleCols.dat` legado registrada no inventário); diálogo com check + mover acima/abaixo; campos disponíveis = defaults + `IndexItem.getMetadataTypes()`
- [X] T019 [P] [US1] Tabelas auxiliares (subitens, item pai, duplicatas, referências) em `iped-rcp/bundles/iped.rcp.views/src/.../AuxTablesPart.java`, sincronizadas com a seleção (FR-007) — 5 abas (subitens, pai, duplicatas, referencia, referenciado-por) com as queries portadas 1:1 dos models legados (`SubitemTableModel`, `ParentTableModel`, `DuplicatesTableModel`, `Referencing/ReferencedByTableModel` incl. P2P/UFED/jumplist); refresh em Job com stamp anti-corrida; exibição cap 1000 linhas (total real no título — nota no inventário)
- [X] T020 [US1] Part de visualização em `iped-rcp/bundles/iped.rcp.viewers/src/.../ContentViewerPart.java`: hospeda o stack `MultiViewer` existente via `SwtAwtBridgeHost` (FR-011, research R4) — viewers registrados neste incremento: Metadata (subclasse anônima como o `ViewerController` legado), Image, Tiff, Html, Email (com `RcpAttachmentSearcher` sobre a sessão — porte do `AttachmentSearcherImpl` sem `App`), IcePDF; navegação anterior/próxima ocorrência ligada à API do viewer; seleção→viewer via `SelectionContext` com resolução do item em Job + `loadFile` na EDT; **pendentes no inventário**: texto com realce/hits, hex, LibreOffice, áudio/vídeo, CAD (entram em iteração US1 seguinte)
- [X] T021 [P] [US1] Bookmarks: criar/renomear/colorir/comentar/excluir + atribuição da seleção via `IBookmarkService`/`IMultiBookmarks` (formato atual — FR-005/014) em `iped-rcp/bundles/iped.rcp.views/src/.../bookmarks/BookmarkManagerDialog.java` — diálogo JFace com chaves legadas `BookmarksManager.*` (nome/cor/comentário/contagem, add/remove seleção, confirmação de exclusão); união de bookmarks e atalhos rápidos ficam com T046/US5 (nota); escrita única via `BookmarkService` (saveState assíncrono + `bookmarks/CHANGED`)
- [X] T022 [P] [US1] Exportação de itens (cópia com propriedades, paridade de saída) em `iped-rcp/bundles/iped.rcp.views/src/.../export/ExportActions.java` (FR-015) — porte dos essenciais de `CopyFiles` (nomes `Util.getValidFilename(Util.getNameWithTrueExt)`, dedup `Util.concat`, subpastas de 1000) e `CopyProperties` (CSV UTF-8 com BOM, `;`, headers localizados); Jobs fora da UI thread; hook de teste `-Diped.rcp.export.dir` substitui o diálogo nativo (T014 — SWTBot não dirige diálogos nativos)
- [X] T023 [US1] Diálogo de relatório (paridade `ReportDialog`/HTMLReport) em `iped-rcp/bundles/iped.rcp.views/src/.../report/ReportWizard.java` como wizard JFace (FR-015) — página 1: bookmarks (include + flag thumbs-only/`-nocontent` por bookmark), saída, keywords, `--nopstattachs`/`--nolinkeditems`/`--append`; página 2: case info completo (`iped.engine.data.ReportInfo`); Finish = mesmo pipeline da UI atual: snapshot `.iped` via `saveState(File)` + `setInReport` e JVM filha `iped.app.bootstrap.Bootstrap` com `-Diped.ui.report` usando `<caso>/iped/lib/iped-search-app.jar` + `jre/` embarcada (caso autocontido)

**Checkpoint**: US1 completa = MVP interno; T014/T015 verdes; paridade das áreas correspondentes no inventário — **COMPLETO no Windows (2026-06-11)**: reactor com 12 módulos verde (`mvn -f iped-rcp/pom.xml install`), bundle novo `iped.rcp.views` + parts no `Application.e4xmi`; **T015/T064 VERDES** no harness headless (11 testes, 0 falhas; 2 skips = pernas multicase sem `-Dcase.dir2`) e **T014 VERDE no produto real** (SWTBot dirigindo o workbench e4 com o caso de referência); inventário atualizado (BU-01/BU-02, TB-01, BM-05, IB-01/IB-02 → paridade; divergências registradas no log). Fix de robustez no core durante a fase: `CaseSessionService.configurePreviewRepositories` tolera `IllegalStateException` (repositório de preview é global por processo; reabrir o mesmo caso na mesma JVM era fatal). **Pendências (não bloqueiam o avanço para US2)**: perna Linux/Xvfb do T014 no CI (`rcp.yml`); smoke visual das parts US1 (aparência nativa — evidências `manual` do inventário); viewers restantes do T020 (texto-com-realce, hex, LibreOffice, áudio/vídeo, CAD) e comparação de saída de exportação (EX-01) em iteração US1 complementar

---

## Phase 4: User Story 2 - Galeria, facetas e filtros avançados (Priority: P2)

**Goal**: Galeria virtual, árvores de navegação, facetas de metadados, filtros lógicos/salvos e similaridade

**Independent Test**: Sequência definida de filtros/similaridade com contagens idênticas à UI atual (cenários US2)

### Tests for User Story 2

- [ ] T024 [P] [US2] Teste SWTBot: combinação de filtros + rolagem de galeria sem travamento em `iped-rcp/tests/iped.rcp.tests.swtbot/src/.../FiltersGalleryTest.java`
- [ ] T025 [P] [US2] Harness de paridade: contagens de filtros/similaridade vs baseline em `iped-rcp/tests/iped.rcp.tests.parity/src/.../FilterParityTest.java`

### Implementation for User Story 2

- [ ] T026 [US2] Galeria em `iped-rcp/bundles/iped.rcp.views/src/.../GalleryPart.java`: Nebula Gallery modo virtual, reuso da lógica de thumbnails do `GalleryModel` desacoplada de Swing (`ImageIcon`→`ImageData`), seleção sincronizada (FR-008, research R12, SC-004)
- [ ] T027 [P] [US2] Árvore de evidências/filesystem em `iped-rcp/bundles/iped.rcp.views/src/.../EvidenceTreePart.java` (`ILazyTreeContentProvider` sobre lógica do `TreeViewModel`) (FR-009)
- [ ] T028 [P] [US2] Árvore de categorias em `iped-rcp/bundles/iped.rcp.views/src/.../CategoryTreePart.java` (porte do `CategoryTreeModel`) (FR-009)
- [ ] T029 [P] [US2] Árvores de bookmarks e filtros de IA em `iped-rcp/bundles/iped.rcp.views/src/.../BookmarkTreePart.java` e `AiFiltersTreePart.java` (FR-009)
- [ ] T030 [US2] Painel de metadados/facetas em `iped-rcp/bundles/iped.rcp.views/src/.../MetadataPanelPart.java`: agregações (`ValueCount`/`RangeCount`/`MoneyCount` de `iped-app/metadata`) + filtro por valores (FR-010)
- [ ] T031 [US2] Serviço de composição de filtros em `iped-rcp/bundles/iped.rcp.core/src/.../FilterStateService.java`: árvore AND/OR/NOT de `IResultSetFilter`, filtros salvos no formato atual + `FiltersPanelPart.java` em `iped.rcp.views` (FR-016, FR-005, data-model `FilterState`)
- [ ] T032 [P] [US2] Buscas por similaridade (imagens, faces, documentos, duplicatas): commands + porte do wiring dos `*Filterer` atuais em `iped-rcp/bundles/iped.rcp.views/src/.../similarity/` (FR-013)

**Checkpoint**: US1 e US2 funcionais e independentes; inventário de paridade atualizado

---

## Phase 5: User Story 3 - Views especializadas: mapa, grafo e timeline (Priority: P3)

**Goal**: Mapa, grafo de comunicações e timeline bridgeados com seleção sincronizada

**Independent Test**: Mesmos dados da UI atual no caso de referência; seleção sincroniza com a tabela (cenários US3)

### Tests for User Story 3

- [ ] T033 [P] [US3] Teste SWTBot/checklist de sincronização de seleção (mapa/grafo/timeline ↔ tabela) em `iped-rcp/tests/iped.rcp.tests.swtbot/src/.../SpecializedViewsTest.java`

### Implementation for User Story 3

- [ ] T034 [US3] Part de mapa em `iped-rcp/bundles/iped.rcp.specialized/src/.../MapPart.java`: bridge do `MapViewer` (iped-geo, JavaFX WebView) via `SwtAwtBridgeHost`, seleção bidirecional (FR-012)
- [ ] T035 [P] [US3] Part de grafo em `iped-rcp/bundles/iped.rcp.specialized/src/.../GraphPart.java`: bridge do `AppGraphAnalytics` (Kharon) + toolbar/sidepanel/ações (expansão, caminhos, exportação) (FR-012)
- [ ] T036 [P] [US3] Part de timeline em `iped-rcp/bundles/iped.rcp.specialized/src/.../TimelinePart.java`: bridge do `IpedChartsPanel` (JFreeChart), zoom/seleção de intervalo filtrando a tabela (FR-012)

**Checkpoint**: US1–US3 independentes; áreas mapa/grafo/timeline com paridade no inventário

---

## Phase 6: User Story 4 - Acompanhar o processamento de um caso (Priority: P4)

**Goal**: Janela de progresso SWT standalone + splash + diálogos do inicializador + modo quase-ao-vivo (FR-024/026/027/029/030)

**Independent Test**: Processar evidência de referência comparando campos exibidos com a janela atual (cenários US4); `--nogui` inalterado

### Tests for User Story 4

- [ ] T037 [P] [US4] Teste de fallback headless (sem display → `ProgressConsole`, processamento conclui) em `iped-rcp/tests/iped.rcp.tests.parity/src/.../HeadlessProgressTest.java` (contrato [progress-ui-events](contracts/progress-ui-events.contract.md))

### Implementation for User Story 4

- [ ] T038 [US4] Janela de progresso SWT/JFace standalone (sem OSGi) em `iped-rcp/bundles/iped.rcp.progress/src/.../ProgressWindow.java`: consome `UIPropertyListenerProvider` com todos os campos do contrato (global, por evidência, taxa/gráfico, fase/workers, contadores, erros, ETA), updates via `Display.asyncExec` (FR-026)
- [ ] T039 [US4] Integrar a janela na JVM de processamento em `iped-app/src/main/java/iped/app/processing/Main.java`: substituir `ProgressFrame`, manter `--nogui`→`ProgressConsole` e fallback headless automático; o botão "abrir análise" lança o produto RCP como processo separado sobre o caso em processamento (modo quase-ao-vivo — research R14, FR-029) ou sobre o caso pronto (FR-026; toque mínimo — FR-028)
- [ ] T040 [P] [US4] Ações pausar/continuar/abortar com confirmação + "abrir análise" (durante o processamento — quase-ao-vivo — ou ao final) lançando o produto RCP (contrato [case-launcher-packaging](contracts/case-launcher-packaging.contract.md)) em `iped.rcp.progress` (validar contra produto de dev até T054)
- [ ] T063 [US4] Implementar modo quase-ao-vivo em `iped-rcp/bundles/iped.rcp.core/src/.../CommitMonitor.java`: detecção de nova geração do índice, recarga do leitor read-only com troca atômica do resultado + evento `results/CHANGED`, storages SQLite read-only com busy timeout, desligamento ao fim do processamento; sem visibilidade de itens não consolidados (divergência registrada no inventário) (FR-029/FR-030, research R14, data-model `CaseSession.commitMonitor`; depende do GO de T062 — ID fora de ordem: adicionado em remediação pós-análise)
- [ ] T041 [P] [US4] Splash e diálogos: splash nativo do produto de análise (`iped.rcp.product`), feedback de inicialização do processamento em SWT e diálogos de erro do inicializador via `MessageBox`/JFace, aposentando `SplashScreenManager`/`StartUpControl` no cut-over (FR-027)

**Checkpoint**: progresso com paridade de campos; headless e `--nogui` intactos; quase-ao-vivo medido contra o gate FR-030 (T062 GO + T063)

---

## Phase 7: User Story 5 - Workspace pessoal: layout, temas, idioma e escala (Priority: P5)

**Goal**: Persistência de layout por usuário+caso, temas, HiDPI, atalhos e i18n completa

**Independent Test**: Reorganizar → reiniciar → restauração fiel; idiomas e HiDPI legíveis (cenários US5)

### Tests for User Story 5

- [ ] T042 [P] [US5] Teste SWTBot de persistência/restauração de layout entre reinícios em `iped-rcp/tests/iped.rcp.tests.swtbot/src/.../WorkspacePersistenceTest.java` (SC-005); incluir caso de estado corrompido (`workbench.xmi` inválido → boot com layout padrão, sem falha)

### Implementation for User Story 5

- [ ] T043 [US5] Persistência de workspace em `iped-rcp/bundles/iped.rcp.app/src/.../WorkspaceLocationResolver.java`: área `~/.iped/ui-workspaces/<case-id>/` (hash do path canônico), reset automático de estado corrompido/incompatível (research R5, FR-017, data-model `WorkspaceState`)
- [ ] T044 [P] [US5] Temas em `iped.rcp.app`: nativo por padrão, escuro seguindo o SO + toggle manual em preferência, CSS e4 mínimo para superfícies não-nativas (FR-018/025, research R8)
- [ ] T045 [P] [US5] HiDPI/escala: honrar `uiScale` do `LocalConfig` mapeando para autoscale SWT; verificação multi-monitor (FR-019; Princípio III — config existente)
- [ ] T046 [P] [US5] Keybindings e4 dos atalhos de triage atuais + tabela de mapeamento documentada em `specs/004-rcp-gui-migration/keybindings-map.md` (FR-021)
- [ ] T047 [US5] Varredura i18n: chaves novas da UI adicionadas aos 6 bundles em `iped-app/resources/localization/`, PT-BR/EN completos, fallback EN sem chaves cruas (FR-020, SC-006, research R7)

**Checkpoint**: SC-005 verificado; sem chaves cruas em PT-BR/EN

---

## Phase 8: User Story 6 - Extensão por terceiros sem fork (Priority: P6)

**Goal**: API provisória utilizável por bundle externo via drop-in (SC-007)

**Independent Test**: Instalar `iped.rcp.sample.view` numa distribuição limpa; view funciona; remoção não quebra boot (cenários US6)

### Tests for User Story 6

- [ ] T048 [P] [US6] Teste de instalação/remoção drop-in (boot íntegro nos 2 casos) em `iped-rcp/tests/iped.rcp.tests.swtbot/src/.../DropinExtensionTest.java`

### Implementation for User Story 6

- [ ] T049 [US6] Finalizar superfície provisória de `iped.rcp.api` conforme contrato: exports públicos mínimos, `x-internal` em todo o resto, `@apiNote` em 100% dos tipos, `ModelAnchors` estável (FR-022)
- [ ] T050 [US6] Carga drop-in de `plugins-ext/` no boot com tolerância a falha (log + aviso não-bloqueante) em `iped-rcp/bundles/iped.rcp.app/src/.../DropinBundleLoader.java` (contrato ui-extension-api)
- [ ] T051 [P] [US6] Extensão de exemplo `iped-rcp/samples/iped.rcp.sample.view/`: `fragment.e4xmi` + part que exibe a seleção corrente, build apenas contra `iped.rcp.api` (SC-007)

**Checkpoint**: SC-007 demonstrável em distribuição limpa

---

## Phase 9: Polish, Empacotamento, Gate de Paridade & Cut-over

**Purpose**: Integração no release, caso autocontido, medições, gate SC-001 e aposentadoria da UI antiga

- [ ] T065 [P] Corrigir a resolução da pasta `localization/` do engine para produtos instalados (achado da perna Linux do checkpoint, 2026-06-11): `iped.localization.Messages.getExternalBundle` NPEa quando a URL da própria classe não é `file:` (classe dentro do wrapper OSGi) e o cwd está fora da árvore de dev — o walk-up por `iped-app/resources/localization` é **sem limite** e estoura na raiz do filesystem; o smoke Windows passou por sorte (produto roda dentro do repositório). Correção aditiva em `iped-api`: consultar uma system property (ex.: `iped.localization.dir`) antes da heurística atual + bound do walk-up com exceção clara; o produto RCP define a propriedade no boot (reusar o resolver do adaptador T009). Pré-requisito do T052/T053 (release/caso reais) e da retirada do workaround usado no WSL (`~/iped-app/resources/localization`). Toque em `iped-api` exige justificativa no PR (Princípio I — mudança aditiva, sem quebra de assinatura) (ID fora de ordem: remediação de achado de implementação)
- [ ] T052 Integrar produto ao release em `iped-app/pom.xml` (perfil `rcp`): copiar produto materializado para `target/release/iped-<ver>/ui/` com `plugins-ext/` vazio (contrato [case-launcher-packaging](contracts/case-launcher-packaging.contract.md)) — **depende de T065** (sem ele o produto instalado NPEa na i18n do engine ao abrir caso)
- [ ] T053 `Manager.prepareOutputFolder` em `iped-engine/src/main/java/iped/engine/core/Manager.java`: copiar `ui/` para `<caso>/iped/ui/` junto de `jre/`/`lib/` (toque em núcleo justificado — plan.md Complexity Tracking; manter nota Linux sem-jre documentada)
- [ ] T054 Shim do caso: `IPED-SearchApp.exe` (launch4j em `iped-app`) passa a exec `<caso>/iped/ui/iped-ui.exe -vm <caso>/iped/jre/bin` + script equivalente Linux, preservando contrato `b8b15735a`
- [ ] T055 [P] Verificação de mídia somente leitura (abre + aviso de bookmark) e de duas instâncias concorrentes no mesmo caso, em Windows e Linux (edge cases; Clarifications concorrência)
- [ ] T056 [P] Medições de desempenho SC-002/003/004 vs UI atual no caso de referência (mesmo hardware), registradas em `specs/004-rcp-gui-migration/perf-report.md`; incluir smoke sintético de escala da tabela virtual e da galeria (≥ 50 M de linhas geradas) para cobrir o edge case de dezenas de milhões de itens
- [ ] T057 Sessão soak de 8 h no caso de referência (SC-008) com critérios objetivos: heap pós-GC e tempo de resposta de busca na 8ª hora ≤ 110% da 1ª hora; verificação de imutabilidade da evidência (hash recursivo dos dados do caso antes/depois da sessão, excluindo áreas de artefatos do usuário — FR-004); registrar em `perf-report.md`
- [ ] T058 **GATE SC-001**: passada completa do `parity-inventory.md` — 100% `paridade` ou `divergência justificada` aprovada; inclui a passada de inspeção visual de aparência nativa por tela, em Windows e Linux, com evidências (screenshots) anexadas ao inventário (SC-010); aplicar re-baseline final se houver marco pendente (Clarifications)
- [ ] T059 **Cut-over**: remover `iped-app/src/main/java/iped/app/ui/**`, `graph/**`, `timelinegraph/**`, `BootstrapUI`/`AppMain`, execution `create-search-jar` e dependências exclusivas (DockingFrames, jcalendar; Kharon/jfreechartextensions permanecem enquanto bridgeados) conforme §Aposentadorias do contrato de empacotamento (FR-023)
- [ ] T060 [P] Documentação: atualizar `CLAUDE.md` raiz (§2/§3/§6/§8), `iped-app/CLAUDE.md`, `iped-engine/CLAUDE.md` (toque no Manager), criar `iped-rcp/CLAUDE.md`, entrada em `ReleaseNotes.txt` (incl. aviso de API provisória)
- [ ] T061 Validação final: `mvn -P rcp clean verify` + cenários 1–8 do [quickstart.md](quickstart.md) executados em Windows e Linux; release Windows testado em máquina sem Java instalado (critério 2 do contrato de empacotamento)

---

## Dependencies & Execution Order

### Phase Dependencies

- **Setup (Phase 1)**: sem dependências
- **Foundational (Phase 2)**: depende da Phase 1 — **BLOQUEIA todas as user stories** (T006 → T008/T011/T012; T007 antes de T008; T011 depende de T006–T010)
- **User Stories (Phases 3–8)**: todas dependem da Phase 2
  - US1 (P1) primeiro — valida esqueleto e4 + bridge (decisão de risco)
  - US2/US3 podem seguir em paralelo após US1 (compartilham seleção/eventos já estáveis)
  - US4 é independente de US1–US3 (JVM de processamento) — pode rodar em paralelo desde o fim da Phase 2
  - US5 depende de US1 (precisa de parts reais para layout/tema/atalhos)
  - US6 depende de US1 (API exercitada por views reais) e idealmente após US2
- **Polish/Cut-over (Phase 9)**: T052–T054 após US1 utilizável; T056–T058 exigem US1–US5 completas; **T059 (cut-over) só após T058 (gate SC-001) aprovado**; T061 por último

### User Story Dependencies

- **US1**: só Foundational — independente
- **US2**: Foundational (+ integra com seleção/resultados de US1, mas testável sozinha sobre o esqueleto)
- **US3**: Foundational + `SwtAwtBridgeHost` (T010); independente de US2
- **US4**: Foundational parcial (T009 i18n); não depende do produto de análise — exceto T063 (quase-ao-vivo), que depende do GO do spike T062 e de o produto abrir caso (T011)
- **US5**: US1 (parts reais)
- **US6**: US1 (API exercitada); contrato finalizado por último entre as stories

### Within Each User Story

- Testes (SWTBot/paridade) escritos primeiro e falhando
- Serviços (`iped.rcp.core`) antes de parts (`iped.rcp.views`)
- Parts antes de integração de seleção/eventos
- Checkpoint validado antes da próxima story

### Parallel Opportunities

- Phase 1: T002, T003, T004 em paralelo (T005 independente, em paralelo)
- Phase 2: T007, T009, T010, T013, T062 em paralelo após T006 (T062 pode até anteceder: depende só do release atual + caso de referência)
- US1: T016, T018, T019, T021, T022 paralelizáveis; T017 e T020 são os críticos
- US2: T027, T028, T029, T032 em paralelo após T026/T031
- US3: T035, T036 em paralelo após T034
- US4 inteira em paralelo com US2/US3 (módulo isolado)
- Phase 9: T055, T056, T060 paralelizáveis

---

## Parallel Example: User Story 1

```bash
# Testes primeiro (devem falhar):
Task: "T014 SWTBot TriageFlowTest em iped-rcp/tests/iped.rcp.tests.swtbot"
Task: "T015 SearchParityTest em iped-rcp/tests/iped.rcp.tests.parity"
Task: "T064 BookmarkRoundTripTest em iped-rcp/tests/iped.rcp.tests.parity"

# Depois, em paralelo (arquivos distintos):
Task: "T016 SearchBarPart em iped.rcp.views"
Task: "T018 ColumnsConfigDialog em iped.rcp.views"
Task: "T019 AuxTablesPart em iped.rcp.views"
Task: "T021 BookmarkActions em iped.rcp.views"
Task: "T022 ExportHandler em iped.rcp.views"

# Críticos em sequência: T017 (tabela virtual) → T020 (viewer bridge) → T023 (relatório)
```

---

## Implementation Strategy

### MVP First (US1 como prova do esqueleto)

1. Phases 1–2 completas (reactor, bundles-base, produto bootável nos 2 SOs)
2. Phase 3 (US1) completa → **PARAR e VALIDAR**: triage ponta-a-ponta + T014/T015 verdes
3. US1 é o teste de fogo das duas decisões de maior risco (R3 wrapper, R4 bridge SWT_AWT) — se o bridge falhar no GTK aqui, reavaliar R4 com custo mínimo

### Incremental Delivery

- Cada story fecha com checkpoint + atualização do inventário de paridade
- US4 (progresso) pode ser entregue/validada em qualquer ponto após Phase 2
- Nada é distribuído ao usuário final antes do gate T058 (cut-over total — FR-023): as fases servem a validação interna, não a releases parciais

### Parallel Team Strategy

- Dev A: US1 → US5; Dev B: US2 → US6; Dev C: US3; Dev D: US4 (isolada)
- Phase 9 em conjunto, com T058 (gate) e T059 (cut-over) como marcos de equipe

---

## Notes

- [P] = arquivos distintos e sem dependência pendente
- Builds locais: sempre `mvn clean ...` (m2e contamina `target/classes`)
- JDK de build: Liberica Full 21 (`H:\java\LibericaJDK-21-Full`)
- Commits após cada tarefa ou grupo lógico; PRs citando princípios da constituição quando tocarem núcleo (T039, T053, T059)
- A UI Swing permanece no repositório até T059 — é a referência viva do inventário; não receber features novas fora de re-baseline
