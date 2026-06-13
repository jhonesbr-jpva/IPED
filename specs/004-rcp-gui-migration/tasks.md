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

- [X] T024 [P] [US2] Teste SWTBot: combinação de filtros + rolagem de galeria sem travamento em `iped-rcp/tests/iped.rcp.tests.swtbot/src/.../FiltersGalleryTest.java` — **escrito primeiro** contra o contrato de widget-ids US2 (`iped.rcp.views.gallery`, `...categories.tree`, `...filters.duplicates`); **VERDE no produto real (Windows, 2026-06-12, caso `F:\test_yara_java21`, 13 s)**: busca vazia (match-all, mesma base das contagens da árvore — aprendizado T015) → galeria espelha o resultado (502.283 itens) → tempestade de rolagem virtual em 5 posições ×2 sem travar a UI thread > 1 s (SC-004) → filtro por categoria (contagem da tabela == contagem do label da árvore == galeria) → + duplicatas (combinação nunca cresce; galeria sincronizada) → limpeza restaura o total; harness roda os 3 testes SWTBot na MESMA janela: `@After` reativa a aba Results (SWTBot não acha widget invisível) e o assert de histórico do T014 virou waitUntil (tabela pré-populada pelo teste anterior)
- [X] T025 [P] [US2] Harness de paridade: contagens de filtros/similaridade vs baseline em `iped-rcp/tests/iped.rcp.tests.parity/src/.../FilterParityTest.java` — **VERDE contra `F:\test_yara_java21`** (2026-06-12: 9 passes + 2 skips justificados — caso triage sem features de similaridade de imagem/face): categorias (contagem == label da árvore == composição booleana legada), composição query+filtro, árvore combinada AND/OR/NOT/mista vs álgebra de conjuntos, duplicatas (porte `DynamicDuplicateFilter`), seleção de bookmarks, facetas de valor (contentType particiona o resultado; cada valor == query legada `field:"valor"`), facetas de range (size; cada range == query `[min TO max]`), filtros salvos (DefaultFilters.txt do caso, formato atual), documento similar; T015/T064 revalidados verdes após a integração do `SearchService` (22 testes, 0 falhas no módulo); `CategoryTreeProbeTest` mantido como sonda diagnóstica da estrutura da árvore

### Implementation for User Story 2

> Serviços headless US2 em `iped.rcp.core` (consumidos por parts e harness): `FilterStateService` (DS; slots de query filters ANDados como o `CaseSearcherFilter` + pipeline de result-set filters em ordem fixa determinística + árvore combinada avaliada com bitsets por fonte como o `CombinedFilterer`; limpa tudo no fechamento do caso), `FilterTreeNode` (AND/OR + negação), `SavedFiltersStore` (`~/.iped/ipedFilters[-locale].txt` + `conf/DefaultFilters.txt`, remoção de OBSOLETE e fix `\\:`), `BookmarkFilters` (união via `filterBookmarks*` — semântica do caminho bitmap legado), `SimilarityFilters` (fábricas sobre `SimilarImagesSearch`/`ImageSimilarityScorer`+`LowScoreFilter`/`SimilarFacesSearch`/`SimilarDocumentSearch` + porte do `DynamicDuplicateFilter` que vivia em `iped-app/ui`), `iped.rcp.core.metadata` (porte fiel de `MetadataSearch`/`ValueCount`/`RangeCount`/`MoneyCount`/`SingleValueCount`/`LookupOrd` desacoplado do `App`; `ValueCountFilter` via `getIdsWithOrd` com o MESMO aggregator dos counts), `iped.rcp.core.trees` (`EvidenceTreeModel` = queries do `TreeViewModel` + filtros do `TreeListener`; `AiFiltersModel` = porte do `AIFiltersLoader`). `SearchService` ganhou referência opcional (greedy) ao `FilterStateService` + `refresh()` (disciplina `updateFileListing()`); sem filtros ativos o caminho verbatim de US1 é intocado (T015 verde).

