"""Chat session history management."""
import logging

from fastapi import APIRouter

import db

logger = logging.getLogger("mindmap.sessions")
router = APIRouter()


@router.get("/{session_id}/history")
async def get_history(session_id: str):
    rows = db.get_history(session_id, limit=50)
    return {"history": [dict(r) for r in rows]}


@router.delete("/{session_id}/history", status_code=204)
async def clear_history(session_id: str):
    db.clear_history(session_id)
