# Feature Specification: Neo4j como plugin opcional do IPED

**Feature Branch**: `010-neo4j-optional-plugin`

**Created**: 2026-10-22

**Status**: Draft

**Input**: User description: "Parece que licença do Neo4j não combina bem do a licença do IPED. Logo quero que o Neo4j seja um plugin opcional do IPED. Caso não esteja presente o IPED deve funcionar sem problemas."

## Visão geral

A **análise de vínculos** do IPED (aba "Vínculos"/"Links": grafo de comunicações — chamadas, e-mails, mensagens, redes sem fio — e, opcionalmente, entidades extraídas por regex) depende hoje do banco de grafos **Neo4j**, que é **embarcado em toda distribuição do IPED**: o motor completo roda isolado em um processo filho (desenho introduzido na feature 003), e sua biblioteca de API mais o cliente de conexão ficam carregados no processo principal. Além disso, todo caso criado recebe uma cópia autocontida da aplicação que inclui esses componentes.

O mantenedor do fork identificou que os termos de licença do Neo4j **não se harmonizam** com a licença/modelo de distribuição pretendido para o IPED. Esta feature resolve a questão **por separação**: a capacidade de análise de vínculos passa a depender de um **plugin opcional**, obtido e instalado à parte, que fornece o motor de grafos e sua integração. A distribuição padrão do IPED deixa de conter qualquer componente do Neo4j e **funciona integralmente sem o plugin**; quando o plugin está instalado, a análise de vínculos se comporta exatamente como hoje.

Já existe na instalação uma **pasta de plugins** configurável (`LocalConfig.txt` → `pluginFolder`), descrita como destinada a "bibliotecas opcionais não embarcadas por restrições de licença". É esse mecanismo que o plugin de análise de vínculos deve reutilizar.

**Fora de escopo** desta feature:

- Substituir o Neo4j por outro motor de grafos ou alterar as funcionalidades/UX da análise de vínculos (só muda a *disponibilidade* e o *empacotamento*).
- A análise jurídica das licenças em si — a feature implementa a separação pedida independentemente do desfecho dessa análise.
- Expor análise de vínculos na Web API (hoje não existe endpoint para isso; continua não existindo).
- Modo de "regerar só o grafo" sobre um caso já processado (análogo ao `--yara-only`). Um caso processado sem o plugin só ganha dados de vínculos ao ser reprocessado/estendido após a instalação do plugin.
- Tornar opcionais outros componentes da distribuição.

## Clarifications

### Session 2026-10-22

- Q: O plugin carrega só as bibliotecas do Neo4j ou também o código de análise de vínculos do IPED? → A: **Modelo híbrido.** O núcleo mantém o módulo de análise de vínculos do IPED — modelagem de entidades e vínculos, geração dos dados durante o processamento, aba "Vínculos" e suas ações — escrito contra abstrações próprias do IPED: as poucas classes do Neo4j usadas hoje pela modelagem/processamento são abstraídas, e a interface de serviço de grafo que a interface gráfica já consome passa a ser o ponto de extensão. O plugin fornece as bibliotecas do Neo4j e a implementação desse serviço sobre elas (criação/importação do banco de grafos, consultas, motor isolado em processo próprio). Resultado: nenhuma referência ao Neo4j no núcleo (fonte ou binários), sem mover o módulo inteiro para o plugin.
- Q: Onde vive o código do plugin e como ele é publicado? → A: **Mesmo repositório, módulo próprio.** Um módulo deste repositório gera o pacote do plugin (com arquivo de licença e aviso de terceiros próprios); o pacote fica fora da pasta de release e é publicado como ativo separado do mesmo release do IPED. Repositório separado descartado.
- Q: A cópia autocontida do caso deve incluir o plugin quando ele está instalado? → A: **Sim (FR-010 mantido).** Quando o plugin está instalado na criação do caso, a cópia da aplicação embutida no caso o inclui, preservando o modelo de caso autocontido da feature 003: o caso abre com análise de vínculos em qualquer máquina. Descartados: plugin apenas por instalação; cópia configurável por caso.
- Q: Com o plugin ausente, o que fazer ao modificar um caso que já tem dados de vínculos (adicionar/remover evidência, continuar processamento interrompido)? → A: **Bloquear a operação.** Ela é recusada antes de qualquer alteração no caso, com mensagem explicando o motivo e a opção explícita de linha de comando para prosseguir; com essa opção, a operação conclui e o log e o console avisam que os dados de vínculos não foram atualizados. Nenhuma marca persistente no caso. Reiniciar do zero (`--restart`) descarta os dados anteriores e não é bloqueado. Descartados: só avisar no momento da operação; marca persistente com aviso na aba.
- Q: Qual a regra de compatibilidade de versão entre plugin e IPED? → A: **Compatibilidade pela versão do ponto de extensão.** O serviço de grafo (ponto de extensão) ganha número de versão próprio, incrementado só quando seu contrato muda; o plugin declara a versão do ponto de extensão que implementa e o IPED o aceita se ela for igual à que ele expõe, independentemente do número do release de cada um. Descartados: igualdade estrita de versão de release; compatibilidade por major.minor.

