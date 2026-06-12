"""
Vertex AI (Gemini) client wrapper for the MindMap AI server.

All prompt functions live here. The client is initialised lazily on first use
so the server starts cleanly even without credentials present.
"""
import json
import logging
import os
import time

from google import genai
from google.genai import types

logger = logging.getLogger("mindmap.gemini")

MODEL = os.environ.get("VERTEX_MODEL", "gemini-2.0-flash-001")

_client: genai.Client | None = None


def client() -> genai.Client:
    global _client
    if _client is None:
        project = os.environ.get("GOOGLE_CLOUD_PROJECT")
        location = os.environ.get("GOOGLE_CLOUD_LOCATION", "us-central1")
        logger.info("Init Vertex AI client project=%s location=%s model=%s", project, location, MODEL)
        _client = genai.Client(vertexai=True, project=project, location=location)
    return _client


def _text(content: str) -> types.Part:
    return types.Part.from_text(text=content)


def _file_part(file_path: str, mime_type: str) -> types.Part:
    with open(file_path, "rb") as fh:
        data = fh.read()
    return types.Part.from_bytes(data=data, mime_type=mime_type)


def _gen(prompt: str, max_tokens: int = 4096, temperature: float = 0.3, json_mode: bool = True):
    cfg = types.GenerateContentConfig(
        max_output_tokens=max_tokens,
        temperature=temperature,
        **({"response_mime_type": "application/json"} if json_mode else {}),
    )
    return client().aio.models.generate_content(
        model=MODEL,
        config=cfg,
        contents=[types.Content(role="user", parts=[_text(prompt)])],
    )


def _parse_json(raw: str):
    brace, bracket = raw.find("{"), raw.find("[")
    if brace == -1 and bracket == -1:
        raise ValueError("No JSON in model response")
    start = min((x for x in (brace, bracket) if x != -1))
    val, _ = json.JSONDecoder().raw_decode(raw, start)
    return val


# ── 1. GENERATION ────────────────────────────────────────────────────

async def generate_from_topic(topic: str, mode: str) -> dict:
    depth_map = {"overview": 2, "deep": 4, "brainstorm": 3}
    depth = depth_map.get(mode, 3)
    extra = (
        "Make it wide and creative with many lateral associations rather than strict hierarchy."
        if mode == "brainstorm" else
        f"Build exactly {depth} levels of hierarchy. Be thorough."
    )
    prompt = (
        f"Create a mindmap for the topic: \"{topic}\"\n"
        f"{extra}\n"
        "Return ONLY valid JSON in this exact shape — no markdown fences:\n"
        '{"id":"root","label":"<central topic>","color":"#4A90D9","emoji":"🧠","children":['
        '{"id":"n1","label":"Branch 1","color":"#E74C3C","emoji":"💡","children":[...]}'
        "]}"
    )
    t = time.monotonic()
    result = await _gen(prompt, max_tokens=6000, temperature=0.5)
    logger.info("generate_from_topic mode=%s %.2fs", mode, time.monotonic() - t)
    return _parse_json(result.text or "")


async def generate_from_text(text: str, source_hint: str = "") -> dict:
    prompt = (
        f"Extract the key ideas from the following {'(' + source_hint + ') ' if source_hint else ''}text "
        "and organise them as a mindmap.\n"
        "Return ONLY valid JSON (no markdown fences):\n"
        '{"id":"root","label":"<main topic>","color":"#4A90D9","emoji":"📄","children":[...]}\n\n'
        f"TEXT:\n{text[:8000]}"
    )
    t = time.monotonic()
    result = await _gen(prompt, max_tokens=6000, temperature=0.2)
    logger.info("generate_from_text source=%s %.2fs", source_hint, time.monotonic() - t)
    return _parse_json(result.text or "")


