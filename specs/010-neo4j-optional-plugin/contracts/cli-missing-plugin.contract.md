# Contrato — Processamento sem o plugin: degradação, bloqueio e override

**Feature**: 010-neo4j-optional-plugin · **Componentes**: `GraphTask`, `GraphPluginGuard`, `Manager`, `CmdLineArgsImpl`

## A. Caso novo / etapa de grafo (FR-004)

| Condição | Comportamento |
|---|---|
| `enableGraphGeneration=true` e plugin `AVAILABLE` | Como hoje: CSVs em `iped/neo4j/csv`, banco em `iped/neo4j/data` no `finish()`. |
| `enableGraphGeneration=true` e plugin não disponível | `GraphTask.enabled=false`; **um** `WARN`: `Graph generation is enabled but the graph plugin is not installed (<estado>): link analysis will be skipped for this case.`; nenhum arquivo em `iped/neo4j/`. Demais tasks inalteradas. |
| `enableGraphGeneration=false` | Como hoje (nenhuma mensagem nova). |

Saída do processamento: código 0; o resumo final não lista erros. `TaskInstaller.xml` e perfis antigos não precisam de edição (FR-005).

## B. Modificação de caso existente (FR-008)

Avaliado por `GraphPluginGuard.checkCaseModification(args, output)` no início de `Manager.process()`, **antes** de qualquer escrita no caso.

Entradas: `modo ∈ {append, continue, removeEvidence, restart, yaraOnly, novo}`, `hasLinkData = exists(output/neo4j/data) || exists(output/neo4j/csv)`, `pluginAvailable`, `flag = --ignore-missing-graph-plugin`.

| modo | hasLinkData | pluginAvailable | flag | Resultado |
|---|---|---|---|---|
| append / continue / removeEvidence | sim | não | não | **Recusa**: `IPEDException` com a mensagem localizada abaixo; nenhuma alteração; exit ≠ 0. |
| append / continue / removeEvidence | sim | não | sim | Prossegue; `WARN` em log **e** console: dados de vínculos não atualizados. Em `removeEvidence`, pula a exclusão no banco, mantém a limpeza dos CSVs. |
| append / continue / removeEvidence | sim | sim | qualquer | Como hoje (grafo atualizado). |
| append / continue / removeEvidence | não | qualquer | qualquer | Sem efeito do guard (cai na regra A). |
| restart | qualquer | não | qualquer | Não bloqueia; apaga `output/neo4j/` antes de reiniciar; segue a regra A. |
| restart | qualquer | sim | qualquer | Como hoje. |
| yaraOnly / novo | — | — | — | Sem efeito do guard. |

**Atenção**: `--yara-only` faz `CmdLineArgs.isContinue()` retornar `true` (feature 001, `SkipCommitedTask`); o guard testa `isYaraOnly()` **antes** de avaliar `isContinue()`, e `GraphPluginGuardTest` cobre esse caso com `isContinue()==true`.

Mensagem de recusa (EN; PT-BR equivalente no bundle `iped-engine-messages`):

```text
This case contains link analysis (graph) data, but the graph plugin is not installed, so the graph cannot be updated.
Install the plugin (see plugins/iped-graph-neo4j/README.md) or re-run with --ignore-missing-graph-plugin to proceed without updating the graph.
```

Aviso ao prosseguir com a flag (log + console):

```text
Proceeding without updating the case graph (--ignore-missing-graph-plugin): link analysis data may no longer reflect the case contents.
```

## C. Flag de linha de comando

| Flag | Tipo | Descrição no `--help` |
|---|---|---|
| `--ignore-missing-graph-plugin` | booleana | `proceed with --append/--continue/-remove on a case that has graph data even if the graph plugin is not installed (the graph will not be updated)` |

Exposta em `iped.engine.CmdLineArgs#isIgnoreMissingGraphPlugin()`; persistida em `caseData` como os demais argumentos. Sem efeito quando o plugin está disponível.

## D. Log do registro no processamento (FR-003)

Primeira linha relevante do log de processamento após o carregamento das configurações: uma das linhas de estado definidas em [graph-spi.contract.md](graph-spi.contract.md) (`INFO` loaded / `INFO` not installed / `ERROR` rejected). Nenhuma delas conta como o aviso de etapa pulada de FR-004, que é o único `WARN` esperado no processamento sem o plugin.

## E. Outros modos e pontos de entrada

- `--yara-only`, `-nogui`, relatório a partir de caso, Web API, `HashDBTool`: nenhuma mudança de comportamento; nenhuma mensagem nova (os dois últimos não resolvem o registro).
- Linux/Windows: idêntico.

## Testes

`GraphPluginGuardTest` cobre cada linha da tabela B com pastas temporárias (presença de `neo4j/data` e/ou `neo4j/csv`), um `CmdLineArgs` de teste e um registro injetável (`GraphPluginRegistry.forTesting(state)`).
