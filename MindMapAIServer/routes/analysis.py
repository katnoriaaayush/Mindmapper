"""Map-level AI analysis endpoints: connections, tags, critique, completeness, balance, regroup."""
import hashlib
import json
import logging
from typing import Optional

from fastapi import APIRouter, HTTPException
from pydantic import BaseModel

import db
import gemini

logger = logging.getLogger("mindmap.analysis")
router = APIRouter()


def _require_map(map_id: str):
    row = db.get_mindmap(map_id)
    if not row:
        raise HTTPException(404, detail="Mindmap not found.")
    return row


def _map_json(row) -> str:
    return row["nodes_json"]


def _flat_labels(nodes_json: str) -> list[str]:
    labels = []
    def walk(node):
        if isinstance(node, dict):
            if "label" in node:
                labels.append(node["label"])
            for child in node.get("children", []):
                walk(child)
        elif isinstance(node, list):
            for item in node:
                walk(item)
    try:
        walk(json.loads(nodes_json))
    except Exception:
        pass
    return labels


def _cached(map_id: str, feature: str, key: str):
    cached = db.get_cache(map_id, feature, hashlib.sha256(key.encode()).hexdigest()[:16])
    return json.loads(cached) if cached else None


def _cache(map_id: str, feature: str, key: str, data):
    db.set_cache(map_id, feature, hashlib.sha256(key.encode()).hexdigest()[:16], json.dumps(data))


@router.get("/{map_id}/connections")
async def discover_connections(map_id: str):
    row = _require_map(map_id)
    labels = _flat_labels(_map_json(row))
    cache_key = ":".join(sorted(labels))
    cached = _cached(map_id, "connections", cache_key)
    if cached is not None:
        return {"connections": cached, "cached": True}
    connections = await gemini.discover_connections(labels, row["central_topic"])
    _cache(map_id, "connections", cache_key, connections)
    return {"connections": connections, "cached": False}


@router.get("/{map_id}/contradictions")
async def detect_contradictions(map_id: str):
    row = _require_map(map_id)
    labels = _flat_labels(_map_json(row))
    cache_key = ":".join(sorted(labels))
    cached = _cached(map_id, "contradictions", cache_key)
    if cached is not None:
        return {"contradictions": cached, "cached": True}
    contradictions = await gemini.detect_contradictions(labels, row["central_topic"])
    _cache(map_id, "contradictions", cache_key, contradictions)
    return {"contradictions": contradictions, "cached": False}


class TagNodesRequest(BaseModel):
    nodes: list[dict]


@router.post("/{map_id}/tags")
async def tag_nodes(map_id: str, body: TagNodesRequest):
    _require_map(map_id)
    tags = await gemini.tag_nodes(body.nodes)
    return {"tags": tags}


@router.get("/{map_id}/regroup-suggestions")
async def suggest_regroup(map_id: str):
    row = _require_map(map_id)
    suggestions = await gemini.suggest_regroup(_map_json(row), row["central_topic"])
    return {"suggestions": suggestions}


@router.get("/{map_id}/completeness")
async def completeness_check(map_id: str):
    row = _require_map(map_id)
    cache_key = _map_json(row)
    cached = _cached(map_id, "completeness", cache_key)
    if cached is not None:
        return {"missing": cached, "cached": True}
    missing = await gemini.completeness_check(_map_json(row), row["central_topic"])
    _cache(map_id, "completeness", cache_key, missing)
    return {"missing": missing, "cached": False}


@router.get("/{map_id}/critique")
async def critique_map(map_id: str):
    row = _require_map(map_id)
    feedback = await gemini.critique_map(_map_json(row), row["central_topic"])
    return {"feedback": feedback}


@router.get("/{map_id}/summary-card")
async def summary_card(map_id: str):
    row = _require_map(map_id)
    cache_key = _map_json(row)
    cached = _cached(map_id, "summary_card", cache_key)
    if cached is not None:
        return {**cached, "cached": True}
    card = await gemini.map_summary_card(_map_json(row), row["central_topic"])
    _cache(map_id, "summary_card", cache_key, card)
    return {**card, "cached": False}


@router.get("/{map_id}/balance")
async def balance_analysis(map_id: str):
    row = _require_map(map_id)
    analysis = await gemini.balance_analysis(_map_json(row))
    return {"branches": analysis}
