# Data Model — Neo4j como plugin opcional do IPED

**Fase 1** do plano. Entidades, atributos, regras de validação e transições de estado derivadas do [spec.md](spec.md) (Key Entities) e das decisões do [research.md](research.md). Não há mudança de formato de índice Lucene nem de banco de grafos: o modelo descreve **estado de runtime, pacote e arquivos do caso**.

---

## 1. GraphPlugin (estado de runtime do plugin na JVM)

Resolvido uma vez por JVM pelo `GraphPluginRegistry` (R-03). Imutável depois de resolvido.

| Campo | Tipo | Descrição |
|---|---|---|
| `state` | enum `GraphPluginState` {`ABSENT`, `AVAILABLE`, `REJECTED`} | Resultado da descoberta/validação. |
| `rejection` | enum `GraphPluginRejection` {`MULTIPLE_COPIES`, `SPI_MISMATCH`, `INCOMPLETE`, `LOAD_ERROR`} ou `null` | Só quando `REJECTED`. |
| `detail` | `String` | Texto humano do motivo (ex.: "plugin SPI v2, core SPI v1"; "missing iped-graph-neo4j/neo4j/"). |
| `pluginVersion` | `String` | `Implementation-Version` do jar (ex.: `4.4.0`). `null` se ABSENT. |
| `spiVersion` | `int` | Versão do ponto de extensão declarada pelo plugin. |
| `builtForIpedVersion` | `String` | Informativo (`IPED-Target-Version`). |
| `location` | `Path` | Jar que forneceu o provedor. |
| `pluginFolder` | `Path` | Pasta de plugins resolvida (`PluginConfig.getPluginFolder()`), para log e cópia no caso. |

**Transições** (ocorrem apenas na resolução):

```text
(início) ──ServiceLoader: 0 provedores──────────────────────────▶ ABSENT
(início) ──≥2 jars com o recurso de serviço (contagem por URL)──▶ REJECTED(MULTIPLE_COPIES)
(início) ──erro ao instanciar (ServiceConfigurationError/Linkage)▶ REJECTED(LOAD_ERROR)
(início) ──1 provedor, spiVersion ≠ CURRENT_SPI_VERSION─────────▶ REJECTED(SPI_MISMATCH)
(início) ──1 provedor, validate() falha─────────────────────────▶ REJECTED(INCOMPLETE)
(início) ──1 provedor, versão ok, validate() ok─────────────────▶ AVAILABLE
```

**Regras**: `isAvailable() == (state == AVAILABLE)`. Qualquer estado ≠ AVAILABLE é tratado pelo núcleo como "plugin ausente" (FR-012), diferindo apenas na mensagem de log (FR-003). O log é emitido exatamente uma vez, na transição: `INFO` para `AVAILABLE` e `ABSENT` (estado padrão da distribuição; não é um aviso para efeito de FR-004/SC-002), `ERROR` para `REJECTED`. A contagem de cópias usa os recursos `META-INF/services/…GraphServiceProvider` por jar de origem, porque `ServiceLoader` deduplica provedores pelo nome da classe.

---

## 2. GraphServiceProvider (contrato do ponto de extensão)

| Campo/Método | Descrição |
|---|---|
| `CURRENT_SPI_VERSION = 1` | Constante no núcleo; incrementada a cada mudança incompatível em `GraphService`, `GraphDatabaseGenerator` ou nos tipos de modelo. |
| `getSpiVersion()` | Versão implementada pelo plugin (lida do manifesto). |
| `getPluginVersion()`, `getBuiltForIpedVersion()`, `getDisplayName()` | Informativos (log e aviso da UI). |
| `validate()` | Verifica integridade do pacote instalado; lança `GraphPluginException(detail)`. |
| `createGraphService()` | Instância do serviço de consulta/ciclo de vida (uma por JVM, cacheada pelo registro). |
| `createDatabaseGenerator()` | Instância do gerador (importação + pós-importação). |

**Regra de compatibilidade** (Clarification 5): aceito **sse** `getSpiVersion() == CURRENT_SPI_VERSION`. Números de release (`pluginVersion`, versão do IPED) **não** participam da decisão.

