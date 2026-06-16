---

description: "Task list for Criação e Abertura de Casos na GUI RCP"
---

# Tasks: Criação e Abertura de Casos na GUI RCP

**Input**: Design documents from `/specs/005-case-creation-wizard/`

**Prerequisites**: [plan.md](plan.md) (required), [spec.md](spec.md) (user stories),
[research.md](research.md) (R1–R8), [data-model.md](data-model.md),
[contracts/](contracts/)

**Tests**: INCLUDED — research R8 + success criteria SC-003/SC-004 require headless +
SWTBot + equivalence tests. Headless service tests gate the engine-equivalence
invariant (FR-013).

**Organization**: tasks grouped by user story (P1→P3) for independent implementation/
testing. Builds on the feature 004 RCP platform (e4/SWT/Tycho), reusing
`CaseSessionService`/`CommitMonitor` and the `iped.rcp.progress` window.

## Format: `[ID] [P?] [Story] Description`

- **[P]**: can run in parallel (different files, no dependency on incomplete tasks)
- **[Story]**: US1 / US2 / US3 (Setup & Foundational & Polish have no story label)
- Exact file paths included. Repo-relative paths under `iped-rcp/`.

## Path Conventions

- Headless services: `iped-rcp/bundles/iped.rcp.core/src/main/java/iped/rcp/core/`
- New UI bundle: `iped-rcp/bundles/iped.rcp.casecreation/src/main/java/iped/rcp/casecreation/`
- App model/menu/lifecycle: `iped-rcp/bundles/iped.rcp.app/`
- i18n catalogs: `iped-app/resources/localization/*.properties` (6 locales; single source, R-004.R7)
- SWTBot: `iped-rcp/tests/iped.rcp.tests.swtbot/`; parity/headless: `iped-rcp/tests/iped.rcp.tests.parity/`

---

## Phase 1: Setup (Shared Infrastructure)

**Purpose**: criar e registrar o novo bundle de UI da feature.

- [ ] T001 Create the new bundle skeleton `iped-rcp/bundles/iped.rcp.casecreation/` (`META-INF/MANIFEST.MF`, `build.properties`, `pom.xml` packaging `eclipse-plugin`, `plugin.xml`) matching the Tycho conventions of the other `iped.rcp.*` bundles
- [ ] T002 Register `iped.rcp.casecreation` in `iped-rcp/features/iped.rcp.feature/feature.xml` and `iped-rcp/products/iped.rcp.product/iped-ui.product`
- [ ] T003 [P] Declare bundle dependencies in `iped-rcp/bundles/iped.rcp.casecreation/META-INF/MANIFEST.MF` (Require-Bundle/Import-Package: `iped.rcp.core`, `iped.rcp.api`, `org.eclipse.jface`, `org.eclipse.e4.ui.*`, `org.eclipse.swt`) and enable `tycho-ds-plugin` in its `pom.xml` if DS is used

**Checkpoint**: bundle compiles empty and is part of the product.

---

## Phase 2: Foundational (Blocking Prerequisites)

**Purpose**: scaffolding compartilhado por todas as user stories — menu "File" e a
capacidade de subir sem caso (a porta de entrada do menu).

**⚠️ CRITICAL**: nenhuma user story fica testável de ponta a ponta antes destas.

- [ ] T004 Add the **File** main-menu container (first child of `mainMenu`) with command-service wiring in `iped-rcp/bundles/iped.rcp.app/Application.e4xmi`, per [contracts/case-menu-commands.contract.md](contracts/case-menu-commands.contract.md) (items are added by each story)
- [ ] T005 Modify `iped-rcp/bundles/iped.rcp.app/src/main/java/iped/rcp/app/LifeCycle.java` to allow starting **without a case** (empty perspective driven by the File menu) instead of forcing a `DirectoryDialog` at boot (research R6 / Complexity Tracking)
- [ ] T006 [P] Add base i18n keys for the File menu (`Menu.File`, separators labels if any) to all 6 `iped-app/resources/localization/iped-app*.properties` bundles

**Checkpoint**: app boots to an empty workbench with an (empty) File menu; ready for stories.

---

## Phase 3: User Story 1 - Criar um caso pelo assistente de Novo Caso (Priority: P1) 🎯 MVP

**Goal**: menu **New Case** → wizard JFace (fontes, saída, perfil, opções) → lança o
`Bootstrap` out-of-process com a janela de progresso da 004 → oferece abrir o caso em
modo quase-ao-vivo. Substitui o lançador `iped.exe` para criação interativa.

**Independent Test**: em release limpo, sem terminal, criar um caso a partir de uma
evidência de referência pelo wizard, acompanhar o progresso e abri-lo; comparar o caso
com um criado pela CLI (mesmo perfil/evidência) — devem ser equivalentes (quickstart V1).

