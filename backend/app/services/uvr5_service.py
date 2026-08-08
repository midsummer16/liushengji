import asyncio
import logging
import os
import sys
import tempfile

import numpy as np
import soundfile as sf

from app.core.config import SAMPLE_RATE
from app.core.vram_manager import vram_manager

logger = logging.getLogger("UVR5Service")

# Minimum RMS for a reference audio to be accepted as a voiceprint.
# Near-silent refs (observed in the wild: rms=0.0) make GPT-SoVITS zero-shot
# degenerate into broadband hiss / low rumbles, which was the root cause of
# the "all synthesized audio is noise" bug. Reject them at intake.
MIN_REF_RMS = 0.015

# UVR5 HP2 model (vocal-isolation). Preferred over the mock DSP path because
# the mock noise-gate was measured to suppress 41% of samples and clip
# high-frequency sibilance (s/z/sh), making clones sound dry/unnatural.
_UVR5_WEIGHTS_DIR = os.path.join(
    os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__)))),
    "GPT-SoVITS", "tools", "uvr5", "uvr5_weights",
)
_HP2_MODEL_CANDIDATES = [
    "HP2-人声vocals+非人声instrumentals.pth",
    "HP2-人声vocals+非歌声instrumentals_14811.pth",
]
_UVR5_TOOL_DIR = os.path.join(
    os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__)))),
    "GPT-SoVITS", "tools", "uvr5",
)


def _find_hp2_model() -> str | None:
    """Return the path to the HP2 weight if present, else None."""
    if not os.path.isdir(_UVR5_WEIGHTS_DIR):
        return None
    for name in _HP2_MODEL_CANDIDATES:
        p = os.path.join(_UVR5_WEIGHTS_DIR, name)
        if os.path.isfile(p) and os.path.getsize(p) > 1_000_000:
            return p
    # fallback: any HP2*.pth present
    try:
        for fn in os.listdir(_UVR5_WEIGHTS_DIR):
            if fn.upper().startswith("HP2") and fn.endswith(".pth"):
                p = os.path.join(_UVR5_WEIGHTS_DIR, fn)
                if os.path.getsize(p) > 1_000_000:
                    return p
    except OSError:
        pass
    return None


class UVRReferenceError(RuntimeError):
    """Raised when a reference audio is unusable (silent / too noisy / corrupt)."""