async def generate_from_file(file_path: str, mime_type: str) -> dict:
    prompt = (
        "Extract the key ideas and structure of this document as a mindmap.\n"
        "Return ONLY valid JSON (no markdown fences):\n"
        '{"id":"root","label":"<document title>","color":"#4A90D9","emoji":"📄","children":[...]}'
    )
    t = time.monotonic()
    cfg = types.GenerateContentConfig(max_output_tokens=6000, temperature=0.2, response_mime_type="application/json")
    result = await client().aio.models.generate_content(
        model=MODEL, config=cfg,
        contents=[types.Content(role="user", parts=[_file_part(file_path, mime_type), _text(prompt)])],
    )
    logger.info("generate_from_file %.2fs", time.monotonic() - t)
    return _parse_json(result.text or "")


# ── 2. NODE INTELLIGENCE ─────────────────────────────────────────────────

async def expand_node(map_context: str, node_label: str, parent_label: str, mode: str, count: int) -> list[dict]:
    mode_instructions = {
        "explore": f"Generate {count} broad subtopics that explore different dimensions of this node.",
        "drill": f"Generate {count} deep, specific subtopics that drill down into the details.",
        "examples": f"Generate {count} concrete, real-world examples or instances.",
    }
    prompt = (
        f"Mindmap context (JSON): {map_context[:2000]}\n\n"
        f"Parent node: \"{parent_label}\"\n"
        f"Selected node: \"{node_label}\"\n\n"
        f"{mode_instructions.get(mode, mode_instructions['explore'])}\n"
        "Return ONLY a JSON array (no markdown fences):\n"
        '[{"id":"e1","label":"Child label","emoji":"🔹","color":"#27AE60"}]'
    )
    t = time.monotonic()
    result = await _gen(prompt, max_tokens=1500, temperature=0.4)
    logger.info("expand_node mode=%s %.2fs", mode, time.monotonic() - t)
    parsed = _parse_json(result.text or "")
    return parsed if isinstance(parsed, list) else parsed.get("children", [])


async def suggest_siblings(map_context: str, node_label: str, parent_label: str, existing_siblings: list[str]) -> list[dict]:
    siblings_str = ", ".join(f'"{s}"' for s in existing_siblings)
    prompt = (
        f"Mindmap context: {map_context[:1500]}\n\n"
        f"Parent node: \"{parent_label}\"\n"
        f"Existing siblings: [{siblings_str}]\n\n"
        "Suggest 4-6 additional sibling nodes that are missing and would complete this branch.\n"
        "Return ONLY a JSON array:\n"
        '[{"id":"s1","label":"Sibling label","emoji":"🔹","color":"#8E44AD"}]'
    )
    t = time.monotonic()
    result = await _gen(prompt, max_tokens=800, temperature=0.4)
    logger.info("suggest_siblings %.2fs", time.monotonic() - t)
    parsed = _parse_json(result.text or "")
    return parsed if isinstance(parsed, list) else []


async def summarise_branch(branch_json: str, central_topic: str) -> str:
    prompt = (
        f"Central mindmap topic: \"{central_topic}\"\n"
        f"Branch structure (JSON): {branch_json[:3000]}\n\n"
        "Write a concise paragraph (100-150 words) summarising this branch — "
        "what it covers, why it matters in the context of the central topic."
    )
    t = time.monotonic()
    result = await _gen(prompt, max_tokens=512, temperature=0.3, json_mode=False)
    logger.info("summarise_branch %.2fs", time.monotonic() - t)
    return result.text or ""


async def improve_labels(nodes: list[dict]) -> list[dict]:
    nodes_str = json.dumps([{"id": n["id"], "label": n["label"]} for n in nodes])
    prompt = (
        "Rewrite each node label to be concise (1-5 words), clear, and well-worded. "
        "Preserve meaning, fix verbosity or ambiguity.\n"
        f"Input: {nodes_str}\n"
        "Return ONLY a JSON array with the same ids and improved labels:\n"
        '[{"id":"n1","label":"Improved label"}]'
    )
    t = time.monotonic()
    result = await _gen(prompt, max_tokens=1000, temperature=0.2)
    logger.info("improve_labels count=%d %.2fs", len(nodes), time.monotonic() - t)
    parsed = _parse_json(result.text or "")
    return parsed if isinstance(parsed, list) else []


