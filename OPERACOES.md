# Operação do workspace IPED — regras e receitas

Companheiro do [`README.md`](README.md) (índice das specs). Vale para `H:\java\workspaces\workspace-iped\`. Escrito em 2026-11-09, ao fim da reorganização; atualize quando a prática mudar.

## O mapa

```
H:\java\workspaces\
├── workspace-iped\
│   ├── master\                       hub: contém o .git; espelho de sepinf-inc/master; NUNCA se desenvolve aqui
│   ├── <NNN-nome>\                   um worktree por feature; pasta = branch
│   ├── iped-4.3.1-mcp\               integração/release da linha MCP sobre a 4.3.1
│   ├── integration-upstream-stack\   agregado para PRs no sepinf-inc (branch integration/upstream-stack)
│   ├── specs-archive\                specs fechadas, índice, este arquivo, tools\New-IpedFeature.ps1
│   └── java-dbx\                     repositório próprio (fork do java-dbx para Tika 3)
├── workspace-rcp\                    workspace Eclipse, só para o worktree do RCP
└── workspace-ofp\                    image-navigator e OFP legado (fora deste documento)
H:\java\_backup\                      bundles git (seguro de vida das operações destrutivas)
```

Branches permanentes: `master` e `4.3.x` (espelhos do upstream; `pull` do `upstream`, `push` para o `origin`), `base/agents` (master + ferramental), `iped-4.3.1-mcp`, `integration/upstream-stack`, `legacy/fork-master` (congelada), `specs-archive`. Tudo o mais é feature.

## Regras

1. **Pasta = branch.** O nome do diretório do worktree é o nome da branch (barra vira hífen: `integration/upstream-stack` → `integration-upstream-stack`).
2. **O hub é só leitura.** Fica em `master`, sem commits próprios. Serve para `git log`, comparar comportamento com o upstream puro e abrigar o `.git`.
3. **Numeração de feature é por repositório.** Próximo número = 1 + maior `NNN-` entre branches locais, branches do fork e specs do `specs-archive`. O script calcula; não escolha na mão.
4. **Nunca apagar branch numerada no GitHub.** Ela reserva o número e guarda a spec original. Apagar localmente ao fechar, à vontade.
5. **Spec é editada só na branch da própria feature.** Cópias herdadas por branching são leitura.
6. **Um checkout do IPED por janela.** VS Code, Claude Code e Eclipse: nunca dois worktrees do IPED abertos na mesma janela/workspace. O m2e resolve dependências por `artifactId` e mistura classes de árvores diferentes (aconteceu em 26/10/2026: `iped-engine` da 010 compilando contra `test-classes` do IPED-RCP e do clone do MCP).
7. **Eclipse só para o RCP**, no workspace `H:\java\workspaces\workspace-rcp`, importando `004-rcp-gui-migration`. Um workspace Eclipse por worktree.
8. **Nunca `claude --worktree` dentro do hub ou de um worktree.** Ele cria worktrees aninhados em `.claude\worktrees\`, fora da convenção (foi assim que o protótipo Python do MCP ficou escondido).
9. **Nunca force-push em branch numerada.** Force-push só nas duas branches de ferramental em que ele é esperado: `base/agents` (rebase sobre o master) e, excepcionalmente, `master` (correção de espelho) — sempre com `--force-with-lease`.
10. **Ferramental não vai para o sepinf-inc.** `.specify/`, `.claude/`, `CLAUDE.md`, `specs/` são do fork. Branch de PR nasce de `master` e recebe só commits de código.

## Receitas

### Feature nova

```powershell
H:\java\workspaces\workspace-iped\specs-archive\tools\New-IpedFeature.ps1 -ShortName <2-4-palavras-kebab> -Line master   # ou -Line 4.3.x
```

O script sincroniza o hub, calcula o número, cria o worktree **destacado** na base (`base/agents` ou `iped-4.3.1-mcp`) e imprime a linha do `/speckit.specify` com `SPECIFY_FEATURE_DIRECTORY=specs/<NNN-nome>` e `GIT_BRANCH_NAME=<NNN-nome>`. Cole essa linha no Claude Code dentro da pasta: o hook do Spec Kit cria a branch e o comando cria a spec, ambos com o mesmo nome. Depois: `git push -u origin <NNN-nome>` e uma linha no `README.md` do `specs-archive`.

Por que os dois overrides: na versão 1.0.12 do Spec Kit a branch é criada por um hook (que numera olhando branches locais e remotas) e a pasta da spec pelo comando central (que numera olhando só o `specs/` do checkout). Sem os overrides, num worktree novo sem `specs/`, a pasta sairia `001-…` e a branch `NNN-…`.

### Trabalhar numa feature existente

Abra o worktree dela. Se não houver worktree (ex.: `005-case-creation-wizard`): `git -C <hub> worktree add ..\<branch> <branch>`.

### Atualizar com o upstream

```powershell
git -C H:\java\workspaces\workspace-iped\master fetch upstream --prune
git -C H:\java\workspaces\workspace-iped\master merge --ff-only upstream/master
git -C H:\java\workspaces\workspace-iped\master push origin master
```

(O `New-IpedFeature.ps1` faz isso e ainda rebaseia a `base/agents`.) Para trazer o master para uma feature em andamento: `git merge master` dentro do worktree dela (ou rebase, se a feature ainda não foi compartilhada).

Linha 4.3.x: `git branch -f 4.3.x upstream/4.3.x`; incorporar em `iped-4.3.1-mcp` é decisão humana (merge), porque é a branch de release.

### Branch limpa para PR no sepinf-inc

A partir de uma feature `NNN-x` baseada em `base/agents`:

```powershell
git -C <hub> worktree add ..\<nome>-upstream -b <nome>-upstream master
git -C ..\<nome>-upstream rebase --onto master base/agents NNN-x      # reaplica só os commits da feature, sem o ferramental
```

ou `cherry-pick` dos commits de código. Conferir com `git diff master <nome>-upstream --stat` que não há `.specify/`, `.claude/`, `CLAUDE.md` nem `specs/`. As quatro branches `*-upstream` existentes estão agregadas em `integration/upstream-stack`.

### Fechar uma feature

1. Merge feito (no fork: `iped-4.3.1-mcp` para a linha MCP, fast-forward; no upstream: PR aceito).
2. Atualizar a cópia da spec em `specs-archive\specs\<NNN-nome>\` (`git restore --source=<branch> --staged --worktree -- specs/<NNN-nome>` dentro do `specs-archive`) e o status no `README.md`; commit e push na `specs-archive`.
3. `git -C <hub> worktree remove ..\<NNN-nome>` (com `--force` se houver `target/`).
4. `git -C <hub> branch -d <NNN-nome>`. A branch no GitHub fica.

### Release da linha MCP (4.3.1)

Sempre a partir do worktree `iped-4.3.1-mcp` (`mvn clean install` → `target\release\iped-4.3.1\`). Copiar o artefato para fora do repositório, por exemplo `H:\java\releases\iped-4.3.1-mcp-<data>\` — artefato implantado não pode depender de um worktree existir. Para distinguir dos releases oficiais, considerar uma versão como `4.3.1-mcp.N` no `pom.xml` da linha (decisão de produto, pendente).

### Atualizar o Spec Kit

Só em `base/agents` (e, se a linha 4.3.x continuar ativa, em `iped-4.3.1-mcp`), em commit próprio `chore(speckit): ...`, em fronteira de fase. Conferir no diff se o `init` tentou substituir `.specify/memory/constitution.md`; se sim, `git checkout -- .specify/memory/constitution.md`. Features em andamento terminam com a versão que têm; não há retrabalho em specs antigas — são documentos, não código.

### Antes de qualquer operação destrutiva

`git -C <hub> bundle create H:\java\_backup\iped-hub-<data>.bundle --all` e `git bundle verify`. Custa 5 segundos.

## Pendências conhecidas (2026-11-09)

- `upstream/4.3.x` tem 2 commits (mar/2026) não incorporados em `iped-4.3.1-mcp`.
- Status dos PRs no sepinf-inc para `java21-migration-upstream`, `yara-engine-upstream` e `tika3-upgrade`: não verificado no índice.
- `005-case-creation-wizard` está inteira dentro da `004`; a branch local pode ser apagada quando a linha RCP for retomada pela `004`.
- A pasta `workspace-iped` ainda pertence ao SID da máquina antiga; o git funciona via `safe.directory = H:/java/workspaces/workspace-iped/*` no `git config --global`. Um `takeown /F H:\java\workspaces /R /D Y` elevado dispensa a exceção.
- `workspace-ofp` (image-navigator: 1 commit não enviado e 37 arquivos não commitados desde 02/08/2026; OFP legado duplicado em `inspiration/`; `OIFE_old` sem cópia) aguarda a própria reorganização.
