# Validation Results — Neo4j como plugin opcional (010)

Registro das validações da feature (tarefas T004, T030, T041–T043, T054–T057, T068–T070, T079, T081–T084). Cada seção indica **o que foi executado**, **como** e **o resultado**. Itens que dependem de interação manual com a UI ficam marcados como **PENDENTE (manual)** — não foram executados nesta sessão de implementação.

Ambiente: Windows 11 Pro for Workstations (10.0.26300), Liberica JDK 21.0.11 Full (build) e JRE 21.0.11 embarcado (execução), Maven 3.9.16.

Artefatos de validação (fora do repositório, pasta temporária da sessão — `<scratch>`):

- `<scratch>/baseline-src/` — `git archive` do commit `975d0d152` (estado **antes** de qualquer mudança de código da feature).
- `<scratch>/baseline-release/` — cópia congelada de `target/release/` do baseline.
- `<scratch>/ref-evidence/` — evidência de referência (ver abaixo).
- `<scratch>/cases/` — casos processados e resumos (`*.summary.txt`, `*.graphstats.txt`).

## Baseline (T004)

**Build**: `mvn -B clean package -DskipTests` sobre o `git archive` de `975d0d152` → `BUILD SUCCESS` (57 s, 17 módulos). Testes não rodados no baseline (só o release importa para a comparação). Release com Neo4j embarcado: `lib/neo4j/` com 239 jars; driver Bolt + netty/reactor em `lib/`.

**Evidência de referência** (28 MB, pasta lógica montada a partir de `iped-parsers/iped-parsers-impl/src/test/resources/test-files`, com os nomes que as assinaturas do IPED esperam):

| Pasta | Arquivos |
|---|---|
| `mail/` | `test_sample.pst`, `test_sample.ost`, `test_mbox.mbox`, `test_msgSample.msg`, `test_msgSampleAttach.msg`, `test_OutlookDBX.dbx` |
| `contacts/` | 5 × `test_contacts*.vcf` |
| `com.whatsapp/` | `databases/msgstore.db`, `databases/wa.db`, `shared_prefs/com.whatsapp_preferences.xml` |
| `org.telegram.messenger/` | `files/cache4.db`, `shared_prefs/userconfing.xml` |
| `skype/` | `main/main.db`, `s4l/s4l-streeguil1.db` |

**Execuções** (release-baseline, `java -jar iped.jar -d <ref-evidence> -o <caso> --nogui`):

| Caso | Perfil | Exit | Duração |
|---|---|---|---|
| `baseline-default` | padrão (`enableGraphGeneration=true`) | 0 | 77 s |
| `baseline-proximity` | `proximity` (perfil customizado: `conf/GraphConfig.json` padrão com `process-proximity-relationships=true`; entidades por regex já ativas no `GraphConfig.json` padrão) | 0 | 81 s |

**Estatísticas finais** (idênticas nas duas execuções): Total Indexed 1428; Subitems Found 1398; Exported Items 1398; Total Carved Items 0; Ignored Items 0; Parsing Exceptions 0; I/O read errors 0; Timeouts 0; Active Items 30; Index internal docs 2856; Discovered/Processed volume 29213755 bytes.

**Ruído do baseline no log** (não relacionado a grafo; serve de referência para "0 erros novos"):

- `baseline-default`: 3 `ERROR` (`VideoThumbTask` — autoteste do MPlayer na primeira execução do release) e 5 `WARN` (`PDFTextParser` plugin JPEG2000 ausente; `RegexTask` reconstrução do cache de regex na primeira execução; 3 × `OutlookPSTParser` fallback para libpff no `.ost`).
- `baseline-proximity`: 0 `ERROR`, 4 `WARN` (JPEG2000 + 3 × libpff).

**Dados de vínculos** (`<caso>/iped/neo4j/{csv,data,logs}` presentes):

| | `baseline-default` | `baseline-proximity` |
|---|---|---|
| Arquivos em `neo4j/csv` | 33 | 42 |
| Linhas de nós nos CSVs | 648 | 913 |
| Linhas de relacionamentos nos CSVs | 970 | 1415 |
| **Nós no banco** (`MATCH (n) RETURN count(n)`) | **648** | **913** |
| **Relacionamentos no banco** | **970** | **1415** |
| Nós por label | CONTACT_GROUP 32, DATASOURCE 1, EMAIL 23, GENERIC 4, PERSON 588 | CONTACT_GROUP 32, DATASOURCE 1, EMAIL 68, GENERIC 4, IP 44, PERSON 588, PHONE 11, URL 165 |
| Relacionamentos por tipo | contact 654, email 150, message 166 | contact 654, email 150, message 166, near_hit 445 |

