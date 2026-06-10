# Research — Migração da GUI do IPED para Eclipse RCP

**Feature**: `004-rcp-gui-migration` | **Date**: 2026-06-10
**Input**: [spec.md](spec.md) | Constituição v1.2.0

Decisões de pesquisa que resolvem os pontos técnicos em aberto do Technical
Context. Cada decisão registra alternativas consideradas. Nada aqui altera o
"o quê" da spec; tudo é "como".

---

## R1. Plataforma-alvo: Eclipse 4 (e4) puro, release train corrente

**Decision**: Eclipse Platform 4.3x (release train mais recente disponível no
início da implementação; baseline mínima 4.32/2024-06, que já exige Java 21) como
*target platform* p2. Aplicação **e4 pura** (modelo `Application.e4xmi` com
`MPart`/`MPerspective`), sem a camada de compatibilidade 3.x (`org.eclipse.ui`).

**Rationale**: O usuário pediu explicitamente o modelo e4/MParts. A camada 3.x
traz bagagem de IDE (views/editors legados) irrelevante para um produto novo.
O requisito de runtime Java 21 da plataforma casa com a baseline do projeto
(constituição: `release = 21`). SWT entrega o requisito central FR-025
(widgets nativos do SO).

**Alternatives considered**:
- *Workbench 3.x compat* — rejeitado: API legada, contraria a motivação (e4).
- *NetBeans Platform* — rejeitado: Swing (não resolve FR-025).
- *JavaFX puro* — rejeitado: não usa widgets nativos; não tem workbench/docking
  maduro nem OSGi nativo.

## R2. Build: reactor Tycho dedicado (`iped-rcp/`), integrado ao Maven raiz

**Decision**: Novo diretório `iped-rcp/` com reactor **Eclipse Tycho 4.x**
(packaging `eclipse-plugin`/`eclipse-feature`/`eclipse-repository`), incluído
como módulo do `pom.xml` raiz **por perfil** (`-P rcp`) e buildável standalone.
O reactor Tycho consome `iped-engine` e demais módulos como dependências
binárias do repositório local (build raiz primeiro). Target platform definida
em arquivo `.target` versionado (`iped-rcp/target-platform/`).

**Rationale**: Tycho é o build de facto para produtos e4/p2 (materialização de
produto por SO via `tycho-p2-director-plugin`, multi-plataforma
`win32.win32.x86_64` + `gtk.linux.x86_64`). Misturar packaging Tycho e jar puro
num mesmo reactor é frágil; o perfil isola o build RCP e mantém o CI atual
verde enquanto a feature evolui (FR-028: divergência contida).

**Alternatives considered**:
- *Bndtools/bnd-maven* — rejeitado: ótimo para OSGi puro, fraco para produto
  e4 + p2 + launchers nativos.
- *Maven puro + jars do SWT* — rejeitado: perde product export, splash nativo,
  launcher equinox, gestão de target platform.

## R3. OSGi-ficação do engine: bundle "biblioteca" único, sem reengenharia

**Decision**: `iped-engine` e sua árvore de dependências (Lucene, Tika, etc.)
entram no runtime OSGi como **um bundle wrapper** (`iped.rcp.libs`) com
`Bundle-ClassPath` embutindo os jars e `Export-Package` apenas dos pacotes que
a UI consome. O engine continua rodando como classpath plano dentro desse
bundle; nenhuma dependência é convertida individualmente.

**Rationale**: A árvore tem ~200 jars não-OSGi com `split packages` e
`ClassLoader` assumptions (JNA, JNI, Jep, scripts). Wrappear individualmente é
frágil e violaria FR-028 (mexer no engine). Um único class space preserva o
comportamento atual do engine.

**Alternatives considered**:
- *p2-maven-plugin wrappeando jar a jar* — rejeitado: explosão de metadados,
  quebra com split packages, alto custo de manutenção.
- *`pomDependencies=consider` para tudo* — parcialmente usado (para jars já
  OSGi), mas não como estratégia única: muitos jars não têm manifesto OSGi.

## R4. Estratégia de widgets: SWT nativo no shell, bridge nos viewers de conteúdo

**Decision**: Estratégia em **duas camadas**:
1. **SWT/JFace nativos** (FR-025): workbench, menus/toolbars/commands, diálogos
   e wizards, tabela de resultados e auxiliares (`TableViewer` com
   `SWT.VIRTUAL`), árvores (`TreeViewer` + `ILazyTreeContentProvider`), painel
   de metadados/facetas, painel de filtros, galeria (**Nebula Gallery**, modo
   virtual), janela de progresso, splash.
