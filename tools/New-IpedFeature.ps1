<#
.SYNOPSIS
    Cria o worktree de uma feature nova do fork do IPED com numeracao global e pasta = branch.

.DESCRIPTION
    Convencao do workspace (H:\java\workspaces\workspace-iped):
      - master\                  hub: contem o .git; espelho de sepinf-inc/master; nunca se desenvolve nele
      - <NNN-nome>\              um worktree por feature, pasta com o mesmo nome da branch
      - base/agents              master + ferramental de agentes (Spec Kit, CLAUDE.md, constituicao): base da linha master
      - iped-4.3.1-mcp           integracao/release da linha MCP sobre a 4.3.1: base da linha 4.3.x
      - specs-archive\           specs fechadas + indice (README.md) + este script (tools\)

    O que o script faz:
      1. (salvo -NoSync) atualiza o hub: fetch de upstream/origin, fast-forward do master, push para o fork,
         rebase da base/agents sobre o master novo (so o commit de ferramental), atualiza a ref 4.3.x.
      2. Calcula o PROXIMO NUMERO GLOBAL: 1 + maior prefixo NNN- entre branches locais, branches do fork
         (ls-remote) e pastas de specs do specs-archive. E por repositorio, nao por worktree.
      3. Cria o worktree DESTACADO na base da linha, ja com o nome definitivo <NNN-ShortName>.
      4. Imprime a linha do /speckit.specify a colar no Claude Code, com SPECIFY_FEATURE_DIRECTORY e
         GIT_BRANCH_NAME fixados: o hook do Spec Kit cria a branch (git checkout -b a partir do HEAD destacado)
         e o comando central cria specs\<NNN-ShortName>\. Pasta, branch e spec ficam com o mesmo nome.

    Por que destacado e nao -b: o hook before_specify da extensao git e obrigatorio e falha se a branch ja
    existir. Deixar a branch para o hook evita a disputa; o nome e garantido por GIT_BRANCH_NAME.

.PARAMETER ShortName
    Nome curto da feature, 2 a 4 palavras em kebab-case (ex.: usn-journal-parser). Vira <NNN-ShortName>.

.PARAMETER Line
    master (padrao) -> base/agents | 4.3.x -> iped-4.3.1-mcp. Use -Base para outra branch.

.PARAMETER Base
    Branch de partida explicita (sobrepoe -Line). Ex.: master (para uma branch limpa de PR no sepinf-inc).

.PARAMETER Description
    Descricao da feature, so para compor a linha do /speckit.specify impressa no fim.

.PARAMETER NoSync
    Nao sincroniza o hub com o upstream antes de criar o worktree.

.PARAMETER DryRun
    Calcula e mostra tudo, sem criar nada nem sincronizar.

.EXAMPLE
    .\New-IpedFeature.ps1 -ShortName usn-journal-parser -Description "Parser para o USN Journal do NTFS"

.EXAMPLE
    .\New-IpedFeature.ps1 -ShortName mcp-case-diff -Line 4.3.x -Description "Comparar dois casos pelo MCP"

.EXAMPLE
    .\New-IpedFeature.ps1 -ShortName fix-lnk-stream -Base master -NoSync -Description "Correcao para PR upstream"
#>
[CmdletBinding()]
param(
    [Parameter(Mandatory = $true, Position = 0)]
    [ValidatePattern('^[a-z0-9]+(-[a-z0-9]+){1,3}$')]
    [string]$ShortName,

    [ValidateSet('master', '4.3.x')]
    [string]$Line = 'master',

    [string]$Base,

    [string]$Description = '',

    [string]$Workspace = 'H:\java\workspaces\workspace-iped',

    [switch]$NoSync,

    [switch]$DryRun
)

$ErrorActionPreference = 'Stop'
$env:GIT_TERMINAL_PROMPT = '0'

