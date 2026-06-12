"""Mindmap CRUD endpoints."""
import json
import logging
import uuid

from fastapi import APIRouter, HTTPException
from pydantic import BaseModel

import db

logger = logging.getLogger("mindmap.mindmaps")
router = APIRouter()


class CreateMindmapRequest(BaseModel):
    title: str
    centralTopic: str
    sessionId: str | None = None
    nodesJson: str | None = None


@router.post("", status_code=201)
async def create_mindmap(body: CreateMindmapRequest):
    map_id = str(uuid.uuid4())
    session_id = body.sessionId or str(uuid.uuid4())
    nodes_json = body.nodesJson or json.dumps({"id": "root", "label": body.centralTopic, "children": []})
    db.insert_mindmap(map_id, body.title, session_id, body.centralTopic, nodes_json)
    logger.info("Created mindmap id=%s title=%s", map_id, body.title)
    return {"id": map_id, "sessionId": session_id, "title": body.title, "centralTopic": body.centralTopic}


@router.get("")
async def list_mindmaps():
    rows = db.list_mindmaps()
    return {"mindmaps": [dict(r) for r in rows]}


@router.get("/{map_id}")
async def get_mindmap(map_id: str):
    row = db.get_mindmap(map_id)
    if not row:
        raise HTTPException(status_code=404, detail="Mindmap not found.")
    data = dict(row)
    data["nodes"] = json.loads(data.pop("nodes_json", "{}"))
    return data


@router.patch("/{map_id}/nodes")
async def update_nodes(map_id: str, body: dict):
    if not db.get_mindmap(map_id):
        raise HTTPException(status_code=404, detail="Mindmap not found.")
    nodes_json = json.dumps(body.get("nodes", {}))
    db.update_mindmap_nodes(map_id, nodes_json)
    return {"updated": True}


@router.delete("/{map_id}", status_code=204)
async def delete_mindmap(map_id: str):
    if not db.get_mindmap(map_id):
        raise HTTPException(status_code=404, detail="Mindmap not found.")
    db.delete_mindmap(map_id)