2. **Bridge `SWT_AWT`** para os viewers de conteúdo existentes do
   `iped-viewers` (HTML/e-mail/áudio via `JFXPanel`, LibreOffice via NOA/
   nativeview, PDF/IcePDF, hex, CAD), o grafo (Kharon/Swing) e a timeline
   (JFreeChart/Swing), hospedados dentro de `MPart`s. Os viewers **não são
   reescritos** nesta feature.

**Rationale**: O ganho de aparência nativa está no chrome e nos widgets
estruturais onde o perito vive (tabelas, árvores, menus, diálogos). Os viewers
são superfícies de renderização de conteúdo — o usuário não percebe toolkit
ali. Reescrever ~todos os viewers multiplicaria o escopo e violaria FR-028
(lógica de viewers preservada para merges upstream).

**Alternatives considered**:
- *Reescrita SWT total (incl. viewers)* — rejeitado: escopo e risco proibitivos
  (LibreOffice/JavaFX/IcePDF não têm equivalente SWT pronto).
- *`FXCanvas` (javafx-swt) para os viewers JavaFX* — rejeitado por ora: exigiria
  reescrever os viewers baseados em `JFXPanel` e adiciona um segundo caminho de
  integração; o bridge único `SWT_AWT` cobre todos os casos. Reavaliável depois
  do cut-over.
- **Riscos conhecidos do `SWT_AWT`** (foco, z-order, GTK): mitigar com um único
  composite-bridge por part, testes em Windows + Linux/GTK desde o primeiro
  milestone (ver quickstart).

## R5. Docking, perspectivas e persistência de layout

**Decision**: Docking/rearranjo via modelo e4 (`PartSashContainer`/`PartStack`
+ addons de DnD e min/max). Layout persistido pelo **mecanismo nativo do e4**
(persisted state / `workbench.xmi`), com área de persistência **no perfil do
usuário** (`~/.iped/ui-workspaces/<case-id>/`), nunca dentro do caso.
Estado corrompido → reset automático para o modelo padrão (equivalente a
`-clearPersistedState`).

**Rationale**: Substitui DockingFrames + `PanelsLayout` sem código próprio.
Persistir fora do caso resolve o edge case de mídia somente leitura e mantém o
caso imutável (Princípio IV).

**Alternatives considered**: persistir no caso (como hoje) — rejeitado: quebra
em mídia somente leitura e suja o artefato forense.

## R6. Serviços e4 e API de extensão provisória

**Decision**: Usar os serviços padrão do e4: `ESelectionService` (seleção de
itens propagada entre parts), `IEventBroker` (substitui
`UIPropertyListenerProvider` no lado UI), Commands/Handlers + keybindings,
DI (`@Inject`/`@UIEventTopic`), Declarative Services para serviços headless.
A API de extensão provisória (FR-022) vive num bundle dedicado
**`iped.rcp.api`**: interfaces de serviço (sessão de caso, resultados,
seleção), tópicos de evento e o mecanismo de contribuição de parts via
**model fragments** (`fragment.e4xmi`) + DS. Bundles de terceiros entram por
drop-in (`dropins/`-like, via p2 directory watcher simples ou
`osgi.bundles`/`bundles.info` gerado no install — detalhe no contrato).

**Rationale**: É exatamente o conjunto de serviços que o usuário citou como
motivação. Model fragments são o caminho e4 idiomático para terceiros
contribuírem parts sem tocar o produto (US6/SC-007).

**Alternatives considered**: extension points clássicos (`plugin.xml`) —
usável, mas o registro de extensões 3.x adiciona dependência da camada compat;
DS + fragments mantém e4 puro.

## R7. i18n: adaptador sobre os bundles existentes

**Decision**: Manter `iped-app/resources/localization/*.properties` como fonte
única de strings; criar adaptador (`Messages` e4-friendly, com
`TranslationService`/locale via `-nl` herdando `iped-locale`) que resolve as
chaves existentes. Chaves novas (exclusivas da nova UI) entram nos mesmos
arquivos, em todos os locales (PT-BR, EN, es-AR, de-DE, fr-FR, it-IT).

**Rationale**: Princípio III (bundles centralizados) e FR-020 sem duplicar
catálogo. Evita re-tradução em massa.

