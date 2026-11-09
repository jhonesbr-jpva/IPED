# Feature Specification: Migração da GUI do IPED para Eclipse RCP

**Feature Branch**: `004-rcp-gui-migration`

**Created**: 2026-06-10

**Status**: Draft

**Input**: User description: "Vamos migrar a GUI do IPED para Eclipse RCP."

## Visão geral

A interface de análise do IPED (SearchApp) é hoje uma aplicação desktop monolítica
construída sobre um toolkit de UI legado e um framework de docking de terceiros sem
evolução ativa. Esta feature migra a GUI de análise para a plataforma **Eclipse RCP**,
trocando a base de apresentação por um workbench maduro com gestão nativa de views
dockáveis, perspectivas e um modelo de componentes de primeira classe.

A migração é **preservadora de comportamento**: o objetivo é que o perito reencontre
na nova UI todas as funções de análise que usa hoje — busca, tabela de resultados,
galeria, árvores, facetas de metadados, viewers, mapa, grafo, timeline, bookmarks,
filtros, exportação e relatório — sem alteração de resultado forense. Nenhuma
funcionalidade nova de análise faz parte do escopo; o ganho está na plataforma
(extensibilidade, manutenibilidade, ergonomia de janelas).

A motivação é dupla (ver Clarifications): (1) **experiência nativa** — eliminar a
aparência "de aplicação Java" e adotar os widgets nativos do sistema operacional
hospedeiro, com janelas, menus, diálogos e controles com a cara do SO em cada
plataforma suportada; (2) **plataforma madura e modular** — apoiar a UI num
framework de bancada mantido ativamente, com modelo de partes, serviços de seleção
e comandos, assistentes (wizards) e modularidade com extensibilidade por plugins de
primeira classe. O pano de fundo é manutenibilidade de longo prazo: o IPED é um
software grande, cuja UI vem ficando difícil de manter sem um framework maduro como
base.

## Clarifications

### Session 2026-06-10

- Q: Qual a motivação primária da migração? → A: Experiência nativa do SO
  hospedeiro (widgets nativos, sair da "cara de Java") **e** aproveitamento pleno do
  framework da plataforma-alvo (modelo de partes, serviço de seleção, comandos,
  wizards, modularidade e extensibilidade via plugins/serviços declarativos), com
  manutenibilidade de longo prazo como pano de fundo. A extensibilidade por
  terceiros é importante, mas não o único driver.
- Q: Qual estratégia de entrega? → A: **Cut-over total** — a nova UI substitui a
  atual em um único release; paridade funcional completa é gate de liberação; a
  partir desse release a UI atual deixa de ser distribuída (precedente da migração
  Java 21). Casos antigos continuam abríveis pela UI embarcada neles (modelo de
  caso autocontido).
- Q: Quais superfícies gráficas entram no escopo? → A: **Todas** — UI de análise
  (SearchApp), janela de progresso do processamento, tela de abertura (splash) e
  diálogos do inicializador.
- Q: Contra qual estado da UI atual a paridade (SC-001) é medida no cut-over? → A:
  **Snapshot + re-baseline controlado** — inventário congelado no início da feature;
  em marcos definidos (ex.: a cada release do fork), mudanças relevantes da UI atual
  são triadas e incorporadas ao inventário de forma controlada.
- Q: Qual o status de estabilidade da API de extensão (FR-022) no cut-over? → A:
  **Provisória** — publicada como experimental (pode mudar sem ciclo de
  depreciação); declarada estável (e então sujeita ao Princípio I da constituição)
  apenas 1–2 releases após o cut-over, quando validada por extensões reais.
- Q: A migração é objetivo do fork ou contribuição ao upstream? → A: **Fork-only
  com divergência contida** — a nova UI é deste fork; mudanças fora da camada de
  apresentação (engine, parsers, lógica de viewers) devem ser minimizadas para
  manter viáveis os merges futuros do upstream nessas camadas. Contribuição
  upstream fica como possibilidade futura, sem ser requisito.
