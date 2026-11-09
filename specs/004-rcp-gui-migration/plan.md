# Implementation Plan: Migração da GUI do IPED para Eclipse RCP

**Branch**: `004-rcp-gui-migration` | **Date**: 2026-06-10 | **Spec**: [spec.md](spec.md)

**Input**: Feature specification from `/specs/004-rcp-gui-migration/spec.md`

**Note**: This template is filled in by the `/speckit-plan` command. See `.specify/templates/plan-template.md` for the execution workflow.

## Summary

Substituir, por **cut-over total**, todas as superfícies gráficas do IPED (UI de
análise, janela de progresso, splash, diálogos do inicializador) por uma
aplicação **Eclipse 4 (e4) pura** sobre SWT/JFace, com widgets nativos do SO
(FR-025), paridade funcional completa contra inventário congelado/re-baselinado
(SC-001) e API de extensão provisória (FR-022). Abordagem técnica: reactor
Tycho dedicado (`iped-rcp/`), engine encapsulado em bundle wrapper único,
shell/estruturas em SWT nativo e viewers de conteúdo existentes preservados via
bridge `SWT_AWT` (divergência contida fora da camada de apresentação — FR-028).
Decisões completas em [research.md](research.md).

## Technical Context

**Language/Version**: Java 21 LTS (Liberica Full 21 — JDK de build em
`H:\java\LibericaJDK-21-Full`; JavaFX embutido continua necessário para os
viewers bridgeados)

**Primary Dependencies**: Eclipse Platform ≥ 4.32 (e4/SWT/JFace/OSGi,
EPL-2.0), Eclipse Tycho 4.x (build), Nebula Gallery (+ NatTable como
fallback), stack atual do engine inalterada (`iped-engine`, Lucene 9.2, Tika
2.4, DockingFrames/Kharon/JFreeChart permanecem só no que for bridgeado ou até
o cut-over)

**Storage**: Pasta do caso (índice Lucene + storages SQLite + bookmarks) —
**somente leitura** para evidência; artefatos do usuário nos formatos atuais
(FR-004/005). Layout de workspace em `~/.iped/ui-workspaces/<case-id>/` (R5)

**Testing**: JUnit 5 + Tycho-surefire (bundles), SWTBot (fluxos de UI, CI
Linux/GTK via Xvfb), harness de paridade headless sobre API do engine,
inventário de paridade versionado (`parity-inventory.md`)

**Target Platform**: Windows x64 (`win32.win32.x86_64`, JRE embarcada) e Linux
x64 (`gtk.linux.x86_64`, Java do sistema) — macOS fora do escopo

**Project Type**: Aplicação desktop (produto RCP/p2) dentro do build Maven
multi-módulo existente; produto integrado ao release atual e copiado para o
caso (autocontido)

**Performance Goals**: Abertura de caso e busca ≤ 110% da UI atual no caso de
referência ≥ 1 M itens (SC-003); fluxo de triage ≤ 110% (SC-002); galeria com
100 mil imagens sem congelamento > 1 s (SC-004); sessão de 8 h estável (SC-008)

**Constraints**: Cut-over total (FR-023); todas as superfícies gráficas
(FR-024); aparência nativa (FR-025); divergência contida fora da camada de
apresentação (FR-028); API de extensão provisória no cut-over; sem auto-update;
casos autocontidos imutáveis; i18n nos 6 locales atuais

**Scale/Scope**: ~250 classes Swing em `iped-app/ui` + `graph/` +
`timelinegraph/` a substituir ou bridgear; 28 requisitos funcionais; 6 user
stories; 2 SOs; caso de referência ≥ 1 M itens

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

| Princípio | Avaliação | Resultado |
|---|---|---|
| **I. Estabilidade da API Pública** | `iped-api`, chaves Lucene (`BasicProps`/`IndexItem`), `AppAnalyzer` e formatos de bookmarks intocados (FR-004/005). A nova API de extensão é **provisória** por decisão registrada (Clarifications) — só entra no regime do Princípio I quando declarada estável. | ✅ PASS |
| **II. Extensão Modular** | Toda a feature vive em módulos novos (`iped-rcp/*`). Toques em componentes núcleo limitados e justificados: `Manager.prepareOutputFolder` (copiar `ui/` para o caso) e aposentadoria de `BootstrapUI`/`AppMain` no cut-over (FR-023). Registrados em Complexity Tracking. | ✅ PASS (com justificativas) |
| **III. Configuração antes de Código** | Preferências de UI via mecanismos atuais (`LocalConfig`/`AnalysisConfig`/Configurables); strings em `localization/` (R7); nenhum threshold hardcoded; profiles intactos. Fronteira *estado de workspace* × *configuração* definida em [data-model.md](data-model.md) §WorkspaceState — citar nos PRs que persistirem preferências. | ✅ PASS |
| **IV. Integridade Forense e Determinismo** | UI read-only sobre evidência; layout persiste fora do caso (R5 — melhora o status quo em mídia somente leitura); charsets explícitos; SLF4J/Log4j2 (sem `System.out`); exportações determinísticas (FR-015). | ✅ PASS |
| **V. Concorrência e Isolamento** | Modelo de workers do engine intocado. Launcher equinox é o processo isolado da UI (R9). Disciplina de thread única SWT (`Display.asyncExec`) substitui a regra da EDT; JavaFX continua via `Platform.runLater` nos viewers bridgeados; `CancelableWorker`/jobs longos via Jobs API do e4 fora da UI thread. | ✅ PASS |
| **Build/Distribuição** | Java 21 mantido; EPL-2.0 em `ThirdParty.txt` + `licenses/`; CI ganha job `rcp` no mesmo PR que introduzir o reactor (R13); versão única `4.4.0-SNAPSHOT` herdada. | ✅ PASS |