async def split_node(node_label: str, parent_label: str, map_context: str) -> list[dict]:
    prompt = (
        f"The node \"{node_label}\" (under \"{parent_label}\") is too broad.\n"
        f"Mindmap context: {map_context[:1000]}\n\n"
        "Split it into 3-5 focused, non-overlapping child nodes.\n"
        "Return ONLY a JSON array:\n"
        '[{"id":"sp1","label":"Focused aspect","emoji":"🔸","color":"#E67E22"}]'
    )
    t = time.monotonic()
    result = await _gen(prompt, max_tokens=600, temperature=0.3)
    logger.info("split_node %.2fs", time.monotonic() - t)
    parsed = _parse_json(result.text or "")
    return parsed if isinstance(parsed, list) else []


async def merge_nodes(node_labels: list[str], parent_label: str) -> dict:
    labels_str = ", ".join(f'"{l}"' for l in node_labels)
    prompt = (
        f"These nodes under \"{parent_label}\" overlap or duplicate each other: [{labels_str}]\n"
        "Propose a single merged node with a canonical label and a brief note explaining what it covers.\n"
        "Return ONLY JSON:\n"
        '{"label":"Merged label","emoji":"🔗","color":"#16A085","note":"What this covers"}'
    )
    t = time.monotonic()
    result = await _gen(prompt, max_tokens=256, temperature=0.2)
    logger.info("merge_nodes count=%d %.2fs", len(node_labels), time.monotonic() - t)
    return _parse_json(result.text or "")


# ── 3. SEMANTIC & RELATIONSHIP ANALYSIS ────────────────────────────────────────────────

async def discover_connections(nodes_flat: list[str], central_topic: str) -> list[dict]:
    nodes_str = json.dumps(nodes_flat[:80])
    prompt = (
        f"Central topic: \"{central_topic}\"\n"
        f"All mindmap node labels: {nodes_str}\n\n"
        "Find non-obvious semantic connections between distant nodes in the list. "
        "Return the 5-10 most interesting cross-branch relationships.\n"
        "Return ONLY a JSON array:\n"
        '[{"from":"Node A","to":"Node B","relationship":"causes|contrasts with|depends on|enables|similar to|part of","explanation":"one sentence"}]'
    )
    t = time.monotonic()
    result = await _gen(prompt, max_tokens=2000, temperature=0.4)
    logger.info("discover_connections %.2fs", time.monotonic() - t)
    parsed = _parse_json(result.text or "")
    return parsed if isinstance(parsed, list) else parsed.get("connections", [])


async def detect_contradictions(nodes_flat: list[str], central_topic: str) -> list[dict]:
    nodes_str = json.dumps(nodes_flat[:80])
    prompt = (
        f"Central topic: \"{central_topic}\"\nNodes: {nodes_str}\n\n"
        "Identify any nodes that contain contradictory claims, conflicting ideas, or logical inconsistencies.\n"
        "Return ONLY a JSON array (empty array if none found):\n"
        '[{"node_a":"Label A","node_b":"Label B","conflict":"explanation"}]'
    )
    t = time.monotonic()
    result = await _gen(prompt, max_tokens=1000, temperature=0.2)
    logger.info("detect_contradictions %.2fs", time.monotonic() - t)
    parsed = _parse_json(result.text or "[]")
    return parsed if isinstance(parsed, list) else []


async def tag_nodes(nodes: list[dict]) -> list[dict]:
    nodes_str = json.dumps([{"id": n["id"], "label": n["label"]} for n in nodes[:60]])
    prompt = (
        "Assign a semantic tag to each node. Tags: concept, person, event, process, tool, risk, location, metric, other.\n"
        f"Nodes: {nodes_str}\n"
        "Return ONLY a JSON array:\n"
        '[{"id":"n1","tag":"concept"}]'
    )
    t = time.monotonic()
    result = await _gen(prompt, max_tokens=2000, temperature=0.1)
    logger.info("tag_nodes count=%d %.2fs", len(nodes), time.monotonic() - t)
    parsed = _parse_json(result.text or "[]")
    return parsed if isinstance(parsed, list) else []


