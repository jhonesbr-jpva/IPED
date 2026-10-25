# iped-graph-neo4j — plugin opcional de análise de vínculos (Neo4j)

> Módulo criado na feature `specs/010-neo4j-optional-plugin/`. Implementa o ponto de extensão de grafo do `iped-engine` com Neo4j 5 e é publicado como **zip separado**. Nenhum módulo do núcleo depende dele; o release padrão (`target/release/`) não contém nada deste módulo.

## 1. Papel

- Implementa `iped.engine.graph.GraphServiceProvider` (SPI v1) — registrado em `src/main/resources/META-INF/services/iped.engine.graph.GraphServiceProvider`.
- Fornece o `GraphService` sobre Bolt (`Neo4jGraphService`) e o `GraphDatabaseGenerator` (`Neo4jDatabaseGenerator`: importação em lote com `neo4j-admin` + pós-importação `GraphPostImport`).
- Lança as JVMs filhas do motor (`GraphServer`, `GraphPostImport`, `neo4j-admin`) com o classpath isolado da *home* do plugin — o motor e o antlr 4.13 nunca entram na JVM do IPED (que mantém o antlr 4.9.2 do libfqlite).

## 2. Classes (`iped.graph.neo4j`)

| Classe | Papel |
|---|---|
| `Neo4jGraphServiceProvider` | Provedor SPI. Lê o **manifesto do próprio jar** (`IPED-Graph-SPI-Version`, `Implementation-Version`, `IPED-Target-Version`); `validate()` exige `<home>/neo4j/iped-graph-server-*.jar` e ≥1 `<home>/neo4j/neo4j-*.jar`; `pluginHome()` = pasta `iped-graph-neo4j/` ao lado do jar (via `ProtectionDomain`). Construtor de pacote `(Manifest, Path)` e `setPluginHomeForTesting` para testes. |
| `Neo4jGraphService` | Ex-`GraphServiceImpl`: inicia o `GraphServer` (protocolo stdio `BOLT_PORT=`/`GRAPH_SERVER_READY`/`STOP`), abre o driver, converte resultados nos adaptadores; `Neo4jException` vira `GraphQueryException` em `advancedSearch`, `searchPaths` e `search`. |
| `BoltEntity`, `BoltNode`, `BoltRelationship`, `BoltPath` | Snapshots somente leitura que implementam os tipos neutros (`GraphNode`...); `BoltNode.getDegree()` vem do `COUNT { (n)--() }` da consulta. |
| `Neo4jDatabaseGenerator`, `Neo4jImportRunner` | Ex-`GraphGenerator`/`GraphImportRunner` (argumentos de importação lidos dos arquivos `GraphFileWriter.ARGS_FILE_NAME-*`). Antes de lançar o `neo4j-admin`, `padShortRows` completa com `""` as linhas que têm menos campos que o cabeçalho (o `GraphFileWriter` amplia o cabeçalho quando novas propriedades aparecem): o importador do Neo4j 5 rejeita linhas curtas, que o 4.4 tolerava. Primeiro só lê o arquivo, em blocos, até a primeira linha curta; só os arquivos que têm uma são reescritos (`<arquivo>.padding.tmp` com `fsync` + `move` atômico). O `ArgsFileFilter` aceita só `import-tool-args-<sufixo>.txt`. |
| `Neo4jChildLauncher` | Comando base: `<java.home>/bin/java -XX:+IgnoreUnrecognizedVMOptions --add-opens... -cp <home>/neo4j/*`. `IOException` descritiva se a pasta do motor não existir. |

Proibido: classes `iped.engine.*`/`iped.app.*` neste módulo (o pacote é `iped.graph.neo4j` para evitar *split package*); o código que roda **dentro** do motor fica em `iped-graph-server`.

## 3. Build e pacote

`mvn -pl iped-graph-neo4j -am package` → `target/iped-graph-neo4j-<versão>.zip`:

```text
iped-graph-neo4j-<versão>.jar      # classpath principal (pluginFolder/*): plugin + driver Bolt
iped-graph-neo4j/                  # home do plugin (fora do classpath principal)
├── neo4j/*.jar                    # zip do iped-graph-server descompactado (prepare-package)
├── plugin.properties              # filtrado (versão, spi.version, built.on) — informativo
├── LICENSE.txt                    # GPL v3 (o pacote combina IPED + Neo4j CE)
├── ThirdParty.txt                 # avisos de terceiros (+ lista gerada do motor)
└── README.md                      # instalação em 3 passos, remoção, compatibilidade
```

- Dependências: `iped-engine` e logging (`slf4j`/`log4j`, herdados do POM pai) em escopo **`provided`**; `neo4j-java-driver` 5.26.0 em `compile`; zip `iped-graph-server:neo4j` em `provided` (só ordena o reactor e alimenta o `unpack`).
- `maven-shade-plugin` 3.6.0: inclui só o driver + `io.netty:*`, `reactor-core`, `reactive-streams`, relocados para `iped.graph.neo4j.shaded.*` (`org.neo4j.driver` **não** é relocado); `ServicesResourceTransformer`; manifesto com `IPED-Graph-SPI-Version=${graph.spi.version}`.
- `graph.spi.version` (propriedade do POM) **deve** acompanhar `GraphServiceProvider.CURRENT_SPI_VERSION` — o teste `declared_spi_version_matches_core` falha se divergir (recurso de teste filtrado `iped/graph/neo4j/build.properties`).