function Step($msg) { Write-Host "`n== $msg" -ForegroundColor Cyan }
function Info($msg) { Write-Host "   $msg" }
function Fail($msg) { Write-Host "!! $msg" -ForegroundColor Red; exit 1 }
function Invoke-Git([string]$Dir) {
    # Executa git.exe em $Dir com os demais argumentos; falha alto se o git falhar.
    # Nao se chama 'Git': funcoes tem precedencia sobre executaveis e a resolucao ignora
    # maiusculas, entao '& git' dentro dela chamaria a propria funcao (recursao infinita).
    # Em PowerShell 5.1, stderr do git redirecionado (2>&1) sob ErrorActionPreference=Stop vira erro
    # fatal; por isso o wrapper roda com Continue e decide pelo codigo de saida.
    $prev = $ErrorActionPreference; $ErrorActionPreference = 'Continue'
    $out = & git.exe -C $Dir @args 2>&1 | ForEach-Object { "$_" }
    $code = $LASTEXITCODE
    $ErrorActionPreference = $prev
    if ($code -ne 0) { Fail ("git -C $Dir $($args -join ' ')`n" + ($out -join "`n")) }
    return $out
}

$Hub = Join-Path $Workspace 'master'
$Archive = Join-Path $Workspace 'specs-archive'

if (-not (Test-Path (Join-Path $Hub '.git'))) { Fail "Hub nao encontrado em $Hub (esperado o .git do repositorio)." }
if ((Invoke-Git $Hub rev-parse --abbrev-ref HEAD) -ne 'master') { Fail "O hub nao esta em 'master'. Nunca desenvolva no hub; volte-o para master antes." }
if ((Invoke-Git $Hub status --porcelain | Measure-Object).Count -gt 0) { Fail "O hub tem alteracoes locais. O hub e so espelho; limpe-o antes." }

if (-not $Base) { $Base = if ($Line -eq '4.3.x') { 'iped-4.3.1-mcp' } else { 'base/agents' } }
& git.exe -C $Hub rev-parse --verify --quiet "refs/heads/$Base" 2>$null | Out-Null
if ($LASTEXITCODE -ne 0) { Fail "A branch de base '$Base' nao existe no hub." }

# ---------------------------------------------------------------- 1. sync
if (-not $NoSync -and -not $DryRun) {
    Step 'Sincronizando o hub com o upstream'
    Invoke-Git $Hub fetch upstream --prune | Out-Null
    Invoke-Git $Hub fetch origin --prune | Out-Null

    $before = Invoke-Git $Hub rev-parse master
    Invoke-Git $Hub merge --ff-only upstream/master | Out-Null
    $after = Invoke-Git $Hub rev-parse master
    if ($before -ne $after) {
        Info "master: $($before.Substring(0,9)) -> $($after.Substring(0,9)) ($(Invoke-Git $Hub rev-list --count "$before..$after") commits do sepinf-inc)"
        Invoke-Git $Hub push origin master | Out-Null
        Info 'master enviado ao fork (origin).'
    } else { Info 'master ja estava em dia com o upstream.' }

    # base/agents = master + 1 commit de ferramental; rebase quando o master anda
    & git.exe -C $Hub merge-base --is-ancestor master base/agents 2>$null
    if ($LASTEXITCODE -ne 0) {
        Info 'base/agents esta atras do master: rebase do commit de ferramental...'
        $tmp = Join-Path $Workspace '_rebase-base-agents'
        $oldBase = Invoke-Git $Hub rev-parse base/agents
        Invoke-Git $Hub worktree add --detach $tmp base/agents | Out-Null
        & git.exe -C $tmp rebase master 2>&1 | Out-Null
        if ($LASTEXITCODE -ne 0) {
            & git.exe -C $tmp rebase --abort 2>$null
            & git.exe -C $Hub worktree remove --force $tmp 2>$null
            Fail 'Conflito ao rebasear base/agents sobre o master (provavelmente .gitignore ou CLAUDE.md). Resolva manualmente num worktree de base/agents e rode de novo.'
        }
        $newBase = Invoke-Git $tmp rev-parse HEAD
        Invoke-Git $Hub worktree remove --force $tmp | Out-Null
        Invoke-Git $Hub branch -f base/agents $newBase | Out-Null
        Invoke-Git $Hub push "--force-with-lease=base/agents:$oldBase" origin base/agents | Out-Null
        Info "base/agents: $($oldBase.Substring(0,9)) -> $($newBase.Substring(0,9)) (rebase + push com lease)"
    } else { Info 'base/agents ja esta sobre o master atual.' }

    # 4.3.x: so a ref espelho; a incorporacao em iped-4.3.1-mcp e decisao humana
    Invoke-Git $Hub branch -f 4.3.x upstream/4.3.x | Out-Null
    $pend = Invoke-Git $Hub rev-list --count 'iped-4.3.1-mcp..upstream/4.3.x'
    if ([int]$pend -gt 0) { Info "aviso: upstream/4.3.x tem $pend commit(s) ainda nao incorporados em iped-4.3.1-mcp." }
}

