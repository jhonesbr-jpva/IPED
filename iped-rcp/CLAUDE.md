# Módulo `iped-rcp`

> **Nova GUI do IPED em Eclipse RCP (e4 puro)**. Reactor **Tycho** dedicado que substitui — por *cut-over total* — todas as superfícies gráficas Swing/JavaFX do IPED (UI de análise, janela de progresso, splash, diálogos do inicializador) por uma aplicação **Eclipse 4 sobre SWT/JFace/OSGi**, com widgets nativos do SO. Feature base **004-rcp-gui-migration** — ver [specs/004-rcp-gui-migration/plan.md](../specs/004-rcp-gui-migration/plan.md), `research.md` (decisões R1–R14), `data-model.md`, `contracts/` e `parity-inventory.md`.
>
> A feature **005-case-creation-wizard** estende esta GUI com **criação/abertura de casos** (menu File com New/Open Case, wizard que lança o `Bootstrap` out-of-process) e **editor de perfis completo** — bundle de UI `iped.rcp.casecreation` (§11.1) + serviços `processing/` e `profiles/` no `iped.rcp.core` (§6). Ver [specs/005-case-creation-wizard/plan.md](../specs/005-case-creation-wizard/plan.md).

## 1. Propósito e estratégia

- Reescrever **apenas a camada de apresentação**. O engine (`iped-engine`, Lucene 9.2, Tika 2.4, Sleuthkit) roda **inalterado** dentro de um bundle wrapper.
- **Paridade funcional completa** contra inventário congelado (SC-001), em **Windows x64** (JRE embarcada) e **Linux x64/GTK** (Java do sistema). macOS fora do escopo.
- Shell, árvores, tabela de resultados, galeria, filtros, busca e bookmarks reescritos em **SWT/JFace nativo**.
- Os **viewers de conteúdo existentes** (`iped-viewers`: PDF, imagem, e-mail, hex, Office…) e as **views especializadas** (mapa, grafo, timeline) são **preservados** e hospedados via bridge `SWT_AWT` — divergência contida fora da camada de apresentação (FR-028, ou seja: nenhum viewer é reescrito).
- **API de extensão provisória** (`iped.rcp.api`) permite contribuir parts de terceiros sem fork.
- Evidência **somente leitura** (FR-004); escritas permitidas: bookmarks (formato atual, FR-005), filtros salvos e preferências de UI.

> ⚠️ **Não é** um módulo Maven comum: é um reactor **Tycho** com perfil próprio. As outras UIs (`iped-app/ui`, `graph/`, `timelinegraph/`, `BootstrapUI`/`AppMain`, DockingFrames) permanecem no repositório **até o release de cut-over**, servindo como fonte das lógicas a portar e referência do inventário de paridade.

## 2. Estrutura do reactor

```text
iped-rcp/                                  # reactor Tycho (perfil -P rcp no pom raiz)
├── pom.xml                                # parent Tycho; versão 4.4.0-SNAPSHOT em lockstep com o raiz
├── target-platform/
│   └── iped-rcp.target                    # Eclipse Platform 4.32 + Nebula Gallery/NatTable + SWTBot (p2 pinado)
├── bundles/
│   ├── iped.rcp.libs/                     # WRAPPER: iped-engine + iped-app(classes) + deps num único class space
│   ├── iped.rcp.sleuthkit/                # HOST bundle do org.sleuthkit.datamodel (jar wrapped; sem fragment nativo)
│   ├── iped.rcp.api/                      # API de extensão PROVISÓRIA (serviços, tópicos de evento, âncoras)
│   ├── iped.rcp.core/                     # sessão de caso, serviços headless (DS), i18n, eventos, modelos de árvore
│   ├── iped.rcp.views/                    # parts SWT: resultados, galeria, árvores, metadados, filtros, busca, bookmarks
│   ├── iped.rcp.casecreation/             # menu File: New/Open Case (wizard out-of-process) + editor de perfis (feature 005)
│   ├── iped.rcp.viewers/                  # hosts SWT_AWT dos viewers de conteúdo existentes
│   ├── iped.rcp.specialized/              # parts bridgeadas: mapa, grafo, timeline
│   ├── iped.rcp.progress/                 # janela de progresso SWT standalone (PLAIN JAR, roda na JVM de processamento)
│   └── iped.rcp.app/                      # Application.e4xmi, LifeCycle, splash, temas, scale, drop-ins, produto
├── features/
│   └── iped.rcp.feature/                  # agrupa os 9 bundles de produção
├── products/
│   └── iped.rcp.product/                  # iped-ui.product (.product) + p2-director (win64 + linux64)
├── samples/
│   └── iped.rcp.sample.view/              # extensão drop-in de exemplo (US6/SC-007) — NÃO entra na feature
└── tests/
    ├── iped.rcp.tests.swtbot/             # fluxos de UI (SWTBot, CI Linux/GTK via Xvfb)
    └── iped.rcp.tests.parity/             # harness headless de paridade sobre a API do engine
```

