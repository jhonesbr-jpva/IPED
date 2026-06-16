# Contract — Assistente de Novo Caso (etapas + mapeamento de argumentos)

**Feature**: `005-case-creation-wizard` | Status: design (Phase 1)
**Bundle**: `iped.rcp.casecreation` (UI) sobre `iped.rcp.core` (serviços headless)

Define as etapas do wizard (JFace `Wizard`/`WizardPage`, R5) e o **mapeamento canônico**
de `NewCaseRequest` → argumentos do `Bootstrap`. O mapeamento é a fonte da verdade do
`BootstrapCommandBuilder` e deve casar 1:1 com `CmdLineArgsImpl`
([CmdLineArgsImpl.java](../../../iped-app/src/main/java/iped/app/processing/CmdLineArgsImpl.java)).

## Etapas (páginas)

| # | Página | Coleta | Validação (bloqueia "Next/Finish") |
|---|---|---|---|
| 1 | **Sources** | 1+ `DataSourceEntry` (path, dname?, password?) | cada path existe/legível ou physical drive; nomes únicos |
| 2 | **Output** | `outputDir`, `mode` (New/Append/Continue/Restart) | gravável; não ⊆ de uma fonte; Append/Continue/Restart exigem `outputDir/iped` existente |
| 3 | **Profile** | `profileName` (lista de embarcados+usuário) + atalho "Manage Profiles…" | perfil existe em `profiles/` |
| 4 | **Common options** | `CommonOptions` curadas (Q4) + `timezone`, `keywordsFile` | tipos/paths válidos |
| 5 | **Advanced** (opcional) | `advancedParams` (`-X k=v`, flags raras) | sintaxe `k=v` |
| 6 | **Summary** | revisão read-only + linha de comando resultante | — |

Cancelar/fechar em qualquer página: **nenhum** efeito colateral (FR-012).

## Mapeamento `NewCaseRequest` → args do Bootstrap

| Campo | Argumento | Observação |
|---|---|---|
| cada `DataSourceEntry.path` | `-d <path>` | repetível; ordem preservada |
| `DataSourceEntry.displayName` | `-dname <name>` | por fonte |
| `DataSourceEntry.password` | `-p <pwd>` | por fonte; não logar em claro |
| `outputDir` | `-o <dir>` | |
| `profileName` | `-profile <name>` | resolve em `profiles/<name>` (R3) |
| `mode=APPEND` | `--append` | |
| `mode=CONTINUE` | `--continue` | |
| `mode=RESTART` | `--restart` | |
| `timezone` | `-tz <tz>` | se presente |
| `keywordsFile` | `-l <file>` | se presente |
| `commonOptions.addOwner` | `--addowner` | flag |
| `commonOptions.ocrSubset` | `-ocr <cat>` | repetível |
| `commonOptions.portable` | `--portable` | flag |
| `commonOptions.noPstAttachs` | `--nopstattachs` | flag |
| `commonOptions.noLinkedItems` | `--nolinkeditems` | flag |
| `commonOptions.downloadInternetData` | `--downloadInternetData` | flag |
| `commonOptions.blocksize` | `-b <n>` | se ≠ 0 |
| `advancedParams[k]` | `-X k=v` ou flag literal | passthrough |
| (interativo, com GUI) | **sem** `--nogui` | a janela de progresso SWT aparece |

> A UI **nunca** define `--yara-only`/`-remove` (fora do escopo de criação interativa).

## Invariantes

- O conjunto de validações do wizard é um **superconjunto amigável** das checagens do
  `CmdLineArgsImpl` (falha cedo, com mensagem clara, antes de lançar o processo) —
  garante SC-003.
- O `BootstrapCommandBuilder` produz uma lista de argumentos que, passada ao
  `Bootstrap`, cria um caso **forensicamente equivalente** ao da mesma linha de comando
  digitada no CLI (FR-013/SC-004).
- Mudança em `CmdLineArgsImpl` (novo parâmetro de criação) que deva aparecer no wizard
  exige atualizar este contrato + o builder.