**Alternatives considered**: catálogos OSGi por bundle (`OSGI-INF/l10n`) —
rejeitado: fragmentaria a fonte de tradução hoje centralizada.

## R8. Temas: nativo por padrão, escuro seguindo o SO

**Decision**: Default = widgets nativos sem CSS (FR-025). Tema escuro segue o
modo escuro do SO (Windows dark mode / GTK dark variant), com CSS e4 mínimo
apenas para superfícies não-nativas (galeria, statusline). Toggle manual
claro/escuro exposto em preferência (FR-018).

**Alternatives considered**: tema CSS completo estilo IDE — rejeitado:
descaracteriza a aparência nativa que motivou a migração.

## R9. Empacotamento, caso autocontido e launcher

**Decision**: `tycho-p2-director-plugin` materializa o produto por SO. O
produto RCP (`ui/` com launcher equinox nativo, `plugins/`, `configuration/`)
é integrado ao release atual em `target/release/iped-<ver>/ui/`, ao lado de
`iped.jar` (processamento CLI, inalterado). Caso autocontido: o `Manager`
passa a copiar também `ui/` para `<caso>/iped/ui/`; o launcher da raiz do caso
(`IPED-SearchApp.exe`, contrato do fix `b8b15735a`) passa a delegar para
`<caso>/iped/ui/<launcher equinox>` com `-vm <caso>/iped/jre/bin` e
`-data` apontando para a área de workspace do usuário. `BootstrapUI`/
`AppMain`/`iped-search-app.jar` são **aposentados no cut-over** (FR-023).

**Rationale**: Preserva o modelo autocontido (Clarifications 003 + 004) e o
hábito do usuário (duplo clique na raiz do caso). `-vm` para a JRE embarcada
mantém Windows sem dependência de Java instalado.

**Alternatives considered**: manter cadeia `BootstrapUI → JVM filha` lançando
OSGi por dentro — rejeitado: o launcher equinox já é o processo isolado
(crash da UI não derruba nada além dela; Princípio V satisfeito sem JVM dupla).

## R10. Progresso do processamento e splash (FR-024/026/027)

**Decision**: A janela de progresso vira **SWT/JFace standalone** (sem OSGi):
novo módulo `iped.rcp.progress` usável como jar simples no classpath da JVM
filha de processamento (`iped.app.processing.Main`), consumindo os mesmos
eventos do engine (`UIPropertyListenerProvider`). `--nogui` continua com
`ProgressConsole` (inalterado). Splash do produto de análise = splash nativo
do launcher equinox; feedback de inicialização do processamento = SWT.
Diálogos do inicializador → `MessageBox`/JFace dialogs.

**Rationale**: O processamento é CLI numa JVM separada sem OSGi; SWT puro roda
ali sem custo. Mantém a regra de thread única de UI (análogo da EDT,
Princípio V: `Display.asyncExec`).

**Alternatives considered**: segundo produto e4 para progresso — rejeitado:
peso desnecessário para uma janela.

## R11. Testes e validação de paridade

**Decision**:
- **SWTBot** para testes de UI dos fluxos P1/P2 (rodando no CI Linux/GTK via
  Xvfb; janela Windows validada manualmente por checklist).
- **Tycho-surefire** para testes de bundle (serviços, adaptadores).
- **Harness de paridade headless**: consultas/filtragens executadas via API do
  engine comparando contagens entre baseline (UI atual) e nova UI — itens do
  inventário SC-001 automatizáveis.
- **Inventário de paridade**: arquivo versionado em
  `specs/004-rcp-gui-migration/parity-inventory.md`, congelado no início e
  re-baselinado por marcos (Clarifications).

**Alternatives considered**: RCPTT — rejeitado: ferramenta pesada e em
manutenção mínima; SWTBot cobre o necessário.

## R12. Tabela de resultados e galeria em escala (SC-003/SC-004)

**Decision**: `TableViewer SWT.VIRTUAL` com fetch preguiçoso sobre
`MultiSearchResult` (ordenação continua no lado engine/`parallelsorter`,
fora da UI thread). Galeria: Nebula Gallery em modo virtual, reusando a
lógica de produção de thumbnails do `GalleryModel` atual desacoplada de
Swing (`ImageIcon` → bytes/`ImageData`). Se `SWT.VIRTUAL` Table não bastar
nos testes com o caso de referência (≥ 1 M itens), fallback aprovado:
**Nebula NatTable**.

