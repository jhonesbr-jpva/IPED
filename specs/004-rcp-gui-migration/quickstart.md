# Quickstart — Validação da GUI Eclipse RCP do IPED

**Feature**: `004-rcp-gui-migration` | Guia de build, execução e validação
ponta-a-ponta. Detalhes de design: [plan.md](plan.md), [research.md](research.md),
[contracts/](contracts/).

## Pré-requisitos

- JDK: **Liberica Full 21** (com JavaFX) — em Windows deste projeto:
  `H:\java\LibericaJDK-21-Full` (`JAVA_HOME` apontando para ele).
- Maven 3.9+.
- Build raiz instalado no repo local (o reactor Tycho consome os módulos como
  binários):
  ```bash
  mvn clean install -DskipTests
  ```
- Linux (para SWTBot/CI): GTK3 + Xvfb (`xvfb-run`).
- Um **caso de referência** processado pelo release corrente (para validação
  de paridade: idealmente ≥ 1 M itens, com imagens, comunicações e eventos
  datados — ver Assumptions da spec). Para smoke rápido, qualquer caso pequeno
  serve.

## Build do produto RCP

```bash
# build completo do reactor Tycho (produto win64 + linux64)
mvn -P rcp clean verify

# produto materializado em:
#   iped-rcp/products/iped.rcp.product/target/products/iped.rcp.product/
#     win32/win32/x86_64/   e   linux/gtk/x86_64/
```

Release integrado (produto dentro de `target/release/iped-<ver>/ui/`):

```bash
mvn -P rcp clean package   # a partir do raiz, com o perfil rcp ativo no iped-app
```

> Lembrete do projeto: sempre `clean` (Eclipse m2e contamina `target/classes`).

## Executar

```bash
# Windows — instalação standalone (abre seletor de caso)
target\release\iped-<ver>\ui\iped-ui.exe

# abrir caso direto
ui\iped-ui.exe H:\casos\caso-teste

# multicase
ui\iped-ui.exe -multicases H:\casos\lista.txt

# a partir de um caso autocontido (fluxo do perito)
H:\casos\caso-teste\IPED-SearchApp.exe   # duplo clique
```

Reset de layout corrompido: apagar `~/.iped/ui-workspaces/<case-id>/` ou
lançar com `-clearPersistedState`.

## Cenários de validação (mapeiam as user stories)

| # | Cenário | Comando/ação | Resultado esperado |
|---|---|---|---|
| 1 | **US1 — triage** | Abrir caso de referência; buscar termo conhecido; ordenar por nome; abrir item nos viewers; criar bookmark; exportar seleção | Contagens idênticas à UI atual; viewers renderizam; bookmark persiste e é legível pela UI atual; arquivos exportados idênticos |
| 2 | **US2 — galeria/filtros** | Ativar galeria; combinar categoria + faceta de metadado + bookmark; busca por imagem similar; salvar filtro | Contagens iguais à UI atual em cada passo; rolagem sem travar (SC-004) |
| 3 | **US3 — views especializadas** | Abrir mapa, grafo e timeline no caso de referência | Mesmos dados da UI atual; seleção sincronizada com a tabela |
| 4 | **US4 — progresso** | `iped.exe -d <evidência> -o <saída>` | Janela SWT com campos do contrato [progress-ui-events](contracts/progress-ui-events.contract.md); `--nogui` idêntico ao atual |
| 5 | **US5 — workspace** | Rearranjar parts; trocar tema; reiniciar | Layout restaurado (SC-005); idioma segue locale; HiDPI legível |
| 6 | **US6 — extensão** | Copiar `iped.rcp.sample.view.jar` para `ui/plugins-ext/`; reabrir; depois remover | View aparece com seleção funcionando (SC-007); remoção não quebra o boot |
| 7 | **Edge — mídia RO** | Abrir caso em pasta marcada somente leitura | Abre; aviso ao gravar bookmark |
| 8 | **Edge — concorrência** | Duas instâncias no mesmo caso (rede) | Semântica atual preservada (leitura ok; lock de escrita) |
| 9 | **FR-002 — multicase** | `ui\iped-ui.exe -multicases H:\casos\lista.txt` (2+ casos) | Contagens, filtros e bookmarks com a mesma semântica da UI atual |
| 10 | **FR-029 — quase-ao-vivo** | Durante processamento, abrir análise pela janela de progresso | Dados consolidados visíveis; atualização a cada consolidação; processamento sem atraso (FR-030) |

## Testes automatizados

```bash
# testes de bundle + SWTBot (Linux: sob Xvfb)
mvn -P rcp verify                          # tycho-surefire incluso
xvfb-run mvn -P rcp -pl :iped.rcp.tests.swtbot verify

# harness de paridade headless (compara contagens com baseline)
mvn -P rcp -pl :iped.rcp.tests.parity verify -Dcase.dir=H:/casos/caso-referencia
```

## Gates de aceitação (antes do cut-over)

1. `parity-inventory.md` 100% com status `paridade` ou `divergência
   justificada` aprovada (SC-001), incluindo as seções de interoperabilidade
   de bookmarks (SC-009) e de aparência nativa por tela (SC-010).
2. Medições SC-002/003/004 registradas contra a UI atual no mesmo hardware.
3. Sessão de 8 h sobre o caso de referência sem crash (SC-008).
4. Critérios dos 3 contratos verificados em Windows e Linux.
5. PT-BR e EN sem chaves cruas em todas as telas (SC-006).