## User Scenarios & Testing *(mandatory)*

### User Story 1 - Usar o IPED completo sem o plugin de análise de vínculos (Priority: P1)

Um perito instala a distribuição padrão do IPED — que não contém mais nenhum componente do Neo4j — e usa a ferramenta normalmente: processa evidências pela linha de comando e analisa o caso na interface gráfica. A análise de vínculos simplesmente não está disponível: o processamento executa todas as outras etapas normalmente, não gera dados de vínculos e registra uma única mensagem clara explicando o motivo; a interface abre o caso sem oferecer a aba/ações de grafo e sem qualquer erro; busca, galeria, bookmarks, visualizadores, metadados, timeline, mapa, relatório e exportação funcionam como sempre.

**Why this priority**: É a promessa central do pedido ("caso não esteja presente, o IPED deve funcionar sem problemas") e é o que torna a separação de licença efetiva — a distribuição padrão é o que é publicado e redistribuído.

**Independent Test**: Gerar/obter a distribuição padrão; verificar que nenhum componente do Neo4j está presente; processar uma evidência de referência com o perfil padrão; abrir o caso na interface; executar o checklist de smoke da UI; comparar os resultados forenses com a versão anterior.

**Acceptance Scenarios**:

1. **Given** uma instalação padrão sem o plugin, **When** o conteúdo da distribuição é inventariado (bibliotecas, pasta de plugins, ferramentas), **Then** nenhum arquivo originário do projeto Neo4j (motor, biblioteca de API, cliente de conexão ou dependências exclusivas deles) está presente.
2. **Given** uma instalação padrão sem o plugin e o perfil padrão (geração de grafo habilitada na configuração), **When** um caso é processado, **Then** o processamento conclui com sucesso, todas as demais etapas produzem os mesmos resultados da versão anterior, nenhum dado de vínculos é criado no caso e o log contém exatamente um aviso explicando que a análise de vínculos foi pulada porque o plugin não está instalado.
3. **Given** um caso processado sem o plugin, **When** o perito o abre na interface de análise em uma instalação sem o plugin, **Then** a interface abre sem diálogos de erro, a aba de vínculos e as ações de grafo (menus, menu de contexto, botões, atalhos) não são oferecidas e todas as outras funções permanecem disponíveis.
4. **Given** um perfil customizado copiado de uma versão anterior que lista a etapa de grafo no pipeline, **When** o processamento roda sem o plugin, **Then** a etapa é ignorada com o mesmo aviso único e o processamento não aborta.
5. **Given** uma instalação sem o plugin e casos sem dados de vínculos, **When** qualquer outro ponto de entrada ou modo é usado (processamento com `--append`, `--continue`, `--restart`, `--yara-only`, `-nogui`; geração de relatório a partir de um caso; Web API; ferramenta de base de hashes), **Then** nenhum deles falha nem registra erro atribuível à ausência do plugin.

---

### User Story 2 - Instalar o plugin e recuperar a análise de vínculos (Priority: P2)

Um perito que precisa da análise de vínculos obtém o pacote do plugin (distribuído separadamente, compatível com a versão do IPED instalada), extrai-o na pasta de plugins da instalação e reinicia o IPED. A partir daí o processamento gera o grafo e a interface oferece a aba de vínculos com todas as operações atuais — comportamento idêntico ao de hoje. Se o perito remover o plugin, a instalação volta ao comportamento da User Story 1.

**Why this priority**: Devolve a funcionalidade a quem precisa dela; depende da separação feita em P1.