- Q: Como tratar o acesso concorrente de múltiplos peritos ao mesmo caso? → A:
  **Paridade com o comportamento atual** — múltiplas instâncias podem abrir o mesmo
  caso simultaneamente, com as mesmas semânticas e limitações de hoje (leitura
  concorrente; escrita de bookmarks com a mesma disciplina de lock). Sem garantias
  novas de colaboração.
- Q: O mecanismo de atualização/provisionamento de componentes da plataforma entra
  no escopo? → A: **Fora do escopo** — modelo de distribuição atual mantido
  (release = instalação nova; casos autocontidos imutáveis). Plugins de terceiros
  (US6) são instalados por drop-in manual. Auto-update pode virar feature futura.
- Q: Como fica o modo interativo (UI de análise aberta durante o processamento),
  dado que a nova arquitetura separa a UI de análise e o processamento em
  processos distintos? → A: **Modo quase-ao-vivo (cross-process)** — a UI de
  análise abre o caso em processamento em modo somente leitura sobre os dados já
  consolidados e se atualiza automaticamente a cada consolidação do processamento.
  **Divergência registrada** (re-baseline): itens ainda não consolidados deixam de
  ser visíveis em tempo real (na UI atual eram, por compartilharem o mesmo
  processo). A leitura concorrente não pode bloquear nem atrasar o processamento
  (FR-030); uma investigação técnica (spike) valida a contenção de I/O antes da
  implementação.

## User Scenarios & Testing *(mandatory)*

### User Story 1 - Triage essencial em caso processado (Priority: P1)

Um perito abre um caso processado na nova UI de análise e executa o fluxo central de
triage: pesquisa por palavras-chave, ordena e percorre a tabela de resultados,
examina o conteúdo dos itens nos viewers, marca itens com bookmarks e exporta os
itens selecionados.

**Why this priority**: É o fluxo de trabalho diário e indispensável do perito. Sem
ele não existe UI de análise viável — é o MVP que prova a migração de ponta a ponta
(abrir caso → buscar → visualizar → marcar → exportar).

**Independent Test**: Abrir um caso de referência na nova UI e completar o fluxo
busca → ordenação → visualização → bookmark → exportação, comparando os resultados
(itens retornados, conteúdo exibido, arquivos exportados) com a UI atual sobre o
mesmo caso.

**Acceptance Scenarios**:

1. **Given** um caso processado pelo release corrente, **When** o perito o abre na
   nova UI, **Then** o caso carrega sem conversão nem reprocessamento e exibe a mesma
   contagem de itens da UI atual.
2. **Given** o caso aberto, **When** o perito executa uma pesquisa com a mesma
   sintaxe de consulta usada hoje, **Then** o conjunto de itens retornado é idêntico
   ao da UI atual para a mesma consulta.
3. **Given** um item selecionado na tabela de resultados, **When** o perito abre a
   aba de visualização, **Then** o conteúdo é renderizado nos mesmos formatos
   suportados hoje (texto, hex, imagem, áudio/vídeo, HTML, e-mail, documentos de
   escritório).
4. **Given** itens marcados com um bookmark criado na nova UI, **When** o caso é
   reaberto, **Then** o bookmark persiste com os mesmos itens e permanece legível
   pelas ferramentas atuais que consomem bookmarks do caso.
5. **Given** uma seleção de itens, **When** o perito aciona a exportação, **Then**
   os arquivos exportados são idênticos (conteúdo e estrutura) aos exportados pela
   UI atual.

---

### User Story 2 - Galeria, facetas e filtros avançados (Priority: P2)

O perito alterna para a galeria de imagens/vídeos, refina o conjunto de resultados
combinando filtros (categorias, bookmarks, valores de metadados, filtros salvos,
operadores E/OU/NÃO) e usa buscas por similaridade (imagens parecidas, faces,
documentos, duplicatas).

**Why this priority**: É o segundo pilar do trabalho de análise, decisivo em casos
com material visual massivo; depende do P1 mas multiplica a produtividade do perito.

**Independent Test**: Sobre o caso de referência, aplicar uma sequência definida de
filtros e similaridades na nova UI e na atual, comparando as contagens de itens
resultantes em cada passo.