Contagens do banco obtidas com uma ferramenta de linha de comando (`GraphStats`, em `<scratch>/graph-stats/`) que usa apenas `GraphServiceFactoryImpl` + `GraphService.advancedSearch` — assinaturas idênticas antes e depois da feature, de modo que a mesma ferramenta mede o baseline e o plugin. O banco é aberto a partir de uma **cópia** de `neo4j/` para manter o caso-baseline congelado.

**CSV golden do `GraphFileWriter` (insumo de T040)**: um conjunto fixo de nós/relacionamentos (acentos, vírgulas, aspas, barra invertida, quebra de linha, labels com `_`, nó com dois labels, substituições/merge de nós, relacionamentos com e sem propriedades) foi escrito com o `GraphFileWriter` **antigo** (API `DynLabel`/`DynRelationshipType`, jars do release-baseline) e os 26 arquivos texto resultantes (CSVs de dados e cabeçalhos, `replace.csv`, `import-tool-args-iped.txt`) foram copiados para `iped-engine/src/test/resources/iped/engine/graph/golden/` com `.gitattributes` `* -text` (CRLF preservado byte a byte em qualquer plataforma). Os arquivos `*_fieldData` (serialização Java interna) só têm o **nome** verificado.

**PENDENTE (manual, UI)**: exportação CSV das propriedades de todos os itens pela UI e o roteiro fixo de 10 operações na aba "Vínculos". Para a paridade forense foi usada, em vez da exportação pela UI, uma comparação automatizada do índice Lucene (ver T042).

## Checkpoint Phase 2 (T030)

- `mvn -B -pl iped-engine,iped-app,iped-graph-neo4j -am compile` → `BUILD SUCCESS` (18 módulos do reactor, incluindo o novo `iped-graph-neo4j`).
- `grep -rl "import org.neo4j" iped-engine/src iped-app/src` → **vazio**.
- `grep -rn "org.neo4j" iped-engine/src iped-app/src` → **vazio** (nem em comentários).
- **Achado durante a migração**: `iped-engine/.../data/BitmapBookmarks.java` usava `org.eclipse.collections...IntArrayList`, que só chegava ao classpath (e ao `lib/` do release, `eclipse-collections-11.1.0`) como dependência **transitiva** de `neo4j-graphdb-api` — nunca declarada nem registrada no `ThirdParty.txt`. Ao remover o Neo4j do núcleo, a compilação quebrou. Correção: troca por `java.util.HashSet<Integer>` nos dois pontos de uso (lista de ids de bookmarks usada só para teste de pertinência — semântica idêntica). Consequência: `eclipse-collections*.jar` também deixa o `lib/` do release, junto com o resto da árvore do Neo4j.
- **Correção latente encontrada na retipagem da UI**: o `BoltEntity` da feature 003 devolvia `null` para propriedade ausente (em vez de `NotFoundException`), então o `try/catch NotFoundException` de `GraphModel.getLabel` nunca disparava e uma propriedade ausente virava `NullPointerException`. Com o teste de nulo (T025) volta o comportamento pretendido: pular campos ausentes.
- `getStartNodeId()`/`getEndNodeId()` (métodos *default* depreciados da API Neo4j, usados em `AppGraphAnalytics` e `AdvancedSearchDialog`) foram reescritos como `getStartNode().getId()`/`getEndNode().getId()` — exatamente o que os *defaults* do Neo4j faziam — para manter o modelo neutro no contrato mínimo do SPI.

## T041 — Build e inventário da distribuição padrão (SC-001)

- `mvn -B clean package -DskipTests` → `BUILD SUCCESS` (18 módulos; `iped-graph-neo4j` construído no reactor, `iped-app` não depende dele).
- Enforcer `ban-neo4j` (`maven-enforcer-plugin` 3.5.0, `bannedDependencies` `org.neo4j:*` + `org.neo4j.driver:*`, transitivo) em `iped-engine` e `iped-app`: regra **passa**. Teste negativo (cópia temporária do POM do engine com `neo4j-java-driver` reintroduzido, apagada em seguida) → `BUILD FAILURE` com a mensagem `Neo4j must not be a dependency of the IPED core ... org.neo4j.driver:neo4j-java-driver:jar:5.26.0 <--- banned`.
- Inventário (contrato release-inventory):
  - `find target/release -iname '*neo4j*'` → **nenhuma linha**.
  - `ls target/release/iped-*/lib | grep -i -E 'netty|reactor|reactive-streams'` → **nenhuma linha**.
  - `grep -rl "import org.neo4j" iped-engine/src iped-app/src` → **nenhuma linha**.
  - `antlr4-runtime-4.9.2.jar` (libfqlite) permanece; nenhum `antlr4-runtime-4.13.x`.
  - Diferença do `lib/` em relação ao release-baseline: **exatamente 21 entradas removidas** — `neo4j/` (pasta isolada), `neo4j-{collections,common,gql-status,graphdb-api,java-driver,resource,unsafe}-5.26.0.jar`, `annotations-5.26.0.jar`, `eclipse-collections{,-api}-11.1.0.jar`, 8 × `netty-*`, `reactive-streams-1.0.4.jar`, `reactor-core-3.6.10.jar` — e **nenhuma adicionada** (347 → 326 entradas).
  - `target/release/plugins/` contém `Readme.txt` e os plugins opcionais já existentes antes da feature (`stanford-corenlp`, `java-dbx`, `telegram-decoder-impl`); **nenhum** `iped-graph-neo4j`. (Observação: o quickstart dizia "apenas `Readme.txt`"; os três jars são pré-existentes e não fazem parte do escopo.)

