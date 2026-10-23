# Research — Neo4j como plugin opcional do IPED

**Fase 0** do plano. Resolve as escolhas técnicas da feature `010-neo4j-optional-plugin`. Formato por item: **Decisão / Justificativa / Alternativas consideradas**.

> Base factual: levantamento direto do repositório em 2026-10-22 — `iped-engine/.../graph/*` (26 classes), `iped-app/.../graph/*` (9 classes com tipos Neo4j), `iped-graph-server/`, `iped-app/pom.xml` (execução `unpack-graph-server`), `Bootstrap`/`BootstrapUI`/`PluginConfig`/`Configuration` (mecanismo de plugins), `Manager` (cópia autocontida e remoção de evidência), `CmdLineArgsImpl`, `LocalConfig.txt`, POM pai (`plugin.dir`). As decisões 1–3 da seção **Clarifications** do [spec.md](spec.md) são premissas aqui.

---

## R-01. Divisão núcleo × plugin (modelo híbrido)

**Decisão**: o núcleo (`iped-engine` + `iped-app`) mantém **todo o módulo de análise de vínculos do IPED** — modelagem de entidades/relacionamentos (`GraphTask`, `GraphConfiguration`, `GraphFileWriter` → CSVs), as consultas de vínculos (`graph/links/*` + `.cypher`), a aba "Vínculos" (`iped.app.graph.*`) — reescrito contra **tipos próprios do IPED**. O plugin (`iped-graph-neo4j`) contém **só o que depende do Neo4j**: a implementação Bolt do serviço (`GraphServiceImpl` → `Neo4jGraphService`), os adaptadores `Bolt{Entity,Node,Relationship,Path}`, a importação em lote (`GraphImportRunner`), o lançador do processo isolado (`Neo4jChildLauncher`), a geração pós-importação (`GraphGenerator` → `Neo4jDatabaseGenerator`) e, embarcado numa pasta isolada, o módulo `iped-graph-server` com o motor Neo4j 5.26 + antlr 4.13.

**Justificativa**: Clarification 1. Inventário mostra que o Neo4j "vaza" para o núcleo apenas por: (a) `org.neo4j.graphdb.Label`/`RelationshipType` em `GraphTask`/`GraphFileWriter` (via `DynLabel`/`DynRelationshipType`, 100% substituíveis por tipos próprios); (b) `Node`/`Relationship`/`Path`/`Entity` nos listeners do `GraphService` e nos 9 arquivos da UI; (c) `NotFoundException`/`QueryExecutionException` em 3 pontos da UI; (d) o cliente Bolt e a importação. Nada disso exige mover a modelagem ou a UI.

**Alternativas**: (A) plugin completo (mover modelagem + UI) — descartado na Clarification 1 (refatoração desproporcional). (B) plugin só de bibliotecas, núcleo compilando contra a API Neo4j em escopo `provided` (modelo do Stanford CoreNLP/NER) — descartado: mantém referências a código GPL do Neo4j no fonte/construção do núcleo, contrariando FR-001.

---

## R-02. Ponto de extensão (SPI): forma e localização

**Decisão**: o ponto de extensão fica em `iped-engine`, pacote `iped.engine.graph` (onde `GraphService` já vive), composto por:

- `GraphServiceProvider` — **descoberto via `java.util.ServiceLoader`** (`META-INF/services/iped.engine.graph.GraphServiceProvider`). Métodos: `int getSpiVersion()`, `String getPluginVersion()`, `String getBuiltForIpedVersion()`, `void validate() throws GraphPluginException` (verifica integridade do pacote, ex.: pasta isolada presente), `GraphService createGraphService()`, `GraphDatabaseGenerator createDatabaseGenerator()`. Constante `CURRENT_SPI_VERSION = 1` no núcleo.
- `GraphService` — interface existente, com os tipos Neo4j trocados pelos tipos próprios (abaixo); contrato funcional inalterado (mesmos métodos, mesma semântica).
- `GraphDatabaseGenerator` — interface nova que absorve o contrato de `GraphGenerator.generate(ImportListener, File dbHome, File... csvDirs)`; `ImportListener` migra para ela.
- Tipos de modelo próprios: `GraphLabel` (substitui `Label`/`DynLabel`), `GraphRelationshipType` (substitui `RelationshipType`/`DynRelationshipType`), `GraphEntity` (contêiner de propriedades: `getId()`, `getElementId()`, `hasProperty`, `getProperty` → `null` se ausente, `getPropertyKeys`, `getAllProperties`), `GraphNode` (`getLabels(): Iterable<GraphLabel>`, `hasLabel`, `getDegree()` — snapshot de grau que `GraphModel` usa em três pontos para dimensionar nós e arestas; o plugin fornece o `COUNT { (n)--() }` já existente em `BoltNode`), `GraphRelationship` (`getType`, `getStartNode`, `getEndNode`, `getOtherNode`), `GraphPath` (`startNode`, `endNode`, `nodes`, `relationships`, `length`, `Iterable<GraphEntity>`), e `GraphQueryException` (unchecked; substitui `QueryExecutionException`).
- Fachada mantida: `GraphServiceFactoryImpl.getInstance().getGraphService()` continua existindo (14 pontos de uso na UI + `Manager`), delegando ao registro; lança `IllegalStateException` se o plugin não estiver disponível.

