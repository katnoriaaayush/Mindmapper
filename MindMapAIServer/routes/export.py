"""Export & presentation endpoints: outline, slides, script, action plan."""
import logging

from fastapi import APIRouter, HTTPException

import db
import gemini

logger = logging.getLogger("mindmap.export")
router = APIRouter()


def _require_map(map_id: str):
    row = db.get_mindmap(map_id)
    if not row:
        raise HTTPException(404, detail="Mindmap not found.")
    return row


@router.get("/{map_id}/export/outline")
async def export_outline(map_id: str):
    row = _require_map(map_id)
    outline = await gemini.export_outline(row["nodes_json"], row["central_topic"])
    return {"outline": outline, "format": "markdown"}


@router.get("/{map_id}/export/slides")
async def export_slides(map_id: str):
    row = _require_map(map_id)
    slides = await gemini.export_slides(row["nodes_json"], row["central_topic"])
    return {"slides": slides}


@router.get("/{map_id}/export/script")
async def export_script(map_id: str):
    row = _require_map(map_id)
    script = await gemini.export_script(row["nodes_json"], row["central_topic"])
    return {"script": script}


@router.get("/{map_id}/export/action-plan")
async def export_action_plan(map_id: str):
    row = _require_map(map_id)
    tasks = await gemini.export_action_plan(row["nodes_json"], row["central_topic"])
    return {"tasks": tasks}
