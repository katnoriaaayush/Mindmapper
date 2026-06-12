# MindMapper — Project Work Plan

A feature-divided work plan for the MindMapper application: a local **FastAPI +
Vertex AI (Gemini) + SQLite** server with a single-page web UI that generates,
edits, analyses, and teaches from mindmaps.

**Status legend:** ✅ Done · 🟡 In progress · ⬜ Not started · 🔁 Recurring

**Effort:** S = ≤1 day · M = 2–3 days · L = ≥1 week (one engineer)

---

## 1. Context & Goals

| | |
|---|---|
| **Goal** | Turn a topic, document, or rough notes into a rich, AI-augmented mindmap, and let users edit, analyse, study, and present it. |
| **Architecture** | Browser SPA → FastAPI (`main.py`) → routers → `gemini.py` / `db.py` → Vertex AI Gemini + SQLite. See `docs/architecture.drawio`. |
| **Current state** | Backend (40+ endpoints across 10 verticals), web UI, and architecture diagram are built. Remaining work is credentials/deploy, persistence wiring, hardening, and test coverage. |

---

## 2. Workstream Overview

| # | Workstream | Status | Effort |
|---|-----------|--------|--------|
| WS0 | Infrastructure & Server Core | ✅ | — |
| WS1 | Mindmap Generation | ✅ (backend) | — |
| WS2 | Node Intelligence | ✅ (backend) | — |
| WS3 | Semantic & Relationship Analysis | ✅ (backend) | — |
| WS4 | Map Analysis & Critique | ✅ (backend) | — |
| WS5 | Contextual Chat (SSE) | ✅ (backend) | — |
| WS6 | Study & Learning Tools | ✅ (backend) | — |
| WS7 | Export & Presentation | 🟡 | M |
| WS8 | Real-Time Suggestions | ✅ (backend) | — |
| WS9 | Language & Accessibility | ✅ (backend) | — |
| WS10 | Creative & Divergent Thinking | ✅ (backend) | — |
| WS11 | Web UI / Frontend | 🟡 | L |
| WS12 | Persistence & State Sync | 🟡 | M |
| WS13 | Auth, Security & Rate Limiting | ⬜ | M |
| WS14 | Testing & Quality | ⬜ | L |
| WS15 | Deployment & Ops | ⬜ | M |

---

## 3. Workstream Detail

### WS0 — Infrastructure & Server Core ✅
Foundation everything else builds on.

| Task | Status | Effort | Files |
|------|--------|--------|-------|
| FastAPI app, CORS, request logging middleware | ✅ | S | `main.py` |
| SQLite schema + persistence layer | ✅ | S | `db.py` |
| Vertex AI client wrapper (lazy init, `_gen` helper, JSON parsing) | ✅ | S | `gemini.py` |
| Health endpoint, `.env.example`, logging config | ✅ | S | `main.py`, `logging_config.py` |
| Serve SPA at `/`, mount `/static` | ✅ | S | `main.py` |

### WS1 — Mindmap Generation ✅ (backend)
Create maps from varied inputs.

| Task | Status | Endpoint |
|------|--------|----------|
| From topic (overview / deep / brainstorm) | ✅ | `POST /generate/from-topic` |
| From pasted text / outline | ✅ | `POST /generate/from-text` |
| From URL (server-side fetch) | ✅ | `POST /generate/from-url` |
| From uploaded PDF/DOCX/TXT | ✅ | `POST /generate/from-file` |
| **Pending:** strengthen JSON-schema validation of returned tree | ⬜ S | — |

### WS2 — Node Intelligence ✅ (backend)
Per-node editing assistance.

| Task | Status | Endpoint |
|------|--------|----------|
| Expand (explore / drill / examples) | ✅ | `POST /{id}/nodes/expand` |
| Suggest siblings | ✅ | `POST /{id}/nodes/suggest-siblings` |
| Summarise branch | ✅ | `POST /{id}/nodes/summarise-branch` |
| Improve labels | ✅ | `POST /{id}/nodes/improve-labels` |
| Split / Merge nodes | ✅ | `POST /{id}/nodes/split` · `/merge` |
| Explain (explain / eli5 / define / relate) | ✅ | `POST /{id}/nodes/explain` |

### WS3 — Semantic & Relationship Analysis ✅ (backend)