async def suggest_regroup(map_json: str, central_topic: str) -> list[dict]:
    prompt = (
        f"Central topic: \"{central_topic}\"\nMindmap (JSON): {map_json[:3000]}\n\n"
        "Analyse the mindmap structure for poor grouping — nodes that belong together but are scattered, "
        "or branches that could be merged or split.\n"
        "Return ONLY a JSON array of suggestions:\n"
        '[{"type":"merge|split|move","nodes":["label1","label2"],"suggestion":"what to do","reason":"why"}]'
    )
    t = time.monotonic()
    result = await _gen(prompt, max_tokens=2000, temperature=0.3)
    logger.info("suggest_regroup %.2fs", time.monotonic() - t)
    parsed = _parse_json(result.text or "[]")
    return parsed if isinstance(parsed, list) else []


# ── 4. MAP ANALYSIS & CRITIQUE ──────────────────────────────────────────────────────────────

async def completeness_check(map_json: str, central_topic: str) -> list[dict]:
    prompt = (
        f"Central topic: \"{central_topic}\"\nMindmap (JSON): {map_json[:3000]}\n\n"
        "What important subtopics, concepts, or aspects are missing from this mindmap? "
        "Score each by importance (1=low, 3=high).\n"
        "Return ONLY a JSON array:\n"
        '[{"missing_topic":"Topic name","importance":3,"suggested_parent":"Parent node","reason":"why it matters"}]'
    )
    t = time.monotonic()
    result = await _gen(prompt, max_tokens=2000, temperature=0.3)
    logger.info("completeness_check %.2fs", time.monotonic() - t)
    parsed = _parse_json(result.text or "[]")
    return parsed if isinstance(parsed, list) else []


async def critique_map(map_json: str, central_topic: str) -> list[dict]:
    prompt = (
        f"Central topic: \"{central_topic}\"\nMindmap (JSON): {map_json[:3000]}\n\n"
        "Review this mindmap for: (a) logical hierarchy issues, (b) labelling problems, "
        "(c) missing connections, (d) unclear or redundant nodes.\n"
        "Return ONLY a JSON array of actionable feedback items:\n"
        '[{"nodeLabel":"optional node label","issue":"description","suggestion":"what to do","severity":"low|medium|high"}]'
    )
    t = time.monotonic()
    result = await _gen(prompt, max_tokens=2500, temperature=0.3)
    logger.info("critique_map %.2fs", time.monotonic() - t)
    parsed = _parse_json(result.text or "[]")
    return parsed if isinstance(parsed, list) else []


async def map_summary_card(map_json: str, central_topic: str) -> dict:
    prompt = (
        f"Central topic: \"{central_topic}\"\nMindmap (JSON): {map_json[:3000]}\n\n"
        "Return ONLY valid JSON with a summary card:\n"
        '{"headline":"2-sentence plain-English summary","centralThemes":["theme1"],'
        '"keyNodes":["most important node labels, up to 5"],'
        '"estimatedReadMinutes":2,"complexityLevel":"simple|moderate|complex"}'
    )
    t = time.monotonic()
    result = await _gen(prompt, max_tokens=800, temperature=0.3)
    logger.info("map_summary_card %.2fs", time.monotonic() - t)
    return _parse_json(result.text or "")


async def balance_analysis(map_json: str) -> list[dict]:
    prompt = (
        f"Mindmap (JSON): {map_json[:3000]}\n\n"
        "Identify branches that are underdeveloped (too few nodes) or overdeveloped (too many) "
        "relative to the overall map. Recommend where to add or trim depth.\n"
        "Return ONLY a JSON array:\n"
        '[{"branch":"Branch label","nodeCount":2,"status":"underdeveloped|overdeveloped|balanced","recommendation":"what to do"}]'
    )
    t = time.monotonic()
    result = await _gen(prompt, max_tokens=1200, temperature=0.2)
    logger.info("balance_analysis %.2fs", time.monotonic() - t)
    parsed = _parse_json(result.text or "[]")
    return parsed if isinstance(parsed, list) else []


