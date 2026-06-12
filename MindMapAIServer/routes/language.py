"""Language & accessibility endpoints: translate, tone rewrite, accessibility summary."""
import logging

from fastapi import APIRouter, HTTPException
from pydantic import BaseModel

import db
import gemini

logger = logging.getLogger("mindmap.language")
router = APIRouter()


def _require_map(map_id: str):
    row = db.get_mindmap(map_id)
    if not row:
        raise HTTPException(404, detail="Mindmap not found.")
    return row


class TranslateRequest(BaseModel):
    nodes: list[dict]
    targetLanguage: str


@router.post("/{map_id}/translate")
async def translate(map_id: str, body: TranslateRequest):
    _require_map(map_id)
    if not body.targetLanguage.strip():
        raise HTTPException(400, detail="targetLanguage is required.")
    translated = await gemini.translate_map(body.nodes, body.targetLanguage)
    return {"translated": translated, "language": body.targetLanguage}


class ToneRequest(BaseModel):
    nodes: list[dict]
    tone: str = "simple"


@router.post("/{map_id}/rewrite-tone")
async def rewrite_tone(map_id: str, body: ToneRequest):
    _require_map(map_id)
    valid_tones = {"simple", "formal", "casual"}
    if body.tone not in valid_tones:
        raise HTTPException(400, detail=f"tone must be one of: {', '.join(valid_tones)}")
    rewritten = await gemini.rewrite_tone(body.nodes, body.tone)
    return {"rewritten": rewritten, "tone": body.tone}


@router.get("/{map_id}/accessibility-summary")
async def accessibility_summary(map_id: str):
    row = _require_map(map_id)
    summary = await gemini.accessibility_summary(row["nodes_json"], row["central_topic"])
    return {"summary": summary}
