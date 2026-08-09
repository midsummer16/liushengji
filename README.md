# 个人专属语音克隆与合成系统 (Voice Clone)

基于 Android (Kotlin + Jetpack Compose) 与 FastAPI + GPT-SoVITS/UVR5 的个人语音克隆工具。

## 功能
- **声纹入库**：录制/上传亲友 3-10 秒语音 -> 后端 UVR5 降噪 -> 生成"声纹卡片"
- **语音合成**：选择声纹卡片 -> 输入任意文本 -> 流式播放克隆语音（首包 < 2s）
- **文本归一化**：自动转换阿拉伯数字为中文读音，按标点切句降低延迟

## 目录结构
```
backend/    FastAPI 后端（SQLite + UVR5 + GPT-SoVITS + 显存调度）
  Dockerfile          # 后端容器镜像（Python 3.12 + PyTorch CUDA）
  entrypoint.sh       # 容器入口：设置 PYTHONPATH 后启动 uvicorn
  GPT-SoVITS/         # 第三方 vendored 仓库（勿改动）
  data/               # voices.db、audio_storage/（卷挂载，不入镜像）
android/    Android 客户端（Kotlin + Jetpack Compose）
docker-compose.yml    # GPU 透传 + 权重/数据卷挂载的一键部署
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

### 2.5 UVR5 人声分离权重
放 `backend/GPT-SoVITS/tools/uvr5/uvr5_weights/`：
```
HP2-人声vocals+非人声instrumentals.pth    # 注册声纹时的参考音频降噪
```
有该权重时参考音频走真实 HP2 人声分离；缺失或推理失败则回退内置 DSP mock（高通 + 齿音感知噪声门 + VAD 去尾），系统仍可跑通。

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
GPT-SoVITS v2 推理常驻显存约 **3-4GB**（api_v2 子进程独占）。UVR5 通过 `vram_manager`
懒加载：收到声纹注册请求时先将 GPT-SoVITS 卸载到 CPU、清空 CUDA cache，再加载 UVR5 HP2
处理完立即卸载并恢复 GPT-SoVITS，两者经 asyncio 锁互斥，不会同时驻留两个大模型
（`core/vram_manager.py`）。

### 5. Docker 部署（可选，替代手动启动）
宿主机装好 NVIDIA 驱动 + Docker + nvidia-container-toolkit 后，在仓库根目录：
```bash
docker compose up -d --build
```
- GPU 透传（`deploy.resources.reservations.devices`）+ `shm_size: 8g`（PyTorch 多进程共享内存）
- 权重目录只读挂载，直接放到宿主机
  `backend/GPT-SoVITS/GPT_SoVITS/pretrained_models/` 与
  `backend/GPT-SoVITS/tools/uvr5/uvr5_weights/` 即可，无需重建镜像
- 数据目录读写挂载到 `backend/data/`（SQLite + 用户音频持久化）
- 容器内通过 `DATA_DIR=/data` 环境变量定位数据（`core/config.py`）
- `entrypoint.sh` 动态设置 `PYTHONPATH`（`/app/GPT-SoVITS` 与内层
  `/app/GPT-SoVITS/GPT_SoVITS`），解决 GPT-SoVITS 导入冲突
- 日志与排障：`docker compose logs -f backend`

> 本机已有 Python 环境时手动启动亦可（见上文第 1-3 节），Docker 适合换机/部署。

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
