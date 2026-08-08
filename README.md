# 个人专属语音克隆与合成系统 (Voice Clone)

基于 Android (Kotlin + Jetpack Compose) 与 FastAPI + GPT-SoVITS/UVR5 的个人语音克隆工具。

## 功能
- **声纹入库**：录制/上传亲友 3-10 秒语音 -> 后端 UVR5 降噪 -> 生成"声纹卡片"
- **语音合成**：选择声纹卡片 -> 输入任意文本 -> 流式播放克隆语音（首包 < 2s）
- **文本归一化**：自动转换阿拉伯数字为中文读音，按标点切句降低延迟

## 目录结构
```
backend/    FastAPI 后端（SQLite + UVR5 + GPT-SoVITS + 显存调度）
android/    Android 客户端（Kotlin + Jetpack Compose）
```

## 后端启动

### 1. 安装依赖
```bash
cd backend
python3 -m venv .venv
source .venv/bin/activate
# PyTorch CUDA 版（与 GPT-SoVITS 生态匹配）
pip install torch torchaudio --index-url https://download.pytorch.org/whl/cu121
pip install -r requirements.txt
```

> 国内网络建议 pip 走清华镜像：`pip install -i https://pypi.tuna.tsinghua.edu.cn/simple -r requirements.txt`
>
> NLTK 英文音素语料需手动放置到 `~/nltk_data/`（cmudict、averaged_perceptron_tagger_eng、punkt_tab、wordnet、words），从
> `https://raw.githubusercontent.com/nltk/nltk_data/gh-pages/packages/` 下载对应 zip 解压即可。

### 2. GPT-SoVITS 权重
vendored 仓库位于 `backend/GPT-SoVITS/`，权重放入其模型目录（推荐 ModelScope 下载，国内快）：
```
GPT-SoVITS/GPT_SoVITS/pretrained_models/
├── chinese-roberta-wwm-ext-large/    # BERT（中文/英文共用）
├── chinese-hubert-base/              # CNHuBERT 特征
├── gsv-v2final-pretrained/
│   ├── s1bert25hz-5kh-longer-epoch=12-step=369668.ckpt   # GPT 权重
│   └── s2G2333k.pth                                       # SoVITS 权重
└── fast_langdetect/lid.176.bin       # 自动下载
```
下载源：`https://modelscope.cn/models/AI-ModelScope/GPT-SoVITS`（权重）与
`https://modelscope.cn/models/hfl/chinese-roberta-wwm-ext-large`（BERT）。

> 若权重缺失，`gpt_sovits.py` 自动回退 Mock 音调（非真实语音），保证 API 可跑通。

### 3. 运行
```bash
uvicorn app.main:app --host 0.0.0.0 --port 8000
```
- Swagger 文档：`http://<server_ip>:8000/docs`
- 健康检查：`http://<server_ip>:8000/health`
- 后端启动时会**自动拉起 GPT-SoVITS 推理服务**（api_v2.py，端口 9880）作为子进程，
  首次加载模型约需 30-60 秒；可用环境变量 `GPT_SOVITS_STREAMING_MODE`（1=高质量流式/3=低延迟）调整。

### 4. 显存调度 (8GB RTX 4060)
GPT-SoVITS v2 推理常驻显存约 **3-4GB**（api_v2 子进程独占）。UVR5 目前为内置 DSP mock（不占显存）；
如需接入真实 UVR5，按 `core/vram_manager.py` 的设计在进程间做懒加载切换。

## API 接口
| 方法 | 路径 | 说明 |
|------|------|------|
| GET | `/api/v1/voices` | 获取声纹列表 |
| POST | `/api/v1/voices` | 上传音频 + name + ref_text 注册声纹 |
| DELETE | `/api/v1/voices/{id}` | 删除声纹 |
| POST | `/api/v1/tts` | 流式合成 `{"voice_id","text"}` -> audio/wav |

## Android 客户端
用 Android Studio 打开 `android/` 目录即可编译运行。

- **音频采集**：`AudioRecord` 44.1kHz 16-bit PCM -> 重采样 32kHz -> 封装 WAV 上传
- **流式播放**：`AudioTrack` MODE_STREAM 边下边播（32kHz 单声道）
- **服务器配置**：首页右上角设置图标，填入后端地址（如 `http://192.168.1.100:8000/` 或 Tailscale IP）

## 外网访问
- **局域网**：手机与笔记本同一 WiFi，直连 `http://<笔记本IP>:8000`
- **异地访问**：安装 Tailscale，两端登录同一账号，使用 `http://<tailscale-ip>:8000`
