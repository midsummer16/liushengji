import asyncio
import io
import logging
import os
import random
import socket
import subprocess
import sys
import time
import wave
from typing import AsyncGenerator, List, Tuple

import httpx

from app.core.config import (
    GPT_SOVITS_API_URL,
    GPT_SOVITS_DIR,
    GPT_SOVITS_HOST,
    GPT_SOVITS_PORT,
    SAMPLE_RATE,
)
from app.services.text_normal import TextNormalizer

logger = logging.getLogger("GPTSoVITSService")

# Soften GPT-SoVITS output (it normalizes to ~1.0 peak which distorts on phone speakers)
OUTPUT_VOLUME = float(os.getenv("GPT_SOVITS_OUTPUT_VOLUME", "0.7"))


def _scale_pcm(pcm: bytes, volume: float) -> bytes:
    if volume >= 1.0 or not pcm:
        return pcm
    import numpy as np

    arr = np.frombuffer(pcm, dtype=np.int16).astype(np.float32) * volume
    np.clip(arr, -32768, 32767, out=arr)
    return arr.astype(np.int16).tobytes()


def _resample_pcm(pcm: bytes, sr_in: int, sr_out: int) -> bytes:
    """Resample int16 mono PCM from sr_in to sr_out using band-limited polyphase filter."""
    if sr_in == sr_out or not pcm:
        return pcm
    import numpy as np
    from scipy.signal import resample_poly

    arr = np.frombuffer(pcm, dtype=np.int16).astype(np.float32)
    # polyphase up/down keeps it band-limited (anti-aliased)
    import math

    g = math.gcd(sr_in, sr_out)
    up = sr_out // g
    down = sr_in // g
    out = resample_poly(arr, up, down)
    out = np.clip(out, -32768, 32767).astype(np.int16)
    return out.tobytes()


class GPTSoVITSAPIError(RuntimeError):
    """API-level error (non-200) returned by the GPT-SoVITS server."""


def _detect_lang(text: str) -> str:
    """Simple zh/en detection. Mixed text defaults to zh (LangSegmenter handles en inside zh)."""
    for ch in text:
        if "\u4e00" <= ch <= "\u9fff":
            return "zh"
    if any(c.isalpha() for c in text):
        return "en"
    return "zh"


def _chunk_text(text: str, max_chars: int = 40) -> List[str]:
    """Split text into short chunks (merge sentences up to max_chars) for stable synthesis."""
    sentences = TextNormalizer.process(text)
    chunks: List[str] = []
    cur = ""
    for s in sentences:
        if len(cur) + len(s) <= max_chars:
            cur += s
        else:
            if cur:
                chunks.append(cur)
            cur = s
    if cur:
        chunks.append(cur)
    return chunks or [text]


def _parse_wav(data: bytes) -> Tuple[int, bytes]:
    """Parse a complete WAV into (sample_rate, pcm_bytes)."""
    w = wave.open(io.BytesIO(data), "rb")
    sr = w.getframerate()
    pcm = w.readframes(w.getnframes())
    w.close()
    return sr, pcm


MIN_PCM_BYTES = 2048  # = 1024 int16 samples; minimum to attempt noise analysis