## 4. Instalação, cópia no caso e compatibilidade

- Usuário extrai o zip **dentro** da pasta de plugins (`<dist>/plugins/`, irmã de `<dist>/iped-<versão>/`) e reinicia; log: `Graph plugin loaded: iped-graph-neo4j <versão> (SPI v1, ...)`.
- O `Manager` copia jar + home para `<caso>/plugins/` quando o plugin está disponível (caso novo ou `--append` sem cópia) — a home é localizada sempre relativa ao jar, então o pacote é relocável.
- Aceito sse `IPED-Graph-SPI-Version == CURRENT_SPI_VERSION`; duas cópias → `MULTIPLE_COPIES`; home faltando → `INCOMPLETE` (ver `GraphPluginRegistry` no engine).

## 5. Testes

- `Neo4jGraphServiceProviderTest` (unitário, sempre roda): versão do SPI declarada, leitura do manifesto, `validate()` em layouts completos/incompletos (pastas temporárias), comando do lançador.
- `Neo4jImportRunnerTest` (unitário, sempre roda, 19 testes): completamento de linhas curtas preservando bytes e terminadores (`\r\n`, `\n`, última linha sem terminador, NUL, vírgula entre aspas); arquivos completos só lidos, sem `.padding.tmp`; resolução cabeçalho/dados a partir dos argumentos (`--nodes=0/…`, prefixo do multi-caso, prefixo de label, vários arquivos, caminhos absolutos, arquivos ausentes); contagem de campos com aspas; equivalência com uma cópia literal do algoritmo byte a byte anterior (entradas enumeradas e aleatórias, todos os tamanhos de buffer); fluxo com o `GraphFileWriter` real, inclusive multi-caso; filtro dos arquivos de argumentos; limpeza do temporário em caso de falha (um caso só no Windows); a linha `Padded …` do log, que a validação procura.
- `Neo4jPluginPackageIT` (integração **gated**, perfil `graph-it`): a partir do zip extraído, carrega o jar empacotado num classloader isolado (sem as classes deste módulo nem o driver sem relocação), resolve o registro, sobe o `GraphServer` pela home do plugin e executa `RETURN 1` pelo driver relocado.
- `Neo4jImportRunnerIT` (integração **gated**, perfil `graph-it`): importa com o `neo4j-admin` real da home extraída um CSV com uma linha completa e uma curta, sobe o `Neo4jGraphService` e confirma que a coluna completada virou propriedade **ausente**, sem nenhuma propriedade com texto vazio. Os dois rodam com:

  ```bash
  IPED_GRAPH_PLUGIN_HOME=<pasta onde o zip foi extraído> mvn -pl iped-graph-neo4j -am verify -Pgraph-it
  ```

## 6. Plano B (relocação)

Se algum ambiente falhar ao carregar o driver relocado (netty usa reflexão/recursos), a alternativa documentada é gerar o jar sem relocação e distribuir os jars do driver (`neo4j-java-driver`, `netty-*`, `reactor-core`, `reactive-streams`) soltos na pasta de plugins, ao lado do jar do plugin — funciona com o mecanismo atual de plugins, ao custo de mais arquivos e de perder a detecção simples de cópias.

## 7. Importação: comportamento e armadilhas

Achados da rodada de regressão de 2026-10-23 (`specs/010-neo4j-optional-plugin/research.md` R-15 e `validation-results.md`):

- `padShortRows` altera **no lugar** os CSVs que recebe.
  - No processamento, são os de `<caso>/iped/neo4j/csv/`, que a `GraphTask` compacta em seguida (`*.gzip`): o caso guarda as linhas completadas.
  - No multi-caso, são as cópias preparadas em `iped-multicases/multicase-<hash>/`; os casos de origem não mudam.
  - Uma primeira passagem só lê; só arquivos com linha curta são reescritos (log `INFO Padded …`), via `<arquivo>.padding.tmp`, com `fsync` antes do `move` atômico. Numa falha o temporário é apagado, a menos que o original tenha sumido num `move` não atômico que falhou: aí o temporário é a única cópia, fica no lugar e um `WARN` avisa (T092).
- Falha da importação (`IMPORT FAILED`, ex.: `Error in input data`) vira só `ERROR Error generating database.` em `Neo4jDatabaseGenerator`: `generate()` devolve `false`, a `GraphTask` segue e o processamento termina com exit 0, deixando `neo4j/data/databases/graph.db` provavelmente inutilizável (mesmo comportamento da 4.3.1). Em validação, procurar essa linha no log.
- `ArgsFileFilter` aceita só `import-tool-args-<sufixo>.txt` (sufixo `[A-Za-z0-9_]+`, como o `import-tool-args-iped.txt` do `GraphFileWriter`). `.gzip` e cópias manuais deixados na pasta são ignorados; até T092, um `.gzip` ao lado do `.txt` quebrava a importação e uma cópia duplicava os relacionamentos. Mesmo assim, descompactar CSVs para análise sempre numa cópia fora do caso.
- O `neo4j-admin` grava `import.report` na pasta de trabalho (`csv/`); ele é compactado junto com os CSVs.