**Acceptance Scenarios**:

1. **Given** o caso aberto, **When** o perito ativa a galeria, **Then** miniaturas de
   imagens e vídeos são exibidas e a navegação acompanha a rolagem sem travar a
   interface.
2. **Given** árvores de categorias e bookmarks visíveis, **When** o perito seleciona
   nós e combina com filtros de valores de metadados, **Then** a contagem de itens
   filtrados é igual à obtida na UI atual com a mesma combinação.
3. **Given** um item de imagem selecionado, **When** o perito aciona a busca por
   imagens similares (ou faces similares), **Then** o conjunto retornado é o mesmo da
   UI atual para o mesmo item e parâmetros.
4. **Given** um conjunto de filtros configurado, **When** o perito salva o filtro e
   reabre o caso, **Then** o filtro salvo está disponível e reproduz o mesmo
   resultado.

---

### User Story 3 - Views especializadas: mapa, grafo e timeline (Priority: P3)

O perito usa as views especializadas para análise contextual: itens
georreferenciados no mapa, grafo de comunicações entre entidades e linha do tempo
de eventos.

**Why this priority**: São diferenciais de análise importantes, porém usados em um
subconjunto dos casos; dependem do núcleo (P1/P2) estar estável.

**Independent Test**: Abrir o caso de referência (que contém itens com coordenadas,
comunicações e eventos datados) e validar que cada view exibe os mesmos dados da UI
atual.

**Acceptance Scenarios**:

1. **Given** itens com coordenadas geográficas, **When** o perito abre a view de
   mapa, **Then** os mesmos itens georreferenciados da UI atual são plotados e a
   seleção no mapa sincroniza com a tabela de resultados.
2. **Given** um caso com dados de comunicação, **When** o perito abre a view de
   grafo, **Then** entidades e ligações exibidas correspondem às da UI atual,
   incluindo expansão de nós e busca de caminhos.
3. **Given** itens com eventos datados, **When** o perito abre a timeline, **Then**
   a distribuição temporal exibida corresponde à da UI atual e o zoom/seleção de
   intervalo filtra a tabela de resultados.

---

### User Story 4 - Acompanhar o processamento de um caso (Priority: P4)

Um perito dispara o processamento de evidências e acompanha o andamento em janela
gráfica: progresso global e por evidência, contadores de itens processados e
encontrados, taxa de processamento, fase corrente, erros e alertas — com a mesma
informação disponível hoje.

**Why this priority**: Com o cut-over total e o escopo de todas as superfícies
gráficas, a janela de progresso também migra; é parte do fluxo diário de quem
processa casos e condição para aposentar a UI atual.

**Independent Test**: Processar uma evidência de referência acompanhando a nova
janela de progresso e comparar as informações exibidas (fases, contadores, taxa,
alertas) com a janela atual no mesmo processamento.

**Acceptance Scenarios**:

1. **Given** um processamento iniciado, **When** a janela de progresso abre,
   **Then** exibe progresso global, progresso por evidência e contadores
   equivalentes aos atuais (itens processados, encontrados, taxa, fase corrente).
2. **Given** erros não-fatais durante o processamento, **When** ocorrem, **Then**
   são apresentados na janela como hoje, sem interromper o processamento.
3. **Given** o modo sem interface gráfica, **When** o processamento roda, **Then**
   o progresso continua disponível em modo texto, como hoje.

---

### User Story 5 - Workspace pessoal: layout, temas, idioma e escala (Priority: P5)

O perito reorganiza as views da bancada de trabalho (arrastar, encaixar, maximizar,
fechar/reabrir), escolhe tema claro ou escuro, trabalha no seu idioma e em monitores
de alta resolução; ao reabrir a aplicação, encontra tudo como deixou.

**Why this priority**: Ergonomia e produtividade contínuas; não bloqueia a análise
em si, mas a persistência de layout e i18n são expectativas consolidadas dos
usuários atuais.

**Independent Test**: Reorganizar o layout, trocar tema e idioma, fechar e reabrir a
aplicação verificando a restauração fiel do estado.

**Acceptance Scenarios**:

1. **Given** um layout personalizado de views, **When** a aplicação é fechada e
   reaberta, **Then** o layout é restaurado exatamente como deixado.
2. **Given** o tema escuro selecionado, **When** o perito percorre todas as views,
   **Then** todos os componentes respeitam o tema com contraste legível.
3. **Given** o sistema em um dos idiomas suportados hoje, **When** a aplicação abre,
   **Then** todos os textos visíveis aparecem no idioma correspondente, com fallback
   para inglês onde faltar tradução.
4. **Given** um monitor de alta densidade (HiDPI), **When** a aplicação abre,
   **Then** textos, ícones e miniaturas escalam de forma legível conforme a
   configuração de escala.

---

### User Story 6 - Extensão por terceiros sem fork (Priority: P6)

Uma equipe forense desenvolve uma view de análise adicional (por exemplo, um painel
específico da sua operação) como componente independente, instala-a sobre o produto
e a utiliza integrada à bancada — sem modificar nem recompilar o produto base.

**Why this priority**: É o benefício estratégico da plataforma-alvo; valida que a
migração entrega extensibilidade real, mas só faz sentido após o núcleo (P1–P3)
estar consolidado.

**Independent Test**: Desenvolver uma view de exemplo como componente separado,
instalá-la em uma distribuição limpa e verificar que ela aparece e funciona na
bancada sem alteração no produto base.

**Acceptance Scenarios**:

1. **Given** um componente de view de exemplo empacotado separadamente, **When** ele
   é instalado em uma distribuição do produto, **Then** a nova view fica disponível
   na bancada, com acesso aos itens do caso e à seleção corrente.
2. **Given** o componente removido, **When** a aplicação reabre, **Then** o produto
   volta ao estado original sem erros.

---

### Edge Cases

- Caso com dezenas de milhões de itens: a UI deve permanecer responsiva na abertura,
  rolagem da tabela e galeria (carregamento incremental), sem esgotar memória.
- Análise multi-caso (vários casos abertos como fonte única): contagens, filtros e
  bookmarks devem se comportar como na UI atual.
- Caso em mídia somente leitura (DVD/pendrive protegido): a UI deve abrir o caso e
  degradar graciosamente as operações de escrita (bookmarks/layout), informando o
  usuário.
- Estado de layout corrompido ou ausente: a aplicação deve abrir com o layout padrão
  em vez de falhar.
- Ferramentas externas de visualização indisponíveis na máquina: o viewer
  correspondente deve degradar graciosamente (mensagem clara), sem derrubar a
  aplicação.
- Idioma do sistema sem tradução disponível: fallback para inglês, sem chaves de
  mensagem cruas na tela.
- Abertura da UI durante um processamento em andamento (modo interativo,
  quase-ao-vivo): a UI de análise abre o caso em processamento e exibe os dados
  consolidados até o momento, atualizando-se a cada nova consolidação; itens ainda
  não consolidados aparecem na consolidação seguinte (divergência registrada — ver
  Clarifications). A leitura nunca bloqueia ou atrasa o processamento.
- Mesmo caso aberto simultaneamente por múltiplos peritos (compartilhamento de
  rede): manter as semânticas e limitações atuais — leitura concorrente funciona;
  escrita de bookmarks segue a mesma disciplina de lock de hoje, sem garantias
  novas de colaboração.
- Sessões longas (um dia inteiro de análise): sem degradação progressiva de memória
  ou desempenho perceptível.

## Requirements *(mandatory)*

### Functional Requirements

**Abertura e compatibilidade de caso**

- **FR-001**: A nova UI MUST abrir um caso processado pelo mesmo release sem
  qualquer conversão, migração ou reprocessamento, exibindo o mesmo universo de
  itens da UI atual.
- **FR-002**: A nova UI MUST suportar análise multi-caso (abrir um conjunto de casos
  como fonte única), com paridade de contagens e filtros.
- **FR-003**: O mecanismo de abertura do caso portátil (duplo clique no launcher na
  raiz do caso) MUST abrir a nova UI usando o runtime e as bibliotecas embarcadas no
  próprio caso (modelo de caso autocontido vigente).
