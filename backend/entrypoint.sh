#!/bin/bash
set -e

# GPT-SoVITS needs multiple paths on PYTHONPATH:
# /app/GPT-SoVITS       → for "from GPT_SoVITS.TTS_infer_pack.TTS import ..."
# /app/GPT-SoVITS/GPT_SoVITS → for "from AR.models..." (AR is inside the inner dir)
# Individual tools/ subdirs are handled by their own sys.path.append calls.
export PYTHONPATH="/app/GPT-SoVITS:/app/GPT-SoVITS/GPT_SoVITS"

exec python -m uvicorn app.main:app --host 0.0.0.0 --port 8000
