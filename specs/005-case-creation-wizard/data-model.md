# Data Model — Criação e Abertura de Casos na GUI RCP

**Feature**: `005-case-creation-wizard` | **Date**: 2026-06-16
**Input**: [spec.md](spec.md) · [research.md](research.md)

Entidades da camada de criação/abertura de casos e gestão de perfis. Todas vivem na
**UI/serviços headless** (`iped.rcp.core` + `iped.rcp.casecreation`); **nenhuma** é
persistida no caso forense nem altera o schema do índice (Princípio I/IV). Tipos
descritos em nível conceitual — assinaturas exatas no `contracts/`.

---

## 1. `NewCaseRequest`

Captura imutável das escolhas do wizard; entrada do `ProcessingLaunchService`.

| Campo | Tipo | Origem (CmdLineArgs) | Regras |
|---|---|---|---|
| `dataSources` | lista de `DataSourceEntry` | `-d` (repetível) | ≥ 1; cada caminho deve existir e ser legível (ou physical drive) |
| `outputDir` | caminho | `-o` | gravável; não pode ser igual nem subpasta de uma fonte (espelha `CmdLineArgsImpl:491`) |
| `profileName` | string | `-profile` | deve existir em `profiles/` (embarcado ou de usuário) |
| `mode` | enum `NEW \| APPEND \| CONTINUE \| RESTART` | `--append`/`--continue`/`--restart` | `APPEND/CONTINUE/RESTART` exigem caso existente em `outputDir/iped` |
| `timezone` | string? | `-tz` | opcional; default = TZ do sistema |
| `keywordsFile` | caminho? | `-l` | opcional; arquivo existente |
| `commonOptions` | `CommonOptions` | ver abaixo | curado (Q4) |
| `advancedParams` | mapa `chave→valor` | `-X` + flags raras | etapa "avançado"; passthrough |

**`DataSourceEntry`**: `{ path, displayName? (-dname), password? (-p) }`. Nomes de
evidência devem ser únicos (espelha `checkDuplicateDataSources`).

**`CommonOptions`** (subconjunto curado de `CmdLineArgsImpl`, Q4): `addOwner`
(`--addowner`), `ocrSubset` (`-ocr`), `portable` (`--portable`), `noPstAttachs`
(`--nopstattachs`), `noLinkedItems` (`--nolinkeditems`), `downloadInternetData`,
`blocksize` (`-b`). Flags raras/de especialista **não** ganham controle (ficam em
`advancedParams` ou só na CLI — FR-007/Out of Scope).

**Transições**: `NewCaseRequest` é construído incrementalmente pelas páginas do wizard
e **congelado** ao "Concluir"; cancelar descarta-o sem efeito (FR-012).

## 2. `ProcessingJobHandle`

Representa um processamento disparado pela UI (subprocesso `Bootstrap`).

| Campo | Tipo | Notas |
|---|---|---|
| `process` | handle do SO | subprocesso `Bootstrap`/`iped.jar` (R1) |
| `outputCaseDir` | caminho | `outputDir/iped` (alvo do near-live) |
| `state` | enum `STARTING \| RUNNING \| SUCCEEDED \| FAILED \| CANCELED` | derivado do exit code + sinais |
| `commandLine` | lista de string | auditável/logável (sem segredos em claro) |

**Ciclo**: `STARTING → RUNNING → (SUCCEEDED \| FAILED \| CANCELED)`. A janela de
progresso SWT (módulo `iped.rcp.progress` da 004) é a superfície de acompanhamento; o
`ProcessingJobHandle` é o vínculo da UI RCP para oferecer o near-live (R7) e refletir o
resultado. A UI **não** comanda o pipeline — apenas observa o processo.

## 3. `ProfileDescriptor`

Um perfil selecionável/editável.

| Campo | Tipo | Regras |
|---|---|---|
| `name` | string | único entre todos os perfis; caracteres válidos de pasta (FR-019) |
| `kind` | enum `BUILT_IN \| USER` | `BUILT_IN` = somente leitura (forensic, pedo, triage, fastmode, blind) |
| `dir` | caminho | `profiles/<name>/` (R3) |
| `basedOn` | string? | perfil de origem ao clonar (proveniência; informativo) |