### Headless services & tests for User Story 1

- [ ] T007 [P] [US1] Create `NewCaseRequest`, `DataSourceEntry`, `CommonOptions`, `ProcessingMode` (NEW/APPEND/CONTINUE/RESTART) in `iped-rcp/bundles/iped.rcp.core/src/main/java/iped/rcp/core/processing/` per [data-model.md](data-model.md) §1
- [ ] T008 [P] [US1] Create `ProfileDescriptor` (built-in/user) + `ProfileService.listProfiles()` discovery (scan `profiles/`) in `iped-rcp/bundles/iped.rcp.core/src/main/java/iped/rcp/core/profiles/` (mutating ops come in US3)
- [ ] T009 [US1] Implement `BootstrapCommandBuilder` (`NewCaseRequest` → Bootstrap arg list) in `iped-rcp/bundles/iped.rcp.core/src/main/java/iped/rcp/core/processing/BootstrapCommandBuilder.java` per [contracts/new-case-wizard.contract.md](contracts/new-case-wizard.contract.md) (depends on T007)
- [ ] T010 [US1] Implement `ProcessingLaunchService` (OSGi DS) + `ProcessingJobHandle` in `iped-rcp/bundles/iped.rcp.core/src/main/java/iped/rcp/core/processing/` — resolve java/`iped.jar`, spawn `Bootstrap` subprocess, lifecycle, FR-024 same-output conflict guard — per [contracts/processing-launch.contract.md](contracts/processing-launch.contract.md) (depends on T009)
- [ ] T011 [P] [US1] Headless test: `BootstrapCommandBuilder` arg mapping + FR-008 validations (source exists, output writable/not-subfolder, mode/case rules) in `iped-rcp/tests/iped.rcp.tests.parity/` (covers SC-003)
- [ ] T012 [P] [US1] Headless **equivalence** test: case created via `BootstrapCommandBuilder` launch vs direct CLI with same profile/evidence → same item/category/index-field universe (FR-013/SC-004) in `iped-rcp/tests/iped.rcp.tests.parity/`

### UI for User Story 1

- [ ] T013 [US1] Implement `NewCaseWizard` + pages (`SourcesPage`, `OutputPage`, `ProfilePage`, `CommonOptionsPage`, `AdvancedOptionsPage`, `SummaryPage`) with per-page validation and native file/dir dialogs in `iped-rcp/bundles/iped.rcp.casecreation/src/main/java/iped/rcp/casecreation/wizard/` (FR-004…FR-009; depends on T007, T008)
- [ ] T014 [US1] Implement `NewCaseHandler` in `iped-rcp/bundles/iped.rcp.casecreation/src/main/java/iped/rcp/casecreation/handlers/NewCaseHandler.java` and add command `iped.rcp.command.newcase` + menuitem to `iped-rcp/bundles/iped.rcp.app/Application.e4xmi` (depends on T004, T013)
- [ ] T015 [US1] On wizard finish: launch via `ProcessingLaunchService` and offer near-live open via `ICaseSessionManager.openCase(..., nearLive=true)` in the wizard/handler (FR-010/FR-011, research R7; depends on T010, T013)
- [ ] T016 [P] [US1] Add i18n keys for the wizard pages + New Case menu to all 6 `iped-app/resources/localization/iped-app*.properties` bundles (FR-023)
- [ ] T017 [US1] SWTBot test: full New Case flow + cancel-leaves-no-artifact (FR-012) in `iped-rcp/tests/iped.rcp.tests.swtbot/` (covers quickstart V1/V2; depends on T013–T015)

**Checkpoint**: User Story 1 = MVP — criação de caso pela UI funciona ponta a ponta.

---

## Phase 4: User Story 2 - Abrir um caso existente pelo menu (Priority: P2)

**Goal**: menu **Open Case** abre um caso processado (reaproveita `CaseSessionService`);
submenu **Recent Cases** para reabertura rápida.

**Independent Test**: com um caso de referência processado, usar Open Case para abri-lo
(paridade com a abertura da 004) e confirmar que aparece em Recent Cases (quickstart V3).