---

## 3. PluginPackage (artefato distribuído)

Produzido pelo módulo `iped-graph-neo4j` (R-04).

| Elemento | Obrigatório | Validação em `validate()` |
|---|---|---|
| `iped-graph-neo4j-<v>.jar` na raiz da pasta de plugins | sim | É o jar que carregou o provedor (origem via `ProtectionDomain`). |
| Manifesto: `IPED-Graph-SPI-Version`, `Implementation-Version`, `IPED-Target-Version` | sim | Ausência de `IPED-Graph-SPI-Version` → `LOAD_ERROR`. |
| `iped-graph-neo4j/neo4j/iped-graph-server-*.jar` | sim | Ausente → `INCOMPLETE`. |
| `iped-graph-neo4j/neo4j/neo4j-*.jar` (motor) | sim | Nenhum → `INCOMPLETE`. |
| `iped-graph-neo4j/plugin.properties` | não | Só informativo (humanos/suporte). |
| `iped-graph-neo4j/LICENSE.txt`, `ThirdParty.txt`, `README.md` | sim (pacote) | Não validados em runtime. |

**Identidade/unicidade**: exatamente um jar provendo `GraphServiceProvider` na pasta de plugins; dois ou mais ⇒ `MULTIPLE_COPIES` (nenhum carregado). A home é localizada **relativa ao jar** (`<pasta do jar>/iped-graph-neo4j/`), o que mantém o pacote relocável (release ou cópia no caso).

---

## 4. PluginFolder (pasta de plugins)

| Contexto | App root (`IPED_APP_ROOT`) | `pluginFolder = ../plugins/` resolve para |
|---|---|---|
| Release instalado | `<dist>/iped-<versão>/` | `<dist>/plugins/` (irmã; o POM pai usa `plugin.dir = ${release.dir}/../plugins`) |
| Caso autocontido (UI/relatório a partir do caso) | `<caso>/iped/` | `<caso>/plugins/` |

**Regra FR-010**: ao criar um caso novo com `GraphPlugin.state == AVAILABLE` — ou em `--append` quando `<caso>/plugins/` ainda não contém o plugin —, o `Manager` copia `location` (jar) e `<pasta do jar>/iped-graph-neo4j/` para `<caso>/plugins/`. Nada mais da pasta de plugins é copiado. Casos criados e nunca estendidos com o plugin disponível não recebem `plugins/`.

---

## 5. CaseLinkData (dados de vínculos do caso)

Localizações **inalteradas** (constantes de `GraphTask`): `iped/neo4j/csv/` (CSVs de nós/relacionamentos — `CSVS_PATH`) e `iped/neo4j/data/` (banco `graph.db` — `DB_DATA_PATH`).

| Estado | Definição | Como surge |
|---|---|---|
| `NONE` | Nem `csv/` nem `data/` existem. | Caso processado com a chave desligada ou sem o plugin; `--restart` sem o plugin (guard apaga `neo4j/`). |
| `PRESENT` | `data/` existe (e normalmente `csv/`). | Processamento com o plugin (CSVs no pipeline, banco em `finish()`); atualizado em `--append`/remoção com o plugin. |
| `STALE` (informal) | `PRESENT`, mas o caso foi modificado com `--ignore-missing-graph-plugin`. | Só por decisão explícita do perito; registrado em log/console, **não** persistido (Clarification 4). |

**Detecção para o guard (FR-008)**: `hasLinkData(output) = exists(output/neo4j/data) || exists(output/neo4j/csv)`.

**Multi-caso (UI)**: o grafo combinado continua em `<pasta dos casos>/iped-multicases/multicase-<hash>/graph` e só é construído quando o plugin está disponível.

---

## 6. Tipos de modelo do grafo (núcleo) e mapeamento para o plugin