## 3. Pilha de tecnologia (específica do módulo)

| Camada | Stack |
|---|---|
| Plataforma UI | **Eclipse Platform 4.32** (release train 2024-06 — primeiro a exigir Java 21), e4/SWT/JFace/Equinox, EPL-2.0 |
| Aplicação | e4 **puro** (`org.eclipse.e4.ui.workbench.swt.E4Application`) — **sem** camada de compatibilidade `org.eclipse.ui` |
| Build | **Eclipse Tycho 4.0.10** (`tycho-maven-plugin`, `tycho-ds-plugin`, `target-platform-configuration`) |
| Galeria | **Nebula Gallery 3.1.1** (widget virtual); **NatTable 2.4.0** como fallback da tabela virtual |
| Serviços headless | **OSGi Declarative Services** (anotações DS; XML gerado pelo `tycho-ds-plugin`) |
| Bridge UI legada | **`SWT_AWT`** (Swing/JavaFX dentro de `Composite` SWT) |
| Testes UI | **SWTBot 4.2.0** (test-time only) + JUnit 5 + tycho-surefire |
| JRE de build | **Liberica Full 21** (`H:\java\LibericaJDK-21-Full`) — JavaFX ainda necessário para os viewers bridgeados |
| Engine | `iped-engine` **inalterado**, embutido em `iped.rcp.libs` |

## 4. O bundle wrapper `iped.rcp.libs` (chave da arquitetura)

Empacota `iped-engine` + `iped-app` (classifier `classes`, p/ as views bridgeadas) + **toda a árvore de dependências não-OSGi** num **único class space OSGi** (research R3). Construído com o **Felix `maven-bundle-plugin`** (não Tycho) e consumido pelos bundles Tycho via `pomDependencies=consider`.

Instruções bnd relevantes (em [bundles/iped.rcp.libs/pom.xml](bundles/iped.rcp.libs/pom.xml)):
- `Embed-Dependency: *;scope=compile|runtime` + `Embed-Transitive` → engine roda no classpath plano de sempre (FR-028).
- `Import-Package: !*` → bundle autocontido; pacotes JDK vêm de *boot delegation*.
- `DynamicImport-Package: *` → engine usa reflection/SPI pesado (Tika, JNA, scripting).
- `Export-Package` cresce **sob demanda**, conforme as UI bundles consomem pacotes: hoje `iped.*`, `org.slf4j`, `org.apache.lucene.*`, `org.apache.tika.*`, `bibliothek.*` — todos `x-internal:=true`. **Não exporta mais `org.sleuthkit.datamodel`** (movido para `iped.rcp.sleuthkit`, §4.1); em troca exporta os pacotes que o datamodel toca em-processo: `org.sqlite`, `com.google.common.*`, `com.google.gson.*`, `com.mchange.v2.c3p0`, `com.zaxxer.sparsebits`, `org.apache.commons.lang3`, `org.joda.time`, `org.postgresql.util`.
- `Embed-Dependency: *;scope=compile|runtime;artifactId=!sleuthkit` → o jar do Sleuthkit **não** é mais embutido aqui.
- `_nouses:=true` e `_fixupmessages` silenciam warnings benignos de split-package (Lucene) e default-package (jars legados).

> ⚠️ **Cuidados**: jars assinados (ex.: BouncyCastle JCE) podem não funcionar embutidos — se aparecer, extrair como bundle wrapped separado. Ao precisar de um pacote do engine numa UI bundle, **adicione-o ao `Export-Package`** do wrapper (sempre `x-internal`), não crie novas dependências diretas.

### 4.1 Bundle host do Sleuthkit — `iped.rcp.sleuthkit`

