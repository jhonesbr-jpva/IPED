# Contrato — Interface de análise com e sem o plugin

**Feature**: 010-neo4j-optional-plugin · **Componentes**: `App`, `MenuClass`, `MenuListener`, `UICaseDataLoader`, `GraphPluginNotice` (novo), `iped.app.graph.*`

## Plugin disponível (`GraphPluginRegistry.get().isAvailable()`)

Comportamento idêntico ao atual (FR-009): aba "Vínculos" (`graphDock`), `AppGraphAnalytics`, filtro `FilterSelectedEdges`, item de menu "adicionar ao grafo", carregamento do banco em `UICaseDataLoader` (`initGraphService`), multi-caso com construção do grafo combinado, todas as operações da aba. As classes de `iped.app.graph` passam a usar `GraphNode`/`GraphRelationship`/`GraphPath`/`GraphLabel`/`GraphQueryException`, sem mudança visível.

## Plugin ausente ou rejeitado

| Elemento | Comportamento |
|---|---|
| `App.appGraphAnalytics` | `null` (não instanciado). |
| `graphDock` | Não criado; não entra na lista de docks nem no layout padrão (`App` já tolera `graphDock == null`). |
| `FilterSelectedEdges` | Não registrado no `filterManager`. |
| `MenuClass` "adicionar ao grafo" | Não adicionado ao menu de contexto. |
| `MenuListener.addEvidenceFilesToGraph` | Caminho inalcançável (item inexistente); guarda defensiva contra `null`. |
| `UICaseDataLoader` | Não chama `initGraphService()`; chama `GraphPluginNotice.showIfNeeded(appCase)`. |
| Atalhos/ações de grafo | Não registrados (seguem o painel). |
| Demais funções (busca, galeria, bookmarks, viewers, metadados, timeline, mapa, relatório, exportação) | Inalteradas. |

Nenhum diálogo de erro e nenhum `ERROR`/stack trace no log atribuível à ausência (SC-005).

## Aviso único (FR-007) — `GraphPluginNotice`

- Condição: plugin não disponível **e** algum `IPEDSource` atômico do caso aberto tem `iped/neo4j/data` (vale para multi-caso).
- Momento: após o carregamento do caso (onde `initGraphService()` era chamado), na EDT via `SwingUtilities.invokeLater`.
- Forma: `JDialog` **modeless** (`ModalityType.MODELESS`), com ícone informativo, botão "OK" e texto localizado; não impede o uso da janela principal.
- Frequência: uma vez por sessão da UI (flag estática), mesmo abrindo vários casos.
- Texto (EN; PT-BR no bundle `iped-desktop-messages`):
  - Título `GraphPlugin.Notice.Title`: `Link analysis unavailable`
  - Corpo `GraphPlugin.Notice.Text`: `This case contains link analysis (graph) data, but the graph plugin is not available, so the "Links" tab is not shown. Install the iped-graph-neo4j plugin into the plugins folder and reopen the case. Reason: {0}` — `{0}` = motivo **localizado** por chave: `GraphPlugin.Reason.NotInstalled` = `not installed`; `GraphPlugin.Reason.MultipleCopies` = `more than one copy found in the plugins folder`; `GraphPlugin.Reason.SpiMismatch` = `incompatible version (plugin SPI v{0}, IPED SPI v{1})`; `GraphPlugin.Reason.Incomplete` = `incomplete installation`; `GraphPlugin.Reason.LoadError` = `failed to load`. Nenhum texto técnico em inglês no diálogo; o detalhe completo (`getDetail()`) vai só para o log.
- Log: `INFO` com a mesma informação.

## Caso sem dados de vínculos, plugin disponível

Como hoje para casos processados com a chave desligada: a aba existe e mostra o estado "sem banco de grafos" (`LoadGraphDatabaseWorker` registra `Graph database not found`). Nenhuma mudança.

## Testes

- Smoke manual (quickstart) com e sem plugin, caso com e sem dados de vínculos, caso único e multi-caso.
- Teste unitário de `GraphPluginNotice.shouldShow(List<IPEDSource>, GraphPluginState)` (lógica pura, sem Swing).