- [X] T026 [US2] Galeria em `iped.rcp.views/src/.../gallery/GalleryPart.java`: Nebula Gallery `SWT.VIRTUAL` (1 grupo `NoGroupRenderer`), pipeline de thumbnails do `GalleryModel` portado em `GalleryThumbProvider` (THUMB do índice → view/preview repository → thumb embutido jpeg → subsample → `ExternalImageConverter`; `BufferedImage`→`ImageData` via `SwtImages`), pool de workers do `ImageThumbTaskConfig.galleryThreads` (cap 20 = legado), cache LRU de 1000 imagens SWT com descarte seguro, guarda de época contra recargas, seleção → `ESelectionService` (FR-008, R12, SC-004); pendências anotadas no inventário: blur/gray, multi-frame de vídeo, sync tabela→galeria, zoom de célula
- [X] T027 [P] [US2] Árvore de evidências em `iped.rcp.views/src/.../trees/EvidenceTreePart.java`: filhos preguiçosos por nó (mesmas queries legadas), toggle "listagem recursiva" (default true como `App`), seleção → subárvore (`parentIds`) ou filhos diretos, root em modo não-recursivo lista os roots (`isRoot:true`, semântica `TreeListener`), nó único publica seleção p/ viewers (FR-009)
- [X] T028 [P] [US2] Árvore de categorias em `iped.rcp.views/src/.../trees/CategoryTreePart.java`: árvore do engine com contagens (labels `Category.toString` legados), multi-seleção → SHOULD `TermQuery` normalizado (query exata do `CategoryTreeListener`), root/vazio limpa (FR-009)
- [X] T029 [P] [US2] `BookmarkTreePart.java` (root + "[Sem Marcadores]" + nomes ordenados por collator, contagens nos labels, multi-seleção → união via engine, segue `bookmarks/CHANGED`) e `AiFiltersTreePart.java` (árvore do `AIFiltersConfig.json` contada/expandida contra o caso, seleção SHOULD como o listener legado, caso sem config = árvore vazia) (FR-009)
- [X] T030 [US2] Painel de metadados/facetas em `iped.rcp.views/src/.../metadata/MetadataPanelPart.java`: combo de campo (localizados), Update em Job (agregação sobre o resultado ATIVO, ordenada por contagem), tabela virtual de valores, Filtrar (multi-seleção → `ValueCountFilter` no slot `metadata`) e Limpar (FR-010)
- [X] T031 [US2] `FilterStateService` (core, ver nota acima) + `FiltersPanelPart.java`: filtros salvos (lista localizada + expressão + Aplicar/Novo/Excluir persistindo no formato atual), árvore combinada AND/OR/NOT (grupos, leaf de filtro salvo, negação, remoção, aplicar), toggle de duplicatas, Limpar tudo, label de filtros ativos (FR-016, FR-005)
- [X] T032 [P] [US2] Similaridade em `iped.rcp.views/src/.../similarity/SimilarityActions.java` + itens no menu de contexto da tabela: imagens similares (query + re-score; ordena por score desc e silencia sem features — comportamentos legados), faces similares, documentos similares (diálogo de % default 70 como `MenuListener`), duplicatas no painel de filtros (FR-013)

**Checkpoint**: US1 e US2 funcionais e independentes; inventário de paridade atualizado — **COMPLETO no Windows (2026-06-12)**: reactor 12 módulos verde; T024 VERDE no produto real + T025 VERDE no harness headless (22 testes do módulo parity, 0 falhas) + T014 revalidado; 7 parts novas no `Application.e4xmi` (stacks left/right/center). **Fixes de produto durante a fase**: (a) `iped.rcp.core` ganhou `org.eclipse.e4.ui.model.workbench` no Require-Bundle — o `UiEventsAddon` (T012) falhava ao instanciar com CNFE de `MPart` no runtime (latente desde a Phase 2, só visível no .log do workbench); (b) lição JFace nas 6 TreeViewers: o input NUNCA pode ser o próprio elemento raiz (`getRawChildren` resolve filhos de elemento `equals` ao input via `getElements` → raiz vira filha de si mesma em cadeia infinita — causa do colapso "Categorias→Categorias→..." e de um StackOverflow no SWTBot); input agora é `Object[]{root}`. **Pendências (não bloqueiam US3)**: perna Linux/Xvfb no CI; smoke visual das parts US2 (evidências `manual`); SI-01/SI-02 guardados por capacidade do caso (rodar contra caso com features de imagem/face); blur/gray, vídeo multi-frame, zoom de célula e sync bidirecional tabela↔galeria em iteração complementar