**Independent Test**: Instalar o plugin sobre uma instalação padrão; processar a mesma evidência de referência; comparar o grafo (contagens de entidades/relacionamentos e resultado das operações) com a versão anterior, que embarcava o Neo4j; remover o plugin e confirmar o retorno ao comportamento de P1.

**Acceptance Scenarios**:

1. **Given** uma instalação padrão e um pacote do plugin compatível, **When** o perito o extrai na pasta de plugins e reinicia o IPED, **Then** o IPED reconhece o plugin sem qualquer edição de configuração ou reconstrução, e o log informa a versão do plugin carregada e a versão do ponto de extensão que ele implementa.
2. **Given** o plugin instalado e a geração de grafo habilitada, **When** um caso é processado, **Then** os dados de vínculos são gerados dentro do caso exatamente como na versão anterior (mesmas entidades e relacionamentos para a mesma entrada), incluindo as opções de entidades por regex e relacionamentos de proximidade.
3. **Given** o plugin instalado, **When** o perito abre um caso que contém dados de vínculos, **Then** a aba de vínculos está disponível e todas as operações funcionam como antes (expandir, expandir com filtro, encontrar conexões/caminhos, buscar entidades/vínculos, mostrar evidência, remover, desfazer/refazer, exportar imagem/vínculos, layouts).
4. **Given** o plugin instalado, **When** o perito adiciona evidência a um caso ou remove evidência dele, **Then** os dados de vínculos são atualizados como na versão anterior.
5. **Given** o plugin instalado, **When** um caso é criado, **Then** a cópia autocontida da aplicação embutida no caso inclui o plugin, de modo que o caso abre com a análise de vínculos disponível a partir da sua própria cópia, em qualquer máquina.
6. **Given** o plugin removido da pasta de plugins, **When** o IPED é reiniciado, **Then** o comportamento volta ao da User Story 1, sem erros residuais, e os casos existentes continuam abrindo.

---

### User Story 3 - Compatibilidade com casos e configurações existentes (Priority: P3)

Um perito possui casos processados por versões anteriores (que contêm dados de vínculos) e perfis/arquivos de configuração customizados. Com ou sem o plugin, tudo isso continua funcionando: os casos abrem; se o plugin está presente, os dados de vínculos são usados; se não está, o perito é informado de que o caso contém dados de vínculos que exigem o plugin — informação, não erro; nenhum arquivo de configuração precisa ser editado.

**Why this priority**: Casos forenses vivem por anos (Princípio I da constituição) e perfis customizados são comuns nas equipes; quebrar qualquer um dos dois seria regressão grave, mas o cenário depende de P1 e P2 estarem definidos.

**Independent Test**: Abrir um caso pré-existente com dados de vínculos em uma instalação sem o plugin (aviso, sem erro) e em uma com o plugin (grafo funciona); carregar um perfil da versão anterior e confirmar que não requer edição.

**Acceptance Scenarios**:

1. **Given** um caso cujos dados de vínculos foram produzidos por uma versão compatível, **When** ele é aberto em uma instalação sem o plugin, **Then** o caso abre normalmente e o perito é informado uma única vez, de forma não bloqueante, de que o caso contém dados de vínculos que exigem o plugin.
2. **Given** o mesmo caso, **When** ele é aberto em uma instalação com o plugin, **Then** a aba de vínculos funciona sobre os dados existentes, sem reprocessamento.
3. **Given** arquivos de configuração e perfis da versão anterior (incluindo a chave de geração de grafo e a entrada da etapa de grafo no pipeline), **When** são carregados pela nova versão, com ou sem o plugin, **Then** nenhuma edição é necessária e nenhum erro ocorre.
4. **Given** um caso com dados de vínculos e uma instalação sem o plugin, **When** o perito tenta adicionar ou remover evidência do caso (ou continuar um processamento interrompido dele), **Then** a operação é recusada antes de qualquer alteração no caso, com mensagem explicando que o caso contém dados de vínculos, que o plugin está ausente e qual opção explícita permite prosseguir.
5. **Given** o mesmo caso e a mesma instalação, **When** o perito repete a operação com a opção explícita de prosseguir, **Then** a operação conclui e o log e o console avisam que os dados de vínculos do caso não foram atualizados e podem não refletir mais o conteúdo do caso.

---

### User Story 4 - Distribuir o IPED e o plugin com licenciamento claro (Priority: P4)