**Justificativa**: `GraphService` já é a interface que a UI consome (Clarification 1); só os *tipos* precisam ser neutros. `ServiceLoader` é o mecanismo padrão do JDK, já usado no projeto (`SearchLinksQuery`, detectores Tika, validadores de regex), e funciona com a pasta de plugins atual (`pluginFolder/*` entra no classpath das JVMs filhas de processamento **e** de UI, pois `BootstrapUI extends Bootstrap`). Manter o SPI em `iped-engine` evita mexer em `iped-api` (Princípio I) — o plugin é do mesmo repositório e versão, então depender de `iped-engine` (escopo `provided`) é aceitável.

**Alternativas**: (a) SPI em `iped-api` — rejeitado por ora: exigiria mover `GraphService` + listeners + tipos para a API pública (ciclo de deprecação, Princípio I) sem ganho prático enquanto o único plugin é interno; pode ser promovido depois. (b) Reflexão/`Class.forName` com nome fixo (modelo do NER) — rejeitado: não suporta validação/versão/múltiplas cópias de forma limpa. (c) Manter `Label`/`RelationshipType` do Neo4j com dependência `provided` — rejeitado (R-01).

---

## R-03. Registro do plugin: descoberta, validação, estados e log

**Decisão**: classe `GraphPluginRegistry` (singleton, `iped.engine.graph`), resolvida **uma única vez por JVM** no primeiro acesso, chamada explicitamente nos pontos de entrada que consomem o grafo: processamento (`iped.app.processing.Main`, após `loadConfigurables`) e UI (`AppMain`, após `loadConfigurables`). Algoritmo:

1. `ServiceLoader.load(GraphServiceProvider.class)` (classloader da aplicação; os jars de `pluginFolder/*` já estão no classpath via `Bootstrap`).
2. Antes de instanciar, contar os recursos `META-INF/services/iped.engine.graph.GraphServiceProvider` visíveis (`ClassLoader.getResources`) por **jar de origem distinto** — `ServiceLoader` ignora provedores duplicados pelo nome da classe, então duas versões do mesmo plugin produziriam um único provedor sem essa contagem. 0 recursos → estado **ABSENT**. ≥ 2 jars → **REJECTED(MULTIPLE_COPIES)**, nenhum carregado (FR-012). Erro ao instanciar (`ServiceConfigurationError`, `NoClassDefFoundError`, `LinkageError`) → **REJECTED(LOAD_ERROR)**.
3. Um provedor: `getSpiVersion() != CURRENT_SPI_VERSION` → **REJECTED(SPI_MISMATCH)** com as duas versões (FR-011); senão `validate()` — falha → **REJECTED(INCOMPLETE)** com o motivo (FR-012).
4. Sucesso → **AVAILABLE**; instancia `GraphService`/`GraphDatabaseGenerator` sob demanda.

Log único no momento da resolução: `INFO Graph plugin loaded: <nome> <versão do plugin> (SPI v<n>, built for IPED <v>) from <jar>` / `INFO Graph plugin not installed (plugin folder: <pasta>)` — nível INFO porque a ausência é o estado padrão da distribuição e FR-004/SC-002 contam "exatamente um aviso" (o do `GraphTask`) — / `ERROR Graph plugin rejected (<motivo>): <detalhe>` (FR-003). A Web API e a ferramenta de hashes **não** consomem o ponto de extensão, logo não resolvem o registro (interpretação de FR-003: "todo ponto de entrada que pode usar o grafo").

