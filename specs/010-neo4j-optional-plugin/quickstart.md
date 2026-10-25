# Quickstart — Neo4j como plugin opcional do IPED

Guia de desenvolvimento e validação da feature `010-neo4j-optional-plugin`. Complementa [plan.md](plan.md), [research.md](research.md) e os contratos em [contracts/](contracts/).

## 1. Pré-requisitos

- JDK 21 com JavaFX (Liberica Full), Maven 3.6+, `JAVA_HOME` configurado (ver `CLAUDE.md` §5).
- Um baseline de comparação: um release da versão anterior (com Neo4j embarcado) e uma evidência de referência pequena com comunicações (e-mails/chats) para gerar grafo. O baseline (tarefa T004) registra: estatísticas finais do processamento, exportação CSV das propriedades de todos os itens (UI → selecionar tudo → exportar propriedades), contagens de nós/relacionamentos, o resultado do **roteiro fixo de 10 operações** (com as entidades de partida escolhidas) e uma segunda execução com `process-proximity-relationships=true` e entidades por regex ativas (FR-009).
- Para a rodada com imagem grande (§4, passo 19):
  - a imagem de referência (em 2026-10-23, uma E01 de 68 GB com ~2,32 M itens);
  - um release **IPED 4.3.1** (Java 11, Neo4j 4.4 embarcado) e o caso-baseline processado por ele (perfil `forensic`);
  - o **`LocalConfig.txt` usado nesse baseline**, a ser copiado para o release candidato (ver "Método de paridade", item 4).

## 2. Construir

```bash
# núcleo + plugin (reactor completo)
mvn -B clean package

# só o plugin (e o que ele depende)
mvn -pl iped-graph-neo4j -am package
```

Saídas:
- Distribuição padrão: `target/release/iped-<versão>/` + `target/release/plugins/` (`Readme.txt` e os plugins opcionais que já existiam antes da feature — nenhum `iped-graph-neo4j`; ver T041).
- Plugin: `iped-graph-neo4j/target/iped-graph-neo4j-<versão>.zip` (**fora** de `target/release/`).

Depois de correções, reconstruir **tudo** (`mvn -B clean package`) e instalar release e plugin numa pasta nova. Não trocar jars à mão numa instalação ou num caso existentes: na rodada de 2026-10-23 isso foi feito para destravar a análise, e o resultado deixou de valer como teste limpo.

Verificar o invariante "zero Neo4j" (SC-001) com os comandos de [contracts/release-inventory.contract.md](contracts/release-inventory.contract.md).

## 3. Instalar o plugin numa distribuição

```text
<dist>/
├── iped-<versão>/        # app root (LocalConfig.txt: pluginFolder = ../plugins/)
└── plugins/              # pasta de plugins (irmã)
    ├── iped-graph-neo4j-<versão>.jar
    └── iped-graph-neo4j/
        ├── neo4j/…
        ├── plugin.properties, LICENSE.txt, ThirdParty.txt, README.md
```

1. Extrair `iped-graph-neo4j-<versão>.zip` dentro de `<dist>/plugins/`.
2. Reiniciar o IPED.
3. Conferir no log: `Graph plugin loaded: iped-graph-neo4j <versão> (SPI v1, built for IPED <versão>) from …`.

Remover: apagar o jar e a pasta `iped-graph-neo4j/`; reiniciar.

## 4. Roteiro de validação (mapeado aos critérios da spec)