# ── 5. CONTEXTUAL CHAT ──────────────────────────────────────────────────────────────

async def chat_stream(map_json: str, history, user_message: str):
    system_ctx = (
        f"You are an intelligent assistant helping the user work with their mindmap.\n"
        f"Mindmap JSON:\n{map_json[:4000]}\n\n"
        "Answer questions about the mindmap content, suggest improvements, explain concepts, "
        "or help the user understand relationships between topics."
    )
    history_contents = [
        types.Content(role=row["role"], parts=[_text(row["content"])])
        for row in history
    ]
    chat = client().aio.chats.create(model=MODEL, history=history_contents)
    return await chat.send_message_stream([_text(system_ctx + "\n\nUser: " + user_message)])


async def explain_node(node_label: str, map_context: str, central_topic: str, mode: str) -> str:
    instructions = {
        "explain": f'Explain "{node_label}" thoroughly in the context of "{central_topic}".',
        "eli5": f'Explain "{node_label}" as if to a 10-year-old, simply and with an analogy.',
        "define": f'Give the definition of "{node_label}", including etymology if relevant and domain context.',
        "relate": f'Explain how "{node_label}" connects to and relates to other concepts in the mindmap.',
    }
    prompt = (
        f"Mindmap context (JSON): {map_context[:2000]}\n\n"
        f"{instructions.get(mode, instructions['explain'])}"
    )
    t = time.monotonic()
    result = await _gen(prompt, max_tokens=800, temperature=0.4, json_mode=False)
    logger.info("explain_node mode=%s %.2fs", mode, time.monotonic() - t)
    return result.text or ""


# ── 6. STUDY & LEARNING ──────────────────────────────────────────────────────────────

async def generate_flashcards(map_json: str, central_topic: str, style: str, count: int) -> list[dict]:
    style_instructions = {
        "term-definition": "Front: the node label as a term. Back: a clear definition.",
        "question-answer": "Front: a question about the node. Back: the answer.",
        "fill-in-the-blank": "Front: a sentence with a key word blanked out (___). Back: the missing word + explanation.",
    }
    prompt = (
        f"Central topic: \"{central_topic}\"\nMindmap (JSON): {map_json[:4000]}\n\n"
        f"Generate exactly {count} flashcards. Style: {style_instructions.get(style, style_instructions['question-answer'])}\n"
        "Assign difficulty based on node depth and concept complexity.\n"
        "Return ONLY a JSON array:\n"
        '[{"nodeId":"optional","front":"Front text","back":"Back text","difficulty":"easy|medium|hard"}]'
    )
    t = time.monotonic()
    result = await _gen(prompt, max_tokens=5000, temperature=0.2)
    logger.info("generate_flashcards style=%s count=%d %.2fs", style, count, time.monotonic() - t)
    parsed = _parse_json(result.text or "[]")
    return parsed if isinstance(parsed, list) else parsed.get("flashcards", [])


async def generate_quiz(map_json: str, central_topic: str, count: int) -> list[dict]:
    prompt = (
        f"Central topic: \"{central_topic}\"\nMindmap (JSON): {map_json[:4000]}\n\n"
        f"Generate {count} quiz questions. Mix types: multiple-choice (mcq), true/false (true_false), short answer (short).\n"
        "Return ONLY a JSON array:\n"
        '[{"nodeId":"optional","question":"?","options":["A","B","C","D"],"answer":"A","type":"mcq"},'
        '{"nodeId":"optional","question":"?","options":["True","False"],"answer":"True","type":"true_false"},'
        '{"nodeId":"optional","question":"?","options":[],"answer":"short answer","type":"short"}]'
    )
    t = time.monotonic()
    result = await _gen(prompt, max_tokens=4000, temperature=0.3)
    logger.info("generate_quiz count=%d %.2fs", count, time.monotonic() - t)
    parsed = _parse_json(result.text or "[]")
    return parsed if isinstance(parsed, list) else parsed.get("questions", [])