Separado do wrapper para que `org.sleuthkit.datamodel` viva no seu próprio class space OSGi. Também construído com o **Felix `maven-bundle-plugin`** (consumido via `pomDependencies=consider`).

- Embute **só** `org.sleuthkit:sleuthkit` (o pom `install-file` não tem deps transitivas) e exporta `org.sleuthkit.*;x-internal:=true`. `Import-Package: !*` + `DynamicImport-Package: *`.
- **Sem fragment / `Bundle-NativeCode`**: o JNI do Sleuthkit roda **out-of-process** (`SleuthkitServer` = `java -cp <caso>/iped/lib/*`), e o `libtsk_jni` é auto-extraído do próprio jar pelo `org.sleuthkit.datamodel.LibraryUtils`. Um fragment nativo nunca seria acionado pelo framework OSGi aqui.
- **Uso in-process**: a UI de análise abre o `SleuthkitCase` (SQLite via `sqlite-jdbc`, **sem** JNI) para navegar a árvore TSK (`IPEDSource.openSleuthkitCase`). Esse caminho referencia sqlite/guava/gson/c3p0/joda/commons-lang3/sparsebits/postgresql — que ficam embutidos no `iped.rcp.libs` e são resolvidos aqui via dynamic import (cópia única; um único load nativo do sqlite).

## 5. API de extensão provisória — `iped.rcp.api`

Único pacote que terceiros podem compilar contra (`Export-Package: iped.rcp.api;version="0.1.0"`). **Provisória**: pode mudar **sem ciclo de deprecação** até ser declarada estável (1–2 releases após o cut-over) — só então entra no regime do **Princípio I** da constituição. Contrato em [contracts/ui-extension-api.contract.md](../specs/004-rcp-gui-migration/contracts/ui-extension-api.contract.md).

| Tipo | Papel |
|---|---|
| `ICaseSessionService` | sessão de caso aberta (read-only sobre evidência). |
| `ISearchService` | `count(query)` / `search(query, offset, limit)` — mesma sintaxe da UI atual (FR-006). |
| `IItemAccessService` | acesso a `IItem`/metadados a partir de `ItemId`. |
| `IBookmarkService` | leitura/gravação de bookmarks (formato atual, FR-005). |
| `ItemId` | identificador estável `(sourceId, itemId)`. |
| `SelectionContext` | payload da seleção publicada no `ESelectionService` (chave `UiEventTopics.SELECTION_KEY`). |
| `ModelAnchors` | ids **estáveis** de `MPartStack` onde fragments de terceiros podem ancorar (`PART_STACK_CENTER/LEFT/RIGHT/BOTTOM`). |
| `UiEventTopics` | tópicos públicos do `IEventBroker`: `CASE_OPENED/CLOSED/RELOADED`, `RESULTS_CHANGED`, `BOOKMARKS_CHANGED`. |

Tudo fora de `iped.rcp.api` é interno (`x-internal`) e **sem garantia de compatibilidade**.

## 6. Core de serviços — `iped.rcp.core`

UI-toolkit free de propósito (o harness de paridade headless o dirige direto). Serviços registrados como **OSGi DS** (`@Component`).