O mantenedor gera o release: o pacote padrão não contém o Neo4j e seus avisos de terceiros não o listam mais como componente embarcado; o plugin é produzido como pacote separado, com seu próprio texto de licença e avisos de terceiros; a documentação ao usuário explica que a análise de vínculos é um plugin opcional (o que adiciona, onde obter, como instalar, compatibilidade de versão, como remover); a construção e os testes automatizados da distribuição padrão passam sem o plugin instalado.

**Why this priority**: A motivação de licença só se cumpre se distribuição, avisos e documentação forem consistentes; depende de P1 e P2.

**Independent Test**: Executar a construção do projeto; inspecionar o pacote de release e o pacote do plugin; conferir avisos de terceiros e documentação.

**Acceptance Scenarios**:

1. **Given** a construção do projeto, **When** o pacote padrão de release é produzido, **Then** ele não contém componente algum do Neo4j e seu aviso de terceiros não descreve o Neo4j como componente embarcado.
2. **Given** a construção do projeto, **When** o pacote do plugin é produzido, **Then** ele é um pacote instalável separado, que carrega seu próprio texto de licença e avisos de terceiros cobrindo o motor de grafos e suas dependências, e declara a versão do ponto de extensão que implementa e a versão do IPED com que foi construído.
3. **Given** a documentação ao usuário, **When** um perito procura a análise de vínculos, **Then** a documentação afirma que é um plugin opcional e descreve como obtê-lo, instalá-lo, verificar a compatibilidade de versão e removê-lo.
4. **Given** um ambiente sem o plugin instalado, **When** a construção e os testes automatizados da distribuição padrão são executados, **Then** eles passam.

---

### Edge Cases

- **Plugin incompleto ou corrompido** (arquivos faltando ou ilegíveis na pasta de plugins): tratado como ausente; uma única mensagem de erro no log identifica o problema; o IPED segue sem análise de vínculos.
- **Versão do ponto de extensão declarada pelo plugin diferente da exposta pelo IPED** (plugin mais antigo ou mais novo que o núcleo): o plugin não é carregado; a mensagem informa as duas versões do ponto de extensão e as versões de release envolvidas; o IPED segue sem análise de vínculos.
- **Mais de uma cópia/versão do plugin** na pasta de plugins: tratado como configuração inválida; nenhuma cópia é carregada e a mensagem explica o conflito.
- **Plugin ausente e arquivo de configurações do grafo presente** (é o arquivo padrão da distribuição): ignorado silenciosamente.
- **Plugin ausente e caso com dados de vínculos** submetido a adição/remoção de evidência ou à continuação de um processamento interrompido: a operação é recusada antes de qualquer alteração, salvo opção explícita de prosseguir — com ela, prossegue com aviso de dados de vínculos desatualizados (ver User Story 3). Reiniciar do zero não é bloqueado: descarta os dados anteriores e o caso resultante fica sem dados de vínculos (aviso único do FR-004).
- **Plugin presente, mas o motor de grafos falha ao iniciar** (porta ocupada, dados de vínculos corrompidos no caso): aplica-se o tratamento de erro já existente; a falha fica contida no componente de vínculos e não afeta o restante do processamento nem da interface — como hoje, pois o motor continua isolado em processo próprio.
- **Caso criado sem o plugin e aberto depois em instalação com o plugin** (ou vice-versa): o caso abre normalmente; a aba de vínculos, quando disponível, mostra o estado "sem dados de vínculos" já existente para casos processados com a geração de grafo desabilitada.
- **Interface multi-caso**: se algum dos casos abertos contém dados de vínculos e o plugin está ausente, o aviso informativo é exibido uma única vez na sessão.
- **Processamento sem interface (`-nogui`) e Linux**: aplicam-se as mesmas regras de detecção, aviso e degradação; o procedimento de instalação do plugin é o mesmo em Windows e Linux.

## Requirements *(mandatory)*

### Functional Requirements