# ---------------------------------------------------------------- 2. numero global
Step 'Calculando o proximo numero global'
$numbers = @()
$rx = [regex]'(^|/)(\d{3})-'
function Collect([string[]]$names) {
    foreach ($n in $names) { $m = $rx.Match($n.Trim()); if ($m.Success) { $script:numbers += [int]$m.Groups[2].Value } }
}
Collect (Invoke-Git $Hub for-each-ref --format='%(refname:short)' refs/heads refs/remotes)
$remoteHeads = & git.exe -C $Hub ls-remote --heads origin 2>$null
if ($LASTEXITCODE -eq 0) { Collect ($remoteHeads | ForEach-Object { ($_ -split '\t')[1] -replace '^refs/heads/', '' }) } else { Info 'aviso: sem acesso ao GitHub agora; numerando so com o que ha localmente.' }
if (Test-Path (Join-Path $Archive 'specs')) { Collect (Get-ChildItem (Join-Path $Archive 'specs') -Directory | Select-Object -ExpandProperty Name) }
$archiveBranchSpecs = & git.exe -C $Hub ls-tree --name-only specs-archive specs/ 2>$null
if ($LASTEXITCODE -eq 0) { Collect ($archiveBranchSpecs | ForEach-Object { $_ -replace '^specs/', '' }) }
Collect (& git.exe -C $Hub ls-tree --name-only $Base specs/ 2>$null | ForEach-Object { $_ -replace '^specs/', '' })

$max = if ($numbers.Count) { [int](($numbers | Measure-Object -Maximum).Maximum) } else { 0 }   # Measure-Object devolve Double; D3 exige inteiro
$num = '{0:D3}' -f ($max + 1)
$branch = "$num-$ShortName"
$target = Join-Path $Workspace $branch
Info "maior numero em uso: $('{0:D3}' -f $max)  ->  nova feature: $branch"
Info "base: $Base @ $((Invoke-Git $Hub rev-parse --short $Base))"
Info "pasta: $target"

& git.exe -C $Hub rev-parse --verify --quiet "refs/heads/$branch" 2>$null | Out-Null
if ($LASTEXITCODE -eq 0) { Fail "A branch $branch ja existe." }
if (Test-Path $target) { Fail "A pasta $target ja existe." }

# ---------------------------------------------------------------- 3. worktree destacado
$specifyLine = "/speckit.specify SPECIFY_FEATURE_DIRECTORY=specs/$branch GIT_BRANCH_NAME=$branch $Description".TrimEnd()

if ($DryRun) {
    Step 'DryRun: nada foi criado. Comandos que seriam executados:'
    Info "git -C $Hub worktree add --detach `"$target`" $Base"
    Info "(no Claude Code, dentro da pasta)  $specifyLine"
    exit 0
}

Step 'Criando o worktree (destacado na base; a branch sera criada pelo hook do Spec Kit)'
Invoke-Git $Hub worktree add --detach $target $Base | Out-Null
Info "ok: $target @ $((Invoke-Git $target rev-parse --short HEAD)) (HEAD destacado em $Base)"

# ---------------------------------------------------------------- 4. proximos passos
Step 'Proximos passos'
Write-Host @"
   1. Abra o Claude Code na pasta:   cd "$target"  ;  claude
   2. Cole exatamente esta linha (ela fixa a pasta da spec e o nome da branch, ambos = $branch):

      $specifyLine

      O hook before_specify (extensao git) roda 'git checkout -b $branch' a partir do HEAD destacado;
      o comando central cria specs\$branch\spec.md e .specify\feature.json.
   3. Primeiro push:  git push -u origin $branch
   4. Registre a feature no indice:  $Archive\README.md
   Regras: um worktree por janela do VS Code/Claude Code; nunca apagar branch numerada no GitHub;
   spec editada so na branch da propria feature. Ver $Archive\OPERACOES.md.
"@