- **`CaseSessionService` / `CaseSession` / `ICaseSessionManager`** ([session/](bundles/iped.rcp.core/src/main/java/iped/rcp/core/session/)): ciclo `OPENING → READY → CLOSING → CLOSED` sobre um `IPEDMultiSource` (caso único embrulhado num multisource de 1 → consumidores tratam ambos uniformemente). Single + multicase (FR-001/002), detecção de mídia read-only, `askImagePathIfNotFound=false` (serviço nunca abre diálogo Swing do engine — falhas viram `CaseOpenException`).
- **Modo near-live** (FR-029, task T063, gated pelo spike de contenção SQLite T062): quando o caso ainda está sendo processado, `CommitMonitor` observa as gerações do índice e **troca atomicamente** o `source` da sessão a cada consolidação (`CaseSession.swapSource` sob `reloadLock`), publicando `case/RELOADED` + `results/CHANGED`. Consumidores **não devem cachear** o source entre eventos.
- **i18n** (`i18n/Messages`, research R7/FR-020): adaptador para os catálogos existentes (`iped-app/resources/localization/*.properties`, distribuídos como `localization/`). **Fonte única** de traduções — sem catálogos por-bundle. Precedência de locale: `iped-locale` (system prop) > `-nl`/`osgi.nl` (Equinox) > default da JVM; empurra o resultado de volta no `LocaleResolver` para o engine/viewers legados renderizarem no mesmo idioma.
- **Eventos** (`events/`): `UiEventPublisher` desacopla a publicação no `IEventBroker` do e4 (wired em T012) do serviço de sessão.
- **Filtros, metadados, árvores** (`filters/`, `metadata/`, `trees/`): estado de filtros, agregação de facetas (`MetadataAggregator`, `ValueCount*`), modelos de árvore (evidência, categorias, bookmarks, AI filters), `ResultSet`/`ResultSorter`.
- **Lançamento de processamento** (`processing/`, feature 005): `BootstrapCommandBuilder` mapeia um `NewCaseRequest` (+ `DataSourceEntry`/`CommonOptions`/`AdvancedOptions`/`ProcessingMode`) para os args do `Bootstrap` (`-d/-o/-profile/…`) e valida (FR-008, **nunca** `--nogui`); `ProcessingLaunchService` (DS) resolve java/`iped.jar` e dispara o processamento **out-of-process** com guarda de conflito de saída (FR-024) e expõe `profilesDir()`. Coberto por `BootstrapCommandBuilderTest`/`ProcessingLaunchServiceTest`.
- **Perfis** (`profiles/`, feature 005): `ProfileService` descobre (`listProfiles`) e edita perfis dirigido pelos arquivos de config — `loadModel` faz merge base (config canônico = `profilesDir.getParent()`) ← override do perfil em `ProfileConfigModel`/`ConfigFileGroup`/`ConfigOption` (+ `AdvancedFile` p/ xml/json; comentário `#` vira descrição); `saveModel` grava **só** as chaves redefinidas (perfis enxutos, UTF-8) e poda arquivos sem override; `createProfile`/`deleteProfile` com embarcados read-only (FR-018). Toolkit-free, coberto por `ProfileServiceTest`.

## 7. Parts SWT — `iped.rcp.views`

Reescrita nativa das superfícies principais (substituem as classes Swing de `iped-app/ui`):
- `ResultsTablePart` — tabela de resultados (SWT virtual; NatTable como fallback).
- `gallery/GalleryPart` (+ `GalleryThumbProvider`, `SwtImages`) — galeria sobre **Nebula Gallery**.
- `trees/` — `EvidenceTreePart`, `CategoryTreePart`, `BookmarkTreePart`, `AiFiltersTreePart`.
- `metadata/MetadataPanelPart`, `filters/FiltersPanelPart`, `SearchBarPart` (+ `SearchJobs`).
- `bookmarks/BookmarkManagerDialog`, `handlers/ManageBookmarksHandler`, `CheckActions`.
- `export/ExportActions`, `report/ReportWizard`, `similarity/SimilarityActions`, `ColumnsConfigDialog`, `ResultColumns`, `AuxTablesPart`.

Operações longas usam a **Jobs API do e4** (`org.eclipse.core.jobs`) fora da UI thread — substituem `CancelableWorker`/SwingWorker.

## 8. Bridge de viewers — `iped.rcp.viewers`

Hospeda o stack `iped-viewers` **sem reescrevê-lo**.
- **`bridge/SwtAwtBridgeHost`** (research R4): **um** `Composite` `SWT_AWT` por part, criado uma vez e **nunca reparentado** (reparenting é a maior fonte de glitches de z-order no GTK). Sandwich pesado `Frame > Panel > JRootPane`; forwarding de foco SWT→AWT; `sun.awt.xembedserver=true` no GTK. Construtor na **UI thread SWT**; conteúdo Swing sempre tocado na **EDT** internamente.
- **`part/ContentViewerPart`** (T020/FR-011): hospeda o `MultiViewer` (seleção por MIME) dentro do bridge. Espelha as abas do `ViewerController` legado.
- `part/RcpTextViewer` (novo), `part/RcpAttachmentSearcher`, `bridge/UiThreads`.

## 9. Views especializadas — `iped.rcp.specialized`

Parts bridgeadas das views que dependem de tecnologia AWT/Swing/JavaFX pesada:
- `parts/MapPart` (mapa — `iped-geo`, JavaFX WebView), `parts/GraphPart` (grafo — Kharon/Neo4j), `parts/TimelinePart` (JFreeChart), sobre `parts/AbstractBridgedPart`.
- `bridge/` — `LegacyApp`, `LegacyUiBridge`, `MirrorResultsModel`, `RcpLegacyProviders`: adaptam o contrato `ResultSetViewer`/DockingFrames (subclasse de `DefaultSingleCDockable`) à visibilidade de part do e4.

