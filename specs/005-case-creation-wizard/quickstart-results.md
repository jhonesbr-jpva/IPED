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
| V2 — Validações do wizard | ⏳ pendente | — |
| V3 — Open Case (+ recentes) | 🟡 parcial | Open Case ✅ (abriu o caso do V1); invalid-folder + Recent Cases pendentes (Recent Cases **deferido** T018/T021) |
| V4 — Criar/editar perfil | 🟡 validado | clone `triage`→`triage-copy` criado e **processado pelo engine** ✅; editor (Preferences) coberto por testes headless + smoke anterior; re-confirmar edição→save→override enxuto opcional |
| V5 — i18n (`-nl pt_BR`/`en`) | ⏳ pendente | EN+pt_BR completos; demais locales caem no EN (sem chave crua) |

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

## Revisão dos logs de processamento (12:24 triage, 12:34 forensic) — nada a corrigir

Ambos concluíram. Apenas ruído **pré-existente e benigno** do engine (igual no CLI normal):

- **INFO** `Delete failed on …Temp\…\*.dll` (7-Zip-JBinding, jna, libesedb, sqlitejdbc): Windows
  não deleta libs nativas JNI ainda carregadas pela JVM no shutdown; SO limpa depois. Não-crítico.
- **WARN** `JpegParser … EOFException` em `Carved-*.jpg`: fragmentos JPEG carveados incompletos
  (esperado em carving; "Parsing Exceptions: 3").
- Ruído do splash legado `NoSuchFieldException: classes` (`StartUpControl.getCurrentProcessSize`,
  reflection frágil no Java 21) e `No module named 'numpy'` (task Python opcional). Inofensivos,
  não introduzidos pela 005.

## Pendências de validação

- V2 (validações do wizard: fonte inexistente / saída não gravável / saída ⊂ fonte / cancelar).
- V3 (pasta inválida → mensagem; Recent Cases é deferido).
- V4 (re-confirmar opcional no release fresco: editar flag → salvar → checar override enxuto;
  embarcado → "Save As…"; colisão de nome rejeitada).
- V5 (subir com `-nl pt_BR` e `-nl en`; conferir ausência de chaves cruas nos demais locales).
