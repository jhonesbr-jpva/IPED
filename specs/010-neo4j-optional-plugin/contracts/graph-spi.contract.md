# Contrato — Ponto de extensão do serviço de grafo (SPI)

**Feature**: 010-neo4j-optional-plugin · **Módulo**: `iped-engine`, pacote `iped.engine.graph` · **Versão do SPI**: 1

## Objetivo

Permitir que um plugin instalado na pasta de plugins forneça a implementação do serviço de grafo (consulta, ciclo de vida e geração do banco), sem que o núcleo referencie qualquer classe do Neo4j (FR-001, FR-009, FR-011).

## Descoberta

- Mecanismo: `java.util.ServiceLoader<GraphServiceProvider>` no classloader da aplicação. O plugin registra sua implementação em `META-INF/services/iped.engine.graph.GraphServiceProvider`.
- Pré-condição: o jar do plugin está em `pluginFolder` (resolvido por `PluginConfig`), que `Bootstrap` já adiciona ao classpath das JVMs filhas de processamento e de UI.
- Quem resolve: `GraphPluginRegistry.get()` — chamado em `iped.app.processing.Main` (após `loadConfigurables`) e em `AppMain` (após `loadConfigurables`). Resolução única por JVM.

## Interface `GraphServiceProvider`

```java
package iped.engine.graph;

public interface GraphServiceProvider {
    int CURRENT_SPI_VERSION = 1;                 // versão exposta pelo núcleo

    int getSpiVersion();                         // versão implementada pelo plugin
    String getPluginVersion();                   // informativo (Implementation-Version)
    String getBuiltForIpedVersion();             // informativo (IPED-Target-Version)
    String getDisplayName();                     // ex.: "iped-graph-neo4j"

    void validate() throws GraphPluginException; // integridade do pacote instalado

    GraphService createGraphService();
    GraphDatabaseGenerator createDatabaseGenerator();
}
```

## Interface `GraphService` (existente; tipos neutralizados)

Mesmos métodos e semântica de hoje. Diferenças de assinatura:

| Antes (Neo4j) | Depois (núcleo) |
|---|---|
| `List<org.neo4j.graphdb.Node> search(Label, Map)` | `List<GraphNode> search(GraphLabel, Map<String,Object>)` |
| `void search(Label, Map, NodeQueryListener, String...)` | `void search(GraphLabel, Map<String,Object>, NodeQueryListener, String...)` |
| `NodeQueryListener.nodeFound(Node)` | `nodeFound(GraphNode)` |
| `EdgeQueryListener.edgeFound(Relationship)` | `edgeFound(GraphRelationship)` |
| `LinkQueryListener.linkFound(Node, Node)` | `linkFound(GraphNode, GraphNode)` |
| `PathQueryListener.pathFound(Path)` | `pathFound(GraphPath)` |
| `NodeEdgeQueryListener`, `ConnectionQueryListener`, `LabelQueryListener`, `FreeQueryListener` | Idem, com os tipos neutros onde houver `Node`/`Relationship`. |
| Exceções de consulta (`QueryExecutionException`) | `GraphQueryException` (unchecked) |

Métodos que recebem **texto de consulta** (`searchPaths(String, Map, PathQueryListener)`, `advancedSearch(String, FreeQueryListener)`) continuam opacos: o dialeto é o do provedor corrente (Cypher 5 para o plugin Neo4j). O núcleo não interpreta esse texto.

## Interface `GraphDatabaseGenerator`

```java
public interface GraphDatabaseGenerator {
    interface ImportListener { void output(String line); }
    boolean generate(ImportListener listener, File dbHome, File... csvFolders) throws IOException;
}
```

Semântica igual à de `GraphGenerator.generate` da 4.3.1: importação em lote dos CSVs + operações pós-importação (statements configurados + agrupamento de contatos). Executa fora do processo principal (FR-009).

- **Entrada**: cada pasta de `csvFolders` está no formato do `GraphFileWriter` ([data-model.md §5](../data-model.md)), com seus arquivos `import-tool-args-*` e pares cabeçalho/dados. O provedor **MUST** aceitar linhas de dados com menos campos que o cabeçalho e tratar os campos finais ausentes como propriedade ausente: é assim que o núcleo grava quando uma propriedade surge no meio do processamento, e era o comportamento do Neo4j 4.4 até a 4.3.1. Exigências de formato do motor são resolvidas no provedor, sem mudança no núcleo.
- **Escrita nas pastas**: o provedor pode normalizar os CSVs **no lugar**, sem mudar o conteúdo lógico (regra também escrita no Javadoc de `GraphDatabaseGenerator.generate`). O provedor Neo4j 5 completa as linhas curtas antes do `neo4j-admin` ([research.md R-15](../research.md)), e o `neo4j-admin` grava `import.report` na pasta de trabalho (`<dbHome>/csv`). O núcleo só passa pastas graváveis: `<caso>/iped/neo4j/csv` no processamento, ou cópias preparadas em `iped-multicases/multicase-<hash>/` no multi-caso, de modo que os casos de origem não são alterados.
- **Retorno**: `true` se a importação concluiu (a pós-importação roda em seguida); `false` se falhou. Na falha, o provedor registra `ERROR` (`Error generating database.` no provedor Neo4j) e o chamador segue: a `GraphTask` não aborta o processamento (exit 0, como na 4.3.1), mas `dbHome` pode ficar com um banco inutilizável.
- Esclarecimento sem mudança de assinatura: `CURRENT_SPI_VERSION` continua **1**.