| Task | Status | Endpoint |
|------|--------|----------|
| Discover hidden connections | ✅ | `GET /{id}/connections` |
| Detect contradictions | ✅ | `GET /{id}/contradictions` |
| Tag & classify nodes | ✅ | `POST /{id}/tags` |
| Regroup suggestions | ✅ | `GET /{id}/regroup-suggestions` |

### WS4 — Map Analysis & Critique ✅ (backend)

| Task | Status | Endpoint |
|------|--------|----------|
| Completeness / gap check | ✅ | `GET /{id}/completeness` |
| Balance analysis | ✅ | `GET /{id}/balance` |
| Holistic critique | ✅ | `GET /{id}/critique` |
| Summary card | ✅ | `GET /{id}/summary-card` |

### WS5 — Contextual Chat (SSE) ✅ (backend)

| Task | Status | Endpoint |
|------|--------|----------|
| Streaming chat with map context | ✅ | `POST /{id}/chat` |
| Session history persistence | ✅ | `GET/DELETE /api/sessions/{id}/history` |
| **Pending:** trim/window very large maps before context injection | ⬜ S | — |

### WS6 — Study & Learning Tools ✅ (backend)

| Task | Status | Endpoint |
|------|--------|----------|
| Flashcards (3 styles, cached) | ✅ | `POST /{id}/flashcards` |
| Quiz (mcq / true-false / short, cached) | ✅ | `POST /{id}/quiz` |
| Study path for a learning goal | ✅ | `POST /{id}/study-path` |
| Teach-me narrative | ✅ | `POST /{id}/narrative` |

### WS7 — Export & Presentation 🟡
Backend generates content; **file download / rendering still needed.**

| Task | Status | Effort | Endpoint / Note |
|------|--------|--------|------|
| Outline (Markdown) | ✅ | — | `GET /{id}/export/outline` |
| Slides structure | ✅ | — | `GET /{id}/export/slides` |
| Presentation script | ✅ | — | `GET /{id}/export/script` |
| Action plan | ✅ | — | `GET /{id}/export/action-plan` |
| Render slides → PPTX/PDF download | ⬜ | M | needs `python-pptx` / renderer |
| Export map → `.drawio` / OPML / FreeMind | ⬜ | M | new endpoint |
| UI "Export" menu with file download | ⬜ | S | `static/index.html` |

### WS8 — Real-Time Suggestions ✅ (backend)

| Task | Status | Endpoint |
|------|--------|----------|
| Label autocomplete | ✅ | `POST /api/suggest/autocomplete` |
| Next-node suggestions | ✅ | `POST /api/suggest/next-nodes` |
| Emoji suggestion | ✅ | `POST /api/suggest/emoji` |
| **Pending:** debounce + wire autocomplete into the UI editor | ⬜ M | `static/index.html` |

### WS9 — Language & Accessibility ✅ (backend)

| Task | Status | Endpoint |
|------|--------|----------|
| Translate map labels | ✅ | `POST /{id}/translate` |
| Tone rewrite (simple / formal / casual) | ✅ | `POST /{id}/rewrite-tone` |
| Accessibility summary | ✅ | `GET /{id}/accessibility-summary` |
| **Pending:** surface translate/tone controls in UI | ⬜ S | `static/index.html` |

### WS10 — Creative & Divergent Thinking ✅ (backend)

| Task | Status | Endpoint |
|------|--------|----------|
| What-if scenarios | ✅ | `POST /{id}/what-if` |
| Devil's advocate | ✅ | `POST /{id}/devils-advocate` |
| Analogies | ✅ | `POST /{id}/analogies` |
| Random spark | ✅ | `POST /{id}/random-spark` |

### WS11 — Web UI / Frontend 🟡
Single-page app exists; needs depth.

| Task | Status | Effort |
|------|--------|--------|
| SPA scaffold: canvas (SVG), pan/zoom, panels | ✅ | — |
| Generate forms, saved-map list, sample map | ✅ | — |
| Node actions + map-analysis buttons | ✅ | — |
| SSE chat panel | ✅ | — |
| Manual node create / rename / drag-reposition / delete | ⬜ | L |
| Inline display of tags, critique markers, connection edges | ⬜ | M |
| Flashcard / quiz study mode UI | ⬜ | M |
| Export menu + downloads | ⬜ | S |
| Mobile-responsive layout | ⬜ | M |

### WS12 — Persistence & State Sync 🟡
Keep the DB copy of a map in step with UI edits.

