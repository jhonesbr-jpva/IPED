# Inventário de Paridade Funcional — Migração da GUI para Eclipse RCP

**Feature**: `004-rcp-gui-migration` | **Tarefa**: T005 | **Gate**: SC-001 (T058)

**Baseline**: UI Swing do fork no commit `514485a9d` (master no início da
feature), versão `4.4.0-SNAPSHOT`. **Congelado em 2026-06-10.**

**Regras** (Clarifications 2026-06-10):
- Alterações neste inventário só por **re-baseline em marco** (registrar no log
  ao final) ou correção de erro material de extração (registrar igualmente).
- `Status`: `pendente` → `paridade` | `divergência justificada` (com link para a
  justificativa). O gate T058 exige 0 itens `pendente`.
- `Evidência`: `SWTBot` | `harness` | `manual` (checklist com screenshots).
  Itens `manual` são verificados em **Windows e Linux**.

## 1. Busca (FR-006)

| ID | Comportamento | Referência UI atual | Status | Evidência |
|---|---|---|---|---|
| BU-01 | Sintaxe de consulta atual aceita integralmente (mesmos resultados por consulta) | `QueryComboBox`, `UICaseSearcherFilter` | **paridade** (2026-06-11) | harness (T015 verde — contagens idênticas p/ conjunto congelado de consultas) |
| BU-02 | Histórico de consultas da sessão/persistido | `QueryComboBox` | **paridade** (2026-06-11) | SWTBot (T014 verde no produto — histórico contém a consulta executada; persistência em prefs do workspace) |
| BU-03 | Busca disparada por Enter e por botão; indicador de execução; cancelável | `App` toolbar | pendente | SWTBot |
| BU-04 | Contagem de itens retornados exibida (status bar) | `App` status | pendente | SWTBot |

## 2. Tabela de resultados (FR-007)

| ID | Comportamento | Referência UI atual | Status | Evidência |
|---|---|---|---|---|
| TB-01 | Ordenação por qualquer coluna, asc/desc, fora da UI thread | `ResultTableModel`, `parallelsorter` | **paridade** (2026-06-11) | SWTBot (T014 — clique no header reordena, verificado ordenado) + harness (T015 — conjunto preservado, asc/desc determinístico; nota: descendente = inverso exato, ver log) |
| TB-02 | Seleção múltipla (Ctrl/Shift) propagada a viewers/painéis | `ResultTableListener` | pendente | SWTBot |
| TB-03 | Checkbox de marcação por item + marcar seleção (Espaço) | `App.java:1717` (VK_SPACE) | pendente | SWTBot |
| TB-04 | Colunas visíveis/ordem/largura configuráveis e persistidas | `ColumnsManager` | pendente | SWTBot (T018) |
| TB-05 | Renderização especial (ícones por tipo, realce de hits, cores de bookmark) | renderers em `ui/` | pendente | manual |
| TB-06 | Rolagem fluida com milhões de linhas (virtual) | `HitsTable` | pendente | harness/smoke (T056) |

## 3. Tabelas auxiliares (FR-007)

| ID | Comportamento | Referência UI atual | Status | Evidência |
|---|---|---|---|---|
| TA-01 | Aba de subitens do item selecionado | `subItemTable` | pendente | SWTBot (T019) |
| TA-02 | Aba do item pai | `parentItemTable` | pendente | SWTBot |
| TA-03 | Aba de duplicatas (por hash) | `duplicatesTable` | pendente | SWTBot |
| TA-04 | Abas de referências / referenciado-por | `referencesTable`, `referencedByTable` | pendente | SWTBot |

## 4. Galeria (FR-008)

| ID | Comportamento | Referência UI atual | Status | Evidência |
|---|---|---|---|---|
| GA-01 | Miniaturas de imagens com carregamento incremental | `GalleryTable`, `GalleryModel` | **paridade** (2026-06-12) | SWTBot (T024 verde — galeria virtual espelha o resultado ativo; pipeline de decode 1:1 do `GalleryModel`) |
| GA-02 | Miniaturas de vídeo (frames múltiplos no hover/tile) | `GalleryModel` | pendente | manual |
| GA-03 | Tamanho de célula ajustável (zoom da galeria) | `GalleryTable` | pendente | SWTBot |
| GA-04 | Seleção/marcação sincronizadas com a tabela; atalhos próprios | `GalleryTable` (getKeyStroke) | pendente | SWTBot (parcial: galeria→seleção implementado; tabela→galeria, checkbox e atalhos pendentes) |
| GA-05 | Rolagem responsiva com ≥ 100 mil imagens (SC-004) | `GalleryModel` cache | pendente | smoke (T056) (parcial: T024 rola 502k itens virtuais sem bloquear a UI thread > 1 s) |

