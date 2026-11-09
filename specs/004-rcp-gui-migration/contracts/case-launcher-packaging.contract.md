# Contract — Empacotamento, Caso Autocontido e Launcher

**Feature**: `004-rcp-gui-migration` | Decisão de base: [research.md](../research.md) R9
Preserva o modelo autocontido (Clarifications 003/004) e o contrato do
launcher na raiz do caso (fix `b8b15735a`).

## Layout do release (`target/release/iped-<ver>/`)

```text
iped-<ver>/
├── iped.jar                # processamento CLI (Bootstrap) — interface CLI e contrato
│                           #   de invocação INALTERADOS (conteúdo muda: splash e
│                           #   diálogos do inicializador migram para SWT — FR-027)
├── iped.exe                # launcher de processamento — INALTERADO
├── jre/                    # JRE embarcada (Windows) — INALTERADA
├── lib/                    # deps do processamento; SEM iped-search-app.jar (cut-over)
├── ui/                     # NOVO — produto RCP materializado por SO
│   ├── iped-ui[.exe]       # launcher equinox nativo
│   ├── iped-ui.ini         # -vm ../jre/bin (Windows); args fixos
│   ├── plugins/            # bundles do produto (incl. iped.rcp.libs)
│   ├── configuration/
│   └── plugins-ext/        # drop-in de extensões de terceiros (vazio)
├── conf/, profiles/, scripts/, tools/, models/, localization/, plugins/  # INALTERADOS
└── ...
```

- O produto Linux é materializado em build multi-plataforma (mesma pasta `ui/`
  por release de SO; release Windows carrega só o produto win32).
- `lib/iped-webapi.jar` e `lib/iped-hashdb.jar` permanecem (fora do escopo).

## Layout do caso autocontido (`<caso>/`)

```text
<caso>/
├── IPED-SearchApp.exe       # shim na RAIZ do caso (hábito do usuário mantido)
├── iped/
│   ├── ui/                  # cópia do produto RCP do release que processou
│   ├── jre/                 # JRE copiada (Windows)
│   ├── lib/                 # libs do processamento (sem iped-search-app.jar)
│   └── conf/, ...
└── indexador/ ...           # dados do caso (INALTERADOS)
```

- `Manager.prepareOutputFolder` copia `ui/` junto com `jre/` e `lib/`
  (toque em núcleo justificado — ver plan.md Complexity Tracking).
- Casos de releases anteriores não são afetados (abrem com a UI embarcada
  neles — modelo autocontido; sem requisito retroativo).

## Comportamento do launcher

| Cenário | Comportamento contratado |
|---|---|
| Duplo clique em `<caso>/IPED-SearchApp.exe` (Windows) | Exec de `<caso>/iped/ui/iped-ui.exe` com `-vm <caso>/iped/jre/bin` e o path do caso como argumento de aplicação. |
| `<caso>/iped/ui/iped-ui` (Linux) | Mesmo comportamento com Java 21 Full do sistema (modelo atual de distribuição Linux). |
| Instalação standalone (`release/ui/iped-ui`) | Abre seletor de caso (diálogo nativo) ou recebe `-multicases arquivo.txt` / path de caso como argumento. |
| Workspace e4 (`-data`) | SEMPRE em `~/.iped/ui-workspaces/<case-id>/` — nunca dentro do caso (suporta mídia somente leitura; caso imutável). |
| Caso em mídia somente leitura | Produto abre; escrita de bookmarks desabilitada com aviso claro (edge case da spec). |
| `--nogui` no processamento | Sem UI alguma; `ProgressConsole` em modo texto (FR-026). |

## Aposentadorias no cut-over (FR-023)

| Artefato | Destino |
|---|---|
| `lib/iped-search-app.jar` (`BootstrapUI`/`AppMain`) | Removido do release e do `pom.xml`. |
| `iped-app/src/main/java/iped/app/ui/**`, `graph/**`, `timelinegraph/**` | Removidos do repositório no release de cut-over. |
| DockingFrames, Kharon*, jfreechartextensions*, jcalendar | Removidos das dependências (*permanecem apenas se ainda bridgeados — ver research R4; remoção total é meta pós-cut-over). |
| Splash/`StartUpControl` (JVM pai↔filha) | Substituído pelo splash nativo do launcher equinox. |

## Critérios de aceitação do contrato

1. `mvn -P rcp clean verify` produz `ui/` funcional para win64 e linux64.
2. Caso processado pelo release novo abre por duplo clique na raiz do caso em
   máquina Windows **sem Java instalado** (JRE embarcada via `-vm`).
3. O mesmo caso em compartilhamento de rede somente leitura abre e exibe
   aviso ao tentar gravar bookmark.
4. Remoção de `plugins-ext/*.jar` não impede o boot (US6 cenário 2).