## 10. Janela de progresso — `iped.rcp.progress`

**Plain JAR (não é bundle OSGi)** — roda no **classpath plano da JVM de processamento** (`iped.app.processing.Main`), ao lado do engine, substituindo o Swing `ProgressFrame` (FR-026; `--nogui`/`ProgressConsole` intactos). Research R10, contrato [progress-ui-events.contract.md](../specs/004-rcp-gui-migration/contracts/progress-ui-events.contract.md).
- `ProgressWindow` (SWT puro), `ThroughputCanvas`, `AnalysisUiLauncher`.
- Dependências `provided`: o engine vem do classpath de processamento; SWT é deployado lado a lado (release `ui/progress/`; em dev aponte `iped.progress.ui.dir` / `IPED_PROGRESS_UI_DIR` para uma pasta com este jar + o jar SWT da plataforma).

## 11. Aplicação e produto — `iped.rcp.app`

- **`LifeCycle`** (e4 lifecycle): em `@PostContextCreate` resolve o caso a partir dos **args de programa**; se houver caso, carrega drop-ins (antes do model), resolve workspace por-caso, aplica tema e scale, e **abre a sessão antes do workbench renderizar** (splash nativo dá o feedback — substitui `SplashScreenManager`/`StartUpControl`, FR-027). **Sem caso, sobe um workbench vazio** dirigido pelo menu File (feature 005, T005 — substituiu o `DirectoryDialog` de boot). `@ProcessAdditions` localiza labels do model (R7, incl. os itens do menu File via `MODEL_LABEL_KEYS`) e seta o título. `@PreSave` fecha a sessão.
  - Args aceitos (paridade com `AppMain`): um ou mais caminhos de caso e/ou `-multicases <dir-ou-txt>`; opções `-xxx` desconhecidas são ignoradas.
- **`startup/EarlyStartup`** (Bundle-Activator, start level 4 eager): seta `swt.autoScale` **antes** de qualquer classe SWT (T045/FR-019).
- **Temas** (`theme/ThemeManager`, `SetThemeHandler`, `ThemePreferences`; research R8/FR-018): **widgets nativos por padrão** — o engine CSS só é inicializado quando o tema efetivo é *dark*. Par de temas em [plugin.xml](bundles/iped.rcp.app/plugin.xml): `iped.rcp.theme.native` (vazio) e `iped.rcp.theme.dark`.
- **Scale** (`settings/UiScaleHandler`) — lê/grava `~/.iped/UiScale.txt`, alinhando AWT/Swing bridgeados.
- **Workspace** (`WorkspaceLocationResolver`, research R5/FR-017): área por-usuário/por-caso em `~/.iped/ui-workspaces/<case-id>/` (**não** dentro do caso — melhora o status quo em mídia read-only). Resolvida antes do model carregar; `-data` explícito ainda vence.
- **Drop-ins** (`DropinBundleLoader`, US6/FR-022): instala/inicia todo `.jar` em `plugins-ext/` da instalação (e de `<case>/iped/ui/`) **antes** do model carregar, para que `fragment.e4xmi` apareça no registry. Jar quebrado é logado e pulado — **o produto sempre sobe**. Sem p2/auto-update.
- **Menus app-level** (em [Application.e4xmi](bundles/iped.rcp.app/Application.e4xmi); handlers em `iped.rcp.app.handlers`/diálogos em `iped.rcp.app.about`):
  - **File ▸ Exit** — `QuitHandler` → `IWorkbench.close()` (shutdown limpo do e4: `@PreSave` fecha a sessão); atalho `Ctrl+Q` (`M1+Q`).
  - **Help ▸ About IPED** — `AboutHandler` → `AboutDialog` (estilo About do Eclipse: produto/versão de `iped.engine.Version` + botão **Installation Details** → `InstallationDetailsDialog`, abas **Features** (scan de `<install>/features/`, label/provider via regex no `feature.xml` — sem DOM), **Plugins** (`BundleContext.getBundles()`) e **Configuration** (dump de `System.getProperties()` + copiar)). SWT/JFace **puro** — o e4 puro não traz os diálogos About/Installation do `org.eclipse.ui` (FR-028, fora da camada bridgeada).
  - Labels localizados em `LifeCycle.MODEL_LABEL_KEYS` → chaves `RcpMenu.*`/`RcpAbout.*` (EN + PT-BR). O menu **View** (Theme/UI Scale) vem dos handlers de tema/escala acima.

