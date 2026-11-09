# Arquivo de specs do fork IPED (`jhonesbr-jpva/IPED`)

Branch órfã **`specs-archive`**: cópia consolidada das specs (Spec Kit) de todas as features do fork, cada uma na **versão mais recente** encontrada entre as branches que a carregavam ("a mais nova vence", decisão de 2026-11-08), mais as duas constituições em vigor. Não contém código.

As specs continuam **vivendo nas branches de código** (`specs/NNN-nome/` dentro da branch da feature) — é lá que são editadas. Esta branch é leitura, registro e índice. Para features **abertas**, a cópia aqui é um *snapshot* datado; a versão viva está na branch.

Checkout único: `H:\java\workspaces\workspace-iped\specs-archive`. Nunca aparece nos worktrees de feature.

## Regras

1. **Numeração é por repositório, não por worktree.** O número é o identificador global da feature; o Spec Kit calcula o próximo olhando branches locais, remotas e `specs/`. Próximo número livre: **011**, para qualquer linha (master ou 4.3.x).
2. **Nunca apagar branch numerada no GitHub.** É ela que reserva o número e guarda a spec original. Apagar localmente, à vontade.
3. **Spec é editada só na branch da própria feature.** Cópias herdadas por branching (a `003` dentro da `010`, por exemplo) são só leitura.
4. **Ao fechar uma feature:** atualizar a cópia aqui, mudar o status na tabela, remover o worktree, apagar a branch local (a remota fica).
5. **Colisões históricas** (três `001`) **não foram renumeradas**: commits, `CLAUDE.md` e PRs referem-se aos nomes originais. Desambiguar sempre pelo nome completo.
6. Uma versão nova do Spec Kit não exige retrabalho em specs antigas: atualizar em fronteira de fase, em commit próprio, e nunca regenerar artefatos existentes.

## Índice