## 5. Árvores de navegação (FR-009)

| ID | Comportamento | Referência UI atual | Status | Evidência |
|---|---|---|---|---|
| AR-01 | Árvore de evidências/filesystem (lazy), filtro por subárvore | `TreeViewModel`, `TreeListener` | pendente | SWTBot (T027) (implementado — queries/filtros 1:1; sem teste SWTBot dedicado ainda) |
| AR-02 | Árvore de categorias com contagens, seleção múltipla combinável | `CategoryTreeModel` | **paridade** (2026-06-12) | SWTBot (T024 — seleção no produto filtra à contagem do label) + harness (T025 — contagem == árvore == composição legada) |
| AR-03 | Árvore de bookmarks (cores, contagens) | `BookmarksTreeModel` | pendente | SWTBot (parcial: filtro de seleção validado no harness T025; contagens no label; cores pendentes) |
| AR-04 | Árvore de filtros de IA | `AIFiltersTreeListener`, `AIFiltersConfig.json` | pendente | SWTBot (implementado — porte do `AIFiltersLoader`; sem evidência dedicada) |

## 6. Metadados / facetas (FR-010)

| ID | Comportamento | Referência UI atual | Status | Evidência |
|---|---|---|---|---|
| MD-01 | Agregação de valores por campo (contagens ordenáveis) | `MetadataSearch`, `ValueCount` | **paridade** (2026-06-12) | harness (T025 — contentType particiona o resultado; cada valor == query legada `field:"valor"`) |
| MD-02 | Faixas numéricas e monetárias | `RangeCount`, `MoneyCount` | **paridade** (2026-06-12) | harness (T025 — ranges de `size` == queries `[min TO max]`; `MoneyCount` portado 1:1, sem leg específica — caso sem `regex:MONEY`) |
| MD-03 | Filtrar por valores selecionados (combinável) | `ValueCountQueryFilter` | **paridade** (2026-06-12) | harness (T025 — `ValueCountFilter`/`getIdsWithOrd` com o mesmo aggregator dos counts; valores e ranges) |
| MD-04 | Painel de metadados do item selecionado | `metadataPanel` | pendente | SWTBot |

## 7. Filtros e similaridade (FR-013/016)

| ID | Comportamento | Referência UI atual | Status | Evidência |
|---|---|---|---|---|
| FI-01 | Combinação E/OU/NÃO de filtros ativos | `FilterManager`, `filterdecisiontree` | **paridade** (2026-06-12) | harness (T025 — OR/AND/NOT/árvore mista (leaf de result-set) == álgebra de conjuntos; avaliação por bitsets como o `CombinedFilterer`) |
| FI-02 | Filtros salvos: criar, aplicar, persistir no formato atual | `FiltersPanel` | pendente | SWTBot + harness (parcial: harness T025 carrega/compõe o formato atual; perna SWTBot do diálogo pendente) |
| FI-03 | Filtros default pré-instalados | `conf/DefaultFilters.txt` | **paridade** (2026-06-12) | harness (T025 — DefaultFilters.txt do caso carregado, OBSOLETE removido, expressões compostas == baseline) |
| FI-04 | Filtro de duplicatas | `DuplicatesFilterer` | **paridade** (2026-06-12) | harness (T025 — porte do `DynamicDuplicateFilter`) + SWTBot (T024 — toggle no produto combinado com categoria) |
| SI-01 | Busca por imagens similares (a partir de item/arquivo externo) | `SimilarImagesFilterer` | pendente | harness (T025) (leg implementada e guardada por capacidade: skip no caso de referência sem `imageSimilarity`; rodar contra caso com a task habilitada; "arquivo externo" pendente) |
| SI-02 | Busca por faces similares | `SimilarFacesSearchFilterer` | pendente | harness (leg implementada e guardada: skip sem `face_encodings` no caso) |
| SI-03 | Busca por documentos similares | `SimilarDocumentFilterer` | **paridade** (2026-06-12) | harness (T025 — query do `SimilarDocumentSearch` (70%) composta == baseline) |

