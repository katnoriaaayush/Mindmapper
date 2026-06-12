"""Creative & divergent thinking endpoints: what-if, devil's advocate, analogy, random spark."""
import logging

from fastapi import APIRouter, HTTPException
from pydantic import BaseModel

import db
import gemini

logger = logging.getLogger("mindmap.creative")
router = APIRouter()


def _require_map(map_id: str):
    row = db.get_mindmap(map_id)
    if not row:
        raise HTTPException(404, detail="Mindmap not found.")
    return row


class NodeActionRequest(BaseModel):
    nodeLabel: str


@router.post("/{map_id}/what-if")
async def what_if(map_id: str, body: NodeActionRequest):
    row = _require_map(map_id)
    branches = await gemini.what_if_branch(body.nodeLabel, row["central_topic"], row["nodes_json"][:1500])
    return {"nodeLabel": body.nodeLabel, "scenarios": branches}


@router.post("/{map_id}/devils-advocate")
async def devils_advocate(map_id: str, body: NodeActionRequest):
    row = _require_map(map_id)
    counterpoints = await gemini.devils_advocate(body.nodeLabel, row["central_topic"])
    return {"nodeLabel": body.nodeLabel, "counterpoints": counterpoints}


@router.post("/{map_id}/analogies")
async def analogies(map_id: str, body: NodeActionRequest):
    row = _require_map(map_id)
    result = await gemini.analogy_finder(body.nodeLabel, row["central_topic"])
    return {"nodeLabel": body.nodeLabel, "analogies": result}


@router.post("/{map_id}/random-spark")
async def random_spark(map_id: str, body: NodeActionRequest):
    row = _require_map(map_id)
    spark = await gemini.random_spark(body.nodeLabel, row["nodes_json"][:800])
    return {"nodeLabel": body.nodeLabel, "spark": spark}
