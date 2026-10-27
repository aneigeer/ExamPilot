# ======================================================================================
# AI 微服务镜像（供 docker-compose 使用）
#
# ★ 注意：本项目默认用 run.bat 在宿主机跑（开发调试更方便，改代码不用重建镜像）。
#   本文件用于"使用场景一键部署"或"CI 集成测试"场景。
#
# 构建（在项目根目录执行）：
#   docker build -t examprep-ai-service:1.0.0 -f ai-service/Dockerfile .
#
# 运行：
#   docker run -d --name examprep-ai-service -p 8000:8000 ^
#     -e QDRANT_URL=http://qdrant:6333 ^
#     --network examprep_default ^
#     examprep-ai-service:1.0.0
#
# 体积预估：
#   基础镜像 python:3.13-slim      约 130 MB
#   pip 依赖（约 135MB，--no-cache-dir 清理后）
#   embedding 模型（构建时 COPY 进来）  约 28 MB
#   ------------------------------------------------------------------
#   合计                            约 290 MB
#
#   ★ 不含 PaddleOCR（500MB-1GB）。如需 OCR 容器，另建一个 -ocr 变体镜像。
# ======================================================================================

FROM python:3.13-slim

# 时区设为东八区，保证日志时间与本机一致（使用场景排障看时间戳）
ENV TZ=Asia/Shanghai \
    PYTHONDONTWRITEBYTECODE=1 \
    PYTHONUNBUFFERED=1 \
    PIP_NO_CACHE_DIR=1

WORKDIR /app

# curl 用于容器健康检查
RUN apt-get update \
    && apt-get install -y --no-install-recommends curl tzdata \
    && rm -rf /var/lib/apt/lists/*

# 先装依赖（利用 Docker 层缓存：requirements.txt 不变就不重装）
COPY ai-service/requirements.txt ./requirements.txt
RUN pip install --no-cache-dir -r requirements.txt \
    && pip install --no-cache-dir pytest

# 再拷代码
COPY ai-service/app ./app
COPY ai-service/models ./models
COPY ai-service/pytest.ini ./pytest.ini
COPY ai-service/tests ./tests

# 非 root 运行（安全最佳实践）
RUN useradd -m -u 1000 aiuser && chown -R aiuser:aiuser /app
USER aiuser

EXPOSE 8000

# 容器健康检查：/v1/health 恒 200，能力就绪与否看 body
HEALTHCHECK --interval=30s --timeout=10s --start-period=40s --retries=3 \
    CMD curl -fsS http://127.0.0.1:8000/v1/health || exit 1

# 容器内必须绑 0.0.0.0 才能被端口映射访问；宿主机上请用 HOST=127.0.0.1 运行
CMD ["python", "-m", "uvicorn", "app.main:app", "--host", "0.0.0.0", "--port", "8000"]