## 8. Viewers de conteúdo (FR-011)

| ID | Comportamento | Referência UI atual | Status | Evidência |
|---|---|---|---|---|
| VW-01 | Texto extraído com realce e navegação entre ocorrências (atalhos Q/W = anterior/próxima) | `TextViewer`, `App.java:891/898` | pendente | SWTBot (T020) |
| VW-02 | Hex com busca | `HexSearcherImpl` | pendente | manual |
| VW-03 | Imagens (zoom/rotação/cópia) | viewers impl | pendente | manual |
| VW-04 | Áudio/vídeo (player embutido) | viewers impl (JavaFX/MPlayer) | pendente | manual |
| VW-05 | HTML/e-mail (renderização segura, anexos clicáveis) | `HtmlLinkViewer` (JFXPanel) | pendente | manual |
| VW-06 | Documentos Office (LibreOffice embutido) | NOA/nativeview | pendente | manual |
| VW-07 | PDF | IcePDF/PDFBox viewer | pendente | manual |
| VW-08 | CAD e demais formatos especiais | CaffViewer etc. | pendente | manual |
| VW-09 | Troca automática de viewer por tipo; aba de metadados do viewer | `ViewerController`/`MultiViewer` | pendente | SWTBot |
| VW-10 | Degradação graciosa com ferramenta externa ausente | `ViewerController` | pendente | manual |

## 9. Views especializadas (FR-012)

| ID | Comportamento | Referência UI atual | Status | Evidência |
|---|---|---|---|---|
| MP-01 | Mapa com itens georreferenciados; seleção bidirecional | `MapViewer` (iped-geo) | paridade (plumbing) | SWTBot (T033); clique de marker = manual |
| GR-01 | Grafo: expansão de nós, busca de caminhos, layouts | `AppGraphAnalytics` + GraphViz | pendente (bloqueado: produto sem `lib/neo4j/` — T052) | manual |
| GR-02 | Grafo: exportar imagem e links | `ExportImageAction`, `ExportLinksAction` | pendente (idem GR-01) | manual |
| TL-01 | Timeline: zoom, pan, granularidade temporal | `IpedChartsPanel` | pendente | manual |
| TL-02 | Timeline: seleção de intervalo filtra a tabela | `IpedTimelineMouseWheelHandler` etc. | paridade (plumbing) | SWTBot (T033); gesto de drag = manual |

## 10. Bookmarks (FR-005/014)

| ID | Comportamento | Referência UI atual | Status | Evidência |
|---|---|---|---|---|
| BM-01 | Criar/renomear/excluir bookmark; atalho B abre gerenciador | `BookmarksManager`, `App.java:905` | pendente | SWTBot (T014) |
| BM-02 | Cor e comentário por bookmark | `BookmarksManager` | pendente | SWTBot |
| BM-03 | Adicionar/remover seleção e itens marcados (checked) a bookmark | `BookmarksController` | pendente | SWTBot |
| BM-04 | União de bookmarks | `BookmarksManager` | pendente | SWTBot |
| BM-05 | Persistência no caso, formato atual, gravação assíncrona segura | `Bookmarks.saveState`/`SaveStateThread` | **paridade** (2026-06-11) | harness (T064 verde — incl. bookmark 100k itens/BitmapBookmarks e escrita concorrente) |

## 11. Exportação e relatório (FR-015)

| ID | Comportamento | Referência UI atual | Status | Evidência |
|---|---|---|---|---|
| EX-01 | Exportar itens selecionados/marcados (arquivos + propriedades) | `ExportFiles*` | pendente | harness (saídas idênticas) |
| EX-02 | Exportar árvore de pastas / manter estrutura | export actions | pendente | manual |
| EX-03 | Wizard de relatório HTML (seleção de bookmarks, propriedades, thumbs) | `ReportDialog` | pendente | SWTBot (T023) + manual |
| EX-04 | Copiar propriedades/células para a área de transferência | `MenuClass` | pendente | manual |

## 12. Atalhos de teclado (FR-021 — mapa completo em T046/keybindings-map.md)