---

## Phase 5: User Story 3 - Views especializadas: mapa, grafo e timeline (Priority: P3)

**Goal**: Mapa, grafo de comunicações e timeline bridgeados com seleção sincronizada

**Independent Test**: Mesmos dados da UI atual no caso de referência; seleção sincroniza com a tabela (cenários US3)

### Tests for User Story 3

- [X] T033 [P] [US3] Teste SWTBot/checklist de sincronização de seleção (mapa/grafo/timeline ↔ tabela) em `iped-rcp/tests/iped.rcp.tests.swtbot/src/.../SpecializedViewsTest.java` — **escrito primeiro** contra o contrato de widget-ids US3 (`iped.rcp.specialized.{map,graph,timeline}.host`) + sondas EDT-safe do bridge (`LegacyUiBridge.probeSelectedRows/probeSelectRows/probeDiagnostics`); automatiza: liveness das 3 parts bridgeadas + seleção tabela→espelho (caminho que os listeners legados observam) + espelho→tabela (exatamente o que um clique de marker do mapa / highlight worker da timeline faz: `JTable.addRowSelectionInterval`); interações intra-canvas (JS/Swing) ficam no checklist manual do inventário (SV); **VERDE no produto real (Windows, 2026-06-12, caso `F:\test_yara_java21`, 37 s; suíte completa 4 testes 0 falhas com T014/T024 revalidados na MESMA janela)**. **Fix estrutural exigido pelo teste (beneficia o produto)**: o espelhamento `UiEventsAddon` (T012) depende do agregador de seleção da part ATIVA do e4, que exige foco real do SO (ausente no harness) — os publicadores de seleção (`ResultsTablePart`, `LegacyUiBridge`) agora gravam a chave `iped.rcp.selection` DIRETAMENTE no contexto da aplicação (determinístico em qualquer ambiente; addon mantido como espelho genérico p/ terceiros)
- [X] T062b (infra desta fase) Wrapper `iped.rcp.libs` passou a embutir **iped-app** (classes do legado graph/timelinegraph + iped-geo/kharon/jfreechart/jcalendar transitivos) e exportar `bibliothek.*`: exigiu (a) execução `attach-classes-jar` no `iped-app/pom.xml` — as execuções classifierless do maven-jar-plugin sobrescrevem o artefato principal (o jar instalado era o subset hashdb de 3,7 KB!), o jar completo agora é anexado com classifier `classes`; (b) `_fixupmessages` no bnd (jar legado com referência a classe no default package — irrelevante: o wrapper importa nada)

### Implementation for User Story 3

> Infra comum em `iped-rcp/bundles/iped.rcp.specialized/` (bundle novo): `LegacyUiBridge` — UM `JTable` espelho oculto reproduz a disciplina da tabela-compartilhada do App legado (modelo: col 0 = nº da linha, col 1 = checked via `BookmarkService`; `RowSorter` obrigatório — guarda do `MapViewer.valueChanged`); seleção bidirecional com guarda de eco + coalescing em thread própria; `RcpLegacyProviders` implementa `IMultiSearchResultProvider`+`GUIProvider` sobre `SearchService`/sessão; `LegacyApp.bind` põe `App.get().appCase`/`casesPathFile` (campos públicos; `CachePersistance` lê em initializer ESTÁTICO — bind antes de qualquer classe da timeline) e registra `IBookmarksController` mínimo; `AbstractBridgedPart` = SwtAwtBridgeHost por part + forwarding de results/CHANGED, seleção e case/CLOSED + replay de seleção/resultado pré-existentes (parts são lazy).

