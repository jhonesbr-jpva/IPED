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
| `commonOptions.addOwner` | `--addowner` | flag (Common) |
| `commonOptions.portable` | `--portable` | flag (Common) |
| `commonOptions.noPstAttachs` | `--nopstattachs` | flag (Common) |
| `commonOptions.noLinkedItems` | `--nolinkeditems` | flag (Common) |
| `commonOptions.downloadInternetData` | `--downloadInternetData` | flag (Common) |
| `advanced.blocksize` | `-b <n>` | se ≠ 0 (Advanced) |
| `advanced.ocrSubset` | `-ocr <cat>` | repetível (Advanced) |
| `advanced.noContent` | `-nocontent <cat>` | repetível (Advanced) |
| `advanced.logFile` | `-log <file>` | (Advanced) |
| `advanced.noLogFile` | `--nologfile` | flag (Advanced) |
| `advanced.splashMessage` | `-splash <msg>` | (Advanced) |
| `advancedParams[k]` | `-X k=v` ou flag literal | passthrough (Advanced) |
| (interativo, com GUI) | **sem** `--nogui` | a janela de progresso SWT aparece |

> A UI **nunca** define `--yara-only`, `-remove`, `--nogui`, `-asap` nem `--help` (ver
> partição abaixo — tier D).

## Cobertura de opções de criação — partição (SC-002)

Partição **autoritativa** de todos os parâmetros de criação do `CmdLineArgsImpl` em
quatro tiers. SC-002 ("100% das opções acessíveis pelo wizard **ou** documentadas como
fora de escopo") é satisfeito por esta tabela: tiers A–C são acessíveis pelo wizard;
tier D é a documentação explícita do que fica de fora.

| Param (CmdLineArgsImpl) | Tier | Onde no wizard |
|---|---|---|
| `-d`/`-data`, `-dname`, `-p`/`-password` | **A — Core** | página *Sources* (por fonte) |
| `-o`/`-output`, `--append`, `--continue`, `--restart` | **A — Core** | página *Output* (saída + modo) |
| `-profile` | **A — Core** | página *Profile* |
| `-tz`/`-timezone`, `-l`/`-keywordlist` | **A — Core** | topo da página *Common options* |
| `--addowner`, `--portable`, `--nopstattachs`, `--nolinkeditems`, `--downloadInternetData` | **B — Common** | toggles da página *Common options* |
| `-b`/`-blocksize`, `-ocr`, `-nocontent`, `-log`, `--nologfile`, `-splash` | **C — Advanced** | página *Advanced* (campos tipados) |
| `-X` (extras dinâmicos) + qualquer flag rara não listada | **C — Advanced** | página *Advanced* (passthrough `k=v` / flag literal) |
| `-remove` | **D — Out of scope** | remoção de evidência (workflow distinto, não criação) |
| `--yara-only` | **D — Out of scope** | refresh de caso já processado (não criação) |
| `--nogui` | **D — Out of scope** | o wizard é sempre GUI; nunca passado |
| `-asap` | **D — Out of scope (v1)** | integração ASAP/PF (injeta info de caso no HTML report) — adiada; permanece só na CLI |
| `--help`/`-h`/`/?` | **D — Out of scope** | só CLI |

**Decisão ASAP**: o modo `-asap` (integração com o sistema de gerenciamento de casos da
PF) fica **fora do escopo desta feature** — permanece disponível pela CLI/`Main` (os
construtores ASAP de `Main` são intocados). Reavaliável como melhoria futura do wizard.

**Regra de evolução**: um novo parâmetro de criação adicionado ao `CmdLineArgsImpl`
**MUST** ser classificado nesta tabela (A/B/C/D) no mesmo PR — caso contrário SC-002
regride.

## Invariantes

- O conjunto de validações do wizard é um **superconjunto amigável** das checagens do
  `CmdLineArgsImpl` (falha cedo, com mensagem clara, antes de lançar o processo) —
  garante SC-003.
- O `BootstrapCommandBuilder` produz uma lista de argumentos que, passada ao
  `Bootstrap`, cria um caso **forensicamente equivalente** ao da mesma linha de comando
  digitada no CLI (FR-013/SC-004).
- Mudança em `CmdLineArgsImpl` (novo parâmetro de criação) que deva aparecer no wizard
  exige atualizar este contrato + o builder.
