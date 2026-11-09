# Contract — Eventos da Janela de Progresso do Processamento

**Feature**: `004-rcp-gui-migration` | Decisão de base: [research.md](../research.md) R10
Cobre FR-026 (paridade da janela de progresso) e US4.

## Contexto

A JVM filha de processamento (`iped.app.processing.Main`) publica eventos de
progresso via `UIPropertyListenerProvider` (event bus singleton do engine).
Hoje o consumidor é `ProgressFrame` (Swing); no cut-over passa a ser a janela
SWT standalone de `iped.rcp.progress` (sem OSGi, jar no classpath da JVM de
processamento). `ProgressConsole` (modo texto, `--nogui`) **não muda**.

## Conjunto de eventos consumidos (paridade)

A janela SWT DEVE consumir e exibir o mesmo conjunto de propriedades que o
`ProgressFrame` atual consome do `UIPropertyListenerProvider`, incluindo:

| Informação exibida | Paridade exigida |
|---|---|
| Progresso global (itens processados / total, %) | Sim |
| Progresso e status por evidência | Sim |
| Taxa de processamento (GB/h, itens/s) e gráfico de throughput | Sim |
| Fase corrente do pipeline / tasks ativas por worker | Sim |
| Contadores: itens encontrados, processados, carved, subitens, erros | Sim |
| Erros e alertas não-fatais (lista consultável) | Sim |
| Estimativa de término (ETA) | Sim |
| Ações do usuário: pausar/continuar e abortar com confirmação | Sim |
| Abrir a UI de análise ao final (quando habilitado) | Sim — passa a lançar o produto RCP (contrato `case-launcher-packaging`) |

> O inventário item-a-item (nomes exatos das propriedades publicadas) é
> extraído de `ProgressFrame` no primeiro milestone e congelado em
> `parity-inventory.md` seção "progresso" (SC-001).

## Regras de implementação

- **Thread de UI**: todo update via `Display.asyncExec` (eventos chegam de
  threads do engine — análogo da regra EDT, Princípio V).
- **Sem alteração no publicador**: `UIPropertyListenerProvider` e os pontos de
  publicação no engine/`Main` não mudam (FR-028); apenas o consumidor troca.
- **Ciclo de vida**: fechar a janela NÃO aborta o processamento (paridade);
  abortar é ação explícita com confirmação.
- **Headless**: ausência de display (SSH/CI) não pode quebrar o processamento
  — fallback automático para `ProgressConsole` com aviso em log.
- **i18n**: mesmas chaves de `localization/` usadas hoje pelo `ProgressFrame`
  (adaptador R7).

## Critérios de aceitação do contrato

1. Processamento de evidência de referência exibe na janela SWT os mesmos
   campos/contadores registrados no inventário de paridade (US4 cenário 1).
2. Erros não-fatais aparecem sem interromper o processamento (US4 cenário 2).
3. `--nogui` produz exatamente a saída de console atual (US4 cenário 3).
4. Processamento em ambiente sem display conclui normalmente (fallback).
