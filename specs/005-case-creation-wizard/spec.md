# Feature Specification: Criação e Abertura de Casos na GUI RCP (assistente de Novo Caso + gestão de perfis)

**Feature Branch**: `005-case-creation-wizard`

**Created**: 2026-06-16

**Status**: Draft

**Input**: User description: "Vamos aposentar o aplicativo de criação de casos (iped.exe) e criar uma entrada de menu no novo aplicativo RCP criado (iped.ui.exe) para criar o caso. Nesse menu deve ter as entradas Open Case e New Case. O Open Case dispensa comentários. Quando o usuário clicar em New Case o sistema mostrará um Wizard contendo as opções de criação do caso (endereço da evidência a ser analisada, perfil escolhido, etc...). É importante também ter uma tela de criação/edição de perfis, permitindo o investigador criar perfis novos."

## Visão geral

Hoje a criação de um caso no IPED é uma operação à parte da análise: o investigador
usa um lançador/aplicativo de criação de casos (o executável `iped.exe`) e/ou a
linha de comando para escolher a evidência, a pasta de saída e o perfil de
processamento, dispara o processamento e só depois abre o caso para análise. Essa
separação obriga o usuário a transitar entre dois mundos (criação fora da UI de
análise, muitas vezes via terminal) e expõe um lançador autônomo que duplica a
porta de entrada do produto.

Esta feature **unifica a entrada do produto na nova UI RCP** (a aplicação de análise
migrada na feature 004 — `iped.ui.exe`). O investigador passa a abrir e criar casos
diretamente pelo menu da bancada de análise:

- **Open Case** — abre um caso já processado para análise.
- **New Case** — abre um **assistente (wizard)** que reúne as opções de criação de
  caso (fontes de evidência, pasta de saída, perfil de processamento e demais
  opções), dispara o processamento (com a janela de progresso já migrada na 004) e,
  ao final, oferece abrir o caso recém-criado.

Além do wizard, a feature entrega uma **tela de criação/edição de perfis**, para que
o investigador crie perfis novos (tipicamente clonando um perfil existente e
ajustando quais funcionalidades de processamento ficam ativas e suas opções), em vez
de editar arquivos de configuração manualmente.

Com isso, a **criação interativa de casos deixa de depender do `iped.exe`** e passa a
ocorrer pela UI RCP. O `iped.exe` **permanece distribuído** como porta de execução
headless (automação, scripts, servidores) — apenas deixa de ser a porta promovida para
criação interativa. A remoção completa do `iped.exe` fica como passo **futuro**,
condicionado a o novo launcher RCP ganhar um modo headless equivalente (ver
Clarifications e Assumptions).

A feature é **preservadora de comportamento de processamento**: o resultado forense
de um caso criado pelo wizard com um dado perfil é equivalente ao do mesmo perfil
executado hoje. O ganho está na ergonomia (porta de entrada única, sem terminal) e na
gestão de perfis assistida.

## Clarifications

### Session 2026-06-16

- Q: Ao "aposentar o iped.exe", o que exatamente é retirado da distribuição? → A:
  Apenas o lançador gráfico autônomo de criação de casos; o motor de processamento por
  linha de comando (headless) permanece para automação, scripts e servidores.
- Q: Qual a profundidade da tela de criação/edição de perfis? → A: Editor completo — a
  UI expõe para edição todas as opções de configuração do pipeline que um perfil
  parametriza (não apenas toggles e opções comuns).
- Q: Quando o investigador pode abrir o caso recém-criado para análise no fluxo do
  wizard? → A: Durante o processamento, em modo somente-leitura quase-ao-vivo (feature
  004), atualizando a cada consolidação; também após a conclusão.
- Q: O wizard precisa cobrir todas as opções de criação da CLI atual ou um subconjunto
  curado? → A: Subconjunto curado de opções comuns na UI (+ etapa "avançado"); flags
  raras/de especialista permanecem na linha de comando/arquivos de configuração e
  documentadas.