| # | Passo | Esperado | Critério |
|---|---|---|---|
| 1 | Inventário da distribuição (seção 2) | 0 arquivos Neo4j; enforcer e CI verdes | SC-001, SC-009 |
| 2 | Processar a evidência de referência **sem** o plugin, perfil padrão (`enableGraphGeneration=true`); repetir com um perfil customizado copiado do baseline cuja `TaskInstaller.xml` lista `GraphTask` | Conclui; 1 `WARN` "Graph generation is enabled but the graph plugin is not installed" (a linha `INFO` do registro não conta); 0 erros; sem `iped/neo4j/`; paridade forense com o baseline pelo **método de paridade** abaixo | SC-002, FR-004, FR-005 |
| 3 | Abrir o caso do passo 2 na UI sem o plugin | Sem aba "Vínculos", sem item "adicionar ao grafo", sem diálogo de erro, sem stack trace; checklist de smoke 100% | SC-005, FR-006 |
| 4 | Abrir um caso **com** dados de vínculos (baseline) na UI sem o plugin | Diálogo modeless único "Link analysis unavailable"; tudo o mais funciona | FR-007 |
| 5 | `--append` no caso do passo 4 sem o plugin; em seguida `--yara-only` no mesmo caso | `--append`: recusa com a mensagem e exit ≠ 0, caso intocado. `--yara-only`: **não** bloqueado | FR-008 |
| 6 | Repetir com `--ignore-missing-graph-plugin` | Prossegue; `WARN` em log e console | FR-008 |
| 7 | Instalar o plugin (seção 3); cronometrar | ≤ 3 passos, < 5 min, 0 edições de config | SC-003 |
| 8 | Processar a evidência de referência **com** o plugin (perfil padrão e, numa segunda execução, com `process-proximity-relationships=true` e entidades por regex ativas no `GraphConfig.json`) | `iped/neo4j/csv` e `data` criados; contagens de nós/relacionamentos iguais ao baseline equivalente nas duas execuções | SC-004, FR-009 |
| 9 | UI com o plugin: roteiro fixo de 10 operações (expandir, expandir com filtro, conexões, caminhos, buscar entidades, buscar vínculos, mostrar evidência, remover, desfazer/refazer, exportar vínculos), com as mesmas entidades de partida e parâmetros registrados no baseline (§1) | Resultados idênticos ao baseline | SC-004 |
| 10 | `--append` e remoção de evidência com o plugin | Grafo atualizado como no baseline | FR-009 |
| 11 | Caso criado com o plugin: conferir `<caso>/plugins/`; abrir pelo `IPED-SearchApp` do caso em outra máquina sem o plugin instalado | Aba "Vínculos" disponível a partir da cópia | FR-010 |
| 12 | Multi-caso (2 casos com grafo) com o plugin | Grafo combinado gerado e aba funcional | FR-009 |
| 13 | Remover o plugin; repetir 2 e 3 | Mesmo resultado; 0 erros residuais | SC-008, FR-015 |
| 14 | Perfis/configs da versão anterior (cópia de `profiles/*`, `TaskInstaller.xml` com `GraphTask`) com e sem o plugin | Carregam sem edição; sem erro | SC-007, FR-005 |
| 15 | Medir tempo de inicialização (CLI e UI) com/sem plugin vs. versão anterior (a medição "com plugin" inclui a home `iped-graph-neo4j/` na pasta de plugins) | Diferença < 1 s | SC-006 |
| 16 | Plugin corrompido (apagar `iped-graph-neo4j/neo4j/`), duas cópias do jar, jar com `IPED-Graph-SPI-Version: 2` | Rejeitado com o motivo no log; IPED segue sem grafo | FR-011, FR-012 |
| 17 | `--restart` sem o plugin num caso com grafo | Não bloqueia; `iped/neo4j/` removido; caso final sem dados de vínculos | FR-008 |
| 18 | Linux (WSL): passos 2, 3, 7, 8 | Idênticos | FR-017 |
| 19 | Imagem de referência grande (§1), perfil `forensic`, **com** o plugin, × IPED 4.3.1, os dois com o **mesmo `LocalConfig.txt`** (método de paridade, item 4) | Conclui com 0 `ERROR`, como a 4.3.1. Com linhas curtas, aparece `INFO Padded <n> row(s) of <arquivo> to <m> columns, as required by the Neo4j 5 importer.` e **nenhum** `ERROR Error generating database.`. Resumo do importador (`IMPORT DONE` … `Imported:` nós/relacionamentos) igual ao da 4.3.1 ou com a diferença triada. Estatísticas finais como as da 4.3.1, salvo as diferenças esperadas (método de paridade, item 5). Aba "Vínculos" abre o grafo pela cópia do caso | SC-002, SC-004, FR-009, FR-010 |

### Método de paridade (SC-002)

Reutiliza o contrato da feature 003 (`specs/003-java21-migration/contracts/parity-validation.contract.md`): campos C1–C8 (hashes, MIME, contagens, categorias, texto normalizado, carved, YARA, timeline), exclusões E1–E5 e casamento por trackID. Procedimento prático, sem ferramenta dedicada:

1. Comparar as estatísticas finais do log de processamento (total de itens, subitens, carved, exceções de parsing, erros de I/O) — devem ser iguais.
2. Na UI de cada caso, selecionar todos os itens e exportar as propriedades para CSV; ordenar os dois CSVs por trackID e fazer `diff` restrito às colunas C1–C8 (texto: comparar após a normalização do contrato).
3. Qualquer divergência é triada como no contrato: benigna (documentar como exclusão) ou regressão (corrigir antes de prosseguir).
4. **Mesmo `LocalConfig.txt` nos dois lados.** Além de perfil, `-tz` e flags (contrato da 003), copiar o `LocalConfig.txt` do baseline para o release candidato, ajustando só `pluginFolder` se a pasta de plugins for outra, e conferir a linha `LocalConfig: {…}` no início dos dois logs:
   - `indexTempOnSSD = true` (com `indexTemp` num SSD) liga a `TempFileTask`;
   - `hashesDB` é exigido pela `HashDBLookupTask` dos perfis `forensic`/`pedo`/`blind`, que sem ele se desliga com 1 `ERROR`;
   - `locale` muda nomes e bytes de subitens gerados;
   - `indexTemp`, `outputOnSSD` e `numThreads` mudam o tempo.

   Na rodada de 2026-10-23, o par com arquivos diferentes deixou cerca de 1% dos itens e o tempo sem comparação possível.
