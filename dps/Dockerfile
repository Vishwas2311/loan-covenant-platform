# Document Processing Service. One image, three roles chosen by the command:
#   API:          uvicorn --factory dps.main:create_app --host 0.0.0.0 --port 9400
#   worker:       python -m dps.worker                       (DPS_WORKER_POOL=STANDARD)
#   heavy worker: python -m dps.worker  with INSTALL_HEAVY=1  (DPS_WORKER_POOL=HEAVY, Docling, CPU torch)
FROM python:3.12-slim
ARG INSTALL_HEAVY=0
ENV PYTHONDONTWRITEBYTECODE=1 PYTHONUNBUFFERED=1 CREWAI_TRACING_ENABLED=false
RUN useradd --system --create-home --uid 10001 dps && apt-get update \
    && apt-get install -y --no-install-recommends libgl1 libglib2.0-0 && rm -rf /var/lib/apt/lists/*
WORKDIR /app
COPY pyproject.toml README.md ./
COPY src ./src
COPY alembic.ini ./
COPY migrations ./migrations
RUN pip install --no-cache-dir ".[worker]" \
    && if [ "$INSTALL_HEAVY" = "1" ]; then          pip install --no-cache-dir --extra-index-url https://download.pytorch.org/whl/cpu ".[heavy]"; fi
# /home/dps/.cache holds the Docling models the heavy worker downloads on first use.
RUN mkdir -p /data/storage /home/dps/.cache && chown -R dps /data /home/dps/.cache
USER 10001
ENV DPS_DATABASE_URL=sqlite:////data/dps.db DPS_STORAGE_DIR=/data/storage
EXPOSE 9400
CMD ["sh", "-c", "alembic upgrade head && exec uvicorn --factory dps.main:create_app --host 0.0.0.0 --port 9400"]