async def study_path(map_json: str, central_topic: str, learning_goal: str) -> list[dict]:
    prompt = (
        f"Central topic: \"{central_topic}\"\nLearning goal: \"{learning_goal}\"\n"
        f"Mindmap (JSON): {map_json[:3000]}\n\n"
        "Recommend an ordered sequence of nodes to study. Explain why each step follows from the previous.\n"
        "Return ONLY a JSON array:\n"
        '[{"step":1,"nodeLabel":"Node to study","reason":"why this step","estimatedMinutes":5}]'
    )
    t = time.monotonic()
    result = await _gen(prompt, max_tokens=2000, temperature=0.3)
    logger.info("study_path %.2fs", time.monotonic() - t)
    parsed = _parse_json(result.text or "[]")
    return parsed if isinstance(parsed, list) else []


async def teach_me_narrative(map_json: str, central_topic: str, branch_label: str | None) -> str:
    scope = f'the branch "{branch_label}"' if branch_label else "the entire mindmap"
    prompt = (
        f"Central topic: \"{central_topic}\"\nMindmap (JSON): {map_json[:4000]}\n\n"
        f"Write a flowing, engaging tutor-style explanation of {scope}. "
        "Walk through the concepts in a logical order as if teaching a student from scratch. "
        "Use clear language, analogies where helpful. Aim for 300-500 words."
    )
    t = time.monotonic()
    result = await _gen(prompt, max_tokens=2000, temperature=0.5, json_mode=False)
    logger.info("teach_me_narrative scope=%s %.2fs", scope, time.monotonic() - t)
    return result.text or ""


# ── 7. EXPORT & PRESENTATION ───────────────────────────────────────────────────────────────

async def export_outline(map_json: str, central_topic: str) -> str:
    prompt = (
        f"Central topic: \"{central_topic}\"\nMindmap (JSON): {map_json[:4000]}\n\n"
        "Convert this mindmap into a clean Markdown outline using nested bullet points. "
        "Preserve the full hierarchy. Output plain Markdown only."
    )
    t = time.monotonic()
    result = await _gen(prompt, max_tokens=3000, temperature=0.1, json_mode=False)
    logger.info("export_outline %.2fs", time.monotonic() - t)
    return result.text or ""


async def export_slides(map_json: str, central_topic: str) -> list[dict]:
    prompt = (
        f"Central topic: \"{central_topic}\"\nMindmap (JSON): {map_json[:4000]}\n\n"
        "Convert this mindmap into a presentation structure. Each top-level branch = one slide section. "
        "Write 3-5 bullet points per slide.\n"
        "Return ONLY a JSON array:\n"
        '[{"slideNumber":1,"title":"Slide title","bullets":["point 1","point 2"],"speakerNote":"optional note"}]'
    )
    t = time.monotonic()
    result = await _gen(prompt, max_tokens=3000, temperature=0.3)
    logger.info("export_slides %.2fs", time.monotonic() - t)
    parsed = _parse_json(result.text or "[]")
    return parsed if isinstance(parsed, list) else []


async def export_script(map_json: str, central_topic: str) -> str:
    prompt = (
        f"Central topic: \"{central_topic}\"\nMindmap (JSON): {map_json[:4000]}\n\n"
        "Write a natural, spoken presentation script that walks through this mindmap. "
        "It should flow smoothly as if spoken aloud, ~2-3 minutes speaking time."
    )
    t = time.monotonic()
    result = await _gen(prompt, max_tokens=2500, temperature=0.5, json_mode=False)
    logger.info("export_script %.2fs", time.monotonic() - t)
    return result.text or ""


async def export_action_plan(map_json: str, central_topic: str) -> list[dict]:
    prompt = (
        f"Central topic: \"{central_topic}\"\nMindmap (JSON): {map_json[:4000]}\n\n"
        "Convert this mindmap into a prioritised action plan. Each node that implies an action becomes a task.\n"
        "Return ONLY a JSON array:\n"
        '[{"taskId":1,"task":"Task description","priority":"high|medium|low","dependsOn":[],"estimatedHours":2}]'
    )
    t = time.monotonic()
    result = await _gen(prompt, max_tokens=3000, temperature=0.3)
    logger.info("export_action_plan %.2fs", time.monotonic() - t)
    parsed = _parse_json(result.text or "[]")
    return parsed if isinstance(parsed, list) else []