- [ ] T018 [P] [US2] Implement `RecentCasesStore` (`~/.iped/recent-cases.*`, add/update/prune, cap) in `iped-rcp/bundles/iped.rcp.core/src/main/java/iped/rcp/core/processing/RecentCasesStore.java` per [data-model.md](data-model.md) §5
- [ ] T019 [US2] Implement `OpenCaseHandler` (native `DirectoryDialog` → `ICaseSessionManager.openCase`) and `OpenRecentHandler` in `iped-rcp/bundles/iped.rcp.casecreation/src/main/java/iped/rcp/casecreation/handlers/` (invalid folder → clear message, no crash)
- [ ] T020 [US2] Add `iped.rcp.command.opencase` + Open Case menuitem and the dynamic **Recent Cases** submenu (populated from `RecentCasesStore`) to `iped-rcp/bundles/iped.rcp.app/Application.e4xmi` (depends on T004, T018, T019)
- [ ] T021 [US2] Update `RecentCasesStore` on every case open (Open/New/near-live) — wire into the session-open path in `iped-rcp/bundles/iped.rcp.casecreation/` / handlers (depends on T018)
- [ ] T022 [P] [US2] Add i18n keys for Open Case / Recent Cases to all 6 `iped-app/resources/localization/iped-app*.properties` bundles
- [ ] T023 [P] [US2] Headless test for `RecentCasesStore` (add/update/prune/cap) + SWTBot test for Open Case valid/invalid + recent reopen in `iped-rcp/tests/iped.rcp.tests.parity/` and `iped-rcp/tests/iped.rcp.tests.swtbot/`

**Checkpoint**: User Stories 1 e 2 funcionam independentemente.

---

## Phase 5: User Story 3 - Criar e editar perfis de processamento (Priority: P3)

**Goal**: tela de gestão de perfis + **editor completo** (todas as opções de config do
pipeline, dirigido pelos arquivos de config), com clonar, salvar-como e perfis
embarcados somente leitura. Novos perfis aparecem no wizard.

**Independent Test**: criar/clonar um perfil, alterar uma opção, salvar; confirmar que
grava só o override em `profiles/<nome>/` e que aparece no wizard; tentar editar um
embarcado → "Salvar como" (quickstart V4 / SC-005).

- [ ] T024 [P] [US3] Create `ProfileConfigModel`, `ConfigFileGroup`, `ConfigOption`, `ProfileValidation` in `iped-rcp/bundles/iped.rcp.core/src/main/java/iped/rcp/core/profiles/` per [data-model.md](data-model.md) §4
- [ ] T025 [US3] Extend `ProfileService` with `loadModel` (base+override merge from `IPEDConfig.txt`/`LocalConfig.txt`/`conf/*` + `#` comments as descriptions), `createProfile`, `saveModel` (write overridden keys only, UTF-8), `deleteProfile` — built-in read-only — in `iped-rcp/bundles/iped.rcp.core/src/main/java/iped/rcp/core/profiles/ProfileService.java` per [contracts/profile-editor.contract.md](contracts/profile-editor.contract.md) (depends on T008, T024)
- [ ] T026 [US3] Implement `ProfileManagerDialog` (list/create/clone/delete) + `ManageProfilesHandler` and add `iped.rcp.command.manageprofiles` + menuitem to `iped-rcp/bundles/iped.rcp.app/Application.e4xmi` in `iped-rcp/bundles/iped.rcp.casecreation/src/main/java/iped/rcp/casecreation/profiles/` + `.../handlers/` (depends on T004, T025)
- [ ] T027 [US3] Implement `ProfileEditorDialog` + `ConfigOptionGrid` (groups per config file; advanced `.xml`/`.json` section; built-in → "Save as" per FR-018) in `iped-rcp/bundles/iped.rcp.casecreation/src/main/java/iped/rcp/casecreation/profiles/` (depends on T025)
- [ ] T028 [US3] Wire a "Manage Profiles…" shortcut into the wizard `ProfilePage` and refresh the profile list after edits (FR-017) in `iped-rcp/bundles/iped.rcp.casecreation/src/main/java/iped/rcp/casecreation/wizard/ProfilePage.java` (depends on T013, T026)
- [ ] T029 [P] [US3] Add i18n keys for the profile manager/editor to all 6 `iped-app/resources/localization/iped-app*.properties` bundles
- [ ] T030 [P] [US3] Headless tests for `ProfileService`: base+override merge, save round-trip (overrides only), built-in isolation, name collision/validation (FR-018/FR-019) in `iped-rcp/tests/iped.rcp.tests.parity/`
- [ ] T031 [US3] SWTBot test: create/clone/edit profile, built-in "Save as", new profile appears in wizard (SC-005) in `iped-rcp/tests/iped.rcp.tests.swtbot/` (depends on T026–T028)
- [ ] T032 [US3] CONDITIONAL (plan-B, only if the install dir is read-only): store user profiles in `~/.iped/profiles/` and extend `-profile` resolution in `iped-engine/src/main/java/iped/engine/config/Configuration.java` or `iped-app/src/main/java/iped/app/processing/Main.java` (~line 174) — justify in [plan.md](plan.md) Complexity Tracking (research R3)

**Checkpoint**: todas as três user stories funcionam independentemente.

---

## Phase 6: Polish & Cross-Cutting Concerns

**Purpose**: documentação, transição e validação final.

