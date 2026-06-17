# Contract — Serviço e editor de perfis

**Feature**: `005-case-creation-wizard` | Status: design (Phase 1)
**Serviço**: `iped.rcp.core.profiles.ProfileService` (headless, DS)
**UI**: `iped.rcp.casecreation.profiles.ProfileManagerDialog` / `ProfileEditorDialog`

Define a descoberta, leitura (modelo de config base+override), criação, edição e
gravação de perfis. Implementa o "editor completo" (Q2) via grid dirigido por arquivos
(R4) e a regra de storage/resolução (R3).

## Operações do `ProfileService`

| Operação | Entrada | Saída | Regras |
|---|---|---|---|
| `listProfiles()` | — | `List<ProfileDescriptor>` | varre `profiles/`; marca `BUILT_IN` os 5 embarcados |
| `loadModel(name)` | nome | `ProfileConfigModel` | merge: config canônico (base) ← overrides do perfil |
| `createProfile(name, basedOn?)` | nome único + base? | `ProfileDescriptor` (`USER`) | rejeita nome em colisão/ inválido (FR-019) |
| `saveModel(name, model)` | nome `USER` + modelo | — | grava **só** opções `overridden` no `profiles/<name>/`; recusa `BUILT_IN` |
| `deleteProfile(name)` | nome `USER` | — | recusa `BUILT_IN` |

## Modelo de configuração (R4)

- **Fonte das opções**: arquivos `key=value` do release —
  `IPEDConfig.txt`, `LocalConfig.txt`, `conf/*.txt|.properties`. Cada arquivo → um
  `ConfigFileGroup`; cada chave → `ConfigOption` (ver [data-model.md](../data-model.md)).
- **Descrição** de cada opção = bloco de comentários `#` que a precede no arquivo base.
- **Valor efetivo** = base sobrescrito pelo override do perfil em edição; `overridden`
  marca as chaves redefinidas pelo perfil.
- **Formatos não-`key=value`** (`conf/*.xml`, `conf/*.json`): expostos numa seção
  "arquivos avançados" com edição de texto (cópia para o perfil ao salvar) — cobertura
  "completa" sem parser visual por formato.

## Gravação (round-trip)

- `saveModel` escreve em `profiles/<name>/IPEDConfig.txt` (e/ou `conf/<arquivo>`)
  **apenas** as chaves com `overridden=true` — mantém perfis enxutos (ex.: `forensic`
  só redefine 4 flags, ver
  [profiles/forensic/IPEDConfig.txt](../../../iped-app/resources/config/profiles/forensic/IPEDConfig.txt)).
- Charset **UTF-8** via o mecanismo `UTF8Properties` do engine (Princípio IV).
- **Perfis embarcados são templates somente leitura** (FR-018): editar um `BUILT_IN`
  exige "Salvar como" novo `USER`; o serviço **recusa** `saveModel`/`deleteProfile`
  sobre `BUILT_IN`.

## Storage e resolução (R3)

- Default: perfis de usuário em **`<install>/profiles/<name>/`** — onde
  `Main.java:174-179` resolve `-profile <name>` **sem mudança no engine**.
- Plano-B (instalação read-only): `~/.iped/profiles/<name>/` + extensão de resolução de
  `-profile` (toque mínimo justificado — Complexity Tracking do [plan.md](../plan.md)).

## Invariantes

- Um perfil de usuário recém-criado/salvo aparece imediatamente em `listProfiles()` e,
  portanto, no wizard (FR-017).
- Um caso processado com um perfil de usuário é **equivalente** ao processado pela CLI
  com `-profile <name>` (FR-013) — o serviço grava exatamente os arquivos que o engine
  lê.
- O serviço é **toolkit-free** (DS) e coberto por testes headless (merge, round-trip,
  isolamento dos embarcados, colisão de nome) — R8.