> ⚠️ **Modelo e4 persistido vence sobre o `Application.e4xmi`**: ao mudar menus/parts, apague o `workbench.xmi` em `~/.iped/ui-workspaces/<id>/.metadata/.plugins/org.eclipse.e4.workbench/` para o e4 carregar o modelo novo (o `LifeCycle` re-aplica só os **labels**, não a estrutura).

**Modelo da aplicação**: [Application.e4xmi](bundles/iped.rcp.app/Application.e4xmi) (part stacks ancorados por `ModelAnchors`; menus File/View/Help). **Produto**: [iped-ui.product](bundles/iped.rcp.product/iped-ui.product) — feature-based, launcher `iped-ui`. `vmArgs`: os `--add-opens` Java 21 do engine, `-Dorg.osgi.framework.bootdelegation=javafx.*,com.sun.*,jdk.*` (bridge AWT/FX, R4), `-Djava.security.manager=allow` (engine instala SM no Java 21 — **aborta no Java 24+/JEP 486**, daí o `-vm` na jre 21 embarcada ser obrigatório) e **`-Dosgi.bundlefile.limit=2000`** — sem ele o Equinox faz thrashing de open/close nos ~475 jars aninhados do `Bundle-ClassPath` do `iped.rcp.libs`, prendendo ~1 core por dezenas de segundos no startup (ver memória `project_iped_rcp_startup_bundlefile`).

### 11.1 Criação e abertura de casos — `iped.rcp.casecreation` (feature 005)

Bundle de UI que **aposenta o `iped.exe` como entrada interativa** de criação de casos (o shim segue distribuído para uso headless/automação — remoção é passo futuro). Contribui o menu **File** e seus diálogos; toda a lógica sem toolkit fica no `iped.rcp.core` (§6, `processing/` + `profiles/`).

- **Menu File** (em [Application.e4xmi](bundles/iped.rcp.app/Application.e4xmi)): **New Case…**, **Open Case…**, **Manage Profiles…** (comandos `iped.rcp.command.{newcase,opencase,manageprofiles}` + handlers no bundle; labels localizados em `LifeCycle.MODEL_LABEL_KEYS` → chaves `RcpMenu.*`).
- **New Case** (`wizard/NewCaseWizard` + páginas `Sources/Output/Profile/Options/Summary`): coleta fontes, saída/modo, perfil e opções; valida via `BootstrapCommandBuilder` e dispara o `ProcessingLaunchService` (subprocesso `Bootstrap` — a janela de progresso da §10 acompanha). A `ProfilePage` tem atalho **Manage Profiles…** que reescaneia o combo (FR-017).
- **Open Case** (`handlers/OpenCaseHandler` → `CaseOpener`): `DirectoryDialog` nativo → abre o caso via `ICaseSessionManager` **fora da UI thread**, atrás de um `ProgressMonitorDialog` (gauge indeterminado, modal); near-live é auto-habilitado pelo serviço de sessão. *(Recent Cases ainda deferido — T018/T021.)*
- **Editor de perfis** (`profiles/`): `ProfileManagerDialog` (criar/clonar/editar/excluir; embarcados read-only) e `ProfileEditorDialog` no **idioma Preferences do Eclipse** — `PreferenceDialog` com árvore de categorias (arquivos raiz / `conf/` / avançados) e página tipada por arquivo (`ConfigFilePreferencePage`: checkbox p/ BOOLEAN, campo p/ INT/TEXT, tooltip do comentário `#`, **Restore Defaults** = descartar overrides daquele arquivo; `AdvancedFilePreferencePage`: texto p/ xml/json) + campo de **busca** que filtra a árvore por nome do arquivo/chave/descrição. Embarcado → **Save As…** novo perfil de usuário.

> ⚠️ **i18n**: os ids de nó do `PreferenceManager` **não podem conter `.`** (é separador de path em `addTo()`); e `Messages.getString(key, arg)` com **um único arg `String`** casa no overload `getString(bundleName, key)`, não no formatador varargs → renderiza `!arg!` (faça cast do arg para `(Object)`).

