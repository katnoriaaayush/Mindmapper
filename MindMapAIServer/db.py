"""
SQLite persistence layer for the MindMap AI server.

Single module-level connection (WAL mode, foreign keys) shared across threads
via a write lock. Workload is a single interactive app — contention is negligible.
"""
import json
import logging
import sqlite3
import threading
from pathlib import Path

logger = logging.getLogger("mindmap.db")

BASE_DIR = Path(__file__).resolve().parent
UPLOADS_DIR = BASE_DIR / "uploads"
UPLOADS_DIR.mkdir(exist_ok=True)
DB_PATH = BASE_DIR / "mindmap.db"

_write_lock = threading.Lock()

_conn = sqlite3.connect(DB_PATH, check_same_thread=False)
_conn.row_factory = sqlite3.Row
_conn.execute("PRAGMA journal_mode = WAL")
_conn.execute("PRAGMA foreign_keys = ON")

_conn.executescript(
    """
    CREATE TABLE IF NOT EXISTS mindmaps (
        id          TEXT PRIMARY KEY,
        title       TEXT NOT NULL,
        session_id  TEXT NOT NULL,
        central_topic TEXT NOT NULL,
        nodes_json  TEXT NOT NULL DEFAULT '[]',
        created_at  INTEGER NOT NULL DEFAULT (unixepoch()),
        updated_at  INTEGER NOT NULL DEFAULT (unixepoch())
    );

    CREATE TABLE IF NOT EXISTS ai_cache (
        map_id      TEXT NOT NULL,
        feature     TEXT NOT NULL,
        key_hash    TEXT NOT NULL,
        content     TEXT NOT NULL,
        cached_at   INTEGER NOT NULL DEFAULT (unixepoch()),
        PRIMARY KEY (map_id, feature, key_hash),
        FOREIGN KEY (map_id) REFERENCES mindmaps(id) ON DELETE CASCADE
    );

    CREATE TABLE IF NOT EXISTS flashcards (
        id          INTEGER PRIMARY KEY AUTOINCREMENT,
        map_id      TEXT NOT NULL,
        node_id     TEXT,
        front       TEXT NOT NULL,
        back        TEXT NOT NULL,
        difficulty  TEXT NOT NULL CHECK(difficulty IN ('easy','medium','hard')),
        FOREIGN KEY (map_id) REFERENCES mindmaps(id) ON DELETE CASCADE
    );

    CREATE TABLE IF NOT EXISTS quiz_questions (
        id          INTEGER PRIMARY KEY AUTOINCREMENT,
        map_id      TEXT NOT NULL,
        node_id     TEXT,
        question    TEXT NOT NULL,
        options     TEXT NOT NULL,
        answer      TEXT NOT NULL,
        type        TEXT NOT NULL CHECK(type IN ('mcq','true_false','short')),
        FOREIGN KEY (map_id) REFERENCES mindmaps(id) ON DELETE CASCADE
    );

    CREATE TABLE IF NOT EXISTS chat_messages (
        id          INTEGER PRIMARY KEY AUTOINCREMENT,
        session_id  TEXT NOT NULL,
        role        TEXT NOT NULL CHECK(role IN ('user','model')),
        content     TEXT NOT NULL,
        timestamp   INTEGER NOT NULL DEFAULT (unixepoch())
    );

    CREATE TABLE IF NOT EXISTS uploads (
        id          TEXT PRIMARY KEY,
        file_path   TEXT NOT NULL,
        file_name   TEXT NOT NULL,
        mime_type   TEXT NOT NULL,
        file_size   INTEGER NOT NULL,
        uploaded_at INTEGER NOT NULL DEFAULT (unixepoch())
    );

    CREATE INDEX IF NOT EXISTS idx_ai_cache_map    ON ai_cache(map_id, feature);
    CREATE INDEX IF NOT EXISTS idx_flashcards_map  ON flashcards(map_id);
    CREATE INDEX IF NOT EXISTS idx_quiz_map        ON quiz_questions(map_id);
    CREATE INDEX IF NOT EXISTS idx_chat_session    ON chat_messages(session_id, timestamp);
    """
)
_conn.commit()
logger.info("Database ready at %s", DB_PATH)


# ── Mindmaps ───────────────────────────────────────────────────────────────────────────────

def get_mindmap(map_id: str):
    return _conn.execute("SELECT * FROM mindmaps WHERE id = ?", (map_id,)).fetchone()


def list_mindmaps():
    return _conn.execute(
        "SELECT id, title, central_topic, session_id, created_at, updated_at FROM mindmaps ORDER BY updated_at DESC"
    ).fetchall()