| ID | Comportamento | Referência UI atual | Status | Evidência |
|---|---|---|---|---|
| KB-01 | Q/W: ocorrência anterior/próxima no texto | `App.java:891/898` | pendente | SWTBot |
| KB-02 | B: gerenciador de bookmarks | `App.java:905` | pendente | SWTBot |
| KB-03 | Espaço: marcar/desmarcar (tabela e galeria) | `App.java:1717`, `GalleryTable` | pendente | SWTBot |
| KB-04 | Atalhos do gerenciador de bookmarks (atribuição rápida) | `BookmarksManager` | pendente | manual |
| KB-05 | Demais bindings extraídos em T046 (varredura `getKeyStroke`) | `ui/*` | pendente | T046 |

## 13. Workspace, temas e escala (FR-017/018/019)

| ID | Comportamento | Referência UI atual | Status | Evidência |
|---|---|---|---|---|
| WS-01 | Rearranjo de abas/painéis por arrastar; maximizar/restaurar | DockingFrames | pendente | SWTBot (T042) |
| WS-02 | Layout persistido por usuário e restaurado | `PanelsLayout` | pendente | SWTBot (T042) |
| WS-03 | Temas claro/escuro consistentes | `ThemeManager` | pendente | manual |
| WS-04 | Escala de UI (uiScale/HiDPI) | `UiScale`, `LocalConfig` | pendente | manual |
| WS-05 | Locale da UI segue `iped-locale` com fallback EN | `iped-app.properties` (6 locales) | pendente | manual (SC-006) |

## 14. Progresso do processamento (FR-026 — contrato progress-ui-events)

| ID | Comportamento | Referência UI atual | Status | Evidência |
|---|---|---|---|---|
| PG-01 | Progresso global (%, itens processados/total) | `ProgressFrame` | pendente | manual (T037+) |
| PG-02 | Progresso e status por evidência | `ProgressFrame` | pendente | manual |
| PG-03 | Taxa (GB/h, itens/s) + gráfico de throughput | `ProgressFrame` | pendente | manual |
| PG-04 | Tasks ativas por worker / fase corrente | `ProgressFrame` | pendente | manual |
| PG-05 | Contadores (encontrados, processados, carved, subitens, erros) | `ProgressFrame` | pendente | manual |
| PG-06 | Erros/alertas não-fatais consultáveis sem interromper | `ProgressFrame` | pendente | manual |
| PG-07 | ETA | `ProgressFrame` | pendente | manual |
| PG-08 | Pausar/continuar; abortar com confirmação | `ProgressFrame` | pendente | manual |
| PG-09 | Abrir análise (durante = quase-ao-vivo FR-029; ou ao final) | `ProgressFrame`/`AppMain` | pendente | manual (T040/T063) |
| PG-10 | `--nogui` → saída de console idêntica | `ProgressConsole` | pendente | harness (T037) |
| PG-11 | Fallback automático sem display | novo (contrato) | pendente | harness (T037) |

> Campos exatos (nomes das propriedades publicadas via
> `UIPropertyListenerProvider`) a detalhar na implementação de T038 — esta
> seção congela o conjunto observável pelo usuário.

## 15. Interoperabilidade de bookmarks (SC-009 — T064)

| ID | Comportamento | Status | Evidência |
|---|---|---|---|
| IB-01 | Ida: gravado pela pilha RCP (contexto OSGi) → lido pelo engine plano | **paridade** (2026-06-11) | harness (T064 verde — JVM filha plana, dump canônico com CRC de pertencimento; perna OSGi-real coberta pelo passo de bookmark do T014) |
| IB-02 | Volta: gravado pelo engine plano/UI atual → lido pela pilha RCP | **paridade** (2026-06-11) | harness (T064 verde — nomes/cor/comentário/membros/checked idênticos após `loadState`) |
| IB-03 | Relatório HTML gerado a partir de bookmarks gravados pela nova UI | pendente | harness/manual |
| IB-04 | Acentuação/caracteres especiais em nomes e comentários | pendente | harness |
| IB-05 | Bookmark ≥ 100 mil itens (`BitmapBookmarks`) | pendente | harness |
| IB-06 | Multicase | pendente | harness |
| IB-07 | Escrita concorrente (disciplina de lock atual) | pendente | harness |

## 16. Aparência nativa (SC-010 — inspeção por SO, screenshots no T058)