- [X] T034 [US3] Part de mapa em `iped-rcp/bundles/iped.rcp.specialized/src/.../MapPart.java`: bridge do `MapViewer` (iped-geo, JavaFX WebView) via `SwtAwtBridgeHost`, seleção bidirecional (FR-012) — `MapViewer.init(mirrorTable, providers, providers)` intocado; contrato DockingFrames adaptado com `DefaultSingleCDockable` destacado cujo `isShowing()` reflete a visibilidade AWT do painel (SWT_AWT propaga a visibilidade da part) e `HierarchyListener` SHOWING_CHANGED → `redraw()` (equivalente do `CDockableLocationListener` legado); cliques em marker selecionam o espelho → bridge publica ao workbench → tabela segue (T033)
- [X] T035 [P] [US3] Part de grafo em `iped-rcp/bundles/iped.rcp.specialized/src/.../GraphPart.java`: bridge do `AppGraphAnalytics` (Kharon) + toolbar/sidepanel/ações (expansão, caminhos, exportação) (FR-012) — painel legado completo hospedado como está; `initGraphService()` na criação (disciplina `LoadGraphDatabaseWorker`, no-op com grafo desabilitado). **Achados p/ inventário**: (a) neste fork o grafo é out-of-process via Bolt (`iped-graph-server` em `lib/neo4j/`) — o produto RCP ainda não embarca essa árvore → "no Bolt port reported" e painel desabilitado graciosamente (resolver junto do empacotamento T052); (b) "mostrar evidência" do nó usa `FileProcessor` (App Swing completo) — degrada com erro logado; diálogos legados abrem com owner invisível (centrados na tela)
- [X] T036 [P] [US3] Part de timeline em `iped-rcp/bundles/iped.rcp.specialized/src/.../TimelinePart.java`: bridge do `IpedChartsPanel` (JFreeChart), zoom/seleção de intervalo filtrando a tabela (FR-012) — **costura aditiva no legado** (`IpedChartsPanel.GUIHost` + default que reproduz os casts `(App)` verbatim — comportamento Swing inalterado; 7 call-sites em `IpedChartPanel`/popups redirecionados; toque justificado: o código bridgeado sobrevive ao cut-over e precisava desacoplar do singleton): `updateFileListing` → slot de query filter `iped.rcp.specialized.timeline` no `FilterStateService` + `searchService.refresh()` em Job (equivalente RCP do `AppListener.updateFileListing`); `updateFilterColors` → no-op (painel de filtros mostra o estado); `getIndexedFieldNames` → `LoadIndexFields` (== `fieldGroups[last]` do ColumnsManager). Primeira exibição: `refreshChart()` + `startCacheCreation()` (cache em `<caso>/iped/data/timecache`, fallback user-home — legado); shows seguintes só re-renderizam se resultados mudaram escondido (flag própria; `isUpdated` é package-private). Highlight/check workers operam no espelho → seleção/checked fluem pelo bridge

**Checkpoint**: US1–US3 independentes; áreas mapa/grafo/timeline com paridade no inventário — **COMPLETO no Windows (2026-06-12)**: reactor 13 módulos verde (bundle novo `iped.rcp.specialized` + 3 parts no stack center do `Application.e4xmi`); **T033 VERDE no produto real** e suíte SWTBot completa 4/4 (T014/T024 revalidados); harness de paridade 23/23 revalidado com o wrapper embutindo iped-app. **Pendências (não bloqueiam US4+)**: evidência `manual` de GR-01/GR-02/TL-01 (interações intra-canvas) e grafo funcional no produto exige embarcar `lib/neo4j/` (amarrado ao empacotamento T052); perna Linux/Xvfb no CI

---

## Phase 6: User Story 4 - Acompanhar o processamento de um caso (Priority: P4)

**Goal**: Janela de progresso SWT standalone + splash + diálogos do inicializador + modo quase-ao-vivo (FR-024/026/027/029/030)

**Independent Test**: Processar evidência de referência comparando campos exibidos com a janela atual (cenários US4); `--nogui` inalterado

### Tests for User Story 4

- [X] T037 [P] [US4] Teste de fallback headless (sem display → `ProgressConsole`, processamento conclui) em `iped-rcp/tests/iped.rcp.tests.parity/src/.../HeadlessProgressTest.java` (contrato [progress-ui-events](contracts/progress-ui-events.contract.md)) — **escrito primeiro** contra o contrato: matriz do `ProgressUiChooser` (nogui→console sempre; sem display→console, nunca falha; legado `ProgressFrame` só até T059), janela recusa abrir em headless (hook `iped.rcp.progress.headless` — display real não é simulável em desktop Windows; perna sem-display verdadeira = CI Linux), console consome o conjunto congelado de eventos sem cancelar, e janela REAL abre/consome/probe quando há display (assumption-guarded). **VERDE (4/4)**. Aprendizado estrutural: o loop SWT NÃO tem a resiliência da EDT — exceção dentro de `readAndDispatch` mata o loop (descoberto com `Worker[0]` vazio); a janela ganhou catch por dispatch + guardas de array vazio