# ── 8. REAL-TIME SUGGESTIONS ───────────────────────────────────────────────────────────────

async def autocomplete_label(partial: str, parent_label: str, siblings: list[str]) -> list[str]:
    siblings_str = ", ".join(f'"{s}"' for s in siblings[:10])
    prompt = (
        f"Parent node: \"{parent_label}\"\nExisting siblings: [{siblings_str}]\n"
        f"User is typing: \"{partial}\"\n\n"
        "Complete the node label. Return ONLY a JSON array of 3-5 short, distinct completions:\n"
        '["Completion 1","Completion 2","Completion 3"]'
    )
    t = time.monotonic()
    result = await _gen(prompt, max_tokens=200, temperature=0.6)
    logger.info("autocomplete_label %.2fs", time.monotonic() - t)
    parsed = _parse_json(result.text or "[]")
    return parsed if isinstance(parsed, list) else []


async def next_node_suggestions(last_label: str, parent_label: str, map_context: str) -> list[dict]:
    prompt = (
        f"Mindmap context: {map_context[:1000]}\nParent: \"{parent_label}\"\nJust added: \"{last_label}\"\n\n"
        "Suggest the next 3 nodes the user should add to this area of the map.\n"
        "Return ONLY a JSON array:\n"
        '[{"label":"Node label","emoji":"🔹","reason":"one-line reason"}]'
    )
    t = time.monotonic()
    result = await _gen(prompt, max_tokens=400, temperature=0.5)
    logger.info("next_node_suggestions %.2fs", time.monotonic() - t)
    parsed = _parse_json(result.text or "[]")
    return parsed if isinstance(parsed, list) else []


async def emoji_suggestion(node_labels: list[str]) -> list[dict]:
    labels_str = json.dumps(node_labels[:30])
    prompt = (
        f"For each node label, suggest the single most fitting emoji.\n"
        f"Labels: {labels_str}\n"
        "Return ONLY a JSON array:\n"
        '[{"label":"Node label","emoji":"🎯"}]'
    )
    t = time.monotonic()
    result = await _gen(prompt, max_tokens=800, temperature=0.3)
    logger.info("emoji_suggestion count=%d %.2fs", len(node_labels), time.monotonic() - t)
    parsed = _parse_json(result.text or "[]")
    return parsed if isinstance(parsed, list) else []


# ── 9. LANGUAGE & ACCESSIBILITY ───────────────────────────────────────────────────────────────

async def translate_map(nodes: list[dict], target_language: str) -> list[dict]:
    nodes_str = json.dumps([{"id": n["id"], "label": n["label"]} for n in nodes])
    prompt = (
        f"Translate each node label to {target_language}. Preserve meaning; keep labels concise.\n"
        f"Nodes: {nodes_str}\n"
        "Return ONLY a JSON array with same ids and translated labels:\n"
        '[{"id":"n1","label":"Translated label"}]'
    )
    t = time.monotonic()
    result = await _gen(prompt, max_tokens=3000, temperature=0.1)
    logger.info("translate_map lang=%s count=%d %.2fs", target_language, len(nodes), time.monotonic() - t)
    parsed = _parse_json(result.text or "[]")
    return parsed if isinstance(parsed, list) else []


async def rewrite_tone(nodes: list[dict], tone: str) -> list[dict]:
    instructions = {
        "simple": "Rewrite in everyday simple language a child could understand.",
        "formal": "Rewrite in formal, professional, academic language.",
        "casual": "Rewrite in a friendly, conversational tone.",
    }
    nodes_str = json.dumps([{"id": n["id"], "label": n["label"]} for n in nodes])
    prompt = (
        f"{instructions.get(tone, 'Rewrite clearly.')}\n"
        f"Nodes: {nodes_str}\n"
        "Return ONLY a JSON array:\n"
        '[{"id":"n1","label":"Rewritten label"}]'
    )
    t = time.monotonic()
    result = await _gen(prompt, max_tokens=3000, temperature=0.3)
    logger.info("rewrite_tone tone=%s %.2fs", tone, time.monotonic() - t)
    parsed = _parse_json(result.text or "[]")
    return parsed if isinstance(parsed, list) else []