- Q: O `iped.exe` é removido nesta feature? → A: Não — `iped.exe` **permanece
  distribuído** como entry headless (automação/servidores); apenas deixa de ser a porta
  promovida para criação interativa. A remoção completa fica para o futuro, quando o
  novo launcher RCP oferecer um modo headless equivalente (remediação do achado I1 do
  `/speckit-analyze`).

## User Scenarios & Testing *(mandatory)*

### User Story 1 - Criar um caso pelo assistente de Novo Caso (Priority: P1)

Um investigador abre a UI de análise, escolhe **New Case** no menu e percorre um
assistente: adiciona a(s) fonte(s) de evidência (uma imagem forense, uma pasta, um
caso ou um relatório), escolhe a pasta de saída do caso, seleciona o perfil de
processamento e, se quiser, ajusta opções comuns (nome da evidência, fuso horário,
OCR, senhas, palavras-chave-alvo, etc.). Ao concluir, o processamento inicia e a
janela de progresso é exibida; o assistente oferece abrir o caso em modo
quase-ao-vivo durante o processamento, e ele permanece abrível normalmente após a
conclusão — tudo sem abrir um terminal nem o antigo lançador.

**Why this priority**: É a capacidade central que substitui o lançador `iped.exe` e
elimina a dependência de linha de comando para o fluxo interativo. Sem ela não há
razão para a feature existir — é o MVP (escolher evidência → perfil → processar →
abrir).

**Independent Test**: Em uma distribuição limpa, sem usar terminal, criar um caso a
partir de uma evidência de referência pelo wizard, acompanhar o progresso até o fim e
abri-lo na UI de análise; comparar o caso resultante (itens, categorias, índice) com
um caso criado pelo fluxo atual usando o mesmo perfil e a mesma evidência.

**Acceptance Scenarios**:

1. **Given** a UI de análise aberta sem nenhum caso, **When** o investigador escolhe
   "New Case", **Then** um assistente é exibido com etapas para fonte(s) de
   evidência, pasta de saída, perfil e opções.
2. **Given** uma fonte de evidência válida, uma pasta de saída gravável e um perfil
   selecionados, **When** o investigador conclui o assistente, **Then** o
   processamento inicia e a janela de progresso é exibida, sem necessidade de
   terminal/CLI.
3. **Given** um processamento iniciado pelo assistente, **When** o assistente oferece
   abrir o caso, **Then** o caso abre na UI de análise em modo somente-leitura
   quase-ao-vivo durante o processamento (atualizando a cada consolidação) e
   permanece abrível normalmente após a conclusão.
4. **Given** o mesmo perfil e a mesma evidência, **When** o caso é criado pelo
   assistente e pelo fluxo atual, **Then** os dois casos são forensicamente
   equivalentes (mesmo universo de itens, categorias e campos indexados).
5. **Given** o assistente em qualquer etapa, **When** o investigador o cancela ou
   fecha, **Then** nenhum caso é criado e nenhum artefato parcial é deixado.

---

### User Story 2 - Abrir um caso existente pelo menu (Priority: P2)

Um investigador escolhe **Open Case** no menu, seleciona a pasta de um caso já
processado e o caso abre na UI de análise, exatamente como hoje, porém a partir da
nova porta de entrada do menu.

**Why this priority**: É a operação mais frequente e a contraparte natural do "New
Case" no menu; a capacidade de abrir casos já existe (feature 004), aqui ela é
exposta pela entrada de menu. Alto valor, baixo esforço incremental.

**Independent Test**: Com um caso de referência já processado, usar "Open Case" para
selecioná-lo e verificar que abre na UI de análise com a mesma contagem de itens e
comportamento da abertura atual.

**Acceptance Scenarios**:

1. **Given** a UI de análise aberta, **When** o investigador escolhe "Open Case" e
   seleciona uma pasta de caso processado, **Then** o caso abre na UI de análise sem
   conversão nem reprocessamento.
2. **Given** uma seleção que não é um caso válido, **When** o investigador confirma,
   **Then** o sistema informa claramente que a pasta não contém um caso e não falha.
3. **Given** um caso aberto recentemente, **When** o investigador retorna ao menu,
   **Then** o caso aparece em uma lista de casos recentes para reabertura rápida.

