FROM python:3.12-slim-bookworm

# System deps: ffmpeg for audio I/O, libsndfile1 for soundfile
RUN apt-get update && apt-get install -y --no-install-recommends \
    ffmpeg \
    libsndfile1 \
    && rm -rf /var/lib/apt/lists/*

WORKDIR /app

# ---- Python dependencies (cached layer) ----
COPY requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt \
    && pip install --no-cache-dir resampy

# ---- NLTK data for English phonemization ----
RUN python -c "import nltk; \
    nltk.download('cmudict'); \
    nltk.download('averaged_perceptron_tagger_eng'); \
    nltk.download('punkt_tab')"

# ---- GPT-SoVITS vendored repo (code only, weights mounted at runtime) ----
COPY GPT-SoVITS/ ./GPT-SoVITS/
# Remove any model weights that may have been copied (they should be mounted)
RUN rm -rf GPT-SoVITS/GPT_SoVITS/pretrained_models/*.pth \
    && rm -rf GPT-SoVITS/GPT_SoVITS/pretrained_models/*.ckpt \
    && rm -rf GPT-SoVITS/tools/uvr5/uvr5_weights/*.pth \
    && rm -rf GPT-SoVITS/tools/uvr5/uvr5_weights/*.ckpt

# ---- Backend application code ----
COPY app/ ./app/

# ---- Create data directory (will be a volume mount) ----
RUN mkdir -p /data/audio_storage /data/samples

# ---- Entrypoint: sets PYTHONPATH and launches uvicorn ----
COPY entrypoint.sh /app/entrypoint.sh
RUN chmod +x /app/entrypoint.sh

EXPOSE 8000

ENTRYPOINT ["/app/entrypoint.sh"]