### Implementation for User Story 4

- [X] T038 [US4] Janela de progresso SWT/JFace standalone (sem OSGi) em `iped-rcp/bundles/iped.rcp.progress/src/.../ProgressWindow.java`: consome `UIPropertyListenerProvider` com todos os campos do contrato (global, por evidência, taxa/gráfico, fase/workers, contadores, erros, ETA), updates via `Display.asyncExec` (FR-026) — módulo **jar plano** no reactor (deps `provided`: engine + SWT 3.126 = nível da target 4.32, via perfis `swt-windows`/`swt-linux` no parent — perfis de pom de dependência não ativam transitivamente, consumidores declaram a property); porte campo-a-campo do `ProgressFrame` (4 seções em `Table` SWT com o MESMO shading por pct, ETA/velocidades com fórmulas verbatim, taskbar via `TaskItem`) + adições do contrato: items/s, sparkline de throughput; publicador intocado (FR-028): listener NÃO-UI + marshaling próprio; chaves novas `ProgressWindow.*` em EN+PT-BR; **smoke real no release (2026-06-12)**: pasta processada com a janela dirigindo o progresso, 0 ERROR, exit 0
- [X] T039 [US4] Integrar a janela na JVM de processamento em `iped-app/src/main/java/iped/app/processing/Main.java`: substituir `ProgressFrame`, manter `--nogui`→`ProgressConsole` e fallback headless automático; o botão "abrir análise" lança o produto RCP como processo separado sobre o caso em processamento (modo quase-ao-vivo — research R14, FR-029) ou sobre o caso pronto (FR-026; toque mínimo — FR-028) — toque mínimo de fato: `ProgressUiChooser` (função pura, testada no T037) + `SwtProgressBridge` (descoberta em runtime: classpath → `-Diped.progress.ui.dir`/env `IPED_PROGRESS_UI_DIR` → `<root>/ui/progress`, via URLClassLoader) — o build padrão do iped-app NÃO ganhou dependência de SWT; sem os jars implantados o fallback é o `ProgressFrame` legado (até o cut-over T059) e sem display é o console com warn; `--nogui` é caminho de código intacto (**smoke real: saída de console legada, exit 0**)
- [X] T040 [P] [US4] Ações pausar/continuar/abortar com confirmação + "abrir análise" (durante o processamento — quase-ao-vivo — ou ao final) lançando o produto RCP (contrato [case-launcher-packaging](contracts/case-launcher-packaging.contract.md)) em `iped.rcp.progress` (validar contra produto de dev até T054) — pause/continue = toggle `worker.state` legado verbatim; Abort = botão com `MessageBox` de confirmação → `provider.cancel(true)`; FECHAR a janela deixou de abortar (confirmação informativa; exigência do contrato — divergência PG-08 registrada no inventário); `AnalysisUiLauncher` resolve o launcher em `-Diped.rcp.ui.home`/env → `<caso>/iped/ui` → `<root>/ui` e lança `iped-ui[.exe] <caso>` destacado (validação no produto de dev via override até T052/T054)
- [X] T063 [US4] Implementar modo quase-ao-vivo em `iped-rcp/bundles/iped.rcp.core/src/.../CommitMonitor.java`: detecção de nova geração do índice, recarga do leitor read-only com troca atômica do resultado + evento `results/CHANGED`, storages SQLite read-only com busy timeout, desligamento ao fim do processamento; sem visibilidade de itens não consolidados (divergência registrada no inventário) (FR-029/FR-030, research R14, data-model `CaseSession.commitMonitor`; depende do GO de T062 — ID fora de ordem: adicionado em remediação pós-análise) — `CommitMonitor` (poll 500 ms de `SegmentInfos.getLastCommitGeneration` com `FSDirectory` efêmero; relocação do índice temp→caso conta como mudança; `EvidenceStatus` dispara reload FINAL e a parada; crash do processamento = idling inofensivo) + `CaseSessionService.reloadSources()`: a recarga reabre a `IPEDMultiSource` INTEIRA e troca atomicamente dentro da `CaseSession` (os mapas id↔doc/categorias/bookmarks do `IPEDSource` são privados do engine — `openIfChanged` interno exigiria toque, FR-028 = zero) com aposentadoria de 1 ciclo da fonte antiga (leitores em voo) e tópico novo `case/RELOADED`; listeners de reload: `BookmarkService.flush` ANTES (edições assíncronas entram no estado recarregado) e `SearchService.refresh` DEPOIS (mesma query → `results/CHANGED`; serviços resolvem a fonte via sessão dinamicamente, então o swap propaga sozinho); storages preview já read-only (T008). **`NearLiveReloadTest` VERDE** no harness (swap + listeners + contagens idênticas + leitura do result set antigo na graça + 2º ciclo fecha o aposentado; ~1,4 s/ciclo no caso de referência); detecção concorrente a processamento real já validada pelo spike T062 (GO); validação produto+processamento ponta-a-ponta fica com T056/T057
- [X] T041 [P] [US4] Splash e diálogos: splash nativo do produto de análise (`iped.rcp.product`), feedback de inicialização do processamento em SWT e diálogos de erro do inicializador via `MessageBox`/JFace, aposentando `SplashScreenManager`/`StartUpControl` no cut-over (FR-027) — splash nativo do produto já entregue no T011; feedback de inicialização = a própria janela SWT abre cedo ("Starting..." + eventos `mensagem`/`decodingDir`, como o frame legado); erros do inicializador: `ProgressWindow.showStartupError` (Display efêmero + `MessageBox`) ligado ao catch do `Main.main` via bridge (best-effort, só com display e módulo implantado); `SplashScreenManager`/`StartUpControl` permanecem ATIVOS até o cut-over T059, como o contrato prevê

