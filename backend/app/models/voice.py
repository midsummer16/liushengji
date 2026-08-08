from pydantic import BaseModel
from typing import Optional, List
from datetime import datetime


class VoiceProfileBase(BaseModel):
    name: str
    ref_text: str


class VoiceProfileCreate(VoiceProfileBase):
    pass


class VoiceProfileResponse(VoiceProfileBase):
    id: str
    audio_path: str
    aux_audio_paths: List[str] = []
    created_at: str

    class Config:
        from_attributes = True


class TTSRequest(BaseModel):
    voice_id: str
    text: str
    speed: Optional[float] = 1.0