**Rationale**: `SWT.VIRTUAL` é o caminho de menor atrito; NatTable fica como
plano B já validado pela comunidade para milhões de linhas.

## R13. Licenciamento e CI

**Decision**: Novas dependências (Eclipse Platform/SWT/JFace/e4 — EPL-2.0;
Nebula — EPL-2.0; Tycho é build-time) registradas em `ThirdParty.txt` com
licenças em `licenses/`. CI ganha job dedicado `rcp` (profile `-P rcp`,
Xvfb + GTK para SWTBot) sem alterar o job atual até o cut-over.

**Rationale**: Exigência expressa da constituição (seção Build/Distribuição:
licenciamento e CI no mesmo PR).

## R14. Modo interativo: leitura quase-ao-vivo cross-process

**Decision** (Clarifications 2026-06-10; remediação do achado I1 do
`/speckit-analyze`): a UI de análise abre o caso **em processamento** como
leitor somente leitura e se atualiza por um **CommitMonitor** no lado da UI:
detecção de nova geração do índice Lucene (poll da geração de `segments_N` /
`DirectoryReader.openIfChanged`) — **zero mudança no engine** para o sinal de
consolidação. Storages SQLite abertos em modo read-only com busy timeout. O
handshake in-process do modo atual (`Manager.setSearchAppOpen`,
`deleteTempDir`, leitura NRT via `IndexWriter` — `UICaseDataLoader:90`) deixa
de existir: cada processo gerencia o próprio temp e ciclo de vida.

**Spike obrigatório (GO/NO-GO antes da implementação)**: medir o impacto de um
leitor concorrente sobre os commits do processamento no caso de referência.
Precedente negativo conhecido neste fork: locks SHARED de um `AppMain` aberto
seguram `storage-*.db` e fazem o COMMIT do `ExportFileTask` entrar em
busy-wait (fluxo `--yara-only`). Se o spike mostrar contenção, os ajustes na
camada de storage do engine (ex.: WAL, flags de conexão read-only) serão
propostos como toque justificado (Princípio II) e adicionados ao Complexity
Tracking do plan.md. Gate quantitativo: FR-030 (≤ 5% de acréscimo no tempo
total; nenhuma consolidação bloqueada).

**Rationale**: preserva o valor de triage do modo interativo (analisar
enquanto processa) sem acoplar o workbench à JVM de processamento e sem
abrir mão do isolamento de processos (Princípio V). Divergência funcional
registrada: itens não consolidados só aparecem na consolidação seguinte.

**Alternatives considered**:
- *Degradar (abrir análise só ao final)* — rejeitada pelo usuário: o modo
  interativo é central no fluxo de triage.
- *Embutir o Equinox na JVM de processamento* — rejeitado: acoplamento de
  classloaders OSGi × classpath plano do engine na mesma JVM e perda do
  isolamento de crash que motivou a separação.

---

## Resumo dos NEEDS CLARIFICATION resolvidos

| Item do Technical Context | Resolução |
|---|---|
| Versão da plataforma RCP | R1 — e4 puro, train corrente (≥ 4.32, Java 21) |
| Sistema de build | R2 — Tycho 4.x, reactor dedicado por perfil |
| Convivência OSGi × engine não-OSGi | R3 — bundle wrapper único |
| Destino dos viewers Swing/JavaFX/LibreOffice | R4 — bridge SWT_AWT, sem reescrita |
| Docking e persistência de layout | R5 — modelo e4, persistência no perfil do usuário |
| Mecanismo de extensão (FR-022) | R6 — `iped.rcp.api` + model fragments + DS |
| Estratégia i18n | R7 — adaptador sobre bundles atuais |
| Tema claro/escuro × nativo | R8 — segue o SO |
| Empacotamento/caso autocontido/launcher | R9 — produto p2 dentro do release; `ui/` no caso |
| Progresso/splash/diálogos | R10 — SWT standalone na JVM de processamento |
| Estratégia de testes | R11 — SWTBot + Tycho-surefire + harness de paridade |
| Escala da tabela/galeria | R12 — SWT.VIRTUAL/Nebula; fallback NatTable |
| Licenças/CI | R13 — EPL-2.0 em ThirdParty.txt; job CI dedicado |
| Modo interativo (UI durante processamento) | R14 — quase-ao-vivo cross-process com spike de contenção |
