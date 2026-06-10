# Contract — API de Extensão da UI (PROVISÓRIA)

**Feature**: `004-rcp-gui-migration` | **Bundle**: `iped.rcp.api`
**Status**: **PROVISÓRIA** no release de cut-over (Clarifications 2026-06-10):
pode mudar sem ciclo de depreciação até declaração formal de estabilidade
(1–2 releases após o cut-over). A partir da estabilização, passa a valer o
Princípio I da constituição. Todo Javadoc da API carrega o aviso
`@apiNote Provisional API — subject to change without deprecation cycle`.

## Escopo

Define o que um bundle de terceiros pode consumir/contribuir (FR-022, US6,
SC-007). Tudo fora deste bundle é interno (`x-internal` nos exports) e não tem
garantia alguma.

## Serviços expostos (OSGi Declarative Services)

| Serviço | Operações (resumo) | Garantias |
|---|---|---|
| `ICaseSessionService` | `getActiveSession()`, listeners de abertura/fechamento | Sessão somente leitura sobre evidência; nunca null após `READY`. |
| `IItemAccessService` | resolução `ItemId → IItem` (lazy), stream de conteúdo, metadados | I/O fora da UI thread é responsabilidade do chamador; streams com charset explícito. |
| `ISearchService` | busca com a sintaxe corrente, aplicação de `IResultSetFilter` | Mesma semântica de consulta da UI (FR-006). |
| `IBookmarkService` | leitura e escrita de bookmarks | Única via de escrita permitida a extensões; formato atual (FR-005). |

## Seleção e eventos

- Seleção corrente via `ESelectionService` do e4 — chave publicada:
  `iped.rcp.selection` (payload: `SelectionContext`, ver
  [data-model.md](../data-model.md)).
- Tópicos `IEventBroker` públicos (prefixo `iped/rcp/`):
  - `iped/rcp/case/OPENED`, `iped/rcp/case/CLOSED`
  - `iped/rcp/results/CHANGED` (novo `MultiSearchResult` ativo)
  - `iped/rcp/bookmarks/CHANGED`
- Tópicos fora do prefixo público são internos.

## Contribuição de parts (views de terceiros)

- Mecanismo: **model fragment** (`fragment.e4xmi`) contribuindo `MPart` /
  `MPartDescriptor` em pontos de extensão do modelo do produto
  (`iped.rcp.app`), + classes DI (`@Inject`, `@UIEventTopic`).
- A part contribuída recebe os serviços acima por injeção.
- IDs de elementos do modelo do produto que são pontos de encaixe estáveis
  (área central, área inferior, área lateral) são listados em
  `iped.rcp.api/ModelAnchors.java` — somente esses IDs são contrato; demais
  IDs do modelo podem mudar.

## Instalação drop-in (US6)

- Diretório `plugins-ext/` na raiz da instalação (e do `<caso>/iped/ui/`):
  bundles `.jar` ali são adicionados ao runtime no boot (sem p2/auto-update —
  fora de escopo por Clarifications).
- Falha ao carregar um bundle externo: log + aviso não-bloqueante; o produto
  abre normalmente (US6 cenário 2: remoção restaura estado original).

## Compatibilidade e validação

- A extensão de exemplo `iped.rcp.sample.view` (no repositório) é o teste vivo
  do contrato (SC-007): builda contra `iped.rcp.api` apenas, instala por
  drop-in e exibe itens da seleção corrente.
- Mudanças neste contrato durante o período provisório DEVEM atualizar a
  extensão de exemplo no mesmo PR.