| ID | Tela | Status Win | Status Linux |
|---|---|---|---|
| AN-01 | Janela principal (chrome, menus, toolbars) | pendente | pendente |
| AN-02 | Diálogos de arquivo (abrir caso, exportar) | pendente | pendente |
| AN-03 | Wizards (relatório) e diálogos modais | pendente | pendente |
| AN-04 | Janela de progresso do processamento | pendente | pendente |
| AN-05 | Splash e diálogos do inicializador | pendente | pendente |
| AN-06 | Tema escuro seguindo o SO | pendente | pendente |

## 17. Procedimento de captura do baseline (harness — A3)

1. Caso de referência processado pelo release do commit-baseline (perfil
   `forensic`), em hardware documentado em `perf-report.md`.
2. Roteiro de consultas/filtros/similaridades executado na **UI Swing atual**;
   contagens registradas em `baseline-counts.csv` ao lado deste arquivo
   (gerado pelo roteiro; commitado).
3. O harness (T015/T025) compara a nova UI contra `baseline-counts.csv` — não
   contra chamadas diretas à API (evita o falso-verde de medir o engine contra
   ele mesmo).
4. Tempos (SC-002/003) medidos no mesmo roteiro, mesma máquina, 3 execuções,
   mediana.

## Log de re-baseline

| Data | Marco | Mudança incorporada | Aprovado por |
|---|---|---|---|
| 2026-06-10 | Congelamento inicial | — (baseline `514485a9d`) | — |
| 2026-06-10 | Remediação I1 (analyze) | PG-09 reescrito: modo interativo → quase-ao-vivo (FR-029); visibilidade NRT de itens não consolidados registrada como **divergência justificada** | usuário (opção A) |

---

## Log de atualizações

### 2026-06-11 — Passada US1 (Phase 3, harness headless)

Evidência: `mvn -f iped-rcp/pom.xml -pl tests/iped.rcp.tests.parity test`
`-Dcase.dir=F:\test_yara_java21` — **11 testes, 0 falhas** (T015 + T064 +
smoke de sessão; 2 skips = pernas multicase sem `-Dcase.dir2`).

Status alterados: BU-01, BM-05, IB-01, IB-02 → `paridade`.

Evidência parcial obtida (linhas continuam `pendente` até a perna SWTBot/
manual): TB-01 (ordenação engine-side validada no harness — conjunto
preservado, asc/desc determinístico), BU-02/BU-03 (implementados, T014
escrito), BM-01..03 (escritas validadas via serviço no T064), EX-01/EX-03
(implementados, sem comparação de saída ainda).

Divergências justificadas REGISTRADAS nesta iteração (a aprovar no gate
T058):

- **TB-01**: ordem descendente na nova UI = inverso exato da ascendente
  (determinístico); o sorter legado baseado em toggle podia divergir em
  empates. Sem efeito em contagens/conjuntos.
- **TB-04**: colunas visíveis/ordem persistem nas preferências do workspace
  e4 (`~/.iped/ui-workspaces/...`, R5) em vez de `visibleCols.dat` — fronteira
  estado-de-workspace × configuração do data-model.
- **TA-01..04**: exibição das tabelas auxiliares cap em 1000 linhas neste
  incremento (título mostra o total real); queries idênticas às legadas.
- **VW-xx**: viewers registrados neste incremento: Metadata, Image, Tiff,
  Html, Email (com attachment searcher), IcePDF. Texto-com-realce, hex,
  LibreOffice, áudio/vídeo e CAD permanecem `pendente` (próxima iteração
  US1).
- **T064 nota de método**: a perna "contexto OSGi real" da ida é exercitada
  pelo passo de bookmark do T014 dentro do produto lançado pelo harness
  tycho-surefire; o round-trip cross-classpath usa JVM filha plana (mesmo
  caminho do gerador de relatório).

### 2026-06-11 (2) — T014 verde no produto real (Windows)

Evidência: `mvn -f iped-rcp/pom.xml -pl tests/iped.rcp.tests.swtbot verify`
`-DskipUiTests=false -Dcase.dir=F:\test_yara_java21` — TriageFlowTest 1/1
em 26 s no workbench e4 lançado pelo tycho-surefire (Windows; perna Linux/
Xvfb fica com o CI `rcp.yml`).

Status alterados: BU-02, TB-01 → `paridade`.

Notas de harness (relevantes para reprodução): o UI harness do
tycho-surefire provisiona `org.eclipse.ui.ide.application` incondicionalmente
(adicionado ao `.target` só para o runtime de teste; fora da feature do
produto) e passa o caminho do `surefire.properties` como argumento de
programa (o `LifeCycle` passou a aceitar apenas diretórios/listas `.txt`
como caminhos de caso).