**Justificativa**: estados explícitos tornam FR-003/FR-011/FR-012 testáveis por unidade (provedores de teste em classloader isolado). Resolução única evita custo de inicialização (SC-006) e garante "um aviso só" (FR-004).

**Alternativas**: resolver preguiçosamente no primeiro uso do grafo — rejeitado: o aviso precisa sair no início do processamento e a UI precisa decidir a montagem da aba antes de abrir o caso.

---

## R-04. Módulo e pacote do plugin

**Decisão**: novo módulo Maven **`iped-graph-neo4j`** (listado no POM pai depois de `iped-graph-server`; construído por padrão no reactor), que produz `iped-graph-neo4j-<versão>.zip` com o layout:

```text
iped-graph-neo4j-<versão>.jar       # entra no classpath principal (pluginFolder/*): provedor SPI, serviço Bolt,
                                    #   adaptadores, importação, lançador + neo4j-java-driver e deps (shaded, R-05)
iped-graph-neo4j/                   # "home" do plugin (subpasta: NÃO entra no classpath principal)
├── neo4j/*.jar                     # pilha isolada: iped-graph-server-<v>.jar + Neo4j CE 5.26.0 + antlr 4.13.x
│                                   #   (conteúdo do zip iped-graph-server, antes descompactado em lib/neo4j/)
├── plugin.properties               # plugin.version, spi.version, iped.version (informativo, filtrado no build)
├── LICENSE.txt                     # GPL v3 (texto)
├── ThirdParty.txt                  # Neo4j CE, driver, netty, reactor, reactive-streams, antlr…
└── README.md                       # instalação, compatibilidade, remoção
```

O manifesto do jar carrega `IPED-Graph-SPI-Version`, `Implementation-Version` e `IPED-Target-Version`; o provedor lê o próprio manifesto para responder `getSpiVersion()`/`getPluginVersion()`/`getBuiltForIpedVersion()`. `validate()` localiza a home como `<pasta do jar>/iped-graph-neo4j/` e exige `neo4j/iped-graph-server-*.jar` + `neo4j/neo4j-*.jar`. O módulo `iped-graph-server` **permanece** como está (código e POM); seu zip passa a ser consumido pelo módulo do plugin em vez de pelo `iped-app`.

**Justificativa**: Clarification 2 (mesmo repositório, módulo próprio, ativo separado). O layout "um jar + uma pasta" respeita o mecanismo atual de plugins sem alterá-lo: `Bootstrap` adiciona `pluginFolder/*` (só arquivos de primeiro nível) ao classpath, então a subpasta mantém o motor isolado — exatamente a razão de existir do `lib/neo4j/` da feature 003. `Configuration.addPluginJarsToConfigurationLookup` já tolera diretórios na pasta de plugins.

**Alternativas**: (a) transformar `iped-graph-server` no plugin — rejeitado: o servidor compila contra o motor completo e o cliente contra o driver + `iped-engine`; separar os dois classpaths em um módulo só complica a montagem. (b) Estrutura `plugins/<nome>/lib/*.jar` com mudança no carregador de plugins — rejeitado: muda o mecanismo genérico sem necessidade. (c) Construir o módulo só sob perfil Maven — rejeitado para já: construir sempre garante que mudanças no SPI quebrem o build do plugin imediatamente (motivo do "mesmo repositório"); o POM do `iped-app` não depende do plugin, então FR-014 é atendido. Pode virar perfil depois se o download do Neo4j pesar no CI.

---

## R-05. Driver Bolt e suas dependências no classpath principal

**Decisão**: o jar do plugin é um **fat jar via `maven-shade-plugin`** com `neo4j-java-driver` 5.26.0 e suas dependências obrigatórias (`reactive-streams`, `netty-handler` + módulos netty, `netty-tcnative-classes`, `reactor-core`) **relocadas** para `iped.graph.neo4j.shaded.*`, com `ServicesResourceTransformer` e exclusão de `slf4j-api` (fornecido pelo IPED). `iped-engine` entra como `provided`.

**Justificativa**: hoje esses jars ficam soltos em `lib/`; depois da remoção, o plugin precisa levá-los. Um único jar (a) mantém o "soltar na pasta" (FR-002), (b) torna trivial detectar "mais de uma cópia" (FR-012), (c) elimina colisão de versões com qualquer netty/reactor que outra dependência venha a trazer para `lib/` (hoje nenhum POM do repositório referencia netty — a relocação é prevenção, não correção).

