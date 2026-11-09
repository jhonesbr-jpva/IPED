# Quickstart Validation Results — Criação e Abertura de Casos na GUI RCP

**Feature**: `005-case-creation-wizard` | **Task**: T036 | **Date**: 2026-06-17
**Tester**: user (interactive) + Claude (analysis) | **Ref**: [quickstart.md](quickstart.md)

Ambiente: produto RCP `iped-uic.exe` (Win x64) + engine release deste repo em
`target/release/iped-4.4.0-SNAPSHOT` (`-Diped.install.dir`), evidência de teste = um
`.rar`. JDK Liberica Full 21.

## Resumo

| Cenário | Status | Nota |
|---|---|---|
| V1 — New Case ponta a ponta | ✅ **PASS** | processou com `forensic` e abriu no RCP UI |
| V2 — Validações do wizard | ✅ **PASS** | 6/6 casos bloqueiam corretamente, nenhum processo lançado |
| V3 — Open Case (+ recentes) | ✅ **PASS** | Open Case ✅ (abriu o caso do V1); pasta inválida → erro claro ✅. Recent Cases **deferido** (T018/T021) |
| V4 — Criar/editar perfil | ✅ **PASS** | clone/editar/salvar (override enxuto) ✅; embarcado → "Save As…" ✅; colisão rejeitada ✅; novo perfil aparece no wizard ✅ |
| V5 — i18n (`-nl pt_BR`/`en`) | ✅ **PASS** | menu + wizard + editor localizados em pt_BR e en (após 2 fixes, abaixo); sem chaves cruas |

## V1 — detalhes

- **File ▸ New Case** → fontes/saída/perfil/opções → Concluir → processamento **out-of-process**
  iniciou **sem terminal**, com janela de progresso; ao final, caso aberto no RCP UI. ✅
- `triage` **não expande RAR** (comportamento esperado do perfil de triagem); `forensic`
  expande e processou o conteúdo do `.rar`. (Não é bug.)
- ⚠️ Auto-open **near-live** ao concluir o wizard ainda é **deferido** (T015) — abrir via
  Open Case após o término.

### Bug real encontrado e corrigido (durante o V1)

1. **SecurityManager no subprocesso de processamento** — `java -jar iped.jar` (lançado pelo
   `ProcessingLaunchService`) abortava com `UnsupportedOperationException: The Security
   Manager is deprecated` (engine instala um SM em `Configuration.loadConfigurables`; Java 21
   exige `-Djava.security.manager=allow`). O `iped.bat`/`iped.exe` passam o flag ao Bootstrap;
   a UI bypassa esses launchers. **Corrigido** em `buildFullCommand` (commit `4b95f3088`),
   espelhando o `iped.bat`. Confirmado nos args do JVM filho do log.

### Achado de ambiente (não é defeito da feature)

- O release inicialmente apontado tinha um **engine antigo** (pré-Java-21: FST, faltavam
  `--add-exports` do PNG reader, "Java 21 not tested"), causando
  `IllegalAccessError` (PNG) e `RuntimeException: unknown object tag -84` (FST em
  `RegexTask.loadCache`). **Resolvido rebuildando o release** (`mvn clean install`) → engine
  atual (FST-free), confirmado: `fst-2.57.jar` removido do `lib/` e "Java 21 not tested" some.

## V2 — detalhes (todos validados pelo usuário 2026-06-17)

1. Sem fonte → Next/Finish desabilitados (página). ✅
2. Saída vazia → Next/Finish desabilitados (página). ✅
3. Saída = subpasta da fonte → erro no Concluir, **não sobrescreve** a pasta da fonte, sem lançar. ✅
4. Modo New sobre caso existente → erro no Concluir. ✅
5. Append/Continue/Restart sem caso → erro no Concluir. ✅
6. Cancelar → nenhum artefato. ✅

(`fonte inexistente` e `saída não gravável` não são facilmente provocáveis pela GUI —
cobertos pela validação unit `BootstrapCommandBuilderTest`/T011.)

## Revisão dos logs de processamento (12:24 triage, 12:34 forensic) — nada a corrigir

Ambos concluíram. Apenas ruído **pré-existente e benigno** do engine (igual no CLI normal):

- **INFO** `Delete failed on …Temp\…\*.dll` (7-Zip-JBinding, jna, libesedb, sqlitejdbc): Windows
  não deleta libs nativas JNI ainda carregadas pela JVM no shutdown; SO limpa depois. Não-crítico.
- **WARN** `JpegParser … EOFException` em `Carved-*.jpg`: fragmentos JPEG carveados incompletos
  (esperado em carving; "Parsing Exceptions: 3").
- Ruído do splash legado `NoSuchFieldException: classes` (`StartUpControl.getCurrentProcessSize`,
  reflection frágil no Java 21) e `No module named 'numpy'` (task Python opcional). Inofensivos,
  não introduzidos pela 005.

## Resultado final — T036 completo (V1–V5 ✅)

Todos os cenários do quickstart passaram. Único deferido: **Recent Cases** (T018/T021), por design.

### Bugs reais encontrados pelo T036 e corrigidos

| # | Sintoma | Causa | Fix |
|---|---|---|---|
| 1 | New Case abortava o processamento (`UnsupportedOperationException: Security Manager`) | subprocesso `java -jar iped.jar` sem `-Djava.security.manager=allow` (engine instala SM no Java 21) | `ProcessingLaunchService.buildFullCommand` passa o flag (espelha `iped.bat`) — `4b95f3088` |
| 2 | Processamento falhava com FST `unknown object tag -84` + PNG `IllegalAccessError` | `-Diped.install.dir` apontava p/ um **engine antigo** (pré-Java-21) | rebuild do release (`mvn clean install`) — ambiente, não código |
| 3 | Itens do menu principal sempre em inglês | `findElements` de 4 args (escopo `ANYWHERE`) não cobre o main menu | `findElements(..., ANYWHERE \| IN_MAIN_MENU)` — `691e1c383` |
| 4 | GUI não subia (`NPE: ICaseSessionManager null`) | header `Service-Component` removido do MANIFEST do `iped.rcp.core` (hazard m2e/tycho-ds) | declarar `Service-Component: OSGI-INF/*.xml` no MANIFEST fonte + null-guard no boot — `6ec9ca55a` |