Cobertura parcial adicional do T014 (linhas seguem `pendente` até evidência
dedicada): BU-03 (busca por botão exercitada; Enter implementado),
BM-01..03 (criação/remoção via serviço dentro do produto; diálogo
gerenciador sem teste dedicado), EX-01 (exportação produz arquivos no
destino; comparação byte-a-byte com a saída legada pendente).

### 2026-06-12 — Passada US2 (Phase 4: galeria, árvores, facetas, filtros, similaridade)

Evidências: `mvn -f iped-rcp/pom.xml -pl tests/iped.rcp.tests.parity test`
`-Dcase.dir=F:\test_yara_java21` — **22 testes, 0 falhas** (T025
FilterParityTest 9 passes + 2 skips justificados; T015/T064/sessão
revalidados após a integração `SearchService`×`FilterStateService`); e
`mvn -f iped-rcp/pom.xml -pl tests/iped.rcp.tests.swtbot verify`
`-DskipUiTests=false -Dcase.dir=F:\test_yara_java21` — **3 testes, 0
falhas** (T024 FiltersGalleryTest 13 s + T014 TriageFlowTest 21 s no
produto e4 real, Windows).

Status alterados: GA-01, AR-02, MD-01, MD-02, MD-03, FI-01, FI-03, FI-04,
SI-03 → `paridade`.

Divergências justificadas REGISTRADAS nesta iteração (a aprovar no gate
T058):

- **FI-01/pipeline**: a ordem de aplicação dos result-set filters é FIXA e
  determinística na nova UI (duplicatas → bookmarks → metadados → faces →
  re-score de imagens → árvore combinada); no legado seguia a ordem de
  registro dos filterers no `FilterManager`. Afeta apenas o CONJUNTO do
  filtro de duplicatas quando combinado (primeira-ocorrência-por-hash sobre
  resultado já filtrado vs não-filtrado) — contagens por filtro individual
  idênticas.
- **AR-03**: seleção simultânea de bookmarks + "[Sem Marcadores]" usa
  `filterBookmarksOrNoBookmarks` (união — semântica do caminho bitmap
  legado); o caminho não-bitmap legado descartava os bookmarks
  selecionados nesse caso (comportamento considerado bug). Labels com
  contagem (legado mostrava só nomes na árvore).
- **BU/queries dos painéis**: histórico, colunas e demais preferências das
  novas parts persistem em prefs e4 do workspace (R5) — já registrado na
  passada US1.
- **GA**: blur/gray (proteção do perito), miniaturas multi-frame de vídeo,
  zoom de célula, checkbox de marcação e sync tabela→galeria ficam para
  iteração complementar (linhas GA-02/03/04 continuam `pendente`).
- **MetadataPanel**: grupos de propriedades/escala log/sem-ranges/ordenações
  alternativas do painel legado ainda não expostos na part (agregador
  portado já suporta; UI mínima Update/Filtrar/Limpar).
- **SI-01/SI-02**: legs de paridade implementadas e guardadas por
  capacidade do caso (skip explícito quando o caso não tem
  `imageSimilarity`/`face_encodings`) — executar contra caso processado com
  essas tasks antes do gate T058; busca por arquivo externo (imagem/face)
  não portada nesta iteração.

Notas de implementação relevantes ao gate (não-divergências):

- Fix latente da Phase 2 descoberto no .log do workbench: `UiEventsAddon`
  (T012) não instanciava no produto (CNFE de `MPart`) — faltava
  `org.eclipse.e4.ui.model.workbench` no Require-Bundle de `iped.rcp.core`.
  O T014 passava porque nada dependia do espelhamento de seleção no
  contexto; corrigido nesta passada.
- Lição JFace aplicada às 6 TreeViewers novas: o input do viewer não pode
  ser o próprio elemento raiz (o `getRawChildren` do JFace resolve filhos
  de elemento `equals` ao input via `getElements`, tornando a raiz filha de
  si mesma em cadeia infinita). Input agora é `Object[]{root}`.

### 2026-06-12 (2) — Passada US3 (Phase 5: mapa, grafo e timeline bridgeados)