---

### User Story 3 - Criar e editar perfis de processamento (Priority: P3)

Um investigador abre a tela de gestão de perfis (pelo menu ou a partir do passo de
seleção de perfil do assistente), cria um novo perfil — tipicamente clonando um
perfil existente — dá-lhe um nome, habilita/desabilita funcionalidades de
processamento e ajusta as opções de configuração do pipeline expostas pelo perfil. O
novo perfil passa a aparecer na lista de perfis selecionáveis do assistente de Novo
Caso.

**Why this priority**: Dá autonomia ao investigador para adaptar o processamento ao
tipo de caso sem editar arquivos de configuração manualmente, e completa a proposta
de criação de casos assistida. Depende de existir o conceito de perfil já usado pelo
wizard (P1), por isso vem depois.

**Independent Test**: Criar um novo perfil clonando um perfil embarcado, alterar ao
menos uma funcionalidade (habilitar/desabilitar), salvá-lo, abrir o assistente de
Novo Caso e confirmar que o novo perfil está disponível e, ao ser usado, produz um
caso coerente com as escolhas feitas.

**Acceptance Scenarios**:

1. **Given** a tela de perfis aberta, **When** o investigador cria um perfil novo a
   partir de um existente e o nomeia, **Then** o perfil é salvo como perfil de
   usuário com nome único.
2. **Given** um perfil de usuário em edição, **When** o investigador habilita/
   desabilita funcionalidades e ajusta opções e salva, **Then** as alterações
   persistem e refletem na próxima vez que o perfil é usado.
3. **Given** um perfil embarcado (forensic, pedo, triage, fastmode, blind), **When**
   o investigador tenta editá-lo, **Then** o sistema preserva o perfil embarcado
   original (salvando as alterações como um novo perfil de usuário ou exigindo
   confirmação explícita), sem sobrescrevê-lo silenciosamente.
4. **Given** um perfil de usuário recém-criado, **When** o investigador abre o
   assistente de Novo Caso, **Then** o novo perfil aparece na lista de perfis
   selecionáveis.
5. **Given** um nome de perfil já existente, **When** o investigador tenta criar
   outro com o mesmo nome, **Then** o sistema impede a colisão e solicita um nome
   diferente.

---

### Edge Cases

- **Pasta de saída já existente/não vazia**: o assistente deve detectar e oferecer
  caminhos claros — adicionar evidência ao caso existente (append/continue),
  reprocessar do zero (restart) ou escolher outra pasta — em vez de sobrescrever sem
  aviso.
- **Fonte de evidência inválida, ilegível ou de formato não suportado**: o
  assistente valida e bloqueia o avanço, com mensagem clara, sem iniciar
  processamento.
- **Sem permissão de escrita / espaço insuficiente na pasta de saída**: detectado
  antes de iniciar, com mensagem clara.
- **Processamento falha no meio**: a janela de progresso reporta o erro (paridade
  com a 004); o caso fica em estado retomável, e o usuário entende como prosseguir.
- **Iniciar um novo processamento conflitando com outro em andamento sobre a mesma
  saída**: o sistema impede ou trata o conflito com clareza.
- **Editar perfil embarcado**: nunca sobrescreve o perfil distribuído; cria perfil de
  usuário ou pede confirmação.
- **Nome de perfil inválido ou em colisão**: rejeitado com orientação.
- **Perfil seleciona funcionalidades que dependem de ferramentas externas
  indisponíveis na máquina**: o assistente/editor avisa, e o comportamento degrada
  como hoje (a funcionalidade indisponível é pulada/sinalizada no processamento).
- **Abrir como caso algo que não é um caso** (pasta arbitrária): mensagem clara, sem
  travar a aplicação.
- **Cancelar o assistente a qualquer momento**: sem efeitos colaterais (nada é criado
  antes da conclusão).
- **Idioma sem tradução das novas telas**: fallback para inglês, sem chaves de
  mensagem cruas.

## Requirements *(mandatory)*

### Functional Requirements

**Menu e navegação**

