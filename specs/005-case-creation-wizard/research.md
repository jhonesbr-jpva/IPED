# Research — Criação e Abertura de Casos na GUI RCP

**Feature**: `005-case-creation-wizard` | **Date**: 2026-06-16
**Input**: [spec.md](spec.md) | Constituição v1.2.0 | Plataforma da feature 004

Decisões que resolvem os pontos técnicos em aberto do Technical Context. Cada decisão
registra alternativas consideradas. Nada aqui altera o "o quê" da spec. Esta feature
**estende a UI da 004** — muitas decisões reusam as da 004 (citadas como R-004.Rx).

---

## R1. Lançamento do processamento: subprocesso `Bootstrap`, sem embutir o engine

**Decision**: A UI RCP (processo Equinox) **lança o processamento como processo
separado**, executando o mesmo `iped.app.bootstrap.Bootstrap` (`iped.jar`) que o CLI
usa hoje, com uma linha de comando montada a partir da seleção do wizard
(`-d`, `-o`, `-profile`, `-tz`, `-p`, `-l`, `--addowner`, `--append`/`--continue`/
`--restart`, etc.). `Bootstrap` segue responsável por montar o classpath dinâmico +
JVM filha + descoberta da janela de progresso SWT (já implementada na 004, R-004.R10).
A UI apenas monta argumentos, dispara o processo e observa o ciclo de vida (exit code,
near-live).

**Rationale**: Reaproveita integralmente a launch path testada (classpath, `--add-opens`
Java 21, `-Xmx`, splash/progress) sem duplicá-la; mantém o **isolamento de processo**
(Princípio V) que motivou a arquitetura da 004 (R-004.R9/R14); **zero mudança no
engine** (FR-028). Reconcilia FR-020 (aposentar a porta `iped.exe` como UX de criação)
com FR-021 (motor headless permanece): o `Bootstrap`/`iped.jar` continua existindo
como motor; o que muda é que a **criação interativa** passa a ser disparada pela UI, e
não por terminal/atalho `iped.exe`.

**Alternatives considered**:
- *Invocar `Manager` (engine) in-process no Equinox* — rejeitado: acopla o classloader
  OSGi ao classpath plano do engine na mesma JVM e perde o isolamento de crash
  (mesmíssima rejeição da R-004.R9).
- *Reimplementar a montagem de classpath/JVM dentro da UI* — rejeitado: duplica a
  lógica frágil do `Bootstrap` (UNO, plugins, TSK, `--add-opens`) e diverge no tempo.

## R2. "Retire iped.exe" — escopo preciso e reconciliação FR-020 × FR-021

**Decision**: Interpretar a aposentadoria como **retirar `iped.exe` como porta de
entrada promovida para criar casos**. O motor de processamento (`iped.jar`/`Bootstrap`,
invocável por `java -jar` ou por um launcher de console) **permanece distribuído e
inalterado** para automação/servidores (FR-021, Clarifications Q1). No empacotamento do
cut-over, o shim `iped.exe` (launch4j) deixa de ser apresentado como o caminho de
criação; documentação e atalhos passam a apontar para a UI. Decidir no `tasks`/cut-over
se o shim é removido ou mantido como entry headless documentado — é detalhe de
empacotamento, não de comportamento.

**Rationale**: No código atual `iped.exe`→`iped.jar` é uma **CLI** (exige `-d`/`-o`;
`CmdLineArgsImpl` lança erro sem datasource) — não há um "launcher gráfico" separado a
remover. A spec usa "lançador gráfico" no sentido de *porta de entrada de criação*; a
reconciliação aqui evita contradição entre FR-020 e FR-021.

**Alternatives considered**:
- *Remover toda a CLI de criação* — rejeitado por Q1 (quebraria automação/servidores e
  perfis `blind`/`triage` headless).

## R3. Storage e resolução de perfis de usuário