- **FR-001**: A distribuição padrão do IPED MUST NOT conter nenhum componente originário do projeto Neo4j — motor de grafos, biblioteca de API, cliente de conexão ou dependências exclusivas deles — nem código do IPED que referencie o Neo4j. O núcleo MUST manter a modelagem de entidades e vínculos, a geração dos dados de vínculos durante o processamento e a interface de análise de vínculos escritas contra abstrações próprias do IPED, tendo o serviço de grafo como ponto de extensão. O plugin, distribuído separadamente, MUST fornecer as bibliotecas do Neo4j e a implementação desse serviço sobre elas (criação/importação do banco de grafos, consultas, motor isolado); sem o plugin, a análise de vínculos fica indisponível.
- **FR-002**: O plugin MUST ser instalável extraindo/copiando seu pacote para a pasta de plugins já suportada pela instalação (de localização configurável), sem editar arquivos de configuração, sem reconstruir o IPED e sem ajustes manuais de carregamento; reiniciar o IPED MUST ser suficiente para ativá-lo.
- **FR-003**: Na inicialização, todo ponto de entrada (processamento por linha de comando, interface de análise, ferramentas auxiliares) MUST detectar se o plugin está instalado e utilizável e MUST registrar no log o resultado: versão do plugin carregada, ausência, ou o motivo da rejeição (incompleto, versão incompatível, cópias conflitantes). A detecção MUST NOT atrasar perceptivelmente a inicialização.
- **FR-004**: Com o plugin ausente, o processamento MUST concluir todas as demais etapas do pipeline com resultados idênticos aos obtidos antes desta feature (itens, hashes, assinaturas/MIME, categorias, texto extraído, itens carved, matches de regex/YARA, timeline, índice, relatório), MUST NOT criar dados de vínculos no caso e MUST emitir exatamente um aviso explicando que a análise de vínculos foi pulada sempre que a geração de grafo estiver habilitada na configuração.
- **FR-005**: Os arquivos de configuração e perfis existentes (os padrão e as cópias customizadas de versões anteriores), incluindo a chave de geração de grafo, a entrada da etapa de grafo no pipeline e o arquivo de configurações do grafo, MUST permanecer válidos com e sem o plugin: nenhuma edição pode ser exigida para executar o IPED em qualquer das duas situações, nem para ativar a análise de vínculos após instalar o plugin quando a chave já está habilitada.
- **FR-006**: Com o plugin ausente, a interface de análise MUST abrir qualquer caso sem diálogos de erro e sem erros no log atribuíveis à ausência; a aba de vínculos e todas as ações relacionadas a grafo (menus, menu de contexto, botões, atalhos) MUST NOT ser oferecidas; todas as demais funções da interface MUST permanecer disponíveis.
- **FR-007**: Com o plugin ausente, quando o caso aberto contém dados de vínculos, a interface MUST informar o perito uma única vez por sessão, de forma não bloqueante (sem diálogo modal de erro), que o caso contém dados de vínculos que exigem o plugin, e SHOULD indicar onde obtê-lo.
- **FR-008**: Com o plugin ausente, operações que alterariam o conjunto de evidências ou itens de um caso que já possui dados de vínculos (adicionar evidência, remover evidência, continuar um processamento interrompido) MUST ser recusadas antes de qualquer alteração no caso, com mensagem que explique o motivo e indique a opção explícita de linha de comando para prosseguir. Com essa opção, a operação MUST concluir normalmente e MUST avisar no log e no console que os dados de vínculos do caso não foram atualizados e podem não refletir mais o conteúdo do caso. Reiniciar o processamento do zero MUST NOT ser bloqueado: descarta os dados de vínculos anteriores e o caso resultante fica sem eles.
- **FR-009**: Com o plugin presente, a análise de vínculos MUST se comportar exatamente como antes desta feature: geração durante o processamento (incluindo entidades por regex e relacionamentos de proximidade, quando configurados), atualização na adição/remoção de evidência, aba de vínculos com todas as suas operações, e o mesmo arquivo de configurações do grafo. O motor de grafos MUST continuar executando isolado do processo principal, como hoje, de modo que falhas nele não derrubem o processamento nem a interface.
- **FR-010**: Casos autocontidos: quando o plugin está instalado no momento da criação de um caso, a cópia da aplicação embutida no caso MUST incluir o plugin, de modo que o caso abra com a análise de vínculos disponível a partir da própria cópia; quando ausente, a cópia simplesmente não o inclui e o caso MUST abrir normalmente.
- **FR-011**: O ponto de extensão (serviço de grafo) MUST ter um número de versão próprio, exposto pelo núcleo e incrementado sempre que seu contrato mudar. O pacote do plugin MUST declarar a versão do ponto de extensão que implementa e, para fins informativos, a versão do IPED com que foi construído. O IPED MUST carregar apenas um plugin cuja versão declarada do ponto de extensão seja igual à que ele expõe, independentemente do número de release de ambos; caso contrário, MUST recusá-lo com mensagem clara que informe as duas versões e seguir sem análise de vínculos.
- **FR-012**: O plugin MUST ser tratado como ausente — com mensagem de log clara identificando o problema — quando estiver incompleto, corrompido ou quando houver mais de uma cópia na pasta de plugins; em nenhum desses casos o IPED pode falhar ao iniciar ou ao processar.
- **FR-013**: O pacote do plugin MUST carregar seu próprio texto de licença e avisos de terceiros cobrindo o motor de grafos e suas dependências; os avisos de terceiros da distribuição padrão MUST NOT mais listar o motor de grafos como componente embarcado; e a documentação ao usuário (lista de funcionalidades, guia de instalação, notas de versão) MUST descrever a análise de vínculos como plugin opcional, incluindo como obtê-lo, instalá-lo, verificar a compatibilidade de versão e removê-lo.
- **FR-014**: A construção e os testes automatizados da distribuição padrão MUST NOT depender do plugin instalado. O plugin MUST ser produzido a partir deste mesmo repositório, por um módulo próprio, como pacote instalável separado — com arquivo de licença e aviso de terceiros próprios —, nunca colocado dentro do pacote padrão de release, e publicado como ativo separado do mesmo release do IPED.
- **FR-015**: Remover o plugin (apagá-lo da pasta de plugins) MUST devolver a instalação ao comportamento de "plugin ausente" sem erros residuais; os dados de vínculos dos casos existentes MUST ser preservados em disco (apenas não utilizados).
- **FR-016**: Toda mensagem nova visível ao perito (avisos na interface, dicas de ferramenta, textos de documentação embutida) MUST estar localizada pelo menos em PT-BR e EN.
- **FR-017**: O comportamento especificado MUST valer em todas as plataformas suportadas pelo IPED (Windows e Linux), com o mesmo pacote de plugin e o mesmo procedimento de instalação.