## 12. Como buildar

Pré-requisitos: **Liberica Full 21** em `H:\java\LibericaJDK-21-Full` (`JAVA_HOME`); o reactor consome os demais módulos como **binários do `.m2`**.

```bash
# 1) Reactor FULL do IPED primeiro (publica iped-engine/iped-app no repositório local):
mvn clean install

# 2) RCP — standalone:
mvn -f iped-rcp/pom.xml clean verify
# ...ou pelo pom raiz:
mvn -P rcp ...
```

> ⚠️ **Sempre build FULL antes** (memória `project_iped_rcp_migration`): subsets `-am` quebram a resolução p2 do Tycho; o builder do IDE (m2e) pode **remover o header `Service-Component`** dos jars do target no meio do build → rode os SWTBot via subset `-pl` a partir do `.m2`. Use `mvn clean package`, **nunca** `package` sozinho (o ECJ do Eclipse envenena `target/classes/` com "Unresolved compilation problems").

Detalhes em [specs/004-rcp-gui-migration/quickstart.md](../specs/004-rcp-gui-migration/quickstart.md).

## 13. Como rodar (release)

O produto é construído pelo `p2-director` para **win32.win32.x86_64** e **gtk.linux.x86_64** e integrado ao release; o caso autocontido carrega a UI com que foi processado (`Manager.prepareOutputFolder` copia `ui/` para `<caso>/iped/`). No Windows, o shim `IPED-SearchApp.exe` (launch4j, raiz do caso) executa o launcher Equinox `iped-ui` com `-vm <caso>/iped/jre/bin`.

O launcher **standalone** `ui/iped-ui.exe` (entrada promovida de criação de casos da feature 005, rodada **sem** o shim, antes de existir um caso) também resolve a jre 21 sozinho: o profile `rcp-product-windows` injeta `-vm ../jre/bin` no `iped-ui.ini` materializado (a `jre/` é irmã da `ui/` tanto no release quanto em `<caso>/iped/`). Sem isso o Equinox cairia no Java do PATH — e o `-Djava.security.manager=allow` aborta a VM no Java 24+ (JEP 486).

> ⚠️ **Splash nativo dá deadlock _racy_ — desabilitado via `-nosplash`**: o splash do Equinox trava em ~1/3 das subidas, em **ambos** os launchers (`iped-ui.exe` GUI e `iped-uic.exe` console): a thread `main` bloqueia no método nativo `JNIBridge._update_splash` durante `EclipseStarter.setStartLevel` (antes do Display SWT existir) e o workbench **nunca renderiza**. Por isso o `.product` passa **`-nosplash`** em `<launcherArgs><programArgs>` (100% confiável; o `<splash>` fica só como âncora de recurso, sem efeito). Diagnóstico de um boot travado: `jstack` mostra `main` parada em `_update_splash`/`setStartLevel` (CPU ~0, RSS ~120 MB); subida sadia fica em `OS.WaitMessage`→`Display.sleep`→`eventLoopIdle` (RSS ~220 MB). Para debug com console, `iped-uic.exe` serve igual (o `-nosplash` do ini vale para os dois launchers).

## 14. Threading

- **SWT é single-thread**: toda manipulação de widget na **UI thread**. Use `Display.asyncExec`/`syncExec` (substitui a regra da EDT do Swing — constituição §V).
- **Jobs longos**: `org.eclipse.core.jobs` (Jobs API do e4), **fora** da UI thread.
- **Bridges**: dentro do `SWT_AWT`, o conteúdo Swing roda na **EDT** e o JavaFX em **`Platform.runLater`** (com `Platform.setImplicitExit(false)`). O `SwtAwtBridgeHost` faz o marshalling entre as threads.
- **Modelo de workers do engine intocado**: o launcher Equinox é o processo isolado da UI (R9); processamento é sempre **outro processo** (R14).

## 15. Cuidados / ⚠️