## Tipos de modelo

`GraphLabel`, `GraphRelationshipType`, `GraphEntity`, `GraphNode`, `GraphRelationship`, `GraphPath`, `GraphQueryException`, `GraphPluginException` — ver [data-model.md §6](../data-model.md). Regras: `GraphEntity.getProperty(key)` retorna `null` quando ausente (nunca lança); `GraphLabel`/`GraphRelationshipType` têm igualdade por nome; `GraphNode.getDegree()` devolve o grau total do nó como snapshot do momento da consulta (0 se desconhecido) — consumido por `GraphModel` para dimensionar nós e arestas.

## Regras do registro (`GraphPluginRegistry`)

| Situação | Estado | Log (uma vez) |
|---|---|---|
| Nenhum recurso `META-INF/services/iped.engine.graph.GraphServiceProvider` no classpath | `ABSENT` | `INFO Graph plugin not installed (plugin folder: <path>)` (INFO: estado padrão da distribuição; não conta como aviso para FR-004/SC-002) |
| ≥ 2 jars contendo esse recurso (contagem por URL via `ClassLoader.getResources`, **antes** do `ServiceLoader`, que deduplica provedores pelo nome da classe) | `REJECTED(MULTIPLE_COPIES)` | `ERROR Graph plugin rejected (multiple copies): <jar1>, <jar2>` |
| Erro ao instanciar | `REJECTED(LOAD_ERROR)` | `ERROR Graph plugin rejected (load error): <exceção>` |
| `getSpiVersion() != 1` | `REJECTED(SPI_MISMATCH)` | `ERROR Graph plugin rejected (SPI mismatch): plugin SPI v<p> (<nome> <versão>), core SPI v1 (IPED <versão>)` |
| `validate()` falha | `REJECTED(INCOMPLETE)` | `ERROR Graph plugin rejected (incomplete): <detalhe>` |
| OK | `AVAILABLE` | `INFO Graph plugin loaded: <nome> <versão> (SPI v1, built for IPED <v>) from <jar>` |

API pública mínima: `static GraphPluginRegistry get()`, `boolean isAvailable()`, `GraphPluginState getState()`, `Optional<GraphPluginRejection> getRejection()`, `String getDetail()`, `GraphServiceProvider getProvider()`, `GraphService getGraphService()` e `GraphDatabaseGenerator getDatabaseGenerator()` (instâncias cacheadas por JVM; todos lançam `IllegalStateException` se não disponível), `Path getPluginJar()`, `Path getPluginHome()`, `static void resetForTesting(ClassLoader)` (somente testes).

Fachada preservada: `GraphServiceFactoryImpl.getInstance().getGraphService()` delega ao registro (cache de uma instância por JVM) e lança `IllegalStateException("Graph plugin not available")` quando `!isAvailable()`.

## Versionamento

- `CURRENT_SPI_VERSION` é incrementado quando qualquer assinatura acima muda de forma incompatível. Mudanças aditivas com `default` methods não incrementam.
- O plugin declara a versão no manifesto (`IPED-Graph-SPI-Version`); o provedor lê o próprio manifesto.
- Números de release não participam da compatibilidade (Clarification 5).

## Testes de contrato

- `GraphPluginRegistryTest` (iped-engine): cada linha da tabela de estados, com provedores de teste em `URLClassLoader` temporário.
- `Neo4jGraphServiceProviderTest` (iped-graph-neo4j): `getSpiVersion() == CURRENT_SPI_VERSION`; `validate()` sobre layouts válidos/incompletos em pasta temporária.
- `Neo4jImportRunnerTest` (iped-graph-neo4j): regra de entrada do `GraphDatabaseGenerator` — linhas curtas completadas byte a byte (terminadores, NUL, vírgula entre aspas), arquivos completos e vazios intocados, cabeçalho/dados resolvidos a partir dos argumentos com prefixo de subpasta (multi-caso), contagem de campos com aspas.
- `Neo4jImportRunnerIT` (iped-graph-neo4j, gated, perfil `graph-it`): a mesma regra contra o importador real — os campos completados são importados como propriedade ausente, nunca como texto vazio.
