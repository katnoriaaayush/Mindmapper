# MindMap AI Server

Local FastAPI server powering AI features in the MindMapper app via Vertex AI (Gemini).

## Setup

```bash
cd MindMapAIServer
python -m venv .venv && source .venv/bin/activate
pip install -r requirements.txt
cp .env.example .env   # fill in your GCP credentials
python main.py
```

Server starts at `http://localhost:3000`. Health check: `GET /health`.

## Configuration (.env)

| Variable | Default | Description |
|---|---|---|
| `GOOGLE_APPLICATION_CREDENTIALS` | — | Path to GCP service account JSON |
| `GOOGLE_CLOUD_PROJECT` | — | GCP project ID |
| `GOOGLE_CLOUD_LOCATION` | `us-central1` | Vertex AI region |
| `VERTEX_MODEL` | `gemini-2.0-flash-001` | Gemini model to use |
| `PORT` | `3000` | Server port |
| `LOG_LEVEL` | `INFO` | `DEBUG` / `INFO` / `WARNING` |

The service account needs the `roles/aiplatform.user` IAM role.

## API Endpoints

### Mindmap CRUD
| Method | Path | Description |
|---|---|---|
| `POST` | `/api/mindmaps` | Create a mindmap |
| `GET` | `/api/mindmaps` | List all mindmaps |
| `GET` | `/api/mindmaps/{id}` | Get mindmap with nodes |
| `PATCH` | `/api/mindmaps/{id}/nodes` | Update node tree |
| `DELETE` | `/api/mindmaps/{id}` | Delete mindmap |

### Generation
| Method | Path | Description |
|---|---|---|
| `POST` | `/api/mindmaps/generate/from-topic` | Generate from a topic string (`overview`/`deep`/`brainstorm`) |
| `POST` | `/api/mindmaps/generate/from-text` | Generate from pasted text or outline |
| `POST` | `/api/mindmaps/generate/from-url` | Generate from a web article URL |
| `POST` | `/api/mindmaps/generate/from-file` | Generate from uploaded PDF/DOCX/TXT |

### Node Intelligence
| Method | Path | Description |
|---|---|---|
| `POST` | `/api/mindmaps/{id}/nodes/expand` | Expand a node into children (`explore`/`drill`/`examples`) |
| `POST` | `/api/mindmaps/{id}/nodes/suggest-siblings` | Suggest missing siblings |
| `POST` | `/api/mindmaps/{id}/nodes/summarise-branch` | Summarise a subtree as a paragraph |
| `POST` | `/api/mindmaps/{id}/nodes/improve-labels` | Rewrite verbose/unclear node labels |
| `POST` | `/api/mindmaps/{id}/nodes/split` | Split a broad node into focused children |
| `POST` | `/api/mindmaps/{id}/nodes/merge` | Merge overlapping nodes into one |
| `POST` | `/api/mindmaps/{id}/nodes/explain` | Explain a node (`explain`/`eli5`/`define`/`relate`) |

### Map Analysis
| Method | Path | Description |
|---|---|---|
| `GET` | `/api/mindmaps/{id}/connections` | Discover hidden cross-branch relationships |
| `GET` | `/api/mindmaps/{id}/contradictions` | Detect conflicting nodes |
| `POST` | `/api/mindmaps/{id}/tags` | Assign semantic tags to nodes |
| `GET` | `/api/mindmaps/{id}/regroup-suggestions` | Suggest restructuring |
| `GET` | `/api/mindmaps/{id}/completeness` | Find missing subtopics |
| `GET` | `/api/mindmaps/{id}/critique` | Holistic map review with feedback |
| `GET` | `/api/mindmaps/{id}/summary-card` | Quick metadata card (themes, complexity) |
| `GET` | `/api/mindmaps/{id}/balance` | Branch balance analysis |

### Chat (Streaming SSE)
| Method | Path | Description |
|---|---|---|
| `POST` | `/api/mindmaps/{id}/chat` | Stream Q&A with full mindmap as context |

### Study & Learning
| Method | Path | Description |
|---|---|---|
| `POST` | `/api/mindmaps/{id}/flashcards` | Generate flashcards (`term-definition`/`question-answer`/`fill-in-the-blank`) |
| `POST` | `/api/mindmaps/{id}/quiz` | Generate mixed quiz questions |
| `POST` | `/api/mindmaps/{id}/study-path` | Ordered study sequence for a learning goal |
| `POST` | `/api/mindmaps/{id}/narrative` | Tutor-style explanation of map or branch |

### Export & Presentation
| Method | Path | Description |
|---|---|---|
| `GET` | `/api/mindmaps/{id}/export/outline` | Markdown nested bullet outline |
| `GET` | `/api/mindmaps/{id}/export/slides` | Slide deck structure |
| `GET` | `/api/mindmaps/{id}/export/script` | Spoken presentation script |
| `GET` | `/api/mindmaps/{id}/export/action-plan` | Prioritised task list |

### Real-Time Suggestions
| Method | Path | Description |
|---|---|---|
| `POST` | `/api/suggest/autocomplete` | Label completions as you type |
| `POST` | `/api/suggest/next-nodes` | What to add after placing a node |
| `POST` | `/api/suggest/emoji` | Emoji per node label |

### Language & Accessibility
| Method | Path | Description |
|---|---|---|
| `POST` | `/api/mindmaps/{id}/translate` | Translate all labels to target language |
| `POST` | `/api/mindmaps/{id}/rewrite-tone` | Shift to `simple`/`formal`/`casual` tone |
| `GET` | `/api/mindmaps/{id}/accessibility-summary` | Plain-text screen-reader description |

### Creative & Divergent Thinking
| Method | Path | Description |
|---|---|---|
| `POST` | `/api/mindmaps/{id}/what-if` | Speculative "what if" scenario branches |
| `POST` | `/api/mindmaps/{id}/devils-advocate` | Counterarguments to a node |
| `POST` | `/api/mindmaps/{id}/analogies` | Cross-domain analogies for a concept |
| `POST` | `/api/mindmaps/{id}/random-spark` | Unexpected idea to break creative blocks |

### Sessions
| Method | Path | Description |
|---|---|---|
| `GET` | `/api/sessions/{id}/history` | Chat history |
| `DELETE` | `/api/sessions/{id}/history` | Clear chat history |