| Item | Risco |
|---|---|
| Reescrever viewer/view especializada | **Não** — bridgeie via `SwtAwtBridgeHost` (FR-028). Reescrita só dispara no cut-over, conforme inventário. |
| Reparentar o `Composite` embutido do bridge | Glitches de z-order/foco no GTK. Crie uma vez, nunca reparente. |
| Manipular widget SWT fora da UI thread | `SWTException` ("Invalid thread access"). Sempre `Display.asyncExec`. |
| Construir o conteúdo bridgeado só na criação do part (`getSession()` na hora) | O part pode nascer **antes do caso ficar READY** (aba ativa no startup, ou load lento) — `getSession()` é null até READY e o e4 **não recria** o part → fica em branco pra sempre. `AbstractBridgedPart` constrói via `buildLegacyContentIfReady()` e **reconstrói no `@UIEventTopic(CASE_OPENED)`** (T079). |
| Anexar conteúdo a um frame SWT_AWT já mostrado | O frame embutido só repinta em **resize SWT** → conteúdo anexado tarde fica invisível até o usuário redimensionar. `SwtAwtBridgeHost.setContent` força repaint (`forceSwtRepaint`, bounce de 1px) após anexar (T080). |
| Adicionar dependência direta de pacote do engine | Em vez disso, exporte o pacote no wrapper `iped.rcp.libs` (`x-internal`). |
| Cachear `CaseSession.getSource()` entre eventos | Em near-live o source é trocado; releia pela sessão a cada `case/RELOADED`/`results/CHANGED`. |
| Mudar `iped.rcp.api` | API provisória, mas trate cada mudança no contrato; só ela é compilável por terceiros. |
| Strings hardcoded na UI | Use `iped.rcp.core.i18n.Messages` + chaves em `localization/` (PT-BR + EN, 6 locales). |
| Catálogo i18n por-bundle (`OSGI-INF/l10n`) | Não — fonte única são os `.properties` do release (R7). |
| `iped.rcp.progress` como bundle OSGi | É **plain jar** de propósito (roda na JVM de processamento). |
| Escrever na evidência | Read-only (FR-004). Só bookmarks/filtros/preferências. |

## 16. Checklist de PR

- [ ] Código novo comentado/documentado em **inglês** (constituição §Fluxo de Desenvolvimento item 2).
- [ ] Build FULL (`mvn clean install`) + `mvn -f iped-rcp/pom.xml clean verify` passando.
- [ ] Manipulação de widget SWT na UI thread; jobs longos via Jobs API.
- [ ] Viewers/views legadas hospedadas via bridge (sem reescrita) — divergência contida.
- [ ] Strings visíveis via `Messages` + chaves em `iped-app/resources/localization/` (PT-BR + EN).
- [ ] Se consumiu pacote do engine: adicionado ao `Export-Package` de `iped.rcp.libs` (`x-internal`).
- [ ] Mudança em `iped.rcp.api` refletida em [contracts/ui-extension-api.contract.md](../specs/004-rcp-gui-migration/contracts/ui-extension-api.contract.md).
- [ ] Cobertura: paridade headless (`iped.rcp.tests.parity`) e/ou fluxo SWTBot (`iped.rcp.tests.swtbot`).
- [ ] Serviço headless novo declarado como **OSGi DS** (`@Component`) — sem dependência de toolkit.
- [ ] `MANIFEST.MF`/`feature.xml`/`.product` atualizados se mudou dependências de bundle.

## 17. Referências

- Plano e decisões (004, base): [specs/004-rcp-gui-migration/plan.md](../specs/004-rcp-gui-migration/plan.md), `research.md` (R1–R14), `data-model.md`, `quickstart.md`, `parity-inventory.md`, `contracts/`.
- Criação/abertura de casos + perfis (005): [specs/005-case-creation-wizard/plan.md](../specs/005-case-creation-wizard/plan.md), `research.md` (R1–R8), `data-model.md`, `contracts/` (`new-case-wizard`, `processing-launch`, `profile-editor`, `case-menu-commands`), `quickstart.md`.
- Módulos consumidos: [iped-engine/CLAUDE.md](../iped-engine/CLAUDE.md), [iped-viewers/CLAUDE.md](../iped-viewers/CLAUDE.md), [iped-geo/CLAUDE.md](../iped-geo/CLAUDE.md), [iped-app/CLAUDE.md](../iped-app/CLAUDE.md).
- Eclipse RCP/e4: <https://www.eclipse.org/eclipse/platform-ui/>
- Eclipse Tycho: <https://tycho.eclipseprojects.io/>
- Nebula Gallery/NatTable: <https://eclipse.dev/nebula/> / <https://www.eclipse.org/nattable/>
- SWTBot: <https://www.eclipse.org/swtbot/>
```