- **FR-001**: A UI RCP MUST oferecer, no menu principal, as entradas **"Open Case"**
  (Abrir Caso) e **"New Case"** (Novo Caso), localizadas, como porta de entrada para
  abrir e criar casos.
- **FR-002**: "Open Case" MUST permitir selecionar, via diálogo de arquivo nativo, a
  pasta de um caso já processado e abri-lo na UI de análise, com paridade ao
  mecanismo de abertura de caso atual (sem conversão nem reprocessamento). O sistema
  SHOULD oferecer uma lista de casos recentes para reabertura rápida.
- **FR-003**: "New Case" MUST iniciar um assistente (wizard) de criação de caso.

**Assistente de Novo Caso**

- **FR-004**: O assistente MUST permitir adicionar uma ou mais fontes de evidência
  (imagens forenses, pastas/fontes lógicas, casos, relatórios UFED, AD1 e demais
  fontes hoje suportadas pelo motor), com seleção via diálogos de arquivo/pasta
  nativos.
- **FR-005**: O assistente MUST permitir definir a pasta de saída do caso e validar
  que ela é gravável.
- **FR-006**: O assistente MUST permitir escolher um perfil de processamento entre os
  perfis disponíveis (embarcados + criados pelo usuário), com o perfil padrão atual
  pré-selecionado.
- **FR-007**: O assistente MUST expor um **subconjunto curado** das opções de criação
  de caso comumente usadas (entre elas: nome da evidência, fuso horário, OCR, senhas,
  palavras-chave-alvo, atribuição de proprietário), agrupadas com defaults sensatos e
  com as opções avançadas em uma etapa/secção "avançado". Flags raras/de especialista
  PODEM permanecer disponíveis apenas pela interface de linha de comando/arquivos de
  configuração, documentadas como tais (consistente com FR-021).
- **FR-008**: O assistente MUST validar as entradas a cada etapa e impedir avançar/
  concluir enquanto houver entradas inválidas (fonte inexistente/ilegível, saída não
  gravável, perfil inválido, conflito de saída não resolvido), com mensagens claras.
- **FR-009**: O assistente MUST suportar, sobre uma pasta de saída existente, os modos
  de execução atuais — **adicionar evidência** (append/continue) e **reprocessar**
  (restart) — com confirmação explícita do usuário antes de executar.
- **FR-010**: Concluir o assistente MUST iniciar o processamento e exibir a janela de
  progresso (a migrada na feature 004), sem exigir uso de terminal/CLI pelo usuário.
- **FR-011**: Após iniciar o processamento, o assistente MUST oferecer abrir o caso em
  processamento na UI de análise em **modo somente-leitura quase-ao-vivo** (feature
  004) — exibindo os dados já consolidados e atualizando-se a cada nova consolidação;
  o caso também MUST permanecer abrível normalmente após a conclusão do processamento.
- **FR-012**: Cancelar ou fechar o assistente antes da conclusão MUST NOT criar caso
  nem deixar artefatos parciais.
- **FR-013**: O caso criado pelo assistente com um dado perfil e evidência MUST ser
  forensicamente equivalente ao criado pelo fluxo atual com o mesmo perfil e
  evidência (mesmo universo de itens, categorias e campos indexados).

**Gestão de perfis**

- **FR-014**: O sistema MUST oferecer uma tela de criação/edição de perfis de
  processamento, acessível pela UI e a partir do passo de seleção de perfil do
  assistente.
- **FR-015**: O usuário MUST poder criar um novo perfil, partindo de um perfil
  existente (clonar) ou de um perfil base, atribuindo-lhe um nome único.
- **FR-016**: O editor de perfis MUST expor para edição **todas as opções de
  configuração do pipeline** que um perfil parametriza — tanto as flags de habilitação
  de funcionalidades quanto os parâmetros dos componentes de processamento — com
  rótulos e descrições localizados e validação por opção.
- **FR-017**: Perfis criados ou editados pelo usuário MUST ficar imediatamente
  disponíveis para seleção no assistente de Novo Caso.
- **FR-018**: Perfis embarcados (forensic, pedo, triage, fastmode, blind) MUST NOT ser
  sobrescritos silenciosamente: editar sobre um perfil embarcado cria um novo perfil
  de usuário ou exige confirmação explícita; os perfis distribuídos permanecem
  íntegros.
