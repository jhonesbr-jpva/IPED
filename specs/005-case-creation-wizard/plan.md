# Implementation Plan: Criação e Abertura de Casos na GUI RCP

**Branch**: `005-case-creation-wizard` | **Date**: 2026-06-16 | **Spec**: [spec.md](spec.md)

**Input**: Feature specification from `/specs/005-case-creation-wizard/spec.md`

**Note**: This template is filled in by the `/speckit-plan` command. See `.specify/templates/plan-template.md` for the execution workflow.

## Summary

Unificar a porta de entrada do produto na **UI RCP** já existente (feature 004,
`iped.ui` / launcher Equinox). Adicionar ao menu principal as entradas **Open Case**
e **New Case**, mais um **gerenciador/editor de perfis**. *Open Case* reaproveita o
`CaseSessionService` (single/multi + near-live) que a 004 já entrega. *New Case* abre
um **wizard JFace** que coleta as opções de criação (fontes, saída, perfil, opções
comuns + etapa "avançado") e **lança o processamento como subprocesso**, invocando o
**mesmo `Bootstrap`/`iped.jar`** que o CLI atual usa — preservando o isolamento de
processo (Princípio V) e o motor headless (FR-021). A janela de progresso SWT migrada
na 004 (`iped.rcp.progress`) já cobre o acompanhamento; ao iniciar, o wizard oferece
**abrir o caso em modo quase-ao-vivo** (FR-011, reaproveitando o `CommitMonitor` da
004). O **editor de perfis completo** (Clarifications: Q2) é uma UI sobre o sistema de
`Configurable`/arquivos de config existente — embodiment direto do Princípio III —
com perfis de usuário gravados em `<install>/profiles/<nome>` (onde `-profile` já os
encontra) e perfis embarcados tratados como templates somente leitura.

Divergência contida fora da camada de apresentação (FR-028): **zero mudança no
engine** no caminho default; o único toque possível (resolução de perfil em diretório
de usuário) é um plano-B condicional registrado em Complexity Tracking. Decisões
completas em [research.md](research.md).

## Technical Context

**Language/Version**: Java 21 LTS (Liberica Full 21 — JDK de build em
`H:\java\LibericaJDK-21-Full`), mesma baseline da 004.

**Primary Dependencies**: Eclipse Platform 4.32 (e4/SWT/JFace/Equinox, EPL-2.0) — em
particular **JFace Wizards/Dialogs** (`org.eclipse.jface.wizard`, já na target
platform, sem dependência nova); reactor **Tycho 4.0.10**; `iped.rcp.*` bundles da
004; `Bootstrap`/`iped.jar` (CLI de processamento) consumido **como subprocesso**,
inalterado; `CmdLineArgsImpl`/JCommander como contrato de argumentos.

**Storage**:
- Perfis embarcados: `<install>/profiles/{forensic,pedo,triage,fastmode,blind}/`
  (somente leitura — templates).
- Perfis de usuário: `<install>/profiles/<nome>/` (R3 — onde `Main` resolve
  `-profile <nome>`; plano-B: `~/.iped/profiles/` + extensão de resolução).
- Casos recentes / preferências do wizard: área de usuário (`~/.iped/`, padrão da 004).
- Caso (saída do processamento): criado pelo engine; **a UI não escreve evidência**.

**Testing**: Tycho-surefire + JUnit 5 para os serviços headless de `iped.rcp.core`
(`ProfileService`, `ProcessingLaunchService` — mapeamento de argumentos, merge de
config base+override, validação); SWTBot (CI Linux/GTK via Xvfb) para o fluxo do
wizard e do editor de perfis; teste de equivalência (FR-013/SC-004) comparando um caso
criado pelo wizard com o mesmo perfil/evidência via CLI.

**Target Platform**: Windows x64 (`win32.win32.x86_64`, JRE embarcada) e Linux x64
(`gtk.linux.x86_64`) — paridade com a 004; macOS fora do escopo.

**Project Type**: Aplicação desktop (produto RCP/p2) — extensão da UI da 004 dentro do
reactor Tycho `iped-rcp/`; o processamento permanece um processo CLI separado.

**Performance Goals**: Sem metas de throughput novas (o processamento é o motor
inalterado). Metas de UX: criar+iniciar um caso pela UI em ≤ 3 min de interação
(SC-001); wizard responsivo; near-live reaproveita o gate FR-030 já validado na 004.