### Key Entities *(include if feature involves data)*

- **Plugin de análise de vínculos**: pacote distribuído separadamente que torna a análise de vínculos operacional. Atributos: versão própria, versão do ponto de extensão que implementa (critério de compatibilidade), versão do IPED com que foi construído (informativa), conteúdo (bibliotecas do Neo4j, implementação do serviço de grafo do IPED sobre o Neo4j, textos de licença e avisos de terceiros próprios), local de instalação (pasta de plugins) e estado em uma instalação (ausente, carregado, rejeitado com motivo).
- **Serviço de grafo (ponto de extensão)**: abstração própria do IPED, já consumida pela interface de análise, pela qual o núcleo cria/importa, consulta e atualiza os dados de vínculos sem conhecer o motor de grafos. O plugin fornece sua implementação. Possui número de versão próprio, incrementado a cada mudança de contrato, que é o critério de compatibilidade com o plugin. Estados: sem implementação disponível (plugin ausente ou rejeitado) ou implementação carregada.
- **Pasta de plugins**: área da instalação destinada a componentes opcionais, com localização configurável; já usada hoje para bibliotecas opcionais não embarcadas por restrição de licença. É espelhada na cópia autocontida embutida em cada caso quando contém o plugin.
- **Dados de vínculos do caso**: entidades e relacionamentos produzidos durante o processamento e armazenados dentro do caso. Podem existir em casos de versões anteriores. Só são criados/atualizados quando o plugin está presente; são preservados (e não usados) quando ausente; só ficam desatualizados se o perito prosseguir explicitamente com uma modificação do caso sem o plugin (FR-008).
- **Chave de geração de grafo**: configuração existente que habilita a geração de dados de vínculos (habilitada no perfil padrão; desabilitada nos perfis `fastmode` e `triage`). Passa a significar "gerar quando o plugin estiver disponível".
- **Distribuição padrão do IPED**: pacote de release publicado. Invariante: não contém componente do motor de grafos; inclui documentação e avisos de terceiros coerentes com isso.

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: Um inventário automatizado do pacote padrão de release reporta **0 arquivos** originários do projeto Neo4j.
- **SC-002**: Processar a evidência de referência com o perfil padrão em uma instalação sem o plugin conclui com sucesso, com **100% de igualdade** no conjunto de resultados forenses definido na feature 003 (hashes, assinatura/MIME, texto extraído, contagens/categorias, itens carved, matches YARA, timeline) em relação à versão anterior, com **exatamente 1 aviso** sobre a análise de vínculos pulada e **0 erros** no log.
- **SC-003**: Um perito seguindo a documentação instala o plugin em **no máximo 3 passos** (obter, extrair na pasta de plugins, reiniciar), em **menos de 5 minutos**, com **0 edições** de configuração.
- **SC-004**: Com o plugin instalado, o caso de referência produz dados de vínculos iguais aos da versão anterior — **mesmas contagens** de entidades e relacionamentos e **resultados idênticos** para um roteiro fixo de 10 operações na aba de vínculos.
- **SC-005**: O checklist de smoke da interface (todas as funções que não são de grafo) passa **100%** em uma instalação sem o plugin, com **0 diálogos de erro** e **0 stack traces** no log, tanto para um caso com dados de vínculos quanto para um caso sem eles.
- **SC-006**: O tempo de inicialização do processamento e da interface de análise varia **menos de 1 segundo** em relação à versão anterior, na mesma máquina (custo da detecção do plugin).
- **SC-007**: **100%** dos arquivos de configuração e perfis distribuídos com a versão anterior carregam sem modificação, com e sem o plugin.
- **SC-008**: Após desinstalar o plugin e reiniciar, SC-002 e SC-005 se repetem com **0 erros residuais**.
- **SC-009**: A construção e os testes automatizados da distribuição padrão passam em um ambiente em que o plugin **não** está instalado.