**Riscos/validação**: netty usa reflexão e recursos `META-INF/services` que a relocação precisa preservar; tarefa de validação obrigatória: iniciar o servidor e executar uma consulta a partir do pacote montado (teste de integração gated). **Plano B** documentado: distribuir os jars do driver soltos ao lado do jar do plugin (sem relocação) — funciona com o mecanismo atual, ao custo de mais arquivos.

---

## R-06. Pasta de plugins: resolução real e cópia para o caso (FR-010)

**Decisão**: `pluginFolder = ../plugins/` é resolvido contra o **app root** (`IPED_APP_ROOT` = pasta do `LocalConfig.txt`), logo a pasta de plugins é **irmã** da pasta da aplicação — no release, `<dist>/plugins/` ao lado de `<dist>/iped-<versão>/` (o POM pai define `plugin.dir = ${release.dir}/../plugins`); num caso, `<caso>/plugins/` ao lado de `<caso>/iped/` (o app root da UI/relatório é `<caso>/iped`). A cópia autocontida (`Manager`, bloco que copia `lib/`, `jre/`, `tools/`…) passa a copiar, **quando o registro está AVAILABLE**, o jar e a home do plugin da pasta de plugins da instalação para `<caso>/plugins/`; em `--append`, se o plugin está disponível e `<caso>/plugins/` ainda não o contém, a mesma cópia é feita (caso criado sem o plugin e estendido com ele). Nenhuma reescrita de `LocalConfig.txt` é necessária (a mesma regra relativa resolve o destino). Os demais conteúdos da pasta de plugins **não** são copiados (comportamento atual preservado).

**Justificativa**: Clarification 3 + verificação empírica da resolução de caminho. Copiar só o plugin de grafo evita inflar casos com CoreNLP/modelos e mantém a semântica "plugins são por instalação" para o resto.

**Alternativas**: copiar a pasta de plugins inteira — rejeitado (tamanho e redistribuição de outros componentes); colocar em `<caso>/iped/plugins/` reescrevendo `pluginFolder` — rejeitado (mexe em `LocalConfig.clearLocalParameters` sem necessidade).

**Nota de documentação**: o `CLAUDE.md` raiz desenha `plugins/` *dentro* de `iped-<versão>/` — está incorreto; corrigir na feature (tarefa de docs).

---

## R-07. Degradação no processamento (FR-004) e `--restart`

**Decisão**: `GraphTask.init()` passa a calcular `enabled = config.isEnabled() && GraphPluginRegistry.get().isAvailable()`. Quando a chave está habilitada e o plugin não, emite **um único** `WARN` (flag estática — há uma instância da task por worker) e não cria `GraphFileWriter` (logo não cria `iped/neo4j/csv`). `GraphTask.commit()` já é no-op sem writer. `finishGraphGeneration()` obtém o gerador via `GraphPluginRegistry.get().getProvider().createDatabaseGenerator()`. No `--restart` sem plugin, o guard (R-08) **apaga `<saída>/neo4j`** (CSVs e banco do processamento abortado) para que o caso resultante não carregue dados de vínculos órfãos.

**Justificativa**: FR-004 exige "nenhum dado de vínculos criado" e "exatamente um aviso"; a `TaskInstaller.xml` continua listando `GraphTask` (classe permanece no núcleo), então perfis antigos seguem válidos sem edição (FR-005).

**Alternativas**: tolerar classe ausente na `TaskInstaller` — desnecessário no modelo híbrido.

---

## R-08. Bloqueio de modificação do caso sem o plugin (FR-008) e flag de override

**Decisão**: nova classe `GraphPluginGuard` (`iped.engine.graph`), invocada por `Manager.process()` **antes de qualquer alteração** (antes de `loadExistingData`/`removeEvidence`/cópia). Regra: se `!args.isYaraOnly()` **e** `(args.isAppendIndex() || args.isContinue() || args.getEvidenceToRemove() != null)` **e** o caso tem dados de vínculos (`<saída>/neo4j/data` **ou** `<saída>/neo4j/csv` existe) **e** o plugin não está AVAILABLE **e** a flag de override não foi passada → lança `IPEDException` com mensagem localizada (bundle `iped-engine-messages*`): *"This case contains link analysis (graph) data, but the graph plugin is not installed, so the graph cannot be updated. Install the plugin or re-run with `--ignore-missing-graph-plugin` to proceed without updating the graph."* (saída não-zero, mesmo caminho das demais falhas de configuração). Com a flag: a operação prossegue e o guard emite `WARN` no log **e** no console (`LOGGER.log(CONSOLE, …)`) de que os dados de vínculos não foram atualizados. Em `removeEvidence`, a exclusão no banco de grafos é pulada (com o mesmo aviso) mas a limpeza dos CSVs (`GraphFileWriter.removeDeletedRelationships`, código do núcleo) continua. `--restart` e `--yara-only` não são bloqueados — atenção: `--yara-only` faz `isContinue()` retornar `true` (feature 001, `SkipCommitedTask`), por isso o guard testa `isYaraOnly()` antes de tudo.