def _is_noise(pcm: bytes, sr: int) -> bool:
    """
    Heuristic noise detection for failed GPT-SoVITS generations.

    A clean speech chunk has strong low-frequency harmonic structure (clear
    formants / pitch track); broadband hiss / white-ish noise has a flat
    spectrum with no clear harmonic peaks. We combine three signals:
      - spectral flatness (high => noise)
      - harmonic ratio / peakiness (low => noise)
      - low-band energy ratio (human voice concentrates energy < 4kHz)

    Critically we do NOT bail out on low RMS: GPT-SoVITS degenerations
    (e.g. from silent/noisy refs) frequently produce low-level broadband
    hiss that the previous `rms < 0.02` early-return let through, which was
    the root cause of the "all output is noise" bug. Silence is rejected by
    a near-zero RMS threshold that corresponds to digital silence, while
    audible-but-quiet hiss is still evaluated.
    """
    if len(pcm) < MIN_PCM_BYTES:
        return False
    import numpy as np

    a = np.frombuffer(pcm, dtype=np.int16).astype(np.float32) / 32768.0
    rms = float(np.sqrt((a ** 2).mean()))

    # True digital silence (all-zero / ≈0) is NOT "noise" ( callers emit a
    # silent placeholder for it). But keep the threshold tiny so that audible
    # low-level hiss (rms ~0.005-0.02) is still judged on its spectrum.
    if rms < 0.001:
        return False

    win = int(sr * 3)
    if len(a) >= win:
        # find the loudest window
        n = len(a) // win
        if n == 0:
            a = a[:win]
        else:
            seg = a[: n * win].reshape(n, win)
            energy = (seg ** 2).mean(axis=1)
            a = seg[int(energy.argmax())]
    else:
        a = a[:win]

    if len(a) < 1024:
        return False

    spec = np.abs(np.fft.rfft(a))
    spec = spec[1:]
    if len(spec) < 16:
        return False
    spec_nz = spec[spec > 1e-9]
    if len(spec_nz) < 16:
        return True  # spectrum is essentially empty of energy beyond DC

    # 1) Spectral flatness: close to 1 => white noise; close to 0 => tonal.
    flatness = float(np.exp(np.mean(np.log(spec_nz))) / np.mean(spec_nz))

    # 2) Peakiness / crest factor in spectrum: speech has pronounced peaks.
    spec_max = float(spec.max())
    spec_mean = float(spec.mean())
    peakiness = spec_max / spec_mean if spec_mean > 1e-9 else 1.0
    # low peakiness (flat) => noise
    low_peaks = peakiness < 8.0

    # 3) Low-band energy ratio: voice concentrates energy below ~4kHz.
    #    Hiss spreads energy across the whole band.
    nyquist = sr / 2.0
    low_bin = max(1, int(len(spec) * 4000.0 / nyquist))
    low_energy = float(spec[:low_bin].sum())
    total_energy = float(spec.sum())
    low_ratio = low_energy / total_energy if total_energy > 1e-9 else 0.0
    # Majority of energy living in high frequencies => hiss.
    high_energy_dominant = low_ratio < 0.45

    votes = (flatness >= 0.40) + (1 if low_peaks else 0) + (1 if high_energy_dominant else 0)
    logger.debug(
        f"[noise-chk] rms={rms:.4f} flat={flatness:.3f} peak={peakiness:.1f} "
        f"low_ratio={low_ratio:.3f} votes={votes}/3 -> {'NOISE' if votes >= 2 else 'ok'}"
    )
    return votes >= 2


class GPTSoVITSManager:
    """
    Manages the GPT-SoVITS api_v2.py subprocess (official inference API server).
    Starts it lazily/on-demand and restarts it if it dies.
    """

    def __init__(self):
        self.process: subprocess.Popen | None = None
        self._lock = asyncio.Lock()
        self._ready = False

    def _port_open(self, timeout: float = 1.0) -> bool:
        try:
            with socket.create_connection((GPT_SOVITS_HOST, GPT_SOVITS_PORT), timeout=timeout):
                return True
        except OSError:
            return False

    async def ensure_running(self) -> bool:
        async with self._lock:
            if self._ready:
                return True
            if self.process is not None and self.process.poll() is not None:
                logger.warning(f"[GPT-SoVITS] api_v2.py exited with code {self.process.returncode}, restarting")
                self.process = None
                self._ready = False
            if self._port_open():
                self._ready = True
                return True
            return await self._start()

    async def _start(self) -> bool:
        if not os.path.isdir(GPT_SOVITS_DIR):
            logger.error(f"[GPT-SoVITS] vendor dir not found: {GPT_SOVITS_DIR}")
            return False
        if not os.path.isfile(os.path.join(GPT_SOVITS_DIR, "api_v2.py")):
            logger.error("[GPT-SoVITS] api_v2.py not found in vendor dir")
            return False

        log_path = os.path.join(GPT_SOVITS_DIR, "gptsovits_api.log")
        logf = open(log_path, "ab")
        cmd = [
            sys.executable,
            "api_v2.py",
            "-a", GPT_SOVITS_HOST,
            "-p", str(GPT_SOVITS_PORT),
            "-c", "GPT_SoVITS/configs/tts_infer.yaml",
        ]
        logger.info(f"[GPT-SoVITS] starting api_v2.py: {cmd}")
        self.process = subprocess.Popen(
            cmd,
            cwd=GPT_SOVITS_DIR,
            stdout=logf,
            stderr=logf,
            stdin=subprocess.DEVNULL,
        )
        return await self._wait_ready(timeout=300)

    async def _wait_ready(self, timeout: float = 300.0) -> bool:
        start = time.time()
        while time.time() - start < timeout:
            if self.process is not None and self.process.poll() is not None:
                logger.error(f"[GPT-SoVITS] api_v2.py died during startup (code {self.process.returncode})")
                return False
            if self._port_open():
                self._ready = True
                logger.info("[GPT-SoVITS] api_v2.py is ready")
                return True
            await asyncio.sleep(2)
        logger.error(f"[GPT-SoVITS] api_v2.py not ready within {timeout}s")
        return False

    def stop(self):
        if self.process is not None:
            try:
                self.process.terminate()
            except Exception:
                pass
            self.process = None
        self._ready = False