async def accessibility_summary(map_json: str, central_topic: str) -> str:
    prompt = (
        f"Central topic: \"{central_topic}\"\nMindmap (JSON): {map_json[:3000]}\n\n"
        "Write a plain-text description of this mindmap suitable for a screen reader. "
        "Describe the structure, main branches, and key concepts in natural language."
    )
    t = time.monotonic()
    result = await _gen(prompt, max_tokens=1000, temperature=0.3, json_mode=False)
    logger.info("accessibility_summary %.2fs", time.monotonic() - t)
    return result.text or ""


# ── 10. CREATIVE & DIVERGENT THINKING ──────────────────────────────────────────────────────

async def what_if_branch(node_label: str, central_topic: str, map_context: str) -> list[dict]:
    prompt = (
        f"Central topic: \"{central_topic}\"\nSelected node: \"{node_label}\"\n"
        f"Map context: {map_context[:1500]}\n\n"
        "Generate 3-4 speculative 'What If' scenarios branching from this node. "
        "These should be thought-provoking, creative, and useful for planning or analysis.\n"
        "Return ONLY a JSON array:\n"
        '[{"id":"w1","label":"What if scenario","emoji":"🔮","children":[{"id":"w1a","label":"Implication","emoji":"➡️"}]}]'
    )
    t = time.monotonic()
    result = await _gen(prompt, max_tokens=1500, temperature=0.7)
    logger.info("what_if_branch %.2fs", time.monotonic() - t)
    parsed = _parse_json(result.text or "[]")
    return parsed if isinstance(parsed, list) else []


async def devils_advocate(node_label: str, central_topic: str) -> list[dict]:
    prompt = (
        f"Central topic: \"{central_topic}\"\nNode: \"{node_label}\"\n\n"
        "Generate 3-5 counterarguments, opposing viewpoints, or critical challenges to this node. "
        "Be intellectually honest and fair.\n"
        "Return ONLY a JSON array:\n"
        '[{"id":"d1","label":"Counterpoint","emoji":"⚡","note":"brief explanation"}]'
    )
    t = time.monotonic()
    result = await _gen(prompt, max_tokens=1000, temperature=0.5)
    logger.info("devils_advocate %.2fs", time.monotonic() - t)
    parsed = _parse_json(result.text or "[]")
    return parsed if isinstance(parsed, list) else []


async def analogy_finder(node_label: str, central_topic: str) -> list[dict]:
    prompt = (
        f"Concept: \"{node_label}\" (in the context of \"{central_topic}\")\n\n"
        "Generate 3 analogies that explain this concept through a completely different domain "
        "(e.g., cooking, sports, nature, mechanics).\n"
        "Return ONLY a JSON array:\n"
        '[{"domain":"Cooking","analogy":"This is like X because Y","explanation":"why this analogy works"}]'
    )
    t = time.monotonic()
    result = await _gen(prompt, max_tokens=800, temperature=0.6)
    logger.info("analogy_finder %.2fs", time.monotonic() - t)
    parsed = _parse_json(result.text or "[]")
    return parsed if isinstance(parsed, list) else []


async def random_spark(node_label: str, map_context: str) -> dict:
    prompt = (
        f"Map context: {map_context[:1000]}\nNode: \"{node_label}\"\n\n"
        "Generate one surprising, creative, and loosely related idea that could spark new thinking "
        "about this topic. Be unexpected but relevant.\n"
        "Return ONLY JSON:\n"
        '{"idea":"The spark idea","emoji":"✨","why":"brief explanation of the connection"}'
    )
    t = time.monotonic()
    result = await _gen(prompt, max_tokens=300, temperature=0.8)
    logger.info("random_spark %.2fs", time.monotonic() - t)
    return _parse_json(result.text or "")