**Checkpoint**: progresso com paridade de campos; headless e `--nogui` intactos; quase-ao-vivo medido contra o gate FR-030 (T062 GO + T063) — **COMPLETO no Windows (2026-06-12)**: módulo novo `iped.rcp.progress` (jar plano, 14º módulo do reactor); harness de paridade **28 testes, 0 falhas** (HeadlessProgressTest 4/4 + NearLiveReloadTest + revalidação integral US1/US2); **smokes reais no release** (`target/release`, pasta de 4 arquivos): `--nogui` → console legado intacto/exit 0, e com `IPED_PROGRESS_UI_DIR` → janela SWT dirigiu o processamento inteiro (0 ERROR, caso íntegro); inventário §14 atualizado (PG-01..07/09..11 → paridade, PG-08 → divergência justificada, conjunto de propriedades congelado). Gate FR-030: detecção medida pelo spike T062 (GO, −5,9%); o ciclo de reload custou ~1,4 s no caso de referência. **Pendências (não bloqueiam US5+)**: inspeção visual da janela (T058/AN-04 — screenshots Win/Linux), validação ponta-a-ponta do quase-ao-vivo com produto aberto DURANTE processamento real + medição formal (T056/T057), empacotamento de `ui/progress/` no release (T052) e perna Linux do fallback sem display (CI)

---

## Phase 7: User Story 5 - Workspace pessoal: layout, temas, idioma e escala (Priority: P5)

**Goal**: Persistência de layout por usuário+caso, temas, HiDPI, atalhos e i18n completa

**Independent Test**: Reorganizar → reiniciar → restauração fiel; idiomas e HiDPI legíveis (cenários US5)

### Tests for User Story 5