| Task | Status | Effort |
|------|--------|--------|
| Mindmap CRUD + node tree store | ✅ | — |
| Generic AI cache + flashcard/quiz cache | ✅ | — |
| Persist node mutations (expand/split/edit) back via `PATCH /{id}/nodes` | 🟡 | S |
| Autosave / optimistic concurrency (updated_at check) | ⬜ | M |
| Cache invalidation when the tree changes | ⬜ | S |

### WS13 — Auth, Security & Rate Limiting ⬜

| Task | Status | Effort |
|------|--------|--------|
| API key / token auth on write + AI endpoints | ⬜ | M |
| Per-session rate limiting & quota | ⬜ | M |
| Tighten CORS to known origins (currently `*`) | ⬜ | S |
| Upload hardening (size, MIME, content sniffing) | 🟡 | S |
| SSRF guard on `from-url` (block private IPs/metadata) | ⬜ | S |

### WS14 — Testing & Quality ⬜

| Task | Status | Effort |
|------|--------|--------|
| Unit tests for `db.py` (CRUD, cache, replace ops) | ⬜ | M |
| Route tests with a mocked `gemini` module | ⬜ | M |
| JSON-parsing/robustness tests for model outputs | ⬜ | S |
| SSE chat streaming integration test | ⬜ | S |
| CI workflow (lint + tests) | ⬜ | S |
| SessionStart hook for web sessions (lint/test bootstrap) | ⬜ | S |

### WS15 — Deployment & Ops ⬜

| Task | Status | Effort |
|------|--------|--------|
| Document GCP service-account / ADC setup | ✅ | — (README) |
| Dockerfile + container build | ⬜ | S |
| Config for prod (gunicorn/uvicorn workers, env) | ⬜ | S |
| Structured logging / error reporting sink | ⬜ | M |
| Cost controls (token caps, model selection per route) | 🟡 | S |

---

## 4. Milestones / Phases

| Phase | Theme | Includes | Exit criteria |
|-------|-------|----------|---------------|
| **P0 — Foundation** ✅ | Server + all AI endpoints + UI shell + diagram | WS0–WS6, WS8–WS11 (backend), docs | Endpoints respond; UI renders & calls them |
| **P1 — Make it real** 🟡 | Credentials, persistence wiring, security basics | GCP creds, WS12, WS13 (CORS/SSRF/upload), WS7 UI export | End-to-end AI flows work against live Gemini; edits persist |
| **P2 — Depth & polish** ⬜ | Rich editing, study mode, exports, language UI | WS11 editing, WS6/WS7/WS9 UI surfaces | Users can fully build & study a map in-app |
| **P3 — Production** ⬜ | Tests, CI, Docker, ops | WS14, WS15 | Green CI, containerised, deployable |

---

## 5. Critical Path & Dependencies

```
GCP credentials  ──►  live AI verification  ──►  P1 sign-off
                          │
WS12 persist edits  ──────┤
WS13 SSRF/CORS  ──────────┘

WS11 rich editing  ──►  WS6/WS7 study & export UI  ──►  P2
WS14 tests  ──►  WS15 CI/Docker  ──►  P3
```

- **Blocker:** All AI features need GCP credentials (`GOOGLE_APPLICATION_CREDENTIALS`) before they can be verified end-to-end. Everything else can proceed in parallel.
- **Sequence:** Rich UI editing (WS11) should land before the study-mode and export UIs, which depend on stable client-side map state.

---

## 6. Risks & Mitigations

| Risk | Impact | Mitigation |
|------|--------|-----------|
| Model returns malformed JSON | Endpoint 500s | Tolerant `_parse_json` already in place; add schema validation + retry (WS1) |
| Large maps blow the context window | Truncated/poor answers | Context windowing/trimming before injection (WS5) |
| Gemini cost on heavy use | Budget | Caching (done) + token caps + per-route model choice (WS15) |
| `from-url` SSRF | Security | Block private/metadata IPs (WS13) |
| Open CORS `*` | Security | Restrict to known origins for prod (WS13) |

---

## 7. Immediate Next Actions

1. Provision a **GCP service account** and set `.env` → verify one AI endpoint live.
2. Wire UI node mutations to **`PATCH /{id}/nodes`** so expand/split/edit persist (WS12).
3. Add **SSRF guard** to `from-url` and tighten **CORS** (WS13 quick wins).
4. Stand up **pytest** with a mocked `gemini` module + a CI workflow (WS14).
5. Build the **Export menu** with file downloads (WS7 UI).