Flag: `--ignore-missing-graph-plugin` (booleana) em `CmdLineArgsImpl`, exposta por `iped.engine.CmdLineArgs#isIgnoreMissingGraphPlugin()` (interface em `iped-engine`, não em `iped-api`).

**Justificativa**: Clarification 4. Nome explícito descreve exatamente o que a flag faz (ignorar a ausência do plugin), sem sugerir que desliga o grafo quando o plugin existe.

**Alternativas**: `--skip-graph-update` — rejeitado (semântica ambígua com plugin presente); marca persistente — descartada na Clarification 4.

---

## R-09. Degradação na UI (FR-006/FR-007) e aviso único

**Decisão**: em `App`, `appGraphAnalytics`, `graphDock` e o filtro `FilterSelectedEdges` só são criados/registrados quando `GraphPluginRegistry.get().isAvailable()`; `MenuClass` (item "adicionar ao grafo"), `MenuListener` e `UICaseDataLoader` passam a tratar `appGraphAnalytics == null`. O código em `App` já trata `graphDock == null` em dois pontos, o que indica o caminho. Aviso FR-007: classe nova `GraphPluginNotice` (`iped.app.ui`) — `JDialog` **modeless** (`ModalityType.MODELESS`), exibido via `SwingUtilities.invokeLater` **uma vez por sessão** quando o plugin não está disponível e **algum** `IPEDSource` atômico do caso (inclui multi-caso) tem `iped/neo4j/data`; texto em `iped-desktop-messages*.properties` (PT-BR + EN, mais os demais idiomas existentes quando possível), com o caminho da documentação do plugin.

**Justificativa**: FR-006 exige que nada de grafo seja oferecido; criar o painel condicionalmente é a forma mais simples e evita qualquer `NoClassDefFoundError` tardio. Diálogo modeless atende "não bloqueante, sem diálogo modal de erro" sem exigir um novo componente de notificação.

**Alternativas**: manter a aba com painel informativo — descartado na spec (premissa "aba oculta"); mensagem só no log — não atende FR-007.

---

## R-10. Garantia "zero Neo4j no núcleo" (SC-001, FR-014)

**Decisão**: três camadas. (1) `maven-enforcer-plugin` com `bannedDependencies` para `org.neo4j:*` e `org.neo4j.driver:*` nos POMs de `iped-engine` e `iped-app` (falha o build se qualquer dependência transitiva reaparecer). (2) Remoção da dependência zip `iped-graph-server` e da execução `unpack-graph-server` do `iped-app/pom.xml` (e do comentário `excludeTypes`). (3) Passo de CI que falha se existir qualquer arquivo com `neo4j` no nome sob `target/release/` e que publica `iped-graph-neo4j/target/*.zip` como artefato separado. O `quickstart.md` traz o inventário manual equivalente.

**Alternativas**: só verificação manual — insuficiente para SC-001 ("inventário automatizado").

---

## R-11. Licenciamento e documentação (FR-013)

