"""Streaming chat endpoint (Server-Sent Events) — chat with the mindmap."""
import json
import logging
from typing import Optional

from fastapi import APIRouter, HTTPException
from fastapi.responses import StreamingResponse
from pydantic import BaseModel

import db
from gemini import chat_stream

logger = logging.getLogger("mindmap.chat")
router = APIRouter()


class ChatRequest(BaseModel):
    message: str
    sessionId: Optional[str] = None


@router.post("/{map_id}/chat")
async def chat(map_id: str, body: ChatRequest):
    row = db.get_mindmap(map_id)
    if not row:
        raise HTTPException(404, detail="Mindmap not found.")
    if not body.message.strip():
        raise HTTPException(400, detail="message is required.")

    sid = body.sessionId or row["session_id"]
    map_json = row["nodes_json"]

    async def event_generator():
        full_response = ""
        try:
            history = db.get_history(sid)
            stream = await chat_stream(map_json, history, body.message)

            async for chunk in stream:
                token = getattr(chunk, "text", None) or ""
                if token:
                    full_response += token
                    yield f"data: {token}\n\n"

            db.insert_message(sid, "user", body.message)
            db.insert_message(sid, "model", full_response)
            logger.info("chat map=%s session=%s streamed %d chars", map_id[:8], sid[:8], len(full_response))
            yield "data: [DONE]\n\n"

        except Exception as err:
            logger.exception("Chat stream error map=%s session=%s", map_id[:8], sid[:8])
            yield f"data: [ERROR] {err}\n\n"

    return StreamingResponse(
        event_generator(),
        media_type="text/event-stream",
        headers={"Cache-Control": "no-cache", "Connection": "keep-alive"},
    )