- [X] T042 [P] [US5] Teste SWTBot de persistência/restauração de layout entre reinícios em `iped-rcp/tests/iped.rcp.tests.swtbot/src/.../WorkspacePersistenceTest.java` (SC-005); incluir caso de estado corrompido (`workbench.xmi` inválido → boot com layout padrão, sem falha) — **escrito primeiro** contra o contrato do `WorkspaceLocationResolver` (T043); 7 testes: área por caso (estável/insensível à ordem/distinta + root sobreponível), corrompido + versão incompatível → reset com `.bak` (nível de arquivo = exatamente o caminho que o lifecycle roda antes do load; a perna "boot com estado pré-corrompido" não é encenável in-harness — workbench já está de pé quando os testes rodam), persistência viva (tag no modelo + `IModelResourceHandler.save()` = caminho do shutdown + assert no `workbench.xmi`) e **restauração REAL entre lançamentos** via protocolo de marker (2 execuções consecutivas do harness = 2 boots do produto), mais o anti-`!key!` (SC-006/T047); **VERDE no produto real (Windows, 2026-06-12; suíte completa 11/11 com T014/T024/T033 revalidados)** — o test-first pegou 2 bugs reais do resolver (prefixo do nome dependente da ordem em multicase; semântica FRESH×COMPATIBLE em área já carimbada)

### Implementation for User Story 5

- [X] T043 [US5] Persistência de workspace em `iped-rcp/bundles/iped.rcp.app/src/.../WorkspaceLocationResolver.java`: área `~/.iped/ui-workspaces/<case-id>/` (hash do path canônico), reset automático de estado corrompido/incompatível (research R5, FR-017, data-model `WorkspaceState`) — `case-id` = `<nome-sanitizado>[+N]-<sha256-16>` sobre paths canônicos ordenados (case-insensitive no Windows); aplicado no `LifeCycle.@PostContextCreate` ANTES do load do modelo (padrão e4 canônico; ordem `createE4Workbench`→lifecycle→`loadApplicationModel`→`checkInstanceLocation`/lock verificada no bytecode da target platform); `-data` explícito vence (harness/tests) mas a validação de estado roda igual; produto perdeu o `-data` fixo (vira `osgi.instance.area.default` de segurança); carimbo `layout.version` (=2; bump a cada mudança incompatível do `Application.e4xmi` — modelo persistido vence o estático) + validação XML do `workbench.xmi` → reset move p/ `.bak`; **nota p/ T055**: 2ª instância no MESMO caso agora esbarra no lock e4 do workspace por-caso APÓS abrir a sessão (comportamento a decidir/medir lá)
- [X] T044 [P] [US5] Temas em `iped.rcp.app`: nativo por padrão, escuro seguindo o SO + toggle manual em preferência, CSS e4 mínimo para superfícies não-nativas (FR-018/025, research R8) — preferência global em `~/.iped/UiTheme.txt` (`system|light|dark`; estilo `UiScale.txt` — tema é do usuário, não viaja com caso/workspace); dark efetivo = pref ou `Display.isSystemDarkTheme()`; win32 = display-data do SWT (explorer theme, title coloring, menu bar — fixadas na criação dos shells, por isso aplicadas no lifecycle), GTK = `setDarkThemePreferred` por reflexão (best-effort); CSS: temas `iped.rcp.theme.{native,dark}` (`native.css` vazio, `dark.css` ~20 linhas só chrome/containers); **achado de plataforma (bytecode verificado)**: o `E4Application` SOBRESCREVE a chave de contexto `cssTheme` depois do lifecycle e o CSS engine fica sempre ativo (default `e4_default`) — o canal confiável é a pref InstanceScope `themeid` que o `ThemeEngine.restore()` prioriza, pinada a TODO boot (dark→dark, claro→native — limpa pin obsoleto quando o SO volta a claro no modo system); menu `View → Theme` (radio sincronizado da pref) com troca viva do CSS + aviso i18n de restart p/ chrome nativo
- [X] T045 [P] [US5] HiDPI/escala: honrar `uiScale` mapeando para autoscale SWT; verificação multi-monitor (FR-019) — **correção factual**: a fonte legada real é `~/.iped/UiScale.txt` (classe `UiScale`; `LocalConfig` não tem uiScale — registrado em WS-04 do inventário); `EarlyStartup` (Bundle-Activator, autostart start-level 4 no `.product`) lê o arquivo e seta `swt.autoScale` (fator→percentual) ANTES de qualquer classe SWT (DPIUtil congela a property no primeiro load — tarde demais no lifecycle, dado que o Display nasce antes dele); `auto` = autoscale nativo por monitor (FR-025); `-Dswt.autoScale` explícito vence; diálogo `View → UI Scale...` reusa o `UiScale` legado (mesmo arquivo/formato/chave i18n `MenuListener.UiScaleDialog`); lado AWT dos viewers bridgeados alinhado via `UiScale.loadUserSetting()` no lifecycle (`sun.java2d.uiScale`); verificação multi-monitor = manual no T058 (inventário)
- [X] T046 [P] [US5] Keybindings e4 dos atalhos de triage atuais + tabela de mapeamento documentada em `specs/004-rcp-gui-migration/keybindings-map.md` (FR-021) — Ctrl+B = comando+handler+binding e4 (`M1+B`, contexto window; mesmo diálogo do menu de contexto, seleção viva da chave compartilhada); atalhos da tabela no `SWT.KeyDown` da part (semântica de seleção/foco como o `KeyListener` legado): Espaço (toggle com valor da primeira linha; space-check nativo de linha única suprimido), Ctrl/Alt+R/P/F/D (check/uncheck + subitens/pai/referencia/referenciado — queries extraídas do T019 para `RelatedItemsQueries`, busca em Job + escrita em lote nova `BookmarkService.setCheckedEngine` = disciplina multi-setting legada), Ctrl+C (divergência justificada: linha em vez de célula — `FULL_SELECTION` SWT sem foco de célula); Ctrl+Q/W (blur/gray) e atalhos rápidos por bookmark documentados como pendentes (T026/BM-*); correção factual do KB-01 no inventário (descrição não batia com o código legado)
- [X] T047 [US5] Varredura i18n: chaves novas da UI adicionadas aos 6 bundles em `iped-app/resources/localization/`, PT-BR/EN completos, fallback EN sem chaves cruas (FR-020, SC-006, research R7) — varredura automatizada (`specs/004-rcp-gui-migration/tools/t047_add_keys.py`, idempotente, inserção alfabética): **142 entradas** em 10 arquivos — `AppLifeCycle.*`/`ColumnsDialog.*`/`ContentViewer.*`/`ExportItems.*`/`ResultsTable.*`/`SearchBar.*` completadas em de/es/fr/it, `ProgressWindow.*` (engine) idem, e chaves novas `RcpMenu.*`/`RcpParts.*`/`RcpTheme.*` nos 6 locales; labels de parts/menus do `Application.e4xmi` localizados em runtime no `@ProcessAdditions` (mapa elementId→chave legada: `App.Gallery`, `TreeViewModel.RootName`, `App.Links`...; o mecanismo `%key` do e4 lê OSGI-INF/l10n por bundle — incompatível com o catálogo central, R7) — também corrige labels persistidos em outra língua (modelo restaurado vence o estático); guarda anti-`!key!` no T042; **aprendizado de harness**: lookups de abas no SWTBot têm de resolver pelos catálogos (o harness roda no locale do sistema = pt_BR — labels EN hardcoded quebraram T024/T033 e foram corrigidos)

