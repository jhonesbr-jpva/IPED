# Data Model — Migração da GUI do IPED para Eclipse RCP

**Feature**: `004-rcp-gui-migration` | **Date**: 2026-06-10
**Input**: [spec.md](spec.md) §Key Entities | [research.md](research.md)

A feature é uma camada de apresentação: os dados forenses (caso, itens,
índice) são **entradas imutáveis** providas pelo engine e não mudam de
formato. Este modelo descreve as entidades próprias da camada de UI e como
elas referenciam o modelo do engine.

## Entidades do engine (referenciadas, NÃO modificadas)

| Entidade | Origem | Uso pela nova UI |
|---|---|---|
| `IPEDSource`/`IPEDMultiSource` | iped-engine | Sessão de caso aberta (single/multicase). Somente leitura. |
| `IItem`/`ItemId` | iped-api/engine | Item de análise; identidade = (sourceId, id). |
| `MultiSearchResult` | iped-engine | Conjunto de resultados paginável/ordenável; backing da tabela virtual (R12). |
| `IMultiBookmarks` | iped-api/engine | Bookmarks/tags — formato atual preservado (FR-005); única via de escrita. |
| `IResultSetFilter(er)` | iped-engine | Filtros combináveis (FR-016). |
| Eventos de processamento | `UIPropertyListenerProvider` | Consumidos pela janela de progresso (contrato `progress-ui-events`). |

**Regra de validação global**: nenhuma escrita fora de `IMultiBookmarks`,
filtros salvos e preferências de UI (FR-004).

## Entidades próprias da camada RCP

### CaseSession (`iped.rcp.core`)

Sessão de análise sobre um caso (ou multicase) aberto.

| Campo | Tipo | Regras |
|---|---|---|
| `casePaths` | lista de paths | ≥ 1; validados antes de abrir (existência de índice). |
| `source` | `IPEDMultiSource` | Criado na abertura; fechado no dispose da sessão. |
| `readOnlyMedia` | boolean | Detectado na abertura; quando true, desabilita escrita de bookmarks com aviso (edge case). |
| `interactive` | boolean | True quando o caso aberto ainda está em processamento (detectado pelo estado do caso em disco); habilita o `CommitMonitor`. |
| `commitMonitor` | serviço interno | Modo quase-ao-vivo (FR-029, research R14): observa a geração do índice do caso e, a cada consolidação detectada, recarrega o leitor somente leitura e publica `results/CHANGED`. NUNCA adquire locks de escrita nem segura locks que bloqueiem o processamento (FR-030). Desligado quando o processamento termina. |

Estados: `OPENING → READY → CLOSING → CLOSED`; falha em `OPENING` → diálogo de
erro e encerramento limpo (sem caso meio-aberto). Em modo interativo, `READY`
admite ciclos de recarga disparados pelo `CommitMonitor` (a UI permanece
responsiva durante a recarga; a troca do resultado é atômica — ver
`ResultSetModel`).

> Nota de migração: a UI atual recebia o `Manager` vivo no mesmo processo
> (leitura em tempo real via `IndexWriter` + handshake `setSearchAppOpen`/
> `deleteTempDir`). Esses mecanismos in-process deixam de existir: cada processo
> gerencia seu próprio temp e ciclo de vida (Clarifications 2026-06-10).

### SelectionContext (publicado via `ESelectionService`)

Seleção corrente compartilhada entre parts (tabela → viewers/metadados/mapa…).

| Campo | Tipo | Regras |
|---|---|---|
| `activeItem` | `ItemId` ou null | Item em foco (alimenta viewers). |
| `selectedItems` | lista ordenada de `ItemId` | Pode ser grande (operações em lote usam iteração preguiçosa). |
| `originPartId` | string | Evita eco de seleção (part que originou ignora o próprio evento). |

### ResultSetModel (`iped.rcp.views`)

Estado da tabela de resultados e da galeria (mesmo backing, duas projeções).

| Campo | Tipo | Regras |
|---|---|---|
| `result` | `MultiSearchResult` | Substituído atomicamente a cada busca/filtro (nunca mutado in-place na UI thread). |
| `sortKey` | coluna + direção | Ordenação executada fora da UI thread (engine/parallelsorter). |
| `visibleColumns` | lista ordenada | Persistida em preferências do usuário (paridade `ColumnsManager`). |
| `checkedItems` | conjunto de `ItemId` | Estado de marcação (checkbox) — semântica atual. |

### FilterState (`iped.rcp.core`)

Composição corrente de filtros (FR-016) — árvore AND/OR/NOT de
`IResultSetFilter` + consulta de busca corrente + histórico.

Regras: aplicar = nova consulta ao engine (nunca filtragem na UI thread);
filtros salvos serializados no formato atual (FR-005).

### WorkspaceState (e4 persisted state — R5)

| Campo | Regras |
|---|---|
| Modelo e4 persistido (`workbench.xmi`) | Em `~/.iped/ui-workspaces/<case-id>/`; `case-id` = hash do path canônico do caso. |
| Versão de layout | Incompatível ou corrompido → reset silencioso para o modelo padrão + log. |
| Preferências (tema, colunas, escala) | Escopo usuário (não viajam com o caso). |

**Fronteira com o Princípio III (Configuração antes de Código)** — regra para
PRs: é **estado de workspace** (este escopo, perfil do usuário) o que só afeta
apresentação e não altera resultado de análise/processamento — layout,
geometria de janelas, tema claro/escuro, colunas visíveis/ordem, histórico de
consultas. Permanece **Configurable** (em `conf/`/profiles) o que é política
ajustável pelo perito com efeito sobre comportamento — `uiScale` e threads
(`LocalConfig`), `AnalysisConfig`, filtros default, categorias. Divergências de
local de persistência vs UI atual (ex.: colunas hoje persistidas pelo
`ColumnsManager`) são registradas no inventário de paridade como justificadas.

### ExtensionContribution (`iped.rcp.api` — PROVISÓRIA)

Contribuição de terceiros (US6): bundle OSGi com `fragment.e4xmi` (parts) e/ou
serviços DS consumindo a API provisória. Identidade = Bundle-SymbolicName.
Regras: ausência do bundle não pode impedir o boot (tolerância a remoção —
US6 cenário 2); acesso ao caso somente via serviços de `iped.rcp.api`
(detalhe no contrato `ui-extension-api`).

### ParityInventoryItem (artefato de validação — SC-001)

Linha do inventário `parity-inventory.md`: `id`, `área` (busca, tabela,
galeria…), `descrição do comportamento`, `referência na UI atual`
(classe/menu), `status` (pendente/paridade/divergência justificada),
`evidência` (teste SWTBot, harness ou checklist manual). Congelado no início;
alterações apenas por re-baseline registrado (Clarifications).

## Relações (visão)

```text
CaseSession 1—1 IPEDMultiSource
CaseSession 1—* ResultSetModel (tabela, galeria, tabelas auxiliares)
ResultSetModel *—1 FilterState (resultado corrente deriva da composição)
SelectionContext —→ ItemId* (referencia itens da CaseSession)
WorkspaceState —(por usuário+caso)— CaseSession
ExtensionContribution —consome→ iped.rcp.api (CaseSession/Selection somente leitura)
```