- **FR-019**: O sistema MUST validar o nome do perfil (unicidade e caracteres
  válidos) e impedir colisões de nome.

**Transição (`iped.exe` deixa de ser a porta de criação interativa)**

- **FR-020**: A criação interativa de casos MUST passar a ocorrer pela UI RCP, que se
  torna a porta promovida (menu, documentação, atalhos). O `iped.exe` MUST deixar de
  ser apresentado/promovido como o caminho de criação interativa; não é necessário usá-lo
  (nem um terminal) para criar um caso.
- **FR-021**: O `iped.exe`/motor de processamento dirigido por linha de comando (execução
  headless) MUST **permanecer distribuído e inalterado** para automação, scripts e
  cenários de servidor. A remoção do `iped.exe` é um passo **futuro fora do escopo
  desta feature**, condicionado a o novo launcher RCP oferecer um modo headless
  equivalente (decisão registrada nas Clarifications 2026-06-16).

**Consistência com a plataforma RCP (feature 004)**

- **FR-022**: As novas telas (assistente, diálogos de arquivo/pasta, editor de
  perfis) MUST apresentar aparência e diálogos nativos do sistema operacional
  hospedeiro, em consistência com a feature 004.
- **FR-023**: Todos os textos visíveis das novas telas MUST ser localizados no
  conjunto de idiomas hoje suportado pela UI (paridade com a feature 004), com
  fallback para inglês.
- **FR-024**: O sistema MUST impedir, ou tratar com clareza, o início de um novo
  processamento que conflite com outro processamento já em andamento sobre a mesma
  pasta de saída.

### Key Entities

- **Caso**: saída do processamento (índice, dados extraídos, configurações e
  artefatos do usuário). Pode ser aberto (Open Case) ou criado (New Case).
- **Fonte de evidência (data source)**: entrada a ser processada — imagem forense,
  pasta/fonte lógica, caso, relatório UFED, AD1; um caso pode ter várias.
- **Perfil de processamento**: conjunto nomeado de funcionalidades habilitadas e
  opções que parametriza o pipeline. Pode ser **embarcado** (somente leitura como
  template) ou **de usuário** (criado/editado pela tela de perfis).
- **Job de processamento**: execução que transforma fonte(s) de evidência + perfil +
  opções em um caso, acompanhada pela janela de progresso.
- **Assistente de Novo Caso (wizard)**: fluxo guiado, multi-etapas, que coleta os
  parâmetros de criação e dispara o job.
- **Editor de perfis**: tela de criação/edição de perfis de usuário.

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: Um investigador cria e inicia um caso inteiramente pela UI (sem abrir
  terminal nem o antigo lançador), a partir de uma evidência de referência, em até 3
  minutos de interação até o início do processamento.
- **SC-002**: 100% das opções de criação de caso aceitas hoje pelo fluxo atual estão
  acessíveis pelo assistente ou explicitamente documentadas como fora de escopo,
  conforme a partição A/B/C/D em
  `contracts/new-case-wizard.contract.md` (fonte da verdade).
- **SC-003**: O assistente rejeita 100% das submissões inválidas testadas (fonte
  inexistente, saída não gravável, conflito de saída não resolvido) com mensagem
  clara e sem iniciar processamento.
- **SC-004**: Para um conjunto de perfis e evidências de referência, o caso criado
  pelo assistente é forensicamente equivalente ao criado pelo fluxo atual em 100% das
  comparações (mesmo universo de itens, categorias e campos indexados).
- **SC-005**: Um perfil criado pela tela de perfis aparece e é selecionável no
  assistente de Novo Caso em 100% dos casos, e produz um caso coerente com as
  funcionalidades habilitadas/desabilitadas escolhidas.
- **SC-006**: Toda criação interativa de caso pode ser realizada de ponta a ponta pela
  UI RCP **sem usar `iped.exe` nem um terminal** (verificado por inspeção do fluxo). O
  `iped.exe` permanece no release apenas como entry headless para automação — nenhum
  caminho de criação interativa depende dele.
