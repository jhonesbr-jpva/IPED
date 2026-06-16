# Contract — Lançamento do processamento pela UI RCP

**Feature**: `005-case-creation-wizard` | Status: design (Phase 1)
**Serviço**: `iped.rcp.core.processing.ProcessingLaunchService` (headless, DS)

Define como a UI RCP (processo Equinox) dispara o processamento **out-of-process**,
reusando o `Bootstrap`/`iped.jar` existente (R1) sem tocar o engine.

## Operações

| Operação | Entrada | Saída | Erros |
|---|---|---|---|
| `launch(NewCaseRequest)` | request congelado | `ProcessingJobHandle` | `LaunchException` (java/jar não encontrados, output inválido) |
| `cancel(handle)` | handle | — | encerra o subprocesso (best-effort) |

## Resolução do executável

1. **Java**: a JRE embarcada do release (`<root>/jre/bin/java` no Windows) ou o
   `java.home` do sistema (Linux) — mesma lógica que o `Bootstrap` já aplica; a UI
   prefere reexecutar **`iped.jar` via `Bootstrap`** (não reimplementa classpath).
2. **iped.jar**: `<root>/iped.jar` (entry `iped.app.bootstrap.Bootstrap`). `<root>` é a
   raiz da instalação (descoberta a partir da localização do produto RCP; o produto fica
   em `<root>/ui/`).
3. Comando = `[ java, -jar, <root>/iped.jar, <args do new-case-wizard.contract> ]`.
   `Bootstrap` cuida de `-Xmx`, `--add-opens`, plugins, TSK, UNO, splash e da descoberta
   da **janela de progresso SWT** (R-004.R10 — já implementada).

## Ciclo de vida e sinais

- `ProcessingJobHandle.state`: `STARTING → RUNNING → (SUCCEEDED|FAILED|CANCELED)`,
  derivado do **exit code** do subprocesso (0 = sucesso). A janela de progresso SWT é a
  superfície de acompanhamento (FR-010/FR-026); o serviço apenas observa o processo.
- Streams stdout/stderr do filho são redirecionados/registrados (SLF4J) — o `Bootstrap`
  já emite `IpedSubProcessTempFolder:` etc.; a UI não interpreta o protocolo de
  progresso (isso é da janela SWT).
- **Near-live** (R7): após `STARTING`, a UI pode chamar
  `ICaseSessionManager.openCase(outputDir/iped, nearLive=true)` (caminho da 004); a
  abertura é independente do ciclo do subprocesso.

## Concorrência (FR-024)

- O serviço **recusa** lançar um segundo processamento sobre o **mesmo `outputDir`** se
  já houver um `ProcessingJobHandle` ativo conhecido, com mensagem clara. (Conflitos por
  processos externos continuam protegidos pelos locks do engine, como hoje.)

## Invariantes / não-fazer

- **Não** instanciar `Manager`/engine in-process no Equinox (Princípio V; R1).
- **Não** duplicar a montagem de classpath/JVM do `Bootstrap`.
- **Não** passar `--nogui` no fluxo interativo (a janela de progresso é desejada).
- A UI **não** escreve na evidência nem no caso além do que o engine grava.
