"""Node-level AI endpoints: expand, siblings, summarise, improve labels, split, merge."""
import json
import logging
from typing import Optional

from fastapi import APIRouter, HTTPException
from pydantic import BaseModel

import db
import gemini

logger = logging.getLogger("mindmap.nodes")
router = APIRouter()


def _require_map(map_id: str):
    row = db.get_mindmap(map_id)
    if not row:
        raise HTTPException(404, detail="Mindmap not found.")
    return row


def _map_json(row) -> str:
    return row["nodes_json"] if isinstance(row["nodes_json"], str) else json.dumps(row["nodes_json"])


class ExpandRequest(BaseModel):
    nodeId: str
    nodeLabel: str
    parentLabel: str = ""
    mode: str = "explore"
    count: int = 5


@router.post("/{map_id}/nodes/expand")
async def expand_node(map_id: str, body: ExpandRequest):
    row = _require_map(map_id)
    valid_modes = {"explore", "drill", "examples"}
    if body.mode not in valid_modes:
        raise HTTPException(400, detail=f"mode must be one of: {', '.join(valid_modes)}")
    count = min(max(body.count, 2), 10)
    children = await gemini.expand_node(_map_json(row), body.nodeLabel, body.parentLabel, body.mode, count)
    return {"nodeId": body.nodeId, "children": children}


class SuggestSiblingsRequest(BaseModel):
    nodeLabel: str
    parentLabel: str
    existingSiblings: list[str] = []


@router.post("/{map_id}/nodes/suggest-siblings")
async def suggest_siblings(map_id: str, body: SuggestSiblingsRequest):
    row = _require_map(map_id)
    suggestions = await gemini.suggest_siblings(
        _map_json(row), body.nodeLabel, body.parentLabel, body.existingSiblings
    )
    return {"suggestions": suggestions}


class SummariseBranchRequest(BaseModel):
    branchJson: dict
    nodeLabel: str


@router.post("/{map_id}/nodes/summarise-branch")
async def summarise_branch(map_id: str, body: SummariseBranchRequest):
    row = _require_map(map_id)
    summary = await gemini.summarise_branch(json.dumps(body.branchJson), row["central_topic"])
    return {"nodeLabel": body.nodeLabel, "summary": summary}


class ImproveLabelsRequest(BaseModel):
    nodes: list[dict]


@router.post("/{map_id}/nodes/improve-labels")
async def improve_labels(map_id: str, body: ImproveLabelsRequest):
    _require_map(map_id)
    if not body.nodes:
        raise HTTPException(400, detail="nodes list is required.")
    improved = await gemini.improve_labels(body.nodes)
    return {"improved": improved}


class SplitNodeRequest(BaseModel):
    nodeLabel: str
    parentLabel: str = ""


@router.post("/{map_id}/nodes/split")
async def split_node(map_id: str, body: SplitNodeRequest):
    row = _require_map(map_id)
    children = await gemini.split_node(body.nodeLabel, body.parentLabel, _map_json(row))
    return {"originalLabel": body.nodeLabel, "splitInto": children}


class MergeNodesRequest(BaseModel):
    nodeLabels: list[str]
    parentLabel: str = ""


@router.post("/{map_id}/nodes/merge")
async def merge_nodes(map_id: str, body: MergeNodesRequest):
    _require_map(map_id)
    if len(body.nodeLabels) < 2:
        raise HTTPException(400, detail="At least 2 node labels required to merge.")
    merged = await gemini.merge_nodes(body.nodeLabels, body.parentLabel)
    return {"merged": merged}


class ExplainNodeRequest(BaseModel):
    nodeLabel: str
    mode: str = "explain"


@router.post("/{map_id}/nodes/explain")
async def explain_node(map_id: str, body: ExplainNodeRequest):
    row = _require_map(map_id)
    valid_modes = {"explain", "eli5", "define", "relate"}
    if body.mode not in valid_modes:
        raise HTTPException(400, detail=f"mode must be one of: {', '.join(valid_modes)}")
    explanation = await gemini.explain_node(body.nodeLabel, _map_json(row), row["central_topic"], body.mode)
    return {"nodeLabel": body.nodeLabel, "mode": body.mode, "explanation": explanation}
