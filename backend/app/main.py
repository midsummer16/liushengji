from fastapi import FastAPI
from fastapi.middleware.cors import CORSMiddleware
import uvicorn
import logging
import asyncio

from app.core.database import init_db
from app.api.voices import router as voices_router
from app.api.tts import router as tts_router
from app.core.config import SERVER_HOST, SERVER_PORT
from app.services.gpt_sovits import gpt_sovits_manager

logging.basicConfig(
    level=logging.INFO,
    format="%(asctime)s [%(levelname)s] %(name)s: %(message)s",
)

app = FastAPI(
    title="Voice Clone & Synthesis API",
    description="FastAPI Backend for UVR5 Denoising & GPT-SoVITS Speech Synthesis",
    version="1.0.0",
)

app.add_middleware(
    CORSMiddleware,
    allow_origins=["*"],
    allow_credentials=True,
    allow_methods=["*"],
    allow_headers=["*"],
)

app.include_router(voices_router)
app.include_router(tts_router)


@app.on_event("startup")
async def on_startup():
    await init_db()
    logging.info("SQLite database initialized successfully.")
    asyncio.create_task(gpt_sovits_manager.ensure_running())


@app.on_event("shutdown")
async def on_shutdown():
    gpt_sovits_manager.stop()


@app.get("/health")
async def health_check():
    return {"status": "ok", "service": "Voice Clone Backend"}


if __name__ == "__main__":
    uvicorn.run("app.main:app", host=SERVER_HOST, port=SERVER_PORT, reload=True)
