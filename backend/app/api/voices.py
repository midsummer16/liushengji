import os
import json
import uuid
import aiofiles
from fastapi import APIRouter, UploadFile, File, Form, HTTPException, Depends
from typing import List
import aiosqlite

from app.core.database import get_db
from app.core.config import AUDIO_STORAGE_DIR
from app.models.voice import VoiceProfileResponse
from app.services.uvr5_service import uvr5_service, UVRReferenceError

router = APIRouter(prefix="/api/v1/voices", tags=["Voices"])


def _load_aux(paths_json: str) -> List[str]:
    try:
        data = json.loads(paths_json or "[]")
        return [p for p in data if isinstance(p, str) and p]
    except Exception:
        return []


def _safe_remove(*paths: str) -> None:
    """Best-effort delete of files that may or may not exist."""
    for p in paths:
        if p:
            try:
                os.remove(p)
            except OSError:
                pass


@router.get("", response_model=List[VoiceProfileResponse])
async def list_voices(db: aiosqlite.Connection = Depends(get_db)):
    """
    FR-1: Fetch list of stored Voice Profiles.
    """
    try:
        cursor = await db.execute(
            "SELECT id, name, ref_text, audio_path, aux_audio_paths, created_at FROM voice_profiles ORDER BY created_at DESC"
        )
        rows = await cursor.fetchall()
        await cursor.close()
        return [
            VoiceProfileResponse(
                id=row["id"],
                name=row["name"],
                ref_text=row["ref_text"],
                audio_path=row["audio_path"],
                aux_audio_paths=_load_aux(row["aux_audio_paths"]),
                created_at=str(row["created_at"]),
            )
            for row in rows
        ]
    finally:
        await db.close()


@router.post("", response_model=VoiceProfileResponse)
async def register_voice(
    name: str = Form(...),
    ref_text: str = Form(...),
    audio_file: UploadFile = File(...),
    aux_files: List[UploadFile] = File(default=None),
    db: aiosqlite.Connection = Depends(get_db),
):
    """
    FR-1 & FR-2: Upload main reference audio (+ optional aux references) -> UVR5 -> Save.
    """
    voice_id = str(uuid.uuid4())
    raw_filename = f"{voice_id}_raw.wav"
    clean_filename = f"{voice_id}_clean.wav"

    raw_path = os.path.join(AUDIO_STORAGE_DIR, raw_filename)
    clean_path = os.path.join(AUDIO_STORAGE_DIR, clean_filename)

    # 1. Save main file
    try:
        async with aiofiles.open(raw_path, "wb") as out_file:
            content = await audio_file.read()
            await out_file.write(content)
    except Exception as e:
        raise HTTPException(status_code=500, detail=f"Failed to save uploaded audio: {str(e)}")

    # 2. UVR5 denoise main (rejects silent/noisy refs by raising UVRReferenceError)
    try:
        await uvr5_service.process_audio(raw_path, clean_path)
    except UVRReferenceError as e:
        _safe_remove(raw_path)
        raise HTTPException(status_code=400, detail=str(e))

    # 3. Save + process aux references
    aux_clean_paths: List[str] = []
    if aux_files:
        for i, aux in enumerate(aux_files):
            aux_raw = os.path.join(AUDIO_STORAGE_DIR, f"{voice_id}_aux{i}_raw.wav")
            aux_clean = os.path.join(AUDIO_STORAGE_DIR, f"{voice_id}_aux{i}_clean.wav")
            try:
                async with aiofiles.open(aux_raw, "wb") as out_file:
                    content = await aux.read()
                    await out_file.write(content)
                await uvr5_service.process_audio(aux_raw, aux_clean)
                aux_clean_paths.append(aux_clean)
            except UVRReferenceError as e:
                # Roll back everything written so far for this registration.
                _safe_remove(raw_path, clean_path, *aux_clean_paths)
                for j in range(i + 1):
                    _safe_remove(os.path.join(AUDIO_STORAGE_DIR, f"{voice_id}_aux{j}_raw.wav"))
                raise HTTPException(status_code=400, detail=f"Aux reference #{i} rejected: {e}")
            except Exception as e:
                _safe_remove(raw_path, clean_path, *aux_clean_paths)
                for j in range(i + 1):
                    _safe_remove(os.path.join(AUDIO_STORAGE_DIR, f"{voice_id}_aux{j}_raw.wav"))
                raise HTTPException(status_code=500, detail=f"Failed to process aux audio #{i}: {str(e)}")

    # 4. Save entry in SQLite
    try:
        await db.execute(
            "INSERT INTO voice_profiles (id, name, ref_text, audio_path, aux_audio_paths) VALUES (?, ?, ?, ?, ?)",
            (voice_id, name, ref_text, clean_path, json.dumps(aux_clean_paths)),
        )
        await db.commit()

        cursor = await db.execute(
            "SELECT id, name, ref_text, audio_path, aux_audio_paths, created_at FROM voice_profiles WHERE id = ?",
            (voice_id,),
        )
        row = await cursor.fetchone()
        await cursor.close()

        return VoiceProfileResponse(
            id=row["id"],
            name=row["name"],
            ref_text=row["ref_text"],
            audio_path=row["audio_path"],
            aux_audio_paths=_load_aux(row["aux_audio_paths"]),
            created_at=str(row["created_at"]),
        )
    finally:
        await db.close()


@router.delete("/{voice_id}")
async def delete_voice(voice_id: str, db: aiosqlite.Connection = Depends(get_db)):
    """
    FR-1: Delete a stored Voice Profile (including all raw/clean files).
    """
    try:
        cursor = await db.execute(
            "SELECT audio_path, aux_audio_paths FROM voice_profiles WHERE id = ?", (voice_id,)
        )
        row = await cursor.fetchone()
        await cursor.close()

        if not row:
            raise HTTPException(status_code=404, detail="Voice profile not found")

        candidates = [row["audio_path"]]
        candidates += _load_aux(row["aux_audio_paths"])
        for audio_path in candidates:
            for candidate in (audio_path, audio_path.replace("_clean.wav", "_raw.wav")):
                if os.path.exists(candidate):
                    try:
                        os.remove(candidate)
                    except Exception:
                        pass

        await db.execute("DELETE FROM voice_profiles WHERE id = ?", (voice_id,))
        await db.commit()

        return {"status": "success", "message": f"Voice profile {voice_id} deleted"}
    finally:
        await db.close()