- **SC-007**: 100% dos textos visíveis das novas telas estão localizados em português
  e inglês; nos demais idiomas suportados não há chaves de mensagem cruas na tela
  (fallback funcionando).
- **SC-008**: Um investigador sem familiaridade com a linha de comando do IPED
  completa, sem consultar documentação de CLI, o fluxo "criar caso → acompanhar
  progresso → abrir caso" em uma primeira tentativa.

## Assumptions

- **Construída sobre a feature 004 (migração RCP)**: a UI de análise já é a aplicação
  RCP (`iped.ui.exe`); o menu, os diálogos nativos, a janela de progresso de
  processamento e a abertura de casos já existem ou foram migrados na 004. Esta
  feature adiciona a porta de entrada de criação (menu + wizard) e a gestão de
  perfis sobre essa base.
- **`iped.exe` permanece como entry headless**: "aposentar `iped.exe`" significa apenas
  que ele deixa de ser a porta promovida para **criação interativa** — o binário/CLI
  **continua distribuído e inalterado** para automação, scripts e servidores (perfis
  como `blind`/`triage` continuam executáveis sem GUI). A remoção do `iped.exe` é passo
  futuro, fora do escopo, condicionado a um modo headless no novo launcher RCP.
  Confirmado nas clarificações 2026-06-16 (incl. remediação I1 do `/speckit-analyze`).
- **Editor de perfis completo**: o editor parte de um perfil existente (clonar) e
  expõe para edição todas as opções de configuração do pipeline que o perfil
  parametriza (flags de habilitação + parâmetros dos componentes de processamento),
  com rótulos amigáveis e validação por opção. Perfis embarcados são templates somente
  leitura; edições sobre eles viram perfis de usuário. Confirmado na clarificação
  2026-06-16.
- **Execução do processamento**: o job de processamento disparado pelo wizard usa o
  mesmo motor de processamento atual (executado como processo separado, conforme o
  modelo vigente), preservando isolamento de falhas; a UI apenas dispara e acompanha.
- **Estratégia de entrega**: o cut-over total da feature 004 refere-se à **UI de
  análise** (a UI Swing antiga é substituída). Para **criação**, o `iped.exe`/CLI
  **coexiste** intencionalmente como entry headless (não como porta interativa
  concorrente) — ver Clarifications/FR-021. Não há duas UIs *interativas* de criação no
  mesmo release.
- **Plataformas e idiomas**: Windows e Linux, com o conjunto de idiomas já suportado
  pela UI (paridade com a feature 004); macOS e novos idiomas fora de escopo.
- **Perfis embarcados atuais**: forensic, pedo, triage, fastmode e blind permanecem
  distribuídos e servem de base para clonagem.

## Out of Scope

- Novas funcionalidades ou etapas de processamento (qualquer task/análise que não
  exista hoje no motor). A feature expõe e parametriza o que já existe.
- Mudanças no formato do caso, do índice ou no resultado forense de um dado perfil.
- Remoção ou alteração do motor/`iped.exe` de processamento por linha de comando:
  `iped.exe` **permanece distribuído** como entry headless; só deixa de ser a porta
  promovida de criação interativa (FR-020/FR-021). A remoção do `iped.exe` é passo
  futuro, condicionado a um modo headless no novo launcher RCP.
- Controles no **assistente de Novo Caso** para flags raras/de especialista de criação
  de caso (tier D da partição em `contracts/new-case-wizard.contract.md`) — incluindo o
  **modo ASAP** (`-asap`, integração PF), `-remove`, `--yara-only` e `--nogui`:
  permanecem acessíveis só pela linha de comando/arquivos de configuração (ver FR-007).
  Observação: o **editor de perfis** é completo e cobre todas as opções de configuração
  do pipeline (FR-016).
- Agendamento, enfileiramento ou orquestração de múltiplos jobs de processamento
  (criação de um caso por vez pela UI).
- Suporte a macOS e tradução para novos idiomas.
- Processamento embarcado dentro do processo da UI (o job continua a rodar como
  processo separado, conforme o modelo atual).
```