## T042 — Processamento sem o plugin (SC-002, FR-004, FR-005)

**Achado de método**: a primeira execução sobre a evidência de referência fez o SQLite criar arquivos `-wal`/`-shm` **dentro da pasta da evidência** (comportamento pré-existente do IPED com pastas lógicas, alheio à feature), de modo que as execuções seguintes viam 36 arquivos em vez de 30. Por isso a comparação de paridade foi refeita com quatro execuções "limpas" (arquivos laterais do SQLite apagados antes de cada uma) — script `<scratch>/run-parity.sh`:

| Caso | Release | Perfil | Exit | Log: `WARN` / `ERROR` |
|---|---|---|---|---|
| `p-baseline-default` | baseline | padrão | 0 | 4 / 0 |
| `p-nopl-default` | 010, **sem plugin** | padrão | 0 | **5 / 0** |
| `p-baseline-proximity` | baseline | `proximity` | 0 | 4 / 0 |
| `p-nopl-legacy` | 010, **sem plugin** | `legacy` = **cópia do baseline**: `IPEDConfig.txt` e `conf/TaskInstaller.xml` (lista `iped.engine.graph.GraphTask`) do release-baseline + `GraphConfig.json` com proximidade | 0 | **5 / 0** |

- O `WARN` a mais nas execuções sem plugin é **exatamente um**: `[engine.graph.GraphTask] Graph generation is enabled but the graph plugin is not installed (ABSENT): link analysis will be skipped for this case.` Os demais `WARN` são o ruído do baseline (JPEG2000, 3 × libpff). A linha de estado do registro é `INFO`: `Graph plugin not installed (plugin folder: <dist>\plugins)`.
- `<caso>/iped/neo4j/` **não existe** nos casos sem plugin; `<caso>/plugins/` também não.
- Perfil customizado/legado (US1 cenário 4, FR-005): carregado **sem edição** e sem erro, com o mesmo resultado do perfil padrão.
- **Estatísticas finais idênticas** nos dois pares (baseline × sem plugin): Total Indexed 1428; Subitems Found 1398; Exported Items 1398; Carved 0; Ignored 0; Parsing Exceptions 0; I/O read errors 0; Timeouts 0; Text Splits 0; Active Items 30; Index internal docs 2856; volume descoberto/processado 29217851 bytes.

**Paridade item a item (C1–C8)** — substituto automatizado da exportação CSV pela UI: ferramenta `IndexDump` (`<scratch>/index-dump/`, Lucene 9.2 dos jars do release) que despeja, por item (`trackId`), todos os campos armazenados do documento-pai e um SHA-256 dos termos indexados + frequências do campo `content` dos documentos-filhos (proxy do texto extraído, C5). Resultado do `diff` (1428 itens em cada caso):

| Classe de diferença | Campos | Classificação |
|---|---|---|
| Identificadores da execução | `evidenceUUID`, `globalId` (todos os itens) | E4 — UUID aleatório por processamento |
| Ordem de processamento | `id`, `parentId`, `parentIds`, `dataSourcePath` (shard do storage) | E2 — não determinismo multi-thread |
| Ambiente | `accessed`, `timeStamp` (30 arquivos de origem); `modified` de 2 pastas (`com.whatsapp/databases`, `org.telegram.messenger/files`, alteradas pelos arquivos laterais do SQLite) | E1/E4 |
| 9 itens virtuais do PST (`PSTAppointment`/`PSTContact`/`PSTTask` renderizados pelo `OutlookPSTParser`) | `hash`/`md5`/`sha-*`/`idInDataSource`, `size`/`textSize`, `#content-terms`, scores de idioma | **Não determinismo pré-existente**: o hash desses 9 itens muda **a cada execução, inclusive entre duas execuções do próprio baseline** (4 execuções → 4 hashes distintos) |