| Nº | Feature | Linha / base | Branch de código | Status (2026-11-08) | Cópia consolidada de | Observações |
|---|---|---|---|---|---|---|
| 001 | `001-yara-rules-engine` | master do fork | `001-yara-rules-engine` | **Fechada.** Mergeada no master do fork (PR #2, 29/05/2026). Reimplementação limpa `yara-engine-upstream`, integrada em `integration/upstream-stack`. PR no sepinf-inc: não verificado. | `001-yara-rules-engine` @ `8d511fa4f` (29/05) — idêntica em todas as branches | Primeira feature do fork com Spec Kit. |
| 001 | `001-iped-llm-integration` | 4.3.x | `001-iped-llm-integration` | **Fechada** em 06/08/2026 ("close the IPED-LLM integration spec"). Integrada em `iped-4.3.1-mcp`. | `iped-4.3.1-mcp` @ `6aaee45ef` (08/09) | Módulo `iped-mcp` (Java 11). A POC Python de abril/2026 foi avaliada e rejeitada em `research.md` (R1); preservada só em bundle local (`H:\java\_backup`). Colisão de número com a `001-yara`: nasceu numa árvore 4.3.1 sem `specs/`. |
| 001 | `001-tika3-upgrade` | `upstream/master` | `tika3-upgrade` | **Concluída** na branch (30/06/2026); integrada em `integration/upstream-stack`. PR no sepinf-inc: não verificado. | `tika3-upgrade` @ `7121e2a52` (25/06) — idêntica à da `integration` | Branch sem número; só a pasta da spec tem o `001`. Colisão: nasceu do upstream puro, sem `specs/`. |
| 002 | `002-eclipse-rcp-gui-migration` | master do fork | `archive/002-eclipse-rcp-gui-migration` | **Abandonada** no dia em que nasceu (29/05/2026): 1 commit ("RCP Begin"), só a spec. Substituída pela `004` doze dias depois, após a migração para Java 21 (`003`). | `archive/002-…` @ `3764c1380` (29/05) | Spec em inglês, 184 linhas, *Draft*. Registro de por que a numeração pula de 002 para 004. |
| 003 | `003-java21-migration` | master do fork | `003-java21-migration` | **Fechada.** Mergeada no master do fork (PRs #3/#4, 10–12/06/2026). Reimplementação limpa `java21-migration-upstream`, integrada em `integration/upstream-stack`. | `010-neo4j-optional-plugin` @ `c960246a4` (25/10) | A `010` continuou atualizando o relatório de implementação e um contrato desta spec até 25/10 — por isso a cópia mais completa está lá. |
| 004 | `004-rcp-gui-migration` | master do fork (pós-003) | `004-rcp-gui-migration` | **Em andamento, pausada** desde 24/06/2026. Worktree `004-rcp-gui-migration`. | `004-rcp-gui-migration` @ `181369d3b` (22/06) | Módulo `iped-rcp` (Eclipse RCP, Tycho). Contém integralmente a `005`. Eclipse: workspace `H:\java\workspaces\workspace-rcp`. |
| 005 | `005-case-creation-wizard` | `004` | `005-case-creation-wizard` | **Em andamento, pausada** desde 17/06/2026. **Totalmente contida na `004`** (a `004` tem 15 commits além dela). Sem worktree. | `005-case-creation-wizard` @ `11f02026b` (17/06) — idêntica à da `004` | Assistente de criação de caso na UI RCP; `iped.exe` permanece headless. |
| 006 | `006-export-allowlist-socket-transport` | 4.3.x | `006-export-allowlist-socket-transport` | **Fechada** em 13/08/2026. Integrada em `iped-4.3.1-mcp`. | `iped-4.3.1-mcp` @ `03eec875f` (13/08) | Allow-list de exportação e transporte por socket (relay). |
| 007 | `007-mcp-case-processing` | 4.3.x | `007-mcp-case-processing` | **Fechada** em 08/09/2026. Integrada em `iped-4.3.1-mcp`. | `iped-4.3.1-mcp` @ `e80d3618b` (23/08) | Criação de caso via MCP (motor do IPED fora do processo). |
| 008 | `008-thumbnail-image-block` | 4.3.x | `008-thumbnail-image-block` | **Fechada** em 23/09/2026. Integrada em `iped-4.3.1-mcp`, com a correção `fix/mcp-stdio-stdout` (25/09). | `iped-4.3.1-mcp` @ `536034f37` (23/09) | Miniatura como bloco de imagem do protocolo. `iped-4.3.1-mcp` aponta para o estado 001+006+007+008+fix. |
| 009 | `009-semantic-search` | 4.3.x | `009-semantic-search` | **Ativa** (último commit 04/11/2026). Worktree `009-semantic-search`. | *snapshot* de `009-semantic-search` @ `96790f804` (04/11) | Módulo `iped-semantic`; ferramentas `iped_semantic_*` no MCP. Fecha mergeando em `iped-4.3.1-mcp` (fast-forward). |
| 010 | `010-neo4j-optional-plugin` | master do fork (pós-003) | `010-neo4j-optional-plugin` | **Ativa** (último commit 25/10/2026). Worktree `010-neo4j-optional-plugin`. | *snapshot* de `010-neo4j-optional-plugin` @ `c960246a4` (25/10) | Neo4j 5 como plugin opcional (`iped-graph-neo4j`, `iped-graph-server`, SPI `GraphServiceProvider`). |

## Cópias substituídas (para o registro)

Nenhum conteúdo foi perdido: as cópias abaixo continuam nas suas branches. Em todos os casos a cópia descartada é um estado **anterior** da mesma spec, congelado no momento do branching; as poucas linhas exclusivas delas são reformulações.

| Spec | Cópia descartada (branch · tree) | Diferença para a consolidada |
|---|---|---|
| `001-iped-llm-integration` | `integration/upstream-stack`, `001-iped-llm-integration`, `006` · `3c239006` (06/08); `pr6-review` · `f4a0b403` (05/09) | a consolidada (08/09) tem +63 linhas em `spec.md`, +55 em `contracts/mcp-tools.md`, +29 em `data-model.md` |
| `003-java21-migration` | `integration/upstream-stack`, `003`, `004`, `005`, `legacy/fork-master` · `e2a3a04d` (12/06) | a consolidada (25/10) tem +19 linhas em `implementation-report.md` e +7 em `contracts/parity-validation.contract.md` |
| `004-rcp-gui-migration` | `005-case-creation-wizard` · `dd6e7d29` (16/06) | a consolidada (22/06) tem +79 linhas em `tasks.md` e +18 em `parity-inventory.md` |
| `006-export-allowlist-socket-transport` | `integration/upstream-stack` · `3e961045` (12/08) | a consolidada (13/08) tem +92 linhas em `research.md`, +44 no checklist, +20 em `tasks.md`, +14 em `spec.md` |

## Constituições

Duas constituições distintas, de propósito — uma por base:

- [`constitutions/master.md`](constitutions/master.md) — "IPED — Constituição do Projeto", v1.2.0 (ratificada 19/05/2026, emendada 29/05/2026). Vigente em `base/agents` e nas features da linha master.
- [`constitutions/4.3.1.md`](constitutions/4.3.1.md) — "Constituição do IPED — branch 4.3.1", v1.0.0 (ratificada 04/08/2026). Vigente em `iped-4.3.1-mcp` e na `009`.

## Branches sem spec (contexto)

| Branch | O que é |
|---|---|
| `master` | espelho de `sepinf-inc/master` (hub; nunca se desenvolve nele) |
| `4.3.x` | espelho de `sepinf-inc/4.3.x` |
| `base/agents` | `master` + ferramental de agentes (Spec Kit 1.0.12, `CLAUDE.md`, constituição do master) — ponto de partida das features novas da linha master |
| `iped-4.3.1-mcp` | integração/release da linha MCP sobre a 4.3.1 (ponta: `fix/mcp-stdio-stdout`) |
| `integration/upstream-stack` | agrega `java21-migration-upstream`, `yara-engine-upstream`, `tika3-upgrade` e `fix/imagemagick-config-path` sobre `upstream/master`, para PRs no sepinf-inc |
| `fix/imagemagick-config-path` | mergeada no sepinf-inc (#2905) |
| `fix/mcp-stdio-stdout` | correção sobre a `008`; integrada |
| `pr6-review` | revisão de PR da linha MCP; contida na `009` |
| `docs/claude-md-4.3.1` | `CLAUDE.md` adaptado à árvore 4.3.1; contida na `009` |
| `adopt-speckit`, `Inicio-speckit` | adoção do Spec Kit (mai/2026); contidas na `010` |
| `legacy/fork-master` | o `master` do fork até 12/06/2026 (Spec Kit + YARA + Java 21), congelado |
| `archive/002-eclipse-rcp-gui-migration` | ver `002` acima |
