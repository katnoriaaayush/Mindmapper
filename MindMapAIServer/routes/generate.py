"""Mindmap generation endpoints — from topic, text paste, uploaded file, or URL."""
import hashlib
import json
import logging
import uuid
from typing import Optional

import httpx
from fastapi import APIRouter, HTTPException, UploadFile, File, Form
from pydantic import BaseModel

import db
import gemini

logger = logging.getLogger("mindmap.generate")
router = APIRouter()

ALLOWED_MIME = {
    "application/pdf",
    "text/plain",
    "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
    "application/msword",
    "text/markdown",
}


class GenerateFromTopicRequest(BaseModel):
    topic: str
    mode: str = "overview"
    sessionId: Optional[str] = None
    saveAs: Optional[str] = None


class GenerateFromTextRequest(BaseModel):
    text: str
    sourceHint: str = ""
    sessionId: Optional[str] = None
    saveAs: Optional[str] = None


class GenerateFromURLRequest(BaseModel):
    url: str
    sessionId: Optional[str] = None
    saveAs: Optional[str] = None


def _save_mindmap(nodes: dict, title: str, session_id: str) -> str:
    map_id = str(uuid.uuid4())
    central = nodes.get("label", title)
    db.insert_mindmap(map_id, title, session_id, central, json.dumps(nodes))
    return map_id


@router.post("/from-topic")
async def generate_from_topic(body: GenerateFromTopicRequest):
    valid_modes = {"overview", "deep", "brainstorm"}
    if body.mode not in valid_modes:
        raise HTTPException(400, detail=f"mode must be one of: {', '.join(valid_modes)}")

    session_id = body.sessionId or str(uuid.uuid4())
    nodes = await gemini.generate_from_topic(body.topic, body.mode)
    title = body.saveAs or body.topic
    map_id = _save_mindmap(nodes, title, session_id)

    logger.info("Generated mindmap from topic=%s mode=%s id=%s", body.topic, body.mode, map_id)
    return {"mapId": map_id, "sessionId": session_id, "nodes": nodes}


@router.post("/from-text")
async def generate_from_text(body: GenerateFromTextRequest):
    if not body.text.strip():
        raise HTTPException(400, detail="text is required.")

    session_id = body.sessionId or str(uuid.uuid4())
    nodes = await gemini.generate_from_text(body.text, body.sourceHint)
    title = body.saveAs or (body.text[:40] + "...") if len(body.text) > 40 else body.text
    map_id = _save_mindmap(nodes, title, session_id)

    logger.info("Generated mindmap from text len=%d id=%s", len(body.text), map_id)
    return {"mapId": map_id, "sessionId": session_id, "nodes": nodes}


@router.post("/from-url")
async def generate_from_url(body: GenerateFromURLRequest):
    if not body.url.startswith(("http://", "https://")):
        raise HTTPException(400, detail="url must start with http:// or https://")

    try:
        async with httpx.AsyncClient(timeout=15) as client:
            resp = await client.get(body.url, follow_redirects=True)
            resp.raise_for_status()
            text = resp.text[:10000]
    except httpx.HTTPError as exc:
        raise HTTPException(502, detail=f"Failed to fetch URL: {exc}")

    session_id = body.sessionId or str(uuid.uuid4())
    nodes = await gemini.generate_from_text(text, source_hint=body.url)
    title = body.saveAs or body.url[:60]
    map_id = _save_mindmap(nodes, title, session_id)

    logger.info("Generated mindmap from url=%s id=%s", body.url, map_id)
    return {"mapId": map_id, "sessionId": session_id, "nodes": nodes}


@router.post("/from-file")
async def generate_from_file(
    file: UploadFile = File(...),
    sessionId: Optional[str] = Form(None),
    saveAs: Optional[str] = Form(None),
):
    mime = file.content_type or "application/octet-stream"
    if mime not in ALLOWED_MIME:
        raise HTTPException(400, detail=f"Unsupported file type: {mime}")

    data = await file.read()
    if len(data) > 20 * 1024 * 1024:
        raise HTTPException(400, detail="File too large (max 20 MB).")

    upload_id = hashlib.sha256(data).hexdigest()
    file_path = str(db.UPLOADS_DIR / f"{upload_id}{_ext(mime)}")
    with open(file_path, "wb") as fh:
        fh.write(data)
    db.insert_upload(upload_id, file_path, file.filename or "upload", mime, len(data))

    session_id = sessionId or str(uuid.uuid4())
    nodes = await gemini.generate_from_file(file_path, mime)
    title = saveAs or file.filename or "Uploaded document"
    map_id = _save_mindmap(nodes, title, session_id)

    logger.info("Generated mindmap from file=%s id=%s", file.filename, map_id)
    return {"mapId": map_id, "sessionId": session_id, "nodes": nodes}


def _ext(mime: str) -> str:
    return {
        "application/pdf": ".pdf",
        "text/plain": ".txt",
        "text/markdown": ".md",
        "application/vnd.openxmlformats-officedocument.wordprocessingml.document": ".docx",
        "application/msword": ".doc",
    }.get(mime, ".bin")