**Regras de identidade/ciclo**:
- Criar perfil → `kind=USER`, nome único; clona de um `ProfileDescriptor` base ou parte
  do default (FR-014/FR-015).
- Editar `BUILT_IN` → bloqueado; oferece "Salvar como" novo `USER` (FR-018).
- Excluir → permitido só para `USER`.
- Recém-criado/editado aparece imediatamente na lista do wizard (FR-017) —
  `ProfileService` reescaneia `profiles/`.

## 4. `ProfileConfigModel` + `ConfigOption`

Modelo dirigido pelos arquivos de config (R4) que o editor "completo" renderiza.

**`ProfileConfigModel`** = lista ordenada de `ConfigFileGroup`, um por arquivo de config
relevante (`IPEDConfig.txt`, `LocalConfig.txt`, `conf/*.txt|.properties`), mais uma
coleção `advancedFiles` (formatos `.xml`/`.json`).

**`ConfigFileGroup`**: `{ fileName, options: List<ConfigOption> }`.

**`ConfigOption`**:

| Campo | Tipo | Origem |
|---|---|---|
| `key` | string | chave `key=value` no arquivo base |
| `effectiveValue` | string | base sobrescrito pelo override do perfil em edição |
| `baseValue` | string | valor no config canônico do release |
| `overridden` | bool | true se o perfil em edição redefine a chave |
| `description` | string | comentários `#` que precedem a chave no arquivo (em inglês) |
| `type` | enum `BOOLEAN \| INT \| TEXT \| ENUM` (inferido) | heurística do valor/descrição (ex.: `true/false` → BOOLEAN) |

**Regras**:
- **Leitura**: `effectiveValue` = merge (base → override do perfil). Embarcado nunca é
  alterado; o override pertence ao perfil de usuário.
- **Gravação**: grava no `profiles/<name>/<arquivo>` **apenas** as `ConfigOption` com
  `overridden=true` (mantém perfis enxutos como os embarcados — ex.: forensic só
  sobrescreve 4 flags). Charset UTF-8 via o mecanismo `UTF8Properties` (Princípio IV).
- **Validação** (`ProfileValidation`): tipos coerentes; chaves desconhecidas avisadas;
  não corrompe arquivos base.

## 5. `RecentCasesStore`

Lista de casos abertos recentemente (FR-002 SHOULD / US2).

| Campo | Tipo | Notas |
|---|---|---|
| `entries` | lista de `{ path, lastOpened, multicase? }` | ordenada por `lastOpened` desc; cap (ex.: 10) |
| localização | `~/.iped/recent-cases.*` | **fora** do caso (imutável); área de usuário da 004 |

**Regras**: ao abrir um caso (Open/New/near-live), insere/atualiza a entrada; caminhos
inexistentes são podados na exibição.

---

## Relações

```text
NewCaseWizard ──monta──▶ NewCaseRequest ──entra──▶ ProcessingLaunchService
                                   │                        │
                          referencia ProfileDescriptor      └─cria─▶ ProcessingJobHandle ──near-live──▶ CaseSessionService (004)
                                   ▲
ProfileManager/Editor ──CRUD──▶ ProfileService ──lê/grava──▶ ProfileConfigModel (arquivos em profiles/<name>/)
Open Case / Recent ──────────▶ ICaseSessionManager (004) ◀──registra── RecentCasesStore
```

## Fronteiras (o que NÃO é modelado aqui)

- **Schema do índice / formato do caso** — inalterado (Princípio I/IV); criação produz
  um caso idêntico ao do CLI (FR-013).
- **Formato dos perfis** — é o formato de config existente (`Configurable`/arquivos);
  esta feature **consome**, não redefine.
- **Eventos/serviços de análise** (seleção, resultados, bookmarks) — são da 004
  (`iped.rcp.api`/`iped.rcp.core`); criação de caso não os altera.
