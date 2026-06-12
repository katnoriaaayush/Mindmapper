"""Study & learning endpoints: flashcards, quiz, study path, narrative."""
import json
import logging
from typing import Optional

from fastapi import APIRouter, HTTPException
from pydantic import BaseModel

import db
import gemini

logger = logging.getLogger("mindmap.study")
router = APIRouter()


def _require_map(map_id: str):
    row = db.get_mindmap(map_id)
    if not row:
        raise HTTPException(404, detail="Mindmap not found.")
    return row


class FlashcardsRequest(BaseModel):
    style: str = "question-answer"
    count: int = 15


@router.post("/{map_id}/flashcards")
async def generate_flashcards(map_id: str, body: FlashcardsRequest):
    row = _require_map(map_id)
    valid_styles = {"term-definition", "question-answer", "fill-in-the-blank"}
    if body.style not in valid_styles:
        raise HTTPException(400, detail=f"style must be one of: {', '.join(valid_styles)}")
    cached = db.get_flashcards(map_id)
    if cached:
        return {
            "flashcards": [
                {"id": c["id"], "nodeId": c["node_id"], "front": c["front"],
                 "back": c["back"], "difficulty": c["difficulty"]}
                for c in cached
            ],
            "cached": True,
        }
    count = min(max(body.count, 5), 30)
    cards = await gemini.generate_flashcards(row["nodes_json"], row["central_topic"], body.style, count)
    db.replace_flashcards(map_id, cards)
    return {"flashcards": cards, "cached": False}


class QuizRequest(BaseModel):
    count: int = 10


@router.post("/{map_id}/quiz")
async def generate_quiz(map_id: str, body: QuizRequest):
    row = _require_map(map_id)
    cached = db.get_quiz(map_id)
    if cached:
        return {
            "questions": [
                {
                    "id": q["id"], "nodeId": q["node_id"], "question": q["question"],
                    "options": json.loads(q["options"]), "answer": q["answer"], "type": q["type"],
                }
                for q in cached
            ],
            "cached": True,
        }
    count = min(max(body.count, 3), 20)
    questions = await gemini.generate_quiz(row["nodes_json"], row["central_topic"], count)
    db.replace_quiz(map_id, questions)
    return {"questions": questions, "cached": False}


class StudyPathRequest(BaseModel):
    learningGoal: str


@router.post("/{map_id}/study-path")
async def study_path(map_id: str, body: StudyPathRequest):
    row = _require_map(map_id)
    if not body.learningGoal.strip():
        raise HTTPException(400, detail="learningGoal is required.")
    path = await gemini.study_path(row["nodes_json"], row["central_topic"], body.learningGoal)
    return {"path": path, "goal": body.learningGoal}


class NarrativeRequest(BaseModel):
    branchLabel: Optional[str] = None


@router.post("/{map_id}/narrative")
async def teach_me(map_id: str, body: NarrativeRequest):
    row = _require_map(map_id)
    narrative = await gemini.teach_me_narrative(row["nodes_json"], row["central_topic"], body.branchLabel)
    return {"narrative": narrative, "scope": body.branchLabel or "full map"}