**Constraints**: Reaproveitar a launch path do `Bootstrap` (sem duplicar a montagem de
classpath/JVM filha); processamento out-of-process (Princípio V); editor de perfis não
pode corromper perfis embarcados (FR-018) nem o determinismo do caso (FR-013);
subconjunto curado de opções no wizard, flags raras só CLI/config (Q4); i18n nos 6
locales (FR-023); divergência contida (FR-028).

**Scale/Scope**: 24 requisitos funcionais; 3 user stories; ~55 `Configurable`s do
engine a expor no editor de perfis (via grid genérico dirigido pelos arquivos de
config, não 55 telas à mão — R4); ~15–20 parâmetros de criação no wizard (curados de
`CmdLineArgsImpl`); 2 SOs.

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

| Princípio | Avaliação | Resultado |
|---|---|---|
| **I. Estabilidade da API Pública** | Nada toca `iped-api`, chaves Lucene, `AppAnalyzer` ou formatos de bookmark. O editor de perfis escreve **arquivos de config existentes** (formatos `Configurable` atuais) em pastas de perfil; não altera schema de índice. `iped.rcp.api` (provisória) não muda — criação de caso não é parte da API de extensão. | ✅ PASS |
| **II. Extensão Modular** | Tudo novo vive em módulos novos/da 004: novo bundle `iped.rcp.casecreation` (wizard + editor + handlers), serviços headless em `iped.rcp.core`, itens de menu em `iped.rcp.app`. Processamento reusa `Bootstrap` **como subprocesso** (zero mudança). Único toque possível no engine = resolução de perfil em dir de usuário (plano-B condicional, Complexity Tracking). | ✅ PASS |
| **III. Configuração antes de Código** | O editor de perfis é, por definição, uma UI sobre o padrão `Configurable<T>`/arquivos de config (Princípio III materializado). Nenhum threshold hardcoded; perfis dirigem variações de pipeline (sem ramos condicionais). Strings em `localization/` (FR-023). | ✅ PASS |
| **IV. Integridade Forense e Determinismo** | Criar um caso = invocar o **mesmo motor determinístico** com as mesmas configs (FR-013/SC-004 = gate de equivalência). UI read-only sobre evidência. Config files lidos/escritos via `UTF8Properties` (charset explícito). Sem `System.out` (SLF4J). | ✅ PASS |
| **V. Concorrência e Isolamento** | Processamento roda **out-of-process** (subprocesso `Bootstrap` → JVM filha), preservando o isolamento que motivou a separação. Wizard/editor na UI thread SWT (`Display.asyncExec`); lançamento/validação/IO em Jobs API. Near-live = `CommitMonitor` cross-process da 004 (sem handshake in-process). | ✅ PASS |
| **Build/Distribuição** | Código novo no reactor Tycho; novo bundle registrado em `feature.xml` + `.product`. **Sem dependência de terceiros nova** (JFace Wizards já está na plataforma EPL-2.0 registrada na 004). CI: o job `rcp` existente cobre os novos SWTBot. | ✅ PASS |

**Re-check pós-Phase 1**: o design não introduziu violação nova. O único toque
potencial no engine (resolução de perfil em dir de usuário) permanece **condicional**
(plano-B) e está na tabela de complexidade. ✅

## Project Structure

### Documentation (this feature)

```text
specs/005-case-creation-wizard/
├── plan.md              # Este arquivo
├── research.md          # Phase 0 (R1–R8)
├── data-model.md        # Phase 1 — entidades (NewCaseRequest, Profile, ProfileConfigModel, ProcessingJob, RecentCases)
├── quickstart.md        # Phase 1 — build, execução e validação
├── contracts/           # Phase 1 — contratos de interface
│   ├── case-menu-commands.contract.md     # IDs de comando/menu (Open/New/Manage Profiles/Recent)
│   ├── new-case-wizard.contract.md        # etapas do wizard + mapeamento → args do Bootstrap
│   ├── processing-launch.contract.md       # como a UI RCP lança o Bootstrap (subprocesso)
│   └── profile-editor.contract.md          # modelo de config (base+override), storage, validação
├── checklists/
│   └── requirements.md  # checklist de qualidade da spec (já validado)
└── tasks.md             # Phase 2 (/speckit-tasks — NÃO criado aqui)
```

### Source Code (repository root)