## Assumptions

- **Interpretação estrita de "componente do Neo4j"**: motor, biblioteca de API e cliente de conexão saem todos da distribuição padrão, mesmo que o cliente tenha licença permissiva — uma separação parcial não atenderia à motivação, e manter o cliente sem a API seria inútil. O módulo de análise de vínculos do IPED (modelagem, geração dos dados e interface) permanece no núcleo, escrito contra abstrações próprias (ver Clarifications). A análise jurídica das licenças está fora de escopo; a feature entrega a separação independentemente do seu desfecho.
- **Construir o módulo do plugin por padrão ou apenas sob demanda** (de modo que a construção padrão não precise sequer obter as bibliotecas do Neo4j) é decisão de planejamento; a spec exige apenas que a construção e os testes da distribuição padrão não dependam do plugin (FR-014).
- **Versionamento do ponto de extensão**: o número de versão do serviço de grafo começa em 1 nesta feature e só muda quando o contrato muda. Cada release do IPED continua publicando um plugin correspondente, mesmo que a versão do ponto de extensão não tenha mudado; o perito pode manter o plugin anterior enquanto ele for compatível.
- **A chave de geração de grafo mantém o padrão atual** (habilitada no perfil padrão; desabilitada em `fastmode` e `triage`) com o significado "gerar quando o plugin estiver disponível". A ausência produz um único aviso. Justificativa: instalar o plugin é "soltar na pasta e reiniciar", sem edição de configuração; e um aviso no log evita degradação silenciosa.
- **Plugin ausente ⇒ aba oculta**, em vez de uma aba desabilitada; o aviso informativo só aparece quando o caso aberto tem dados de vínculos. Justificativa: evitar interface "morta" e, ao mesmo tempo, informar o perito quando a informação é relevante.
- **Pontos de entrada cobertos**: processamento por linha de comando em todos os modos (incluindo `--append`, `--continue`, `--restart`, `--yara-only`, `-nogui`), interface de análise (caso único e multi-caso), geração de relatório a partir de um caso, Web API e ferramenta de base de hashes. A Web API não possui endpoint de análise de vínculos hoje e continua sem ele.
- **Modelo de caso autocontido preservado** (decisão da feature 003: cada caso carrega o runtime e as bibliotecas usadas no processamento). O plugin, quando presente, passa a integrar essa cópia. Casos criados por versões anteriores mantêm sua própria cópia embutida (que inclui o Neo4j) e continuam sendo analisados com ela; esta feature não os altera retroativamente.
- **Mecanismo de plugins reutilizado, não quebrado**: outros componentes opcionais já colocados na pasta de plugins (por exemplo, modelos de NER, decodificador de Telegram) não são afetados em seu funcionamento; se eles também passam a viajar na cópia do caso junto com o plugin de análise de vínculos é decisão de planejamento (hoje não viajam).
- **Pacote único para Windows e Linux** (o motor de grafos é independente de plataforma, como hoje).
- **As funcionalidades e a UX da análise de vínculos não mudam**; esta feature altera apenas disponibilidade, empacotamento, avisos e documentação.
