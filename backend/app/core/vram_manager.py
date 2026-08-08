import asyncio
import logging
from enum import Enum
import gc

logger = logging.getLogger("VRAMManager")


class ModelMode(str, Enum):
    IDLE = "IDLE"
    GPT_SOVITS = "GPT_SOVITS"
    UVR5 = "UVR5"


class VRAMManager:
    """
    VRAM Management Strategy for 8GB GPU (RTX 4060):
    - GPT-SoVITS remains loaded in VRAM for high-frequency TTS requests.
    - When a UVR5 request arrives, GPT-SoVITS is dynamically offloaded to CPU RAM
      and CUDA cache is emptied.
    - UVR5 is loaded into GPU, processes the reference audio, and is immediately offloaded.
    - GPT-SoVITS is re-loaded into VRAM.
    - Uses an asyncio Lock to prevent simultaneous execution of UVR5 and TTS.
    """

    def __init__(self):
        self._lock = asyncio.Lock()
        self.current_mode = ModelMode.GPT_SOVITS
        self._gpt_sovits_model = None
        self._uvr5_model = None

    async def acquire_uvr5(self):
        await self._lock.acquire()
        try:
            logger.info("[VRAM] Offloading GPT-SoVITS to CPU RAM and clearing CUDA cache...")
            if self._gpt_sovits_model is not None:
                self._gpt_sovits_model.to("cpu")

            self.clear_cuda_cache()
            self.current_mode = ModelMode.UVR5
            logger.info("[VRAM] Ready for UVR5 processing.")
        except Exception:
            # Release the lock if setup fails so callers don't deadlock.
            if self._lock.locked():
                self._lock.release()
            raise

    async def release_uvr5(self):
        logger.info("[VRAM] Unloading UVR5 and restoring GPT-SoVITS to GPU VRAM...")
        try:
            if self._uvr5_model is not None:
                self._uvr5_model.to("cpu")

            self.clear_cuda_cache()

            if self._gpt_sovits_model is not None:
                self._gpt_sovits_model.to("cuda")

            self.current_mode = ModelMode.GPT_SOVITS
        finally:
            # Always release the lock even if CUDA ops raise, otherwise a single
            # CUDA error would deadlock every subsequent UVR5/TTS request.
            if self._lock.locked():
                self._lock.release()
            logger.info("[VRAM] GPT-SoVITS successfully restored to VRAM.")

    async def acquire_tts(self):
        await self._lock.acquire()
        if self.current_mode != ModelMode.GPT_SOVITS:
            logger.info("[VRAM] Ensuring GPT-SoVITS is on GPU...")
            if self._gpt_sovits_model is not None:
                self._gpt_sovits_model.to("cuda")
            self.current_mode = ModelMode.GPT_SOVITS

    def release_tts(self):
        if self._lock.locked():
            self._lock.release()

    @staticmethod
    def clear_cuda_cache():
        try:
            import torch

            if torch.cuda.is_available():
                torch.cuda.empty_cache()
                torch.cuda.ipc_collect()
        except ImportError:
            pass
        gc.collect()


vram_manager = VRAMManager()