```text
iped-rcp/                                  # reactor Tycho da 004 (perfil -P rcp)
├── bundles/
│   ├── iped.rcp.core/                     # + serviços HEADLESS desta feature (DS, testáveis):
│   │   └── src/main/java/iped/rcp/core/
│   │       ├── profiles/                  # ProfileService, ProfileDescriptor (built-in/user),
│   │       │                              #   ProfileConfigModel, ConfigOption, ProfileValidation
│   │       └── processing/                # ProcessingLaunchService, NewCaseRequest, BootstrapCommandBuilder,
│   │                                      #   ProcessingJobHandle, RecentCasesStore
│   ├── iped.rcp.casecreation/             # NOVO bundle (UI): wizard + editor de perfis + handlers
│   │   ├── META-INF/MANIFEST.MF
│   │   ├── plugin.xml                     # (se necessário p/ ícones/temas)
│   │   └── src/main/java/iped/rcp/casecreation/
│   │       ├── handlers/                  # OpenCaseHandler, NewCaseHandler, ManageProfilesHandler, OpenRecentHandler
│   │       ├── wizard/                    # NewCaseWizard + SourcesPage, OutputPage, ProfilePage,
│   │       │                              #   CommonOptionsPage, AdvancedOptionsPage, SummaryPage
│   │       └── profiles/                  # ProfileManagerDialog, ProfileEditorDialog, ConfigOptionGrid
│   └── iped.rcp.app/                      # itens de menu + comandos + LifeCycle (start sem caso)
│       ├── Application.e4xmi              # + Menu "File": Open/New/Recent/Manage Profiles
│       └── src/.../app/LifeCycle.java     # permitir subir SEM caso (estado vazio → menu)
├── features/iped.rcp.feature/feature.xml # + iped.rcp.casecreation
├── products/iped.rcp.product/iped-ui.product
└── tests/
    ├── iped.rcp.tests.swtbot/            # + fluxos do wizard e do editor de perfis
    └── iped.rcp.tests.parity/           # + teste de equivalência caso-wizard × caso-CLI (FR-013/SC-004)

# Módulos existentes consumidos SEM modificação:
iped-app/.../bootstrap/Bootstrap.java      # lançado como subprocesso (entry de processamento, intacto)
iped-app/.../processing/Main.java          # processo filho do Bootstrap (intacto)
iped-app/resources/config/profiles/*       # perfis embarcados (templates) + destino dos perfis de usuário
iped-app/resources/config/IPEDConfig.txt + conf/*   # fonte do modelo de config do editor (com comentários # como descrição)
iped-app/resources/localization/*.properties        # chaves novas (menu, wizard, editor) nos 6 locales

# Toque CONDICIONAL (plano-B, gate R3): resolução de -profile em diretório de usuário
iped-engine/.../config/Configuration.java OU iped-app/.../processing/Main.java:174
# — só se a instalação for read-only impossibilitando <install>/profiles/<nome>;
#   justificar via Princípio II e registrar no Complexity Tracking.
```

**Structure Decision**: seguir a separação da 004 — **serviços headless** (sem
toolkit, DS, testáveis pelo harness de paridade) em `iped.rcp.core`; **UI** (wizard,
diálogos, handlers) num **novo bundle `iped.rcp.casecreation`**; **menu/comandos** no
`iped.rcp.app` (que já é dono do `LifeCycle` e da resolução de caso). O processamento
**não** é reescrito nem embutido: a UI monta a linha de comando do `Bootstrap` e o
lança como processo separado, reusando 100% da launch path existente. Nenhum arquivo
do engine é modificado no caminho default.

## Complexity Tracking

> Itens que exigem justificativa explícita em PR (Princípio II) ou que ficam como
> plano-B condicional. Nenhum é violação no caminho default.

| Item | Why Needed | Simpler Alternative Rejected Because |
|---|---|---|
| `iped.rcp.app/LifeCycle` passa a permitir **subir sem caso aberto** (estado vazio dirigido pelo menu) | "Open Case"/"New Case" são pontos de entrada em runtime; hoje o `LifeCycle` força um caso no startup (args ou `DirectoryDialog`) | Manter o `DirectoryDialog` obrigatório no boot tornaria "Open/New Case" redundantes e impediria criar um caso sem antes abrir outro |
| **Plano-B condicional** — resolução de `-profile <nome>` passa a buscar também `~/.iped/profiles/` (toque mínimo em `Main`/`Configuration`) | Só se a instalação for somente-leitura, impedindo gravar perfis de usuário em `<install>/profiles/` (R3) | O default (gravar em `<install>/profiles/`, onde `Main:176` já resolve) é zero-mudança; a distribuição do IPED é uma pasta portátil normalmente gravável — o plano-B só dispara em instalações travadas |