- **FR-004**: A nova UI MUST NOT modificar os dados de evidência do caso (índice,
  dados extraídos); apenas artefatos do usuário (bookmarks, filtros salvos,
  preferências de layout) podem ser gravados, nas áreas designadas atuais.
- **FR-005**: Bookmarks e filtros salvos gravados pela nova UI MUST permanecer no
  formato atual do caso, legíveis por qualquer ferramenta que hoje os consome (e
  vice-versa).

**Paridade funcional de análise**

- **FR-006**: O perito MUST poder pesquisar com a mesma sintaxe de consulta aceita
  hoje, com histórico de consultas, obtendo conjuntos de resultados idênticos aos da
  UI atual para a mesma consulta sobre o mesmo caso.
- **FR-007**: A tabela de resultados MUST oferecer ordenação por coluna, seleção
  múltipla, marcação (checkbox), e configuração de colunas visíveis equivalentes às
  atuais, incluindo as tabelas auxiliares (subitens, item pai, duplicatas,
  referências).
- **FR-008**: A galeria MUST exibir miniaturas de imagens e vídeos com carregamento
  incremental e seleção sincronizada com a tabela de resultados.
- **FR-009**: As árvores de navegação atuais MUST estar disponíveis: evidências/
  sistema de arquivos, categorias, bookmarks e filtros de IA, com seleção
  combinável aos demais filtros.
- **FR-010**: O painel de metadados MUST exibir agregações por campo (contagens,
  faixas) e permitir filtrar por valores, com resultados iguais aos da UI atual.
- **FR-011**: A visualização de conteúdo de item MUST cobrir todos os formatos
  renderizados hoje (texto extraído com realce de ocorrências, hexadecimal, imagens,
  áudio/vídeo, HTML/e-mail, documentos de escritório, CAD e demais viewers
  existentes), preservando a navegação entre ocorrências.
- **FR-012**: As views especializadas MUST ser providas com paridade: mapa
  (georreferenciamento com seleção sincronizada), grafo de comunicações (expansão,
  caminhos, exportação) e timeline (zoom, seleção de intervalo, filtro).
- **FR-013**: As buscas por similaridade atuais MUST estar disponíveis: imagens
  similares, faces similares, documentos similares e duplicatas.
- **FR-014**: A gestão de bookmarks MUST permitir criar, renomear, colorir,
  comentar, unir e excluir bookmarks, com persistência no caso.
- **FR-015**: A exportação de itens (cópia de arquivos, com propriedades) e a
  geração de relatório MUST produzir saídas idênticas às da UI atual para a mesma
  seleção e configuração.
- **FR-016**: A combinação de filtros com operadores lógicos (E/OU/NÃO) e o
  salvamento/reuso de filtros MUST ter paridade com a UI atual.

**Bancada de trabalho (workspace)**

- **FR-017**: Todas as views MUST ser dockáveis e reorganizáveis (arrastar, encaixar,
  empilhar, maximizar, fechar/reabrir), com o layout persistido por usuário e
  restaurado entre sessões.
- **FR-018**: A aplicação MUST oferecer temas claro e escuro (próprios ou seguindo
  o tema do sistema operacional hospedeiro), aplicados de forma consistente a todas
  as views, sem conflitar com a aparência nativa (FR-025).
- **FR-019**: A aplicação MUST escalar corretamente em monitores de alta densidade,
  respeitando a configuração de escala do usuário.
- **FR-020**: Todos os textos visíveis ao usuário MUST ser localizados nos idiomas
  hoje suportados (português do Brasil, inglês, espanhol, alemão, francês,
  italiano), com fallback para inglês.
- **FR-021**: Os atalhos de teclado do fluxo de triage atual MUST ser preservados ou
  mapeados de forma documentada.

**Extensibilidade**

- **FR-022**: A nova UI MUST permitir que uma view de análise adicional seja
  desenvolvida, empacotada e instalada como componente independente, sem modificação
  ou recompilação do produto base, com acesso ao caso aberto e à seleção corrente.
  No release de cut-over, a API de extensão é publicada como **provisória**
  (sujeita a mudanças sem ciclo de depreciação, com aviso explícito na
  documentação); a declaração de estabilidade ocorre em release posterior, quando
  então passa a valer o Princípio I da constituição.