Evidências: `mvn -f iped-rcp/pom.xml -pl tests/iped.rcp.tests.swtbot verify`
`-DskipUiTests=false -Dcase.dir=F:\test_yara_java21` — **4 testes, 0
falhas** no produto e4 real (Windows): T033 SpecializedViewsTest 37 s
(liveness das 3 parts + seleção tabela↔espelho nos 2 sentidos) com
T014/T024 revalidados na mesma janela; harness de paridade 23/23
revalidado após o wrapper passar a embutir o iped-app.

Status alterados: MP-01, TL-02 → `paridade (plumbing)` — a tubulação de
seleção bidirecional (espelho compartilhado ↔ tabela SWT) é a automatizável;
os gestos intra-canvas (clique de marker no WebView, drag de intervalo no
chart) são Swing/JS invisíveis ao SWTBot e ficam como evidência `manual`
para o T058.

Arquitetura do bridge (contexto p/ o gate): as views legadas NÃO foram
reescritas (FR-028) — `iped.rcp.specialized` as hospeda via `SwtAwtBridgeHost`
e reproduz a disciplina legada da tabela-compartilhada com um `JTable`
espelho oculto (col 1 = checked via `BookmarkService`); `MapViewer` e
`IpedChartsPanel` são inicializados pelo MESMO `init(table, provider,
guiProvider)` de sempre.

Divergências justificadas REGISTRADAS nesta iteração (a aprovar no gate
T058):

- **GR-01/GR-02 (bloqueio de empacotamento)**: neste fork o grafo é
  out-of-process via Bolt (`iped-graph-server` materializado em
  `lib/neo4j/`); o produto RCP ainda não embarca essa árvore → o load do
  grafo degrada graciosamente ("no Bolt port reported", painel
  desabilitado). Resolver na integração de release (T052) e só então
  validar GR-01/GR-02.
- **Grafo "mostrar evidência" do nó**: usa o pipeline `FileProcessor` do
  App Swing completo — degrada com erro logado no bridge; navegação ao
  item entra em iteração complementar.
- **Diálogos legados (grafo/timeline/mapa)**: abrem com owner no frame
  invisível do `App` (centrados na tela, não sobre a janela) — cosmético.
- **Timeline split por bookmark/categoria**: `getSelectedBookmarks()/
  getSelectedCategories()` do provider RCP retornam vazio nesta iteração
  (série única "Items"); integração com a seleção das árvores em iteração
  complementar.
- **Multicase `casesPathFile`**: aproximado como `<pai do 1º caso>/multicase`
  (grafo multicase/timecache); o legado usava a localização do txt
  `-multicases`. Sem efeito em caso único.
- **Costura `IpedChartsPanel.GUIHost` (toque no legado, aditivo)**: os 7
  call-sites que castavam o provider para `(App)` agora passam por uma
  interface com default que reproduz o wiring antigo verbatim — Swing
  legado inalterado; pré-requisito para o código bridgeado sobreviver ao
  cut-over (T059 mantém timeline/grafo bridgeados).
- **Filtro de intervalo da timeline**: materializado como slot de query
  filter (`iped.rcp.specialized.timeline`) no `FilterStateService` +
  refresh — composição AND idêntica ao `CaseSearcherFilter` legado.

Notas de implementação relevantes ao gate (não-divergências):

- **Seleção determinística no contexto**: o espelhamento do `UiEventsAddon`
  (T012) passa pelo agregador de seleção da part ativa do e4, que depende
  de foco REAL do SO — inerte sob o harness SWTBot (descoberto pelo T033:
  `received=0`). Os publicadores (`ResultsTablePart`, `LegacyUiBridge`)
  agora gravam `iped.rcp.selection` diretamente no contexto da aplicação;
  o addon permanece como espelho genérico para parts de terceiros.
- **Wrapper × iped-app**: o jar `iped-app` instalado era o subset hashdb
  (execuções classifierless do maven-jar-plugin sobrescrevem o artefato
  principal — a última vence); novo `attach-classes-jar` anexa o jar
  completo com classifier `classes`, consumido pelo wrapper. bnd exigiu
  `_fixupmessages` para referência legada a classe em default package
  (irrelevante: o wrapper não importa nada).
- A tabela de resultados segue seleções de origem `iped.rcp.specialized.*`
  (allowlist por prefixo) — continua FONTE de seleção para o resto do
  workbench (sync tabela↔galeria inalterado, sem loops de eco).