def insert_mindmap(map_id: str, title: str, session_id: str, central_topic: str, nodes_json: str):
    with _write_lock:
        _conn.execute(
            """INSERT INTO mindmaps (id, title, session_id, central_topic, nodes_json)
               VALUES (?, ?, ?, ?, ?)""",
            (map_id, title, session_id, central_topic, nodes_json),
        )
        _conn.commit()


def update_mindmap_nodes(map_id: str, nodes_json: str):
    with _write_lock:
        _conn.execute(
            "UPDATE mindmaps SET nodes_json = ?, updated_at = unixepoch() WHERE id = ?",
            (nodes_json, map_id),
        )
        _conn.commit()


def delete_mindmap(map_id: str):
    with _write_lock:
        _conn.execute("DELETE FROM mindmaps WHERE id = ?", (map_id,))
        _conn.commit()


# ── AI Cache ─────────────────────────────────────────────────────────────────────────────

def get_cache(map_id: str, feature: str, key_hash: str):
    row = _conn.execute(
        "SELECT content FROM ai_cache WHERE map_id = ? AND feature = ? AND key_hash = ?",
        (map_id, feature, key_hash),
    ).fetchone()
    return row["content"] if row else None


def set_cache(map_id: str, feature: str, key_hash: str, content: str):
    with _write_lock:
        _conn.execute(
            """INSERT INTO ai_cache (map_id, feature, key_hash, content, cached_at)
               VALUES (?, ?, ?, ?, unixepoch())
               ON CONFLICT(map_id, feature, key_hash) DO UPDATE
                 SET content = excluded.content, cached_at = excluded.cached_at""",
            (map_id, feature, key_hash, content),
        )
        _conn.commit()


# ── Flashcards ──────────────────────────────────────────────────────────────────────────────

def get_flashcards(map_id: str):
    return _conn.execute(
        "SELECT * FROM flashcards WHERE map_id = ?", (map_id,)
    ).fetchall()


def replace_flashcards(map_id: str, cards: list[dict]):
    with _write_lock:
        try:
            _conn.execute("BEGIN")
            _conn.execute("DELETE FROM flashcards WHERE map_id = ?", (map_id,))
            _conn.executemany(
                "INSERT INTO flashcards (map_id, node_id, front, back, difficulty) VALUES (?,?,?,?,?)",
                [(map_id, c.get("nodeId"), c["front"], c["back"], c["difficulty"]) for c in cards],
            )
            _conn.commit()
        except Exception:
            _conn.rollback()
            raise


# ── Quiz ───────────────────────────────────────────────────────────────────────────────────

def get_quiz(map_id: str):
    return _conn.execute(
        "SELECT * FROM quiz_questions WHERE map_id = ?", (map_id,)
    ).fetchall()


def replace_quiz(map_id: str, questions: list[dict]):
    with _write_lock:
        try:
            _conn.execute("BEGIN")
            _conn.execute("DELETE FROM quiz_questions WHERE map_id = ?", (map_id,))
            _conn.executemany(
                "INSERT INTO quiz_questions (map_id, node_id, question, options, answer, type) VALUES (?,?,?,?,?,?)",
                [
                    (map_id, q.get("nodeId"), q["question"], json.dumps(q.get("options", [])), q["answer"], q["type"])
                    for q in questions
                ],
            )
            _conn.commit()
        except Exception:
            _conn.rollback()
            raise


# ── Chat ───────────────────────────────────────────────────────────────────────────────────

def insert_message(session_id: str, role: str, content: str):
    with _write_lock:
        _conn.execute(
            "INSERT INTO chat_messages (session_id, role, content) VALUES (?, ?, ?)",
            (session_id, role, content),
        )
        _conn.commit()


def get_history(session_id: str, limit: int = 20):
    return _conn.execute(
        "SELECT role, content FROM chat_messages WHERE session_id = ? ORDER BY timestamp ASC LIMIT ?",
        (session_id, limit),
    ).fetchall()


def clear_history(session_id: str):
    with _write_lock:
        _conn.execute("DELETE FROM chat_messages WHERE session_id = ?", (session_id,))
        _conn.commit()


# ── Uploads ─────────────────────────────────────────────────────────────────────────────────

def get_upload(upload_id: str):
    return _conn.execute("SELECT * FROM uploads WHERE id = ?", (upload_id,)).fetchone()


def insert_upload(upload_id: str, file_path: str, file_name: str, mime_type: str, file_size: int):
    with _write_lock:
        _conn.execute(
            "INSERT OR IGNORE INTO uploads (id, file_path, file_name, mime_type, file_size) VALUES (?,?,?,?,?)",
            (upload_id, file_path, file_name, mime_type, file_size),
        )
        _conn.commit()
