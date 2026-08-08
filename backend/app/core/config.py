import os

BASE_DIR = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

DATA_DIR = os.getenv("DATA_DIR", os.path.join(BASE_DIR, "data"))
AUDIO_STORAGE_DIR = os.path.join(DATA_DIR, "audio_storage")
DB_PATH = os.path.join(DATA_DIR, "voices.db")
WEIGHTS_DIR = os.path.join(BASE_DIR, "weights")

SAMPLE_RATE = 32000
CHANNELS = 1
SAMPLE_WIDTH = 2

os.makedirs(AUDIO_STORAGE_DIR, exist_ok=True)
os.makedirs(WEIGHTS_DIR, exist_ok=True)

SERVER_HOST = os.getenv("SERVER_HOST", "0.0.0.0")
SERVER_PORT = int(os.getenv("SERVER_PORT", "8000"))

# GPT-SoVITS API server (vendored repo running as a subprocess)
GPT_SOVITS_DIR = os.getenv("GPT_SOVITS_DIR", os.path.join(BASE_DIR, "GPT-SoVITS"))
GPT_SOVITS_HOST = os.getenv("GPT_SOVITS_HOST", "127.0.0.1")
GPT_SOVITS_PORT = int(os.getenv("GPT_SOVITS_PORT", "9880"))
GPT_SOVITS_API_URL = f"http://{GPT_SOVITS_HOST}:{GPT_SOVITS_PORT}"
# 1 = fragment streaming (best quality), 3 = fixed-length chunk (fastest)
GPT_SOVITS_STREAMING_MODE = int(os.getenv("GPT_SOVITS_STREAMING_MODE", "1"))