**Checkpoint**: SC-005 verificado; sem chaves cruas em PT-BR/EN — **COMPLETO no Windows (2026-06-12)**: reactor 14 módulos verde; harness de paridade **28 testes, 0 falhas** (4 skips justificados: multicase sem `-Dcase.dir2` + features de similaridade ausentes no caso triage) e suíte SWTBot **11/11 no produto real** (T042 novo + T014/T024/T033 revalidados); perna de restauração entre lançamentos exercitada com 2 execuções consecutivas do harness (protocolo de marker do T042). **Achado de ambiente (registrado p/ futuro)**: builder Java do IDE (JDT.LS/m2e) reempacotou `iped.rcp.core` em `target/` NO MEIO do build do reactor SEM o header `Service-Component` (manifest sem injeção do tycho-ds-plugin) → SCR ignora o bundle → `ICaseSessionManager` null no harness (exit 13); o jar instalado no `.m2` estava íntegro — mitigação: rodar a suíte SWTBot por subset `-pl tests/iped.rcp.tests.swtbot` (provisiona do `.m2`), mantendo o conjunto `.m2` consistente (rebuild parcial de bundle exige rebuildar a feature junto — ela pina qualifiers exatos). Pendências (não bloqueiam US6): perna Linux/Xvfb no CI; smoke visual de temas/escala (AN-06/T058)

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