**Decisão**: `ThirdParty.txt` do núcleo deixa de descrever Neo4j/driver/antlr 4.13 como componentes **embarcados** (`lib/`, `lib/neo4j/`) e passa a ter uma seção própria "Optional plugin iped-graph-neo4j (built from this repository, NOT bundled in the standard distribution)" registrando Neo4j CE 5.26.0 (GPLv3), neo4j-java-driver 5.26.0 (Apache 2.0), Netty (Apache 2.0), reactor-core (Apache 2.0), reactive-streams (MIT-0) e antlr4-runtime 4.13.x (BSD-3), com a nota "distributed separately with its own LICENSE.txt and ThirdParty.txt". Motivo: a constituição exige toda dependência do repositório registrada em `ThirdParty.txt` com licença em `licenses/` — o módulo do plugin continua neste repositório; `licenses/MIT-0.txt` é adicionado (reactive-streams). O plugin ganha `LICENSE.txt` (GPL v3 — o pacote combina código do IPED com o Neo4j CE GPLv3) e `ThirdParty.txt` próprio (Neo4j CE 5.26.0 GPLv3; neo4j-java-driver 5.26.0 Apache-2.0; netty Apache-2.0; reactor-core Apache-2.0; reactive-streams MIT-0; antlr4-runtime BSD-3; demais transitivas do motor). O `LICENSE.txt` do IPED já concede permissão para combinar com plugins "regardless of the license terms of these plugin modules" — nada a mudar nele. Atualizar `README.md` (lista de funcionalidades → "via plugin opcional"), `ReleaseNotes.txt`, `CLAUDE.md` (raiz §2/§3/§6/§8/§10/§13 + correção da pasta de plugins; `iped-engine` seção graph; `iped-app` seção graph) e criar `iped-graph-neo4j/CLAUDE.md` + `README.md` (instalação/compatibilidade/remoção).

---

## R-12. Estratégia de testes

**Decisão**:
- **Unitários (`iped-engine`)**: `GraphPluginRegistryTest` — provedores de teste carregados por `URLClassLoader` temporário cobrindo ABSENT, AVAILABLE, MULTIPLE_COPIES, SPI_MISMATCH, INCOMPLETE, LOAD_ERROR; `GraphPluginGuardTest` — matriz (modo × dados de vínculos × plugin × flag) incluindo `--restart` apagando `neo4j/`; `GraphFileWriterTest` — CSV gerado com `GraphLabel`/`GraphRelationshipType` idêntico ao formato anterior (arquivo golden com cabeçalhos/nomes de arquivo).
- **Unitários (`iped-graph-neo4j`)**: `Neo4jGraphServiceProviderTest` — `getSpiVersion()` igual a `CURRENT_SPI_VERSION`; `validate()` aceita/rejeita layouts montados em pasta temporária; manifesto lido corretamente.
- **Integração gated** (`assumeTrue` em `IPED_GRAPH_PLUGIN_HOME`): a partir do zip montado, iniciar o `GraphServer` pelo `Neo4jChildLauncher` do plugin, executar `RETURN 1` via driver relocado e encerrar — valida R-05.
- **Manual (quickstart)**: matriz SC-001..SC-009 (inventário; processamento sem/com plugin e comparação forense/grafo; instalação ≤ 3 passos; smoke da UI; desinstalação; perfis antigos; tempo de inicialização).

---

## R-13. Cypher e consultas no núcleo

**Decisão**: as consultas Cypher permanecem no núcleo como **dados opacos** — recursos `graph/links/*.cypher` (SPI `SearchLinksQuery`), `GraphService.searchPaths(String query, …)`, `advancedSearch(String)` e `post-generation-statements` do `GraphConfig.json` (executados pela pós-importação, que fica no plugin). O contrato do SPI documenta que o dialeto é definido pelo provedor corrente (Cypher 5 para o plugin Neo4j).

**Justificativa**: são textos de consulta, não código nem biblioteca do Neo4j; movê-los traria churn sem efeito sobre FR-001. Mantém a "busca avançada" da UI intacta.

---

## R-14. Mapeamento de exceções e propriedades ausentes

**Decisão**: `QueryExecutionException` (Neo4j) → `GraphQueryException` (núcleo, unchecked); o plugin converte `org.neo4j.driver.exceptions.*` em `GraphQueryException` preservando a mensagem (a UI já a exibe em `JOptionPane`). `NotFoundException` desaparece: `GraphEntity.getProperty(key)` retorna `null` quando a propriedade não existe; `GraphModel.getLabel` e `ExportLinksWorker` trocam o `try/catch` por teste de nulo.

---

## Pendências encaminhadas ao plano/tarefas (não bloqueiam)

- Validar empiricamente a relocação do netty (R-05) — primeira tarefa do módulo do plugin; plano B pronto.
- Decidir, nas tarefas de docs, se `plugins/Readme.txt` do release menciona o plugin de grafo (sim, uma linha).
- Confirmar no `Manager` o ponto exato de inserção do guard (antes da linha que trata `isContinue()/isRestart()` — hoje `Manager.process()` ~l.251) durante a implementação.