class UVR5Service:
    """
    UVR5 (Ultimate Vocal Remover) reference-audio cleaning service.

    Primary path: real HP2 model (vocal isolation) when weights are present.
    Fallback:   mock DSP (high-pass + sibilance-aware noise gate + VAD trim)
                used only when the HP2 weight is missing or the model fails,
                so the system stays functional without a download.

    Both paths trim leading/trailing silence (VAD) and reject near-silent
    references, because GPT-SoVITS clones whatever it is given — long tails
    of silence and DC rumble are learned back as muffled / unnatural output.
    """

    def __init__(self):
        self.device = "cuda"

    # ---------- shared DSP helpers (also used by the mock fallback) ----------

    @staticmethod
    def _highpass(data: np.ndarray, sr: int, cutoff: float = 80.0) -> np.ndarray:
        """One-pole high-pass via scipy.signal.lfilter (vectorised)."""
        if data.size == 0:
            return data
        import scipy.signal as signal

        rc = 1.0 / (2 * np.pi * cutoff)
        dt = 1.0 / sr
        alpha = rc / (rc + dt)
        return signal.lfilter([alpha, -alpha], [1.0, -alpha], data)

    @staticmethod
    def _noise_gate_sibilance_aware(
        data: np.ndarray, sr: int, threshold: float = 0.005, min_silence_ms: int = 100
    ) -> np.ndarray:
        """Noise gate that protects short sibilance bursts.

        Previous gate (threshold=0.02, per-sample) was measured to attenuate
        41% of samples including high-frequency s/z/sh bursts, making clones
        sound dry. This version only attenuates runs that stay below threshold
        for >= min_silence_ms continuously — i.e. real inter-sentence silence /
        background hiss — while leaving brief low-energy sibilance untouched.
        """
        if data.size == 0:
            return data
        out = data.copy()
        n = out.size
        min_run = int(sr * min_silence_ms / 1000)
        if min_run < 1:
            min_run = 1
        abs_out = np.abs(out)
        below = abs_out < threshold
        # find maximal runs of consecutive below-threshold samples
        i = 0
        while i < n:
            if not below[i]:
                i += 1
                continue
            j = i
            while j < n and below[j]:
                j += 1
            run_len = j - i
            if run_len >= min_run:
                out[i:j] *= 0.1  # attenuate sustained background, keep natural edges
            i = j
        return out

    @staticmethod
    def _trim_silence(data: np.ndarray, sr: int, top_db: int = 30, pad_ms: int = 30) -> np.ndarray:
        """Trim leading/trailing silence with librosa VAD, then pad a little breath room.

        Measured refs had up to 701ms of trailing silence (13.5% of file) that
        GPT-SoVITS learns as a dragged-out tail. We trim it but keep a small
        pad so the clip doesn't start/end too abruptly.
        """
        if data.size == 0:
            return data
        import librosa

        try:
            trimmed, _ = librosa.effects.trim(data, top_db=top_db)
        except Exception:
            trimmed = data
        pad = int(sr * pad_ms / 1000)
        if pad > 0:
            # reflect pad avoids the discontinuity click that zero-padding can
            # introduce when the trim boundary isn't at a zero crossing.
            trimmed = np.pad(trimmed, (pad, pad), mode="reflect")
        return trimmed

    @staticmethod
    def _to_mono_float(data: np.ndarray) -> np.ndarray:
        if data.size == 0:
            return data.astype(np.float32, copy=False)
        if data.ndim > 1:
            data = np.mean(data, axis=1)
        return data.astype(np.float32, copy=False)

    @staticmethod
    def _resample_to(data: np.ndarray, sr_in: int, sr_out: int) -> np.ndarray:
        if sr_in == sr_out or data.size == 0:
            return data
        import scipy.signal as signal
        import math

        g = math.gcd(sr_in, sr_out)
        return signal.resample_poly(data, sr_out // g, sr_in // g).astype(np.float32)

    # ---------- real HP2 model path ----------

    def _run_hp2_sync(self, input_path: str) -> np.ndarray:
        """Synchronous HP2 inference. Returns cleaned vocal as mono float32 @ SAMPLE_RATE.

        Must be called from a worker thread (it blocks on torch). The caller
        already holds vram_manager's UVR5 lock so UVR5 and TTS don't run concurrently.

        AudioPre's internal librosa.load expects 44100 Hz stereo (see vr.py:62-67
        and webui.py:87 — the official webui reformats non-44.1k/stereo inputs via
        ffmpeg first). We pre-convert with scipy polyphase + soundfile once so the
        model sees its native format and we avoid librosa's kaiser_fast resampler,
        which is faster but lower quality than polyphase. Output is 44100 (HP2 native)
        and we downsample back to SAMPLE_RATE once at the end.
        """
        import torch

        if _UVR5_TOOL_DIR not in sys.path:
            sys.path.insert(0, _UVR5_TOOL_DIR)
        from vr import AudioPre  # noqa: E402

        model_path = _find_hp2_model()
        if not model_path:
            raise RuntimeError("HP2 model not found")

        is_half = torch.cuda.is_available() and self.device == "cuda"
        pre: AudioPre | None = None
        try:
            # 1. Pre-convert input to 44100 Hz stereo PCM16 (HP2's expected format).
            raw, raw_sr = sf.read(input_path, always_2d=True)
            raw = self._to_mono_float(raw)  # averages stereo channels
            if raw_sr != 44100:
                raw = self._resample_to(raw, raw_sr, 44100)
            stereo = np.stack([raw, raw], axis=1)  # mono -> stereo
            with tempfile.TemporaryDirectory() as tmp:
                reformatted = os.path.join(tmp, "input_44100.wav")
                sf.write(reformatted, stereo, 44100, subtype="PCM_16")
                vocal_dir = tmp
                # 2. Run HP2; AudioPre writes vocal_<basename>_<agg>.wav into vocal_root.
                pre = AudioPre(
                    agg=10, model_path=model_path, device=self.device, is_half=is_half
                )
                pre._path_audio_(
                    reformatted,
                    ins_root=None,
                    vocal_root=vocal_dir,
                    format="wav",
                    is_hp3=False,
                )
                vocal_files = [
                    f for f in os.listdir(vocal_dir)
                    if f.startswith("vocal_") and f.endswith(".wav")
                ]
                if not vocal_files:
                    raise RuntimeError("HP2 produced no vocal output")
                vocal_path = os.path.join(vocal_dir, sorted(vocal_files)[0])
                data, sr = sf.read(vocal_path)
                data = self._to_mono_float(data)
                # 3. Downsample HP2 output (44100) back to the pipeline's SAMPLE_RATE.
                data = self._resample_to(data, sr, SAMPLE_RATE)
                return data
        finally:
            try:
                if pre is not None:
                    del pre
            except Exception:
                pass
            if torch.cuda.is_available():
                torch.cuda.empty_cache()

    async def _process_with_hp2(self, input_path: str) -> np.ndarray:
        """Async wrapper: run HP2 in an executor so the event loop isn't blocked."""
        loop = asyncio.get_running_loop()
        return await loop.run_in_executor(None, self._run_hp2_sync, input_path)

    # ---------- mock DSP fallback ----------

    def _mock_denoise(self, data: np.ndarray, sr: int) -> np.ndarray:
        """Mock denoise: high-pass + sibilance-aware gate + normalize."""
        data = self._highpass(data, sr, cutoff=80.0)
        max_val = float(np.max(np.abs(data))) if data.size else 0.0
        if max_val > 0:
            data = data / max_val * 0.95
        data = self._noise_gate_sibilance_aware(data, sr, threshold=0.005, min_silence_ms=100)
        return data

    # ---------- public API ----------

    async def process_audio(self, input_path: str, output_path: str) -> str:
        """
        Clean a reference audio and write to output_path.
        Raises UVRReferenceError if the audio is silent / unusable, so callers
        can reject the voice profile at registration time instead of letting
        GPT-SoVITS clone from noise.
        """
        await vram_manager.acquire_uvr5()
        try:
            logger.info(f"[UVR5] Processing reference audio: {input_path}")

            hp2_path = _find_hp2_model()
            data: np.ndarray | None = None
            sr = SAMPLE_RATE

            if hp2_path:
                try:
                    logger.info(f"[UVR5] using real HP2 model: {os.path.basename(hp2_path)}")
                    data = await self._process_with_hp2(input_path)
                except Exception as e:
                    logger.warning(
                        f"[UVR5] HP2 inference failed, falling back to mock DSP: {e}"
                    )
                    data = None

            if data is None:
                logger.info("[UVR5] using mock DSP denoise (no/failed HP2 model)")
                await asyncio.sleep(0.1)  # tiny yield; mock is fast
                raw, raw_sr = sf.read(input_path)
                data = self._to_mono_float(raw)
                if raw_sr != SAMPLE_RATE:
                    data = self._resample_to(data, raw_sr, SAMPLE_RATE)
                data = self._mock_denoise(data, SAMPLE_RATE)

            if data is None or data.size == 0:
                raise UVRReferenceError("Reference audio is empty after processing")

            # VAD trim on the cleaned signal (both paths).
            data = self._trim_silence(data, SAMPLE_RATE, top_db=30, pad_ms=30)

            # Quality gate: reject near-silent references.
            rms = float(np.sqrt((data ** 2).mean())) if data.size else 0.0
            if rms < MIN_REF_RMS:
                logger.error(
                    f"[UVR5] reference rejected: RMS too low ({rms:.5f} < {MIN_REF_RMS}) "
                    f"=> would make GPT-SoVITS produce noise. path={input_path}"
                )
                raise UVRReferenceError(
                    f"Reference audio is too quiet (RMS={rms:.4f}); "
                    "please record 3-10s of clear speech."
                )

            # Final normalize for a consistent level feeding GPT-SoVITS.
            max_val = float(np.max(np.abs(data))) if data.size else 0.0
            if max_val > 0:
                data = data / max_val * 0.95

            sf.write(output_path, data, SAMPLE_RATE, subtype="PCM_16")
            logger.info(
                f"[UVR5] Successfully cleaned and saved to {output_path} "
                f"(rms={rms:.4f}, len={len(data)/SAMPLE_RATE:.2f}s, "
                f"engine={'HP2' if hp2_path else 'mock'})"
            )
            return output_path

        except UVRReferenceError:
            raise
        except Exception as e:
            logger.error(f"[UVR5] audio processing failed (not falling back to copy): {e}")
            raise UVRReferenceError(f"Reference audio processing failed: {e}") from e

        finally:
            await vram_manager.release_uvr5()


uvr5_service = UVR5Service()