**Transição**

- **FR-023**: A entrega MUST ser por **cut-over total**: a nova UI substitui a
  atual em um único release; a paridade funcional completa (inventário do SC-001) é
  gate de liberação; a partir desse release, a UI atual deixa de ser distribuída e
  mantida. Casos processados por releases anteriores continuam sendo analisados pela
  UI embarcada neles (modelo de caso autocontido).
- **FR-024**: O escopo da migração MUST abranger **todas as superfícies gráficas**
  do produto: a UI de análise (SearchApp), a janela de progresso do processamento, a
  tela de abertura (splash) e os diálogos do inicializador.

- **FR-028**: A migração MUST conter a divergência em relação ao upstream
  (`sepinf-inc/IPED`) fora da camada de apresentação: mudanças em engine, parsers e
  lógica de viewers devem se limitar ao mínimo necessário para acoplar a nova UI,
  preservando a viabilidade de merges futuros do upstream nessas camadas.

**Aparência nativa e superfícies adicionais**

- **FR-025**: A nova UI MUST apresentar aparência e comportamento nativos do
  sistema operacional hospedeiro — janelas, menus, diálogos de arquivo, controles e
  fontes do sistema — em cada plataforma suportada, em vez de aparência emulada.
- **FR-026**: A janela de progresso do processamento MUST exibir, com paridade, as
  informações atuais: progresso global e por evidência, contadores de itens, taxa de
  processamento, fase corrente e erros/alertas; o modo texto (sem interface gráfica)
  permanece disponível.
- **FR-027**: A tela de abertura (splash) e os diálogos do inicializador MUST ser
  providos na nova plataforma, preservando o feedback de inicialização atual
  (estágios de carregamento e mensagens de erro de configuração).

**Modo interativo (quase-ao-vivo)**

- **FR-029**: Durante um processamento com interface gráfica, o usuário MUST poder
  abrir a UI de análise sobre o caso em processamento; a UI exibe os dados
  consolidados até o último ponto de consolidação e se atualiza automaticamente a
  cada nova consolidação, sem ação manual. Divergência registrada (Clarifications
  2026-06-10): itens ainda não consolidados não são visíveis até a consolidação
  seguinte.
- **FR-030**: A leitura concorrente da UI de análise MUST NOT bloquear nem atrasar
  o processamento: o tempo total de processamento do caso de referência com a UI
  de análise aberta em modo quase-ao-vivo não pode exceder em mais de 5% o tempo
  sem ela, e nenhuma consolidação pode ficar bloqueada aguardando a UI.

### Key Entities

- **Caso**: saída do processamento (índice, dados extraídos, configurações e
  artefatos do usuário); autocontido — carrega o runtime e a UI com que foi gerado.
- **Item**: unidade de análise (arquivo, mensagem, registro), com metadados,
  conteúdo renderizável e vínculos (pai, subitens, duplicatas, referências).
- **Bookmark/Tag**: marcador criado pelo perito sobre conjuntos de itens; persiste
  no caso em formato compatível com as ferramentas atuais.
- **Filtro**: predicado combinável (categoria, metadado, consulta, similaridade)
  com operadores lógicos; pode ser salvo e reutilizado.
- **View de análise**: superfície funcional da bancada (tabela, galeria, viewer,
  mapa, grafo, timeline, metadados, árvores); unidade de extensão por terceiros.
- **Layout de workspace**: disposição persistida das views por usuário.
- **Bundle de localização**: conjunto de textos da UI por idioma.

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: 100% dos itens do inventário de paridade funcional verificados como
  presentes e equivalentes na nova UI. O inventário é derivado da UI atual e
  congelado no início da feature, com re-baseline controlado em marcos definidos
  (mudanças relevantes da UI atual são triadas e incorporadas; ver Clarifications).
