"""Real-time lightweight suggestion endpoints: autocomplete, next nodes, emoji."""
import logging
from typing import Optional

from fastapi import APIRouter, HTTPException
from pydantic import BaseModel

import db
import gemini

logger = logging.getLogger("mindmap.suggestions")
router = APIRouter()


class AutocompleteRequest(BaseModel):
    partial: str
    parentLabel: str = ""
    siblings: list[str] = []


@router.post("/autocomplete")
async def autocomplete(body: AutocompleteRequest):
    if not body.partial.strip():
        raise HTTPException(400, detail="partial is required.")
    completions = await gemini.autocomplete_label(body.partial, body.parentLabel, body.siblings)
    return {"completions": completions}


class NextNodeRequest(BaseModel):
    mapId: str
    lastLabel: str
    parentLabel: str = ""


@router.post("/next-nodes")
async def next_nodes(body: NextNodeRequest):
    row = db.get_mindmap(body.mapId)
    map_ctx = row["nodes_json"][:800] if row else ""
    suggestions = await gemini.next_node_suggestions(body.lastLabel, body.parentLabel, map_ctx)
    return {"suggestions": suggestions}


class EmojiRequest(BaseModel):
    labels: list[str]


@router.post("/emoji")
async def emoji_suggestion(body: EmojiRequest):
    if not body.labels:
        raise HTTPException(400, detail="labels list is required.")
    emojis = await gemini.emoji_suggestion(body.labels)
    return {"emojis": emojis}
