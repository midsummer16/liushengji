import aiosqlite
from app.core.config import DB_PATH


async def get_db():
    db = await aiosqlite.connect(DB_PATH)
    db.row_factory = aiosqlite.Row
    await db.execute("PRAGMA journal_mode=WAL")
    await db.execute("PRAGMA foreign_keys=ON")
    return db


async def init_db():
    db = await get_db()
    await db.execute("""
        CREATE TABLE IF NOT EXISTS voice_profiles (
            id TEXT PRIMARY KEY,
            name TEXT NOT NULL,
            ref_text TEXT NOT NULL DEFAULT '',
            audio_path TEXT NOT NULL DEFAULT '',
            aux_audio_paths TEXT NOT NULL DEFAULT '',
            created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
        )
    """)
    # Incremental migration: add aux_audio_paths column for older databases
    cursor = await db.execute("PRAGMA table_info(voice_profiles)")
    columns = [row["name"] for row in await cursor.fetchall()]
    await cursor.close()
    if "aux_audio_paths" not in columns:
        await db.execute("ALTER TABLE voice_profiles ADD COLUMN aux_audio_paths TEXT NOT NULL DEFAULT ''")
    await db.commit()
    await db.close()