**Decision**: Perfis de usuário criados pelo editor são gravados como **pasta de perfil
em `<install>/profiles/<nome>/`**, no mesmo formato dos embarcados (um `IPEDConfig.txt`
e/ou `conf/*` com **apenas os overrides**). Assim `Main` resolve `-profile <nome>`
nativamente (`Main.java:174-179` → `<rootPath>/profiles/<nome>`), **sem mudança no
engine**. Perfis embarcados são templates **somente leitura**: editar um embarcado
exige "Salvar como" um novo nome (FR-018). **Plano-B condicional** (instalação
read-only): gravar em `~/.iped/profiles/` e estender a resolução de `-profile` para
buscar também esse diretório — toque mínimo justificado (Complexity Tracking).

**Rationale**: A distribuição do IPED é uma **pasta portátil** normalmente gravável
(não instala em `Program Files`); o default zero-mudança honra o contrato `-profile`
existente e a equivalência forense (FR-013) — o caso criado usa exatamente o mesmo
mecanismo de profile do CLI. Resolve o item "Deferred" do `/speckit-clarify` (onde
ficam os perfis de usuário).

**Alternatives considered**:
- *Sempre `~/.iped/profiles/` + extensão de resolução* — adia para o engine um toque
  que o caminho default dispensa; vira plano-B.
- *Passar config por caminho absoluto em vez de nome* — exigiria mudar `-profile` (que
  aceita nome, não path) no engine; rejeitado por FR-028.

## R4. Editor de perfis "completo": grid dirigido pelos arquivos de config

**Decision**: O editor expõe **todas as opções** (Q2) por um **modelo de configuração
genérico** lido dos arquivos de config canônicos do release — `IPEDConfig.txt`,
`LocalConfig.txt` e `conf/*` (`.txt`/`.properties`) — **não** por ~55 telas escritas à
mão. Cada arquivo vira um grupo; cada par `chave = valor` vira uma `ConfigOption`; os
**comentários `#`** que precedem cada chave nesses arquivos viram a descrição exibida
(eles já documentam cada opção, em inglês). O editor mostra o **valor efetivo**
(base + override do perfil em edição) e grava no perfil de usuário **apenas as chaves
alteradas** (override). Formatos não-`key=value` (`.xml`/`.json`, ex.: `CarverConfig.xml`,
`CategoriesConfig.json`) entram numa aba "arquivos avançados" com edição de texto
assistida (ou ficam listados como editáveis externamente) — cobertura "completa" sem
um parser visual por formato.

**Rationale**: Entrega a decisão "editor completo" com esforço linear no nº de arquivos
(não no nº de opções), reaproveitando a documentação já embutida nos configs. Mantém o
Princípio III (a UI é uma vista sobre os Configurables) e a equivalência (grava os
mesmos arquivos que o CLI lê). Evita acoplar a UI ao conjunto exato de `Configurable`s
(que evolui).

**Alternatives considered**:
- *Telas dedicadas por `Configurable`* — rejeitado: ~55 classes × dezenas de campos =
  custo enorme e frágil a cada nova task/config.
- *Refletir sobre as classes `Configurable<T>`* — rejeitado: muitas não expõem schema
  de campos uniformemente; os arquivos `key=value` comentados são a fonte mais estável.

## R5. Wizard: JFace `Wizard`/`WizardDialog` nativo

**Decision**: O assistente de Novo Caso usa o **framework de wizards do JFace**
(`org.eclipse.jface.wizard.Wizard` + `WizardPage` + `WizardDialog`), já presente na
target platform da 004 (sem dependência nova). Etapas: Fontes → Saída → Perfil →
Opções comuns → (Avançado) → Resumo. Validação por página via
`setPageComplete`/`setErrorMessage`. Diálogos de arquivo/pasta = `FileDialog`/
`DirectoryDialog` nativos (FR-022). Lançar/validar caminhos longos via **Jobs API**
fora da UI thread.

**Rationale**: Wizards nativos atendem FR-003/FR-008/FR-022 sem código de framework
próprio e com aparência do SO. Alinha-se ao uso de JFace da 004.

**Alternatives considered**:
- *Diálogo único multi-aba* — rejeitado: o usuário pediu explicitamente um "Wizard";
  validação passo-a-passo é mais clara para criação de caso.

## R6. Open Case e estado "sem caso" + casos recentes