- **SC-002**: Um perito completa o fluxo padrão de triage (abrir caso → buscar →
  filtrar → visualizar → marcar → exportar) na nova UI sem erros, em tempo no máximo
  10% superior ao da UI atual no mesmo caso e hardware.
- **SC-003**: Abertura do caso de referência (≥ 1 milhão de itens) e resposta de
  busca na nova UI em tempo no máximo 10% superior ao da UI atual.
- **SC-004**: Navegação na galeria com ≥ 100 mil imagens permanece responsiva, sem
  congelamento perceptível da interface (> 1 segundo).
- **SC-005**: O layout personalizado é restaurado corretamente em 100% dos
  reinícios durante o período de validação.
- **SC-006**: 100% das mensagens visíveis localizadas em português e inglês; demais
  idiomas sem chaves cruas na tela (fallback funcionando).
- **SC-007**: Uma view de exemplo desenvolvida como componente independente é
  instalada e utilizada sobre uma distribuição limpa sem qualquer modificação no
  produto base.
- **SC-008**: Sessão contínua de análise de 8 horas sobre o caso de referência sem
  travamento, crash ou degradação perceptível de desempenho.
- **SC-009**: Bookmarks criados na nova UI são lidos corretamente pelas ferramentas
  atuais que consomem bookmarks do caso (e vice-versa) em 100% dos testes de
  ida-e-volta.
- **SC-010**: Em cada plataforma suportada, 100% das telas do inventário de
  superfícies (bancada de análise, progresso, splash, diálogos) usam janelas,
  menus e diálogos de arquivo nativos do sistema operacional, verificado por
  inspeção visual documentada.

## Assumptions

- **Escopo de superfícies**: todas as superfícies gráficas do produto (UI de
  análise, progresso do processamento, splash, diálogos do inicializador), conforme
  Clarifications 2026-06-10. O processamento em si continua dirigido por CLI; a
  janela de progresso é o acompanhamento gráfico desse fluxo.
- **Sem coexistência**: nenhum release distribui as duas UIs em paralelo (cut-over
  total, FR-023). Até o release de cut-over, a UI atual segue sendo a distribuída.
- **Migração preservadora de comportamento**: nenhuma funcionalidade nova de análise;
  paridade com a UI atual é o critério. Melhorias de UX inerentes à nova bancada
  (gestão de janelas, perspectivas) são aceitáveis, mas não requisito.
- **Modelo de caso autocontido mantido** (decisão da feature 003): cada caso carrega
  o runtime e a UI com que foi processado. Casos antigos continuam sendo analisados
  pela UI embarcada neles — **não** há requisito de a nova UI abrir casos de
  releases anteriores.
- **Formatos de artefatos do usuário inalterados**: bookmarks, multibookmarks e
  filtros salvos mantêm o formato atual (Princípio I da constituição).
- **Plataformas**: Windows (com runtime embarcado) e Linux, como hoje. A aparência
  nativa (FR-025) aplica-se a cada SO suportado; suporte a macOS **não** entra no
  escopo desta feature (fica como benefício potencial futuro da plataforma-alvo).
- **Baseline de desempenho**: a UI atual, no mesmo hardware e caso de referência.
- **Idiomas**: o conjunto atual de locales é mantido; nenhum idioma novo.
- **Caso de referência**: existe (ou será gerado) um caso de referência com ≥ 1
  milhão de itens contendo material georreferenciado, comunicações e eventos
  datados, usado para todas as comparações de paridade e desempenho.

## Out of Scope

- Novas funcionalidades de análise (qualquer recurso que não exista na UI atual).
- Suporte a macOS (a aparência nativa aplica-se às plataformas já suportadas:
  Windows e Linux).
- Mudanças no pipeline de processamento, no formato do caso ou no índice.
- Interface Web ou remota (a Web API existente não é afetada).
- Tradução para novos idiomas.
- Importação de layouts de workspace salvos pela UI atual (o usuário recomeça do
  layout padrão na nova UI).
- Mecanismo de atualização/provisionamento automático de componentes (auto-update):
  o modelo de distribuição atual é mantido (release = instalação nova; casos
  autocontidos imutáveis); plugins de terceiros são instalados por drop-in manual.
