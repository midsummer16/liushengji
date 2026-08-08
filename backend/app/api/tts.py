import os
import json
from fastapi import APIRouter, HTTPException, Depends
from fastapi.responses import StreamingResponse
import aiosqlite

from app.core.database import get_db
from app.models.voice import TTSRequest
from app.services.gpt_sovits import gpt_sovits_service

router = APIRouter(prefix="/api/v1/tts", tags=["TTS Synthesis"])


@router.post("")
async def synthesize_speech(request: TTSRequest, db: aiosqlite.Connection = Depends(get_db)):
    """
    FR-3: Speech Synthesis using GPT-SoVITS.
    Returns chunked WAV audio stream for low-latency playback (< 2s TTFB).
    """
    try:
        cursor = await db.execute(
            "SELECT id, name, ref_text, audio_path, aux_audio_paths FROM voice_profiles WHERE id = ?",
            (request.voice_id,),
        )
        row = await cursor.fetchone()
        await cursor.close()

        if not row:
            raise HTTPException(status_code=404, detail="Voice profile not found")

        audio_path = row["audio_path"]
        ref_text = row["ref_text"] or ""

        # v4 model requires a non-empty prompt_text (TTS.py:1119).
        # Reject stale profiles that somehow have an empty ref_text.
        if not ref_text.strip():
            raise HTTPException(
                status_code=400,
                detail="This voice profile has no reference text (ref_text). "
                       "Please re-register the voice with a transcript.",
            )
        try:
            aux_audio_paths = [p for p in json.loads(row["aux_audio_paths"] or "[]") if isinstance(p, str) and p]
        except Exception:
            aux_audio_paths = []

        if not os.path.exists(audio_path):
            raise HTTPException(status_code=400, detail="Reference audio file missing on server")

        stream_generator = gpt_sovits_service.generate_speech_stream(
            text=request.text,
            ref_audio_path=audio_path,
            ref_text=ref_text,
            aux_ref_audio_paths=aux_audio_paths,
            speed=request.speed or 1.0,
        )

        return StreamingResponse(
            stream_generator,
            media_type="audio/wav",
            headers={
                "Content-Disposition": "attachment; filename=synthesized.wav",
                "Cache-Control": "no-cache",
                "X-Accel-Buffering": "no",
            },
        )
    finally:
        await db.close()