- Nenhum outro campo diverge: hashes dos demais 1419 itens (C1), `contentType`/`type` (C2), contagens (C3), `category` (C4), texto indexado dos demais itens (C5), carving (C6 — 0 itens), YARA (C7 — desabilitado no perfil), datas de timeline (C8 — exceto `accessed` das origens).
- **Controle**: o `diff` baseline × baseline (`p-baseline-default` × `p-baseline-proximity`) produz **as mesmas classes de diferença** com contagens equivalentes — o release sem plugin não introduz nenhuma classe nova de divergência. **SC-002: atendido** (paridade forense com o baseline, exclusões documentadas acima).

## T043 — Demais pontos de entrada sem o plugin (SC-005, FR-006, FR-017) — parcial

Executado com o release 010 **sem** o plugin:

| Ponto de entrada | Resultado |
|---|---|
| `--nogui` (processamento) | OK — todas as execuções de T042 (exit 0). |
| `iped.engine.webapi.Main` (`lib/iped-webapi.jar --sources=<json com o caso sem plugin>`) | OK — `GET /sources` → HTTP 200; `GET /search?q=email` → HTTP 200 com resultados; nenhum erro de grafo/Neo4j no console. Observação (pré-existente, não relacionada): no Java 21 a Web API iniciada diretamente precisa de `-Djava.security.manager=allow` (o `Bootstrap` já passa essa flag às JVMs filhas; sem ela `Configuration.loadConfigurables` falha em `System.setSecurityManager`). |
| `iped.engine.hashdb.HashDBTool` (`lib/iped-hashdb.jar`) | OK — sobe e imprime o uso (exit 0). |
| `--yara-only` | **Não executável neste ambiente**: exige `enableYara=true` e a DLL `tools/yara-x/win64/yara_x_capi.dll`, ausente nesta árvore (arquivo ignorado pelo Git). A regra "o guard não bloqueia `--yara-only` mesmo com `isContinue()==true`" está coberta por `GraphPluginGuardTest.new_case_restart_and_yara_only_are_never_refused`. |
| Relatório a partir do caso | **PENDENTE (manual)**: o modo relatório é acionado por um arquivo de bookmarks `.iped` criado pela UI; `-d <pasta do caso>` apenas processa a pasta como fonte lógica (exit 0, 1 `WARN` de grafo, sem `neo4j/`). |
| UI (`IPED-SearchApp`/`BootstrapUI`) — checklist de smoke do quickstart §4 | **PENDENTE (manual)**. Verificação estática: as únicas referências ao pacote `iped.app.graph` fora dele estão em `App` (criação condicionada a `GraphPluginRegistry.get().isAvailable()`); `graphDock`, `FilterSelectedEdges`, item "adicionar ao grafo" (`MenuClass`/`MenuListener`) e `initGraphService` (`UICaseDataLoader`) estão protegidos contra `null`. |

## T053 — Teste de integração do pacote (R-05)

- `mvn -pl iped-graph-neo4j -am verify -Pgraph-it` com `IPED_GRAPH_PLUGIN_HOME` = pasta onde o zip foi extraído → `Neo4jPluginPackageIT`: **1 teste, 0 falhas** (17,5 s). A partir do jar **empacotado** (classloader isolado com o classpath do IPED, sem as classes deste módulo nem o driver/netty/reactor sem relocação): netty e reactor só existem relocados (`iped.graph.neo4j.shaded.*`; `io.netty.channel.Channel` não é visível), o `GraphPluginRegistry` resolve **AVAILABLE**, o `GraphServer` sobe a partir de `iped-graph-neo4j/neo4j/` e o driver relocado executa `RETURN 1`. **Relocação validada — plano B não necessário.**
- A primeira execução do IT mostrou que o failsafe coloca o jar empacotado do próprio módulo (`target/iped-graph-neo4j-*.jar`) no classpath de teste: o registro isolado viu **duas cópias** e rejeitou com `MULTIPLE_COPIES` (comportamento correto do registro). O IT passou a excluir tudo sob `target/` do módulo.

## T054 — Instalação do plugin (SC-003, FR-002, FR-003)

