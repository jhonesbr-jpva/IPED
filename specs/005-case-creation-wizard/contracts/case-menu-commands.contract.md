# Contract — Comandos e itens de menu de criação/abertura de caso

**Feature**: `005-case-creation-wizard` | Status: design (Phase 1)
**Consumidores**: `iped.rcp.app` (Application.e4xmi), `iped.rcp.casecreation` (handlers)

Define os IDs **estáveis** de comando/menu/handler adicionados ao modelo e4 da 004
([Application.e4xmi](../../../iped-rcp/bundles/iped.rcp.app/Application.e4xmi)). Segue o
padrão já usado para "Manage Bookmarks" (comando + handler em bundle de UI, item de
menu no `iped.rcp.app`).

## Menu

Novo `menu:Menu` **"File"** como **primeiro** filho do `mainMenu` (antes de "View"):

| elementId | Label (i18n key) | Comando |
|---|---|---|
| `iped.rcp.app.menu.file` | `Menu.File` | — |
| `iped.rcp.app.menuitem.newcase` | `Menu.NewCase` | `iped.rcp.command.newcase` |
| `iped.rcp.app.menuitem.opencase` | `Menu.OpenCase` | `iped.rcp.command.opencase` |
| `iped.rcp.app.menu.recentcases` | `Menu.RecentCases` | (submenu dinâmico) |
| `iped.rcp.app.menuitem.manageprofiles` | `Menu.ManageProfiles` | `iped.rcp.command.manageprofiles` |

Separadores entre New/Open, Recent e Manage Profiles. Labels localizadas em runtime
pelo `LifeCycle` (catálogos centrais, R-004.R7), como os itens de View já são.

## Comandos / Handlers

| Command id | Handler (contributionURI) | Ação |
|---|---|---|
| `iped.rcp.command.newcase` | `bundleclass://iped.rcp.casecreation/iped.rcp.casecreation.handlers.NewCaseHandler` | abre o `NewCaseWizard` |
| `iped.rcp.command.opencase` | `…handlers.OpenCaseHandler` | `DirectoryDialog` → `ICaseSessionManager.openCase(...)` |
| `iped.rcp.command.manageprofiles` | `…handlers.ManageProfilesHandler` | abre o `ProfileManagerDialog` |

O submenu **Recent Cases** é populado dinamicamente a partir do `RecentCasesStore`
(itens diretos chamando `OpenRecentHandler` com o caminho).

## Keybindings (opcional, FR-021/paridade)

`M1+N` → newcase, `M1+O` → opencase (window-scoped, na `bindingTables` existente).
Documentar no mapa de keybindings da 004 se adicionados.

## Invariantes

- IDs acima são **aditivos** ao modelo da 004; não renomeiam âncoras existentes
  (`ModelAnchors`) nem quebram layouts persistidos.
- Itens habilitados conforme estado: "Open/New/Manage Profiles" sempre; "Recent" só com
  entradas válidas. Com um caso aberto, "Open/New" fecham/trocam a sessão (R6).