| Tipo (núcleo, `iped.engine.graph`) | Substitui (Neo4j) | Implementação no plugin |
|---|---|---|
| `GraphLabel` (`name()`, `of(String)`, `equals/hashCode` por nome) | `org.neo4j.graphdb.Label` + `DynLabel` | Convertido para string de label Cypher / `Label.label(name)` no servidor. |
| `GraphRelationshipType` (`name()`, `of(String)`) | `RelationshipType` + `DynRelationshipType` | Idem. |
| `GraphEntity` (`getId()`, `getElementId()`, `hasProperty`, `getProperty`→`null`, `getPropertyKeys`, `getAllProperties`) | `Entity` (+ `NotFoundException`) | `BoltEntity` sobre `org.neo4j.driver.types.Entity`. |
| `GraphNode` (`getLabels(): Iterable<GraphLabel>`, `hasLabel`, `getDegree()`) | `Node` | `BoltNode` (grau = snapshot `COUNT { (n)--() }` carregado na consulta; usado por `GraphModel` para dimensionar nós e arestas). |
| `GraphRelationship` (`getType`, `getStartNode`, `getEndNode`, `getOtherNode`, `getNodes`) | `Relationship` | `BoltRelationship`. |
| `GraphPath` (`startNode`, `endNode`, `nodes`, `relationships`, `length`, `Iterable<GraphEntity>`) | `Path` | `BoltPath`. |
| `GraphQueryException` (unchecked, `message`) | `QueryExecutionException` | Wrapper de `org.neo4j.driver.exceptions.Neo4jException`. |
| `GraphPluginException` (checked) | — | Lançada por `validate()`. |

**Invariante de determinismo (Princípio IV)**: os CSVs produzidos por `GraphFileWriter` com `GraphLabel`/`GraphRelationshipType` são byte-a-byte iguais aos produzidos com `DynLabel`/`DynRelationshipType` para a mesma entrada (`join(labels)` e nomes de arquivo preservados) — coberto por teste golden (R-12).

---

## 7. Configuração e linha de comando

| Item | Valor/semântica |
|---|---|
| `enableGraphGeneration` (`IPEDConfig.txt`; `true` no perfil padrão, `false` em `fastmode`/`triage`) | "Gerar dados de vínculos **quando o plugin estiver disponível**". Chave ligada + plugin ausente ⇒ 1 `WARN`, etapa pulada. Inalterada nos arquivos distribuídos. |
| `conf/GraphConfig.json` | Inalterado; `post-generation-statements` seguem sendo Cypher executado pela pós-importação (no plugin). |
| `TaskInstaller.xml` (`iped.engine.graph.GraphTask`) | Inalterado; a classe permanece no núcleo. |
| `--ignore-missing-graph-plugin` (novo, booleano) | Permite `--append`/`--continue`/remoção de evidência em caso com dados de vínculos sem o plugin; gera aviso em log + console. Sem efeito quando o plugin está disponível. |

---

## 8. Mensagens novas (localização)

| Bundle | Chave | Uso |
|---|---|---|
| `iped-engine-messages*.properties` | `GraphPlugin.MissingForCaseModification` | Texto da recusa (FR-008), com o nome da flag. |
| `iped-engine-messages*.properties` | `GraphPlugin.ProceedingWithoutUpdate` | Aviso no console ao prosseguir com a flag. |
| `iped-desktop-messages*.properties` | `GraphPlugin.Notice.Title`, `GraphPlugin.Notice.Text` | Diálogo modeless FR-007 (caso com dados de vínculos, plugin ausente). |
| `iped-desktop-messages*.properties` | `GraphPlugin.Reason.NotInstalled`, `GraphPlugin.Reason.MultipleCopies`, `GraphPlugin.Reason.SpiMismatch` (`{0}` SPI do plugin, `{1}` SPI do IPED), `GraphPlugin.Reason.Incomplete`, `GraphPlugin.Reason.LoadError` | Motivo localizado interpolado em `GraphPlugin.Notice.Text` (sem texto técnico em inglês no diálogo; o detalhe completo vai só para o log). |

Mensagens de log técnicas (registro, task) ficam em inglês no código (constituição, fluxo de desenvolvimento §2).
