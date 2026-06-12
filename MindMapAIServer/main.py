"""
MindMap AI Server — FastAPI + Vertex AI (Gemini) + SQLite.

Run with:  python main.py
       or:  uvicorn main:app --host 0.0.0.0 --port 3000
"""
import logging
import os
import time

from dotenv import load_dotenv

load_dotenv()
import logging_config  # noqa: E402

logging_config.configure()

from contextlib import asynccontextmanager  # noqa: E402

from fastapi import FastAPI, Request  # noqa: E402
from fastapi.middleware.cors import CORSMiddleware  # noqa: E402

from routes import (  # noqa: E402
    mindmaps,
    generate,
    nodes,
    analysis,
    chat,
    study,
    export,
    suggestions,
    language,
    creative,
    sessions,
)

logger = logging.getLogger("mindmap")
access_logger = logging.getLogger("mindmap.http")

START_TIME = time.time()


@asynccontextmanager
async def lifespan(_: "FastAPI"):
    port = os.environ.get("PORT", "3000")
    logger.info("MindMap AI Server  →  http://localhost:%s", port)
    logger.info("Health check       →  http://localhost:%s/health", port)
    logger.info("Vertex project     →  %s", os.environ.get("GOOGLE_CLOUD_PROJECT", "(not set)"))
    yield


app = FastAPI(title="MindMap AI Server", version="1.0.0", lifespan=lifespan)

app.add_middleware(
    CORSMiddleware,
    allow_origins=["*"],
    allow_methods=["GET", "POST", "PATCH", "DELETE", "OPTIONS"],
    allow_headers=["*"],
)


@app.middleware("http")
async def log_requests(request: Request, call_next):
    started = time.monotonic()
    client_host = request.client.host if request.client else "?"
    try:
        response = await call_next(request)
    except Exception:
        elapsed_ms = (time.monotonic() - started) * 1000
        access_logger.exception(
            "%s %s ← 500 %.0fms from %s",
            request.method, request.url.path, elapsed_ms, client_host,
        )
        raise
    elapsed_ms = (time.monotonic() - started) * 1000
    level = logging.WARNING if response.status_code >= 400 else logging.INFO
    access_logger.log(
        level, "%s %s ← %d %.0fms from %s",
        request.method, request.url.path, response.status_code, elapsed_ms, client_host,
    )
    return response


# ── Routers ──────────────────────────────────────────────────────────────────────────────

app.include_router(mindmaps.router,    prefix="/api/mindmaps",          tags=["mindmaps"])
app.include_router(generate.router,    prefix="/api/mindmaps/generate",  tags=["generate"])
app.include_router(nodes.router,       prefix="/api/mindmaps",           tags=["nodes"])
app.include_router(analysis.router,    prefix="/api/mindmaps",           tags=["analysis"])
app.include_router(chat.router,        prefix="/api/mindmaps",           tags=["chat"])
app.include_router(study.router,       prefix="/api/mindmaps",           tags=["study"])
app.include_router(export.router,      prefix="/api/mindmaps",           tags=["export"])
app.include_router(suggestions.router, prefix="/api/suggest",            tags=["suggestions"])
app.include_router(language.router,    prefix="/api/mindmaps",           tags=["language"])
app.include_router(creative.router,    prefix="/api/mindmaps",           tags=["creative"])
app.include_router(sessions.router,    prefix="/api/sessions",           tags=["sessions"])


@app.get("/")
async def root():
    """Landing endpoint so the bare base URL is informative instead of a 404."""
    return {
        "service": "MindMap AI Server",
        "version": app.version,
        "docs": "/docs",
        "health": "/health",
        "api": "/api/mindmaps",
    }


@app.get("/health")
async def health():
    return {
        "status": "ok",
        "uptime": int(time.time() - START_TIME),
        "vertexProject": os.environ.get("GOOGLE_CLOUD_PROJECT", "(not set)"),
        "vertexModel": os.environ.get("VERTEX_MODEL", "(not set)"),
        "vertexLocation": os.environ.get("GOOGLE_CLOUD_LOCATION", "(not set)"),
        "credentials": "configured" if os.environ.get("GOOGLE_APPLICATION_CREDENTIALS") else "(not set)",
    }


if __name__ == "__main__":
    import uvicorn
    uvicorn.run(app, host="0.0.0.0", port=int(os.environ.get("PORT", "3000")))