**Decision**: "Open Case" usa um `DirectoryDialog` nativo e delega ao
`ICaseSessionManager`/`CaseSessionService` da 004 (single + `-multicases`). O
`LifeCycle` passa a permitir **subir sem caso** (perspectiva vazia com chamada à ação
do menu) em vez de forçar `DirectoryDialog` no boot; abrir/trocar de caso em runtime =
fechar a sessão atual e abrir a nova pelo mesmo serviço. **Casos recentes** (FR-002
SHOULD / US2) ficam num `RecentCasesStore` em `~/.iped/` (lista de caminhos + carimbo),
exposta como submenu.

**Rationale**: Reaproveita o serviço de sessão (inclusive near-live) sem reescrever
abertura; o estado "sem caso" é o que torna o menu a porta de entrada (Complexity
Tracking). Mantém o caso imutável (recentes ficam fora do caso).

**Alternatives considered**:
- *Manter o boot atual (caso obrigatório) e só adicionar New Case* — rejeitado:
  impediria abrir um caso diferente sem reabrir a aplicação e tornaria "Open Case"
  redundante.

## R7. Abrir o caso novo em modo quase-ao-vivo (FR-011, Q3)

**Decision**: Ao iniciar o processamento, o wizard oferece **abrir o caso em
processamento** pelo caminho near-live já entregue na 004 (R-004.R14:
`CommitMonitor` + `CaseSession.swapSource`, gate FR-030 já validado/GO). A UI abre o
`<saída>/iped` como leitor somente-leitura e atualiza a cada consolidação; após a
conclusão, o mesmo caso permanece abrível normalmente. Nenhum handshake in-process
novo — cada processo cuida do seu ciclo (R-004.R14).

**Rationale**: Reusa investimento da 004 e a divergência já registrada (itens só
aparecem na consolidação seguinte). Atende Q3 sem novo mecanismo.

**Alternatives considered**:
- *Abrir só após a conclusão* — era o default pré-clarify; substituído por Q3.
- *Stream item-a-item em tempo real* — rejeitado (mesma razão da 004: acoplaria a UI à
  JVM de processamento).

## R8. Testes e validação de equivalência

**Decision**:
- **Headless (Tycho-surefire/JUnit 5)** para `ProfileService` (merge base+override,
  round-trip de gravação, isolamento dos embarcados) e `BootstrapCommandBuilder`
  (mapeamento `NewCaseRequest` → args, validações de FR-008).
- **SWTBot** (CI Linux/GTK via Xvfb) para o fluxo do wizard (FR-003…FR-012) e do editor
  de perfis (FR-014…FR-019), incluindo cancelamento sem efeito colateral (FR-012).
- **Equivalência (FR-013/SC-004)**: harness de paridade cria um caso pelo
  `BootstrapCommandBuilder`/serviço e outro pela CLI direta com o mesmo perfil/evidência
  e compara universo de itens/categorias/campos indexados.

**Rationale**: Mantém os serviços de criação **toolkit-free e testáveis** (padrão da
004: lógica em `iped.rcp.core`, UI fina por cima), e prova a invariante central
(FR-013) automatizadamente.

**Alternatives considered**:
- *Só SWTBot end-to-end* — rejeitado: lento e não isola o mapeamento de argumentos nem
  a equivalência forense.

---

## Resumo dos NEEDS CLARIFICATION resolvidos

| Item do Technical Context | Resolução |
|---|---|
| Como a UI RCP dispara o processamento | R1 — subprocesso `Bootstrap`, engine intacto |
| Escopo de "aposentar iped.exe" (FR-020 × FR-021) | R2 — porta de criação aposentada; motor headless mantido |
| Onde/como ficam os perfis de usuário (item Deferred) | R3 — `<install>/profiles/<nome>`; plano-B `~/.iped/profiles/` |
| Editor de perfis "completo" sem 55 telas | R4 — grid dirigido pelos arquivos de config + comentários |
| Tecnologia do wizard | R5 — JFace Wizards (já na plataforma) |
| Open Case + estado sem caso + recentes | R6 — `CaseSessionService` + LifeCycle sem caso + RecentCasesStore |
| Abrir caso novo durante o processamento | R7 — near-live da 004 (CommitMonitor) |
| Estratégia de testes/equivalência | R8 — headless + SWTBot + harness de equivalência |