5. **Diferenças esperadas por correções** (não são regressões; documentar como exclusões):
   - itens ilegíveis pelo Sleuthkit que a versão anterior, com `TempFileTask`, indexava com hashes de zeros, e que agora ficam com erro de I/O e sem hash (`a9df70e05`);
   - entradas do cache do Chromium em pastas com `data_N`/`f_*` apagado de mesmo nome, que a versão anterior podia decodificar do arquivo errado (`a5c724030`).

   - hashes do item processado logo depois de um item cuja leitura falhou no meio, no mesmo worker: a versão anterior os calculava sobre restos do item que falhou; desde T093 eles saem corretos. Na imagem de referência, `WER744.tmp.mdmp` (9,7 MB) falha na leitura em todas as versões; se a falha veio depois do primeiro bloco, o item seguinte do mesmo worker tem hash errado na 4.3.1. Conferir no log qual worker processou o item com erro de I/O.

   Em casos grandes, processar o mesmo release duas vezes (execução de controle) antes de classificar uma diferença como regressão: a ordem da fila de processamento e o carving variam entre execuções.
6. **CSVs do grafo.** Descompactar os `*.gzip` de `iped/neo4j/csv/` numa **cópia fora do caso**, para não alterar o caso. Antes de T092, um `import-tool-args-iped.txt` descompactado ao lado do seu `.gzip` fazia a próxima importação falhar; o plugin agora ignora essas cópias. Normalizar as linhas para o número de colunas do cabeçalho: o plugin completa linhas curtas com `,""`, e quantas são varia com a ordem de processamento. Desconsiderar o id do nó `DATASOURCE`, as colunas `dataSource`/`relId` (E4, como em T055) e o `import.report`.

### Checklist de smoke da UI (SC-005)

Executar sem o plugin, num caso com dados de vínculos e noutro sem, e registrar cada linha em `validation-results.md`:

1. Abrir o caso (sem diálogo de erro) e conferir que a aba "Vínculos" e o item de menu "adicionar ao grafo" não existem.
2. Busca por termo, por propriedade e com filtro de categoria; navegação na tabela e ordenação.
3. Galeria (miniaturas de imagem e de vídeo) e visualizadores (texto, hex, imagem, PDF/Office via LibreOffice, e-mail, HTML).
4. Painéis de metadados/filtros (facetas, incluindo `yara:*` quando houver) e evidências.
5. Criar, renomear, excluir e exportar bookmark; exportar itens selecionados e propriedades para CSV.
6. Timeline e mapa (quando há itens georreferenciados).
7. Gerar relatório HTML a partir do caso.
8. Multi-caso (dois casos) — abertura e busca.
9. Fechar a UI: nenhum `ERROR`/stack trace no log atribuível à ausência do plugin.

## 5. Testes automatizados

```bash
mvn -pl iped-engine test -Dtest='GraphPluginRegistryTest,GraphPluginGuardTest,GraphFileWriterTest'
mvn -pl iped-graph-neo4j test     # inclui Neo4jImportRunnerTest (linhas de CSV curtas, R-15, T092)
# integração gated (exige o zip montado e extraído): Neo4jPluginPackageIT e Neo4jImportRunnerIT
IPED_GRAPH_PLUGIN_HOME=/caminho/plugins mvn -pl iped-graph-neo4j verify -Pgraph-it
# fora do escopo da feature, correções da rodada de 2026-10-23 (T088, T093):
mvn -pl iped-parsers/iped-parsers-impl test -Dtest=CacheIndexParserTest
mvn -pl iped-engine -am test -Dtest=HashTaskTest -Dsurefire.failIfNoSpecifiedTests=false
```

## 6. Depuração

- Estado do plugin: primeira linha `Graph plugin …` no log; `GraphPluginRegistry.get().getDetail()` em depuração.
- Processos filhos: o lançador usa `<home>/neo4j/*`; logs `[graph-server]` em `DEBUG`.
- Pasta de plugins efetiva: `PluginConfig.getPluginFolder()` (log `WARN` quando ausente mostra o caminho).
- Importação do grafo: a saída do `neo4j-admin` vai para o log de processamento (`[graph.neo4j.Neo4jImportRunner$InputReader]`; no multi-caso, para o log da UI).
  - Sucesso termina em `IMPORT DONE … Imported: …`.
  - Falha aparece como `IMPORT FAILED … Error in input data`, seguida de `ERROR [graph.neo4j.Neo4jDatabaseGenerator] Error generating database.`. O processamento termina com exit 0, mas `iped/neo4j/data/databases/graph.db` fica inutilizável e deve ser movido ou apagado antes de regenerar.
  - Para o detalhe do erro, rodar o `neo4j-admin database import full` com `--verbose` sobre uma cópia descompactada da pasta `csv/`.
- Linhas curtas completadas pelo plugin (R-15): `INFO [graph.neo4j.Neo4jImportRunner] Padded <n> row(s) of <arquivo> to <m> columns, as required by the Neo4j 5 importer.`