**Re-check pós-Phase 1**: nenhuma decisão de design introduziu violação nova.
Os dois toques em núcleo permanecem os únicos e estão na tabela de
complexidade. ✅

## Project Structure

### Documentation (this feature)

```text
specs/004-rcp-gui-migration/
├── plan.md              # Este arquivo
├── research.md          # Phase 0 (R1–R13)
├── data-model.md        # Phase 1 — entidades da camada de UI
├── quickstart.md        # Phase 1 — build, execução e validação
├── contracts/           # Phase 1 — contratos de interface
│   ├── ui-extension-api.contract.md
│   ├── case-launcher-packaging.contract.md
│   └── progress-ui-events.contract.md
├── parity-inventory.md  # Inventário SC-001 (criado no 1º milestone, congelado)
└── tasks.md             # Phase 2 (/speckit-tasks — não criado aqui)
```

### Source Code (repository root)

```text
iped-rcp/                                  # NOVO — reactor Tycho (perfil -P rcp)
├── pom.xml                                # parent Tycho; herda versão do raiz
├── target-platform/
│   └── iped-rcp.target                    # Eclipse Platform + Nebula (p2, versionado)
├── bundles/
│   ├── iped.rcp.libs/                     # wrapper: iped-engine + deps (Bundle-ClassPath)
│   ├── iped.rcp.api/                      # API de extensão PROVISÓRIA (serviços, tópicos)
│   ├── iped.rcp.core/                     # sessão de caso, serviços DS, adaptador i18n
│   ├── iped.rcp.app/                      # Application.e4xmi, lifecycle, splash, produto
│   ├── iped.rcp.views/                    # parts SWT: resultados, galeria, árvores,
│   │                                      #   metadados, filtros, busca, bookmarks
│   ├── iped.rcp.viewers/                  # hosts SWT_AWT dos viewers existentes
│   ├── iped.rcp.specialized/              # parts bridgeadas: mapa, grafo, timeline
│   └── iped.rcp.progress/                 # janela de progresso SWT standalone (R10)
├── features/
│   └── iped.rcp.feature/
├── products/
│   └── iped.rcp.product/                  # .product + p2-director (win64 + linux64)
├── samples/
│   └── iped.rcp.sample.view/              # extensão de exemplo (US6/SC-007)
└── tests/
    ├── iped.rcp.tests.swtbot/             # fluxos P1/P2
    └── iped.rcp.tests.parity/             # harness headless de paridade

# Módulos existentes tocados (mínimo necessário — FR-028):
iped-engine/src/main/java/iped/engine/core/Manager.java   # prepareOutputFolder: copiar ui/
iped-app/src/main/java/iped/app/processing/Main.java      # progresso SWT no lugar do
                                           #   ProgressFrame + lançamento da análise
                                           #   (R10/R14); --nogui inalterado
iped-app/src/main/java/iped/app/bootstrap/Bootstrap.java   # diálogos de erro do
                                           #   inicializador em SWT; splash/StartUpControl
                                           #   aposentados no cut-over (T041/FR-027)
iped-app/pom.xml                           # integração do produto no release; remoção
                                           #   de iped-search-app.jar no cut-over;
                                           #   config launch4j do shim IPED-SearchApp.exe
                                           #   → exec do launcher equinox (T054)
# Condicional (gate do spike T062 — research R14): ajustes read-only/WAL na camada
# de storage do engine, se a contenção SQLite exigir; justificar via Princípio II
# e registrar no Complexity Tracking
iped-app/resources/localization/*.properties              # chaves novas da UI (R7)
.github/workflows/maven.yml                # job rcp (Xvfb/GTK) no PR do reactor
ThirdParty.txt, licenses/                  # EPL-2.0 (Platform, Nebula)
```

**Structure Decision**: módulo novo `iped-rcp/` isolado por perfil Maven,
consumindo o restante do projeto como binário. Os únicos arquivos existentes
modificados são os listados acima; `iped-app/ui`, `graph/` e `timelinegraph/`
permanecem no repositório até o cut-over (referência do inventário de paridade
e fonte das lógicas a portar), sendo removidos no release de cut-over junto
com `BootstrapUI`/`AppMain`/DockingFrames.

## Complexity Tracking

> Toques em componentes núcleo exigidos pela constituição (Princípio II) — não
> são violações, mas modificações que demandam justificativa explícita em PR.

| Violation | Why Needed | Simpler Alternative Rejected Because |
|-----------|------------|-------------------------------------|
| `Manager.prepareOutputFolder` (engine, núcleo) passa a copiar `ui/` (produto RCP) para `<caso>/iped/` | Manter o modelo de caso autocontido (caso carrega a UI com que foi processado — Clarifications 003/004) | Copiar a UI por script externo pós-processamento quebraria a atomicidade do empacotamento do caso e o contrato do launcher na raiz do caso (`b8b15735a`) |
| Aposentadoria de `BootstrapUI`/`AppMain` + remoção de `iped-app/ui`, `graph/`, `timelinegraph/` no cut-over | FR-023 exige que a UI antiga deixe de ser distribuída e mantida no release de cut-over | Manter as duas UIs indefinidamente dobraria o custo de manutenção e contraria a decisão de cut-over total registrada nas Clarifications |