- `mvn -pl iped-graph-neo4j -am package` → `iped-graph-neo4j/target/iped-graph-neo4j-4.4.0-SNAPSHOT.zip` (126 MB; jar de 5,5 MB + `iped-graph-neo4j/{neo4j/ (239 jars, conjunto idêntico ao antigo lib/neo4j/), plugin.properties, LICENSE.txt, ThirdParty.txt, README.md}`).
- Instalação numa cópia do release 010: **(1)** obter o zip, **(2)** extrair dentro de `<dist>/plugins/` (poucos segundos), **(3)** reiniciar. **0 edições de configuração.** 3 passos, bem abaixo de 5 min.
- Log do processamento após a instalação: `INFO [engine.graph.GraphPluginRegistry] Graph plugin loaded: iped-graph-neo4j 4.4.0-SNAPSHOT (SPI v1, built for IPED 4.4.0-SNAPSHOT) from <dist>\plugins\iped-graph-neo4j-4.4.0-SNAPSHOT.jar`.
- A mesma linha no log da UI (`AppMain` chama `GraphPluginRegistry.get()` após `loadConfigurables`): **PENDENTE (manual)** — o caminho de código é o mesmo do processamento.

## T055 — Processamento com o plugin (SC-004, FR-009)

| Caso | Release | Perfil | Exit | `iped/neo4j/` | Banco (nós / relacionamentos) | Baseline equivalente |
|---|---|---|---|---|---|---|
| `wp-default` | 010 + plugin | padrão | 0 | `csv`, `data`, `logs` | **648 / 970** | `baseline-default` 648 / 970 |
| `wp-legacy` | 010 + plugin | `legacy` (configs do baseline + proximidade) | 0 | `csv`, `data`, `logs` | **913 / 1415** | `baseline-proximity` 913 / 1415 |

- Estatísticas por label e por tipo de relacionamento: **idênticas** ao baseline nas duas execuções (`diff` dos relatórios `GraphStats` vazio).
- CSVs: mesmos nomes de arquivo (23 e 29); conteúdo idêntico (ordenado) em todos os arquivos de nós, cabeçalhos e `replace.csv`, e colunas `start,end,type` dos relacionamentos idênticas — a única diferença é o id do nó `DATASOURCE`, derivado do UUID da evidência gerado a cada processamento (E4); mapeado esse id, os relacionamentos `contact` também são idênticos. As colunas `dataSource`/`relId` diferem pelo mesmo motivo (UUID e ids de item por execução).

## T057 — Cópia autocontida e remoção (FR-010, FR-015, SC-008)

- Caso criado com o plugin (`wp-default`): `<caso>/plugins/` contém `iped-graph-neo4j-4.4.0-SNAPSHOT.jar` + `iped-graph-neo4j/` (133 MB, os mesmos 239 jars do motor); log `INFO [engine.core.Manager] Copying graph plugin iped-graph-neo4j-4.4.0-SNAPSHOT.jar to <caso>\plugins`. `<caso>/iped/LocalConfig.txt` mantém `pluginFolder = ../plugins/`.
- Resolução a partir da cópia do caso, simulando máquina sem o plugin instalado (classpath = `<caso>/iped/lib/*` + `<caso>/plugins/*`, como o `Bootstrap` monta ao abrir pelo `IPED-SearchApp` do caso): `GraphPluginRegistry` → **AVAILABLE** a partir de `<caso>\plugins\iped-graph-neo4j-4.4.0-SNAPSHOT.jar` (inclui a validação da home relativa ao jar). A abertura visual da aba "Vínculos" pela cópia do caso fica **PENDENTE (manual)**.
- Remoção do plugin da distribuição (jar + pasta apagados) e novo processamento: `INFO Graph plugin not installed (plugin folder: …)` + 1 `WARN` de etapa pulada, exit 0, sem `neo4j/` — mesmo comportamento de US1, sem erro residual.

## T069 — Bloqueio, override, `--restart` e plugin rejeitado (FR-008, FR-011, FR-012)

**Achado de ambiente (pré-existente, fora do escopo)**: neste Windows, **todo `--append`** é recusado antes do `Manager` — inclusive com o release-baseline sobre o próprio caso — com `Appending to case with old version Indexador e Processador de Evid?ncias Digitais 4.4.0-snapshot not supported`. Causa: `CmdLineArgsImpl.checkIfAppendingToCompatibleCase` executa `java … Main -h` (o `java` do `PATH`) e decodifica a saída em UTF-8, mas o filho escreve na página de código nativa quando a saída é um pipe, então "Evidências" nunca casa. Por isso o ramo do guard foi exercitado com `--continue` e `-remove` (mesma condição do `GraphPluginGuard`), e o `--append` está coberto pelos testes de unidade (`GraphPluginGuardTest`).

Caso **com** grafo (`p-baseline-default`, copiado), release 010 **sem** plugin:

| Operação | Resultado |
|---|---|
| `--continue -d <evidência>` | **Recusado**: `ERROR Processing Error: This case contains link analysis (graph) data, but the graph plugin is not installed, so the graph cannot be updated.` + linha com a instrução e a flag; **exit 1**; **0 arquivos** do caso alterados (snapshot nome/tamanho/mtime antes × depois). |
| `--continue --ignore-missing-graph-plugin` | Prossegue (exit 0): console `MSG Proceeding without updating the case graph (--ignore-missing-graph-plugin): …`; log `WARN [engine.graph.GraphPluginGuard] Graph plugin not available (ABSENT): modifying a case with link analysis data because of --ignore-missing-graph-plugin, …` + o `WARN` da `GraphTask`; `iped/neo4j/{csv,data}` preservados (grafo não atualizado). |
| `-remove ref-evidence` | **Recusado** (mesma mensagem, exit 1). |
| `-remove ref-evidence --ignore-missing-graph-plugin` | Prossegue (exit 0): console `MSG` + `WARN` do guard; `WARN [engine.core.Manager] Graph plugin not available: graph connections of the removed evidence were not deleted from the graph database (graph not updated).`; limpeza dos CSVs feita (linhas de dados de relacionamento 970 → 0); banco intocado (648 / 970). |
| `--restart -d <evidência>` | Não bloqueado (exit 0); `iped/neo4j/` **removido**; caso final sem dados de vínculos. |

Plugin presente, porém rejeitado (evidência mínima, release 010 + plugin alterado):

| Cenário | Log (uma linha `ERROR` do registro + o `WARN` da task) | Exit |
|---|---|---|
| `iped-graph-neo4j/neo4j/` apagada | `Graph plugin rejected (incomplete): missing folder <plugins>\iped-graph-neo4j\neo4j (extract the whole plugin zip …)` / `(REJECTED, INCOMPLETE)` | 0 |
| Duas cópias do jar (`…-4.3.0.jar` + `…-4.4.0-SNAPSHOT.jar`) | `Graph plugin rejected (multiple copies): <jar1>, <jar2>` / `(REJECTED, MULTIPLE_COPIES)` | 0 |
| Manifesto com `IPED-Graph-SPI-Version: 2` | `Graph plugin rejected (SPI mismatch): plugin SPI v2 (iped-graph-neo4j 4.4.0-SNAPSHOT), core SPI v1 (IPED 4.4.0-snapshot)` / `(REJECTED, SPI_MISMATCH)` | 0 |

Em todos os casos o IPED segue sem grafo, como sem plugin.

## T070 — Configurações legadas (SC-007, FR-005)

Perfil `legacy` = `IPEDConfig.txt` + `conf/TaskInstaller.xml` (lista `iped.engine.graph.GraphTask`) + `conf/GraphConfig.json` copiados do **release-baseline**: processado **sem** o plugin (`p-nopl-legacy`: exit 0, 1 `WARN`, sem `neo4j/`) e **com** o plugin (`wp-legacy`: exit 0, grafo idêntico ao `baseline-proximity`). Nenhuma edição e nenhum erro.

## T056 — UI e modificação de caso com o plugin (SC-004, FR-009) — parcial

Modificação de caso **com** o plugin × mesma operação com o release-baseline (cópias de `wp-default` e `p-baseline-default`):

| Operação | 010 + plugin (nós / rel.) | Baseline (nós / rel.) |
|---|---|---|
| `--continue -d <evidência>` (grafo regenerado) | exit 0 — **648 / 970** | exit 0 — 648 / 970 |
| `-remove ref-evidence` | exit 0 — **648 / 0** (as 970 conexões da evidência removidas do banco; CSVs sem linhas de dados) | exit 0 — 648 / 0 (idem) |

- `--append` não pôde ser exercitado neste Windows pelo problema pré-existente de `checkIfAppendingToCompatibleCase` descrito em T069 (afeta igualmente o baseline). Com o plugin, o `--append` usa o mesmo caminho da `GraphTask` que o `--continue` (apaga `neo4j/data` e regenera a partir dos CSVs) e copia o plugin para `<caso>/plugins/` se faltar.
- **PENDENTE (manual)**: roteiro fixo de 10 operações na aba "Vínculos" × baseline e o multi-caso com 2 casos (grafo combinado) — exigem interação com a UI.

## T081 — Tempo de inicialização (SC-006)

Os registros de log têm resolução de 1 s, insuficiente para o critério (< 1 s). Foi medido com precisão, por uma ferramenta (`<scratch>/startup-probe/StartupProbe`), exatamente o que a feature acrescenta à inicialização da CLI e da UI — `Configuration.loadConfigurables(appRoot, true)` (que adiciona cada entrada da pasta de plugins, inclusive a home `iped-graph-neo4j/` e o jar do plugin, ao lookup de configuração) seguido de `GraphPluginRegistry.get()` —, na mesma ordem de `iped.app.processing.Main`/`AppMain`, com o classpath que o `Bootstrap` monta (`lib/*` + `plugins/*`). 3 execuções cada:

| Release | Execuções (ms, config + registro) | Média | Δ × baseline |
|---|---|---|---|
| baseline | 2801 / 2800 / 2783 | ≈ 2795 | — |
| 010 sem plugin | 2805 / 2854 / 2834 (registro ≈ 15 ms, `ABSENT`) | ≈ 2831 | ≈ +36 ms |
| 010 com plugin | 3018 / 3040 / 3002 (registro ≈ 17–23 ms, `AVAILABLE`) | ≈ 3020 | ≈ +225 ms |

- **SC-006 atendido** (< 1 s). O acréscimo com o plugin vem do lookup genérico de configuração percorrendo o jar do plugin (≈ 3250 entradas) e a home (≈ 243 entradas) para cada `Configurable` — mesmo mecanismo já aplicado aos demais plugins (CoreNLP etc.). Por ser imperceptível, o lookup **não** foi alterado (filtrar diretórios de plugin mudaria um mecanismo genérico de que outros plugins podem depender).
- Na UI o caminho é o mesmo; sem o plugin a UI ainda deixa de criar o painel de grafo (que o baseline sempre criava). Cronometragem de ponta a ponta da janela da UI: **PENDENTE (manual)**.

## T082 — Smoke Linux (WSL) — PENDENTE

O WSL (Ubuntu 26.04) desta máquina não tem mais um JDK 21 instalado (o usado na feature 003 não está presente), então o smoke Linux exigiria instalar um JDK no ambiente WSL do usuário — não feito nesta sessão. O código do plugin é Java puro; `Neo4jChildLauncher` usa `<java.home>/bin/java` fora do Windows, e o CI (Ubuntu) compila e roda os testes de unidade a cada push.

## T083 — Conformidade com a constituição

- Revisão estática do código novo/movido (24 arquivos: `iped-engine/.../graph/Graph{Label,RelationshipType,Entity,Node,Relationship,Path,QueryException,PluginException,DatabaseGenerator,PluginState,PluginRejection,ServiceProvider,PluginRegistry,PluginGuard}`, `iped-graph-neo4j/**`, `GraphPluginNotice`):
  - comentários/Javadoc/mensagens de log em **inglês**; textos para o usuário nos bundles (EN, PT-BR + de/es/fr/it);
  - **charset explícito**: UTF-8 em todos os `Reader`/`Writer` de processos filhos e arquivos; manifesto lido pela API `JarFile`/`Manifest` (UTF-8 pela especificação); args da importação lidos com `StandardCharsets.UTF_8`. Único caso não-UTF-8: `Neo4jImportRunner` decodifica a saída do `neo4j-admin` com `Charset.defaultCharset()` explícito — código movido sem alteração de comportamento (afeta só o texto de log);
  - **somente SLF4J/Log4j 2** (`GraphPluginGuard` usa a API Log4j, como o `Manager`, por causa do nível customizado `MSG`); nenhum `System.out`/`System.err`;
  - Swing na EDT: `GraphPluginNotice` exibe o diálogo via `SwingUtilities.invokeLater`.
- Testes (`mvn -B -pl iped-engine,iped-app,iped-graph-neo4j,iped-graph-server -am test`, filtrado para os pacotes desses módulos): **iped-engine 154 testes, 0 falhas, 2 ignorados** (testes YARA que exigem a biblioteca nativa); **iped-graph-neo4j 9 testes, 0 falhas**; **iped-app 4 testes, 0 falhas** → `BUILD SUCCESS`. Os 18 testes novos do engine (`GraphPluginRegistryTest` 8, `GraphPluginGuardTest` 8, `GraphFileWriterTest` 2) estão incluídos.

## T079 — Distribuição (SC-009, FR-013, FR-014) — parcial

- `mvn -B clean package` (equivalente ao CI, **com todos os testes**) → `BUILD SUCCESS` em 1 min 55 s: 183 + 154 + 9 + 4 = **350 testes, 0 falhas** (3 ignorados: 1 em parsers, 2 YARA nativos no engine). Enforcer `ban-neo4j` passou em `iped-engine` e `iped-app`.
- Inventário (mesmos comandos de T041) sobre esse release: **vazio** (nenhum `*neo4j*`, nenhum netty/reactor/reactive-streams, nenhum `import org.neo4j` no núcleo); o zip do plugin fica só em `iped-graph-neo4j/target/`.
- Zip do plugin contém `iped-graph-neo4j-4.4.0-SNAPSHOT.jar` + `iped-graph-neo4j/{LICENSE.txt, ThirdParty.txt, README.md, plugin.properties, neo4j/}`.
- **PENDENTE**: "CI da branch verde com o artefato do plugin publicado" — exige enviar a branch ao GitHub (não feito nesta sessão). O passo novo do workflow (`Verify no Neo4j in standard release`) foi executado localmente com o mesmo script sobre `target/release`: saída `OK`, exit 0.

