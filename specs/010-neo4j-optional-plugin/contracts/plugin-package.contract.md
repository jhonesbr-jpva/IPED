# Contrato — Pacote do plugin `iped-graph-neo4j` e instalação

**Feature**: 010-neo4j-optional-plugin · **Módulo produtor**: `iped-graph-neo4j` · **Artefato**: `iped-graph-neo4j-<versão>.zip`

## Layout do zip (raiz = conteúdo a extrair **dentro** da pasta de plugins)

```text
iped-graph-neo4j-<versão>.jar       # classpath principal (fat jar, driver + deps relocados em iped.graph.neo4j.shaded.*)
iped-graph-neo4j/
├── neo4j/                          # pilha isolada (nunca no classpath principal)
│   ├── iped-graph-server-<versão>.jar
│   ├── neo4j-*.jar … antlr4-runtime-4.13.x.jar … (motor Neo4j CE 5.26.0 + transitivas)
├── plugin.properties               # plugin.version=…  spi.version=1  iped.version=…
├── LICENSE.txt                     # GPL v3
├── ThirdParty.txt                  # avisos de terceiros do plugin
└── README.md                       # instalação, compatibilidade, remoção
```

## Manifesto do jar (`META-INF/MANIFEST.MF`)

| Entrada | Valor | Uso |
|---|---|---|
| `IPED-Graph-SPI-Version` | `1` | `getSpiVersion()` — critério de compatibilidade. |
| `Implementation-Version` | `${project.version}` | `getPluginVersion()` — log/diagnóstico. |
| `IPED-Target-Version` | `${project.version}` | `getBuiltForIpedVersion()` — informativo. |
| `Implementation-Title` | `iped-graph-neo4j` | `getDisplayName()`. |

## Conteúdo do jar

- Pacote `iped.graph.neo4j`: `Neo4jGraphServiceProvider`, `Neo4jGraphService`, `BoltEntity/BoltNode/BoltRelationship/BoltPath`, `Neo4jDatabaseGenerator`, `Neo4jImportRunner`, `Neo4jChildLauncher`.
- `META-INF/services/iped.engine.graph.GraphServiceProvider` → `iped.graph.neo4j.Neo4jGraphServiceProvider`.
- Dependências relocadas: `org.neo4j.driver` (mantido no pacote original — é a API usada pelo próprio plugin), `io.netty` → `iped.graph.neo4j.shaded.io.netty`, `reactor` → `iped.graph.neo4j.shaded.reactor`, `org.reactivestreams` → `iped.graph.neo4j.shaded.org.reactivestreams`. `slf4j-api` e `iped-engine` **excluídos** (fornecidos pelo IPED).
- Proibido: qualquer classe `iped.engine.*` ou `iped.app.*` dentro do jar (evita sombra de classes do núcleo).

## Resolução da home

`Neo4jGraphServiceProvider` localiza a home como `<pasta que contém o jar>/iped-graph-neo4j/` (via `ProtectionDomain` do próprio jar). `validate()` exige `neo4j/iped-graph-server-*.jar` e ao menos um `neo4j/neo4j-*.jar`. O lançador monta `-cp <home>/neo4j/*` para os processos filhos (`GraphServer`, `GraphPostImport`, `neo4j-admin`), preservando os `--add-opens` atuais.

## Instalação (FR-002, SC-003) — exatamente 3 passos

1. Obter `iped-graph-neo4j-<versão>.zip` (ativo do release do IPED, publicado ao lado do pacote principal).
2. Extrair o zip **dentro da pasta de plugins** da instalação — por padrão `<dist>/plugins/`, pasta irmã de `<dist>/iped-<versão>/` (`LocalConfig.txt` → `pluginFolder = ../plugins/`). Resultado: `<dist>/plugins/iped-graph-neo4j-<versão>.jar` e `<dist>/plugins/iped-graph-neo4j/`.
3. Reiniciar o IPED (processamento ou UI). Nenhuma edição de configuração; `enableGraphGeneration` já vem habilitada no perfil padrão.

Verificação: o log de processamento/UI contém `Graph plugin loaded: iped-graph-neo4j <versão> (SPI v1 …)`.

## Remoção (FR-015)

Apagar `iped-graph-neo4j-<versão>.jar` e a pasta `iped-graph-neo4j/` da pasta de plugins e reiniciar. Os dados de vínculos dos casos permanecem em disco (`<caso>/iped/neo4j/`), apenas não são usados.

## Atualização / compatibilidade (FR-011, Clarification 5)

- Antes de instalar outra versão, remover a anterior (duas cópias ⇒ `MULTIPLE_COPIES`, nenhuma carregada).
- O plugin é aceito se `IPED-Graph-SPI-Version` for igual à versão do SPI do IPED em execução; o `README.md` do plugin e as notas de release listam a versão do SPI de cada release.

## Cópia para o caso (FR-010)

Quando o plugin está `AVAILABLE` na criação de um caso, o `Manager` copia o jar e a pasta `iped-graph-neo4j/` para `<caso>/plugins/` (irmã de `<caso>/iped/`, que é o app root da cópia autocontida). Abrir o caso a partir da própria cópia (`IPED-SearchApp` do caso) resolve `../plugins/` para esse local.

## Construção (FR-014)

- `mvn -pl iped-graph-neo4j -am package` → `iped-graph-neo4j/target/iped-graph-neo4j-<versão>.zip`.
- O zip **não** é copiado para `target/release/`; `iped-app` não depende do módulo.
- CI publica o zip como artefato separado (`iped-graph-neo4j-<sha>`).

## Avisos de terceiros do plugin (`ThirdParty.txt`)

Neo4j Community Edition 5.26.0 (GPL v3); Neo4j Java Driver 5.26.0 (Apache 2.0); Netty (Apache 2.0); Project Reactor `reactor-core` (Apache 2.0); Reactive Streams (MIT-0); ANTLR 4 runtime 4.13.x (BSD-3); demais transitivas do motor conforme `mvn dependency:list` do módulo `iped-graph-server`. O `LICENSE.txt` do plugin é a GPL v3.