- [ ] T033 [P] Update `iped-rcp/CLAUDE.md` (new bundle `iped.rcp.casecreation`; new `iped.rcp.core` services `ProfileService`/`ProcessingLaunchService`/`RecentCasesStore`; File menu)
- [ ] T034 [P] Transition (FR-020/FR-021): make the RCP app the promoted interactive case-creation entry (menu/docs/shortcuts) while **keeping `iped.exe` distributed and unchanged** as the headless/automation entry (do NOT remove the shim — its removal is a future, out-of-scope step gated on the new launcher gaining a headless mode; Clarifications 2026-06-16 / I1 remediation). Verify no interactive-creation path depends on `iped.exe` (SC-006)
- [ ] T035 Threading/concurrency review: wizard/editor manipulate widgets on the SWT UI thread; launch/validation/IO run via the e4 Jobs API off the UI thread (Princípio V)
- [ ] T036 Run [quickstart.md](quickstart.md) validation scenarios V1–V5 and record results

---

## Dependencies & Execution Order

### Phase Dependencies

- **Setup (Phase 1)**: no dependencies — start immediately.
- **Foundational (Phase 2)**: depends on Setup — **BLOCKS** all user stories (File menu + start-without-case).
- **User Stories (Phase 3–5)**: all depend on Foundational. After that they are independently completable; US3 has a small in-wizard tie (T028) but is otherwise standalone.
- **Polish (Phase 6)**: depends on the desired user stories being complete.

### User Story Dependencies

- **US1 (P1)**: after Foundational. No dependency on US2/US3. Introduces `ProfileService.listProfiles()` (T008) reused by US3.
- **US2 (P2)**: after Foundational. Independent of US1/US3 (reuses 004 `CaseSessionService`).
- **US3 (P3)**: after Foundational. Extends `ProfileService` from US1's T008; the only cross-story touch is T028 (adds a shortcut into US1's `ProfilePage`) — US3 is still independently testable via Manage Profiles.

### Within Each User Story

- Models/records before services; services before handlers/UI; UI before its SWTBot test.
- e4xmi menu edits are sequential (same file): T004 (container) → T014 (New Case) → T020 (Open/Recent) → T026 (Manage Profiles).

### Parallel Opportunities

- Setup: T003 [P] alongside T001/T002 finalization.
- Foundational: T006 [P] (i18n) alongside T004/T005.
- US1: T007 + T008 [P] (different files); T011 + T012 [P] (tests); T016 [P] (i18n).
- US2: T018 [P]; T022 [P]; T023 [P].
- US3: T024 [P]; T029 [P]; T030 [P].
- Polish: T033 + T034 [P].
- Across stories: once Foundational is done, US1/US2/US3 can be staffed in parallel (mind the shared e4xmi/i18n files — coordinate those edits).

---

## Parallel Example: User Story 1

```bash
# Headless model records (different files):
Task: "T007 Create NewCaseRequest/DataSourceEntry/CommonOptions/ProcessingMode"
Task: "T008 Create ProfileDescriptor + ProfileService.listProfiles()"

# Headless tests (after T009/T010):
Task: "T011 BootstrapCommandBuilder mapping + validation test"
Task: "T012 Engine-equivalence test (wizard vs CLI)"
```

---

## Implementation Strategy

### MVP First (User Story 1 only)

1. Phase 1 Setup → Phase 2 Foundational (File menu + start-without-case).
2. Phase 3 US1 (wizard + launch service + near-live).
3. **STOP and VALIDATE**: quickstart V1/V2 — criar caso pela UI e comparar com a CLI.
4. Demo: o `iped.exe` deixa de ser necessário para criar um caso interativamente.

### Incremental Delivery

1. Setup + Foundational → fundação pronta.
2. US1 → criação de caso (MVP) → validar → demo.
3. US2 → Open Case + recentes → validar → demo.
4. US3 → editor de perfis completo → validar → demo.

### Parallel Team Strategy

After Foundational: Dev A → US1; Dev B → US2; Dev C → US3 (begins headless
`ProfileConfigModel`/`ProfileService` independently). Coordinate the shared
`Application.e4xmi` and `localization/*.properties` edits.

---

## Notes

- [P] = different files, no dependency on incomplete tasks.
- Build FULL first (`mvn clean install`) then `mvn -f iped-rcp/pom.xml clean verify`; run SWTBot per `-pl` from `.m2` (project memory).
- Headless services in `iped.rcp.core` are OSGi DS and toolkit-free — testable by the parity harness (R8); UI lives in `iped.rcp.casecreation`.
- **Zero engine changes on the default path** (FR-028); only T032 (conditional) touches the engine, and only for read-only installs.
- New strings in **all 6 locales** via `iped.rcp.core.i18n.Messages` (R-004.R7).
- Processing always runs out-of-process via `Bootstrap` (Princípio V); never embed the engine in the Equinox process.
