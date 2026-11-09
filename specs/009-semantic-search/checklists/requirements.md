# Specification Quality Checklist: Busca semântica sobre os artefatos interpretados pelo IPED

**Purpose**: Validate specification completeness and quality before proceeding to planning
**Created**: 2026-09-26
**Feature**: [spec.md](../spec.md)

## Content Quality

- [x] No implementation details (languages, frameworks, APIs)
- [x] Focused on user value and business needs
- [x] Written for non-technical stakeholders
- [x] All mandatory sections completed

## Requirement Completeness

- [x] No [NEEDS CLARIFICATION] markers remain
- [x] Requirements are testable and unambiguous
- [x] Success criteria are measurable
- [x] Success criteria are technology-agnostic (no implementation details)
- [x] All acceptance scenarios are defined
- [x] Edge cases are identified
- [x] Scope is clearly bounded
- [x] Dependencies and assumptions identified

## Feature Readiness

- [x] All functional requirements have clear acceptance criteria
- [x] User scenarios cover primary flows
- [x] Feature meets measurable outcomes defined in Success Criteria
- [x] No implementation details leak into specification

## Notes

Estado em 2026-09-26, depois de `/speckit-specify`, `/speckit-clarify`, da decisão sobre RAG e das
emendas do plano. Todos os itens passam. O histórico de cada decisão está na seção Clarifications
da spec (12 entradas).

**Escopo como está hoje**
- Superfícies: interface de análise **e** agente (MCP), com a mesma ordenação (FR-028, SC-011).
- Modalidade texto: trechos, transcrições e **sequências de conversa** ancoradas nas mensagens
  (FR-005). Modo **semântico** ou **híbrido**; o híbrido combina posição, não pontuação (FR-046,
  FR-047, SC-018).
- Modalidade visual: imagens **e vídeos**, estes pelos quadros já extraídos. Chaves independentes
  para imagens e vídeos. Instante do quadro **aproximado** e declarado (FR-029, FR-032, FR-035).
- Sem enriquecimento de caso já processado (FR-030).
- Profundidade padrão extensível até um teto, sem piso de proximidade. Método exato ou aproximado
  declarado (FR-007).
- Modelos: o de texto no pacote, o visual em pacote separado, e remoto como opção. A exigência é o
  **modelo**, não a fonte (FR-041 a FR-045, FR-044 pela emenda A4).
- **RAG fica no harness**: o IPED recupera e cita, e não chama modelo de linguagem (FR-040,
  FR-048).

**Menções deliberadas que não são vazamento de implementação**
- "servidor MCP" nomeia uma superfície de produto existente.
- "RAG" aparece para delimitar o que a feature **não** faz, com a definição no próprio texto.
- A seção "Constitution Check" da spec cita runtime, módulo e thread de interface, porque existe
  para antecipar ao plano o que a constituição exige.

**Metas de partida, sem medição**
- SC-010 (texto, ≤ 2×) e SC-013 (texto e imagens, ≤ 3×). Se a bancada as refutar, a revisão volta
  para a spec (Assumptions).

**Dependem do conjunto de avaliação** (a construir na entrega)
- SC-001 a SC-003, SC-010, SC-012 a SC-015 e SC-018.

**Decididos pela medição, não agora**
- Modo padrão de fábrica (`hybrid` ou `semantic`), por SC-018.
- Perfis distribuídos que ligam a capacidade, por SC-010, SC-013 e SC-015.