## T084 — Matriz consolidada (quickstart §4)

| # | Passo | Status | Evidência |
|---|---|---|---|
| 1 | Inventário da distribuição | ✅ local / ⏳ CI | T041, T079 (enforcer + inventário vazio; CI depende de push) |
| 2 | Processar sem plugin (perfil padrão + perfil legado com `GraphTask`) | ✅ | T042: 1 `WARN`, 0 `ERROR`, sem `neo4j/`, estatísticas idênticas, paridade C1–C8 com exclusões documentadas |
| 3 | UI sem plugin (checklist de smoke) | ⏳ manual | T043 (verificação estática dos pontos protegidos; demais pontos de entrada CLI/Web API/HashDB OK) |
| 4 | Caso com grafo na UI sem plugin → aviso modeless | ⏳ manual | T068; lógica coberta por `GraphPluginNoticeTest` |
| 5 | Modificação sem plugin recusada; `--yara-only` não bloqueado | ✅ (via `--continue`/`-remove`) | T069; `--append` impedido neste Windows por problema pré-existente de `checkIfAppendingToCompatibleCase` (afeta o baseline também) e coberto por `GraphPluginGuardTest`; `--yara-only` coberto por teste (DLL YARA-X ausente) |
| 6 | Override `--ignore-missing-graph-plugin` | ✅ | T069 (`MSG` no console + `WARN` no log; CSVs limpos no `-remove`; banco intocado) |
| 7 | Instalar o plugin (≤ 3 passos, < 5 min, 0 edições) | ✅ | T054 (extração em segundos; log `Graph plugin loaded: …`) |
| 8 | Processar com plugin (padrão + proximidade) | ✅ | T055: 648/970 e 913/1415, idênticos ao baseline por label/tipo; CSVs idênticos salvo o UUID da evidência |
| 9 | Roteiro de 10 operações na aba "Vínculos" | ⏳ manual | — |
| 10 | `--append`/remoção com plugin | ✅ parcial | T056: `--continue` e `-remove` idênticos ao baseline; `--append` impedido pelo problema pré-existente citado no passo 5 |
| 11 | Cópia em `<caso>/plugins/` e abertura pela cópia sem plugin instalado | ✅ cópia + resolução / ⏳ UI | T057 (registro resolve AVAILABLE a partir de `<caso>/plugins/` com o `lib/` do caso) |
| 12 | Multi-caso com plugin | ⏳ manual | — |
| 13 | Remover o plugin e repetir 2 e 3 | ✅ passo 2 / ⏳ passo 3 (UI) | T057 |
| 14 | Perfis/configs da versão anterior com e sem plugin | ✅ | T070 |
| 15 | Tempo de inicialização | ✅ (sonda) / ⏳ cronometragem da UI | T081: +36 ms sem plugin, +225 ms com plugin (< 1 s) |
| 16 | Plugin corrompido / duas cópias / SPI v2 | ✅ | T069 (motivo no log, IPED segue sem grafo) |
| 17 | `--restart` sem plugin em caso com grafo | ✅ | T069 (`iped/neo4j/` removido, exit 0) |
| 18 | Linux (WSL) | ⏳ | T082 (WSL sem JDK 21) |

**Testes automatizados da feature**: `GraphPluginRegistryTest` (8), `GraphPluginGuardTest` (8), `GraphFileWriterTest` (2), `Neo4jGraphServiceProviderTest` (9), `GraphPluginNoticeTest` (4) e `Neo4jPluginPackageIT` (1, gated) — todos verdes.

**Achados fora do escopo, registrados para acompanhamento** (pré-existentes, reproduzidos também com o release-baseline):

1. `CmdLineArgsImpl.checkIfAppendingToCompatibleCase` decodifica em UTF-8 a saída de `java … -h` (o `java` do `PATH`), mas no Windows o processo filho escreve na página de código nativa quando a saída é um pipe: "Evidências" vira "Evid?ncias", a comparação de versão falha e **todo `--append` é recusado** ("Appending to case with old version … not supported"). Também falha se não houver `java` no `PATH`.
2. Processar uma pasta lógica com bancos SQLite em modo WAL (WhatsApp `msgstore.db`/`wa.db`, Telegram `cache4.db`) cria arquivos `-wal`/`-shm` **dentro da pasta da evidência**, alterando a fonte entre execuções.
3. Os 9 itens virtuais do PST (`PSTAppointment`/`PSTContact`/`PSTTask`) têm conteúdo (hash) diferente a cada processamento.
4. A Web API iniciada diretamente no Java 21 precisa de `-Djava.security.manager=allow`.