gpt_sovits_manager = GPTSoVITSManager()


class GPTSoVITSService:
    """
    Real GPT-SoVITS zero-shot inference via the official api_v2.py server.

    Strategy (fixes per-sentence noise):
      - split input into short chunks (<=40 chars)
      - synthesize each chunk in a separate non-streaming call
      - detect broadband-noise chunks (spectral flatness) and retry with a
        different seed (up to 3 retries); emit silence if still bad
      - stream header + clean chunk PCM to the client as they complete
    Falls back to a mock tone generator if the real server is unavailable.
    """

    async def generate_speech_stream(
        self,
        text: str,
        ref_audio_path: str,
        ref_text: str,
        aux_ref_audio_paths: List[str] | None = None,
        speed: float = 1.0,
    ) -> AsyncGenerator[bytes, None]:
        try:
            if await gpt_sovits_manager.ensure_running():
                async for chunk in self._real_stream(text, ref_audio_path, ref_text, aux_ref_audio_paths or [], speed):
                    yield chunk
                return
            logger.warning("[GPT-SoVITS] real server unavailable, falling back to mock")
        except httpx.HTTPError as e:
            logger.error(f"[GPT-SoVITS] server unreachable, falling back to mock: {e}")
        except Exception as e:
            logger.error(f"[GPT-SoVITS] real inference failed, falling back to mock: {e}")

        async for chunk in self._mock_stream(text, speed):
            yield chunk

    async def _real_stream(
        self,
        text: str,
        ref_audio_path: str,
        ref_text: str,
        aux_ref_audio_paths: List[str],
        speed: float,
    ) -> AsyncGenerator[bytes, None]:
        chunks = _chunk_text(text)
        logger.info(f"[GPT-SoVITS] synth {len(chunks)} chunk(s), ref={ref_audio_path}, aux={len(aux_ref_audio_paths)}")

        header_sent = False
        timeout = httpx.Timeout(600.0, connect=5.0)
        async with httpx.AsyncClient(timeout=timeout) as client:
            for idx, chunk in enumerate(chunks):
                result = await self._synthesize_chunk_with_retry(
                    client, chunk, ref_audio_path, aux_ref_audio_paths, ref_text, speed
                )
                if result is None:
                    # failed after retries / api error on this chunk -> silence placeholder
                    duration = max(0.3, len(chunk) * 0.15 / speed)
                    pcm = b"\x00\x00" * int(SAMPLE_RATE * duration)
                    logger.warning(f"[GPT-SoVITS] chunk [{idx+1}/{len(chunks)}] silenced: {chunk[:24]}")
                else:
                    sr, pcm = result
                    # Resample to the configured rate if the model emitted something else
                    # (e.g. a v3/v4 checkpoint at 24k/48k). Skipping this used to make the
                    # whole stream play back at the wrong speed => "noise/garble".
                    if sr != SAMPLE_RATE:
                        pcm = _resample_pcm(pcm, sr, SAMPLE_RATE)
                    # soften output to avoid clipping/distortion on phone speakers
                    pcm = _scale_pcm(pcm, OUTPUT_VOLUME)
                    logger.info(f"[GPT-SoVITS] chunk [{idx+1}/{len(chunks)}] ok: {chunk[:24]}")

                if not header_sent:
                    yield self._wav_header() + pcm
                    header_sent = True
                else:
                    yield pcm

    async def _synthesize_chunk_with_retry(
        self,
        client: httpx.AsyncClient,
        chunk: str,
        ref_audio_path: str,
        aux_ref_audio_paths: List[str],
        ref_text: str,
        speed: float,
    ) -> Tuple[int, bytes] | None:
        for attempt in range(4):  # 1 initial + 3 retries
            seed = random.randint(0, 2 ** 31 - 1)
            try:
                sr, pcm = await self._synthesize_chunk(
                    client, chunk, ref_audio_path, aux_ref_audio_paths, ref_text, speed, seed
                )
            except GPTSoVITSAPIError as e:
                logger.error(f"[GPT-SoVITS] chunk API error (no retry): {e}")
                return None
            except Exception as e:
                # WAV parse errors, network glitches, etc. — retry rather than
                # immediately falling back to the full mock stream.
                logger.warning(f"[GPT-SoVITS] chunk error attempt {attempt + 1}/4: {e}")
                if attempt == 3:
                    return None
                continue
            # validate against the real sample rate returned by the subprocess,
            # not the configured one (a different model checkpoint may emit 24k/48k).
            if not _is_noise(pcm, sr):
                return sr, pcm
            logger.warning(
                f"[GPT-SoVITS] noisy chunk, retry {attempt + 1}/3 (seed={seed}): {chunk[:24]}"
            )
        return None

    async def _synthesize_chunk(
        self,
        client: httpx.AsyncClient,
        chunk: str,
        ref_audio_path: str,
        aux_ref_audio_paths: List[str],
        ref_text: str,
        speed: float,
        seed: int,
    ) -> Tuple[int, bytes]:
        payload = {
            "text": chunk,
            "text_lang": _detect_lang(chunk),
            "ref_audio_path": ref_audio_path,
            "aux_ref_audio_paths": aux_ref_audio_paths,
            "prompt_text": ref_text,
            "prompt_lang": _detect_lang(ref_text),
            "top_k": 10,
            "top_p": 0.9,
            "temperature": 0.7,
            "text_split_method": "cut1",
            "batch_size": 1,
            "batch_threshold": 0.75,
            "split_bucket": True,
            "speed_factor": speed,
            "fragment_interval": 0.05,
            "seed": seed,
            "parallel_infer": True,
            "repetition_penalty": 1.35,
            "sample_steps": 32,
            "super_sampling": False,
            "streaming_mode": 0,
            "overlap_length": 2,
            "min_chunk_length": 16,
            "media_type": "wav",
        }
        resp = await client.post(f"{GPT_SOVITS_API_URL}/tts", json=payload)
        if resp.status_code != 200:
            raise GPTSoVITSAPIError(f"GPT-SoVITS HTTP {resp.status_code}: {resp.text[:200]}")
        return _parse_wav(resp.content)

    async def _mock_stream(self, text: str, speed: float) -> AsyncGenerator[bytes, None]:
        sentences = TextNormalizer.process(text)
        logger.info(f"[GPT-SoVITS][mock] {len(sentences)} sentences")
        header_sent = False
        for sentence in sentences:
            pcm = await self._mock_synthesize_sentence(sentence, speed)
            if not header_sent:
                yield self._wav_header() + pcm
                header_sent = True
            else:
                yield pcm
            await asyncio.sleep(0.01)

    async def _mock_synthesize_sentence(self, sentence: str, speed: float) -> bytes:
        import numpy as np

        duration = max(0.4, len(sentence) * 0.15 / speed)
        num_samples = int(SAMPLE_RATE * duration)
        t = np.linspace(0, duration, num_samples, False)
        audio = (
            0.4 * np.sin(2 * np.pi * 220.0 * t)
            + 0.2 * np.sin(2 * np.pi * 440.0 * t)
            + 0.1 * np.sin(2 * np.pi * 660.0 * t)
        )
        fade_in = int(0.02 * SAMPLE_RATE)
        fade_out = int(0.02 * SAMPLE_RATE)
        if len(audio) > fade_in + fade_out:
            audio[:fade_in] *= np.linspace(0, 1, fade_in)
            audio[-fade_out:] *= np.linspace(1, 0, fade_out)
        await asyncio.sleep(0.1)
        return (audio * 32767).astype(np.int16).tobytes()

    @staticmethod
    def _wav_header() -> bytes:
        """44-byte canonical WAV header for a streamed (length-unknown) mono 16-bit PCM.

        Uses data size = 0xFFFFFFFF so that saved .wav files are accepted by
        standard players (which treat it as "stream until EOF"), instead of the
        previous 0 which made saved files report nframes=0 / unplayable.
        The client still skips these 44 bytes and plays raw PCM, so this only
        affects correctness of the on-disk artifact, not real-time playback.
        """
        import struct

        num_channels = 1
        bits_per_sample = 16
        byte_rate = SAMPLE_RATE * num_channels * bits_per_sample // 8
        block_align = num_channels * bits_per_sample // 8
        data_size = 0xFFFFFFFF  # unknown / stream to EOF
        fmt_chunk_size = 16
        # riff_size would overflow for the streaming case; set it to 0xFFFFFFFF
        # too, which players interpret as "size unknown / stream until EOF".
        riff_size = 0xFFFFFFFF

        return b"RIFF" + struct.pack("<I", riff_size) + b"WAVE" + \
            b"fmt " + struct.pack("<IHHIIHH", fmt_chunk_size, 1, num_channels,
                                   SAMPLE_RATE, byte_rate, block_align, bits_per_sample) + \
            b"data" + struct.pack("<I", data_size)


gpt_sovits_service = GPTSoVITSService()
