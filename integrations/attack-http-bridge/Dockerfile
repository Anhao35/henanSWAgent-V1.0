FROM python:3.13-slim
ARG MITRE_MCP_COMMIT=9ebd694bfc9869e81d5d1a3d3e7964e31a1e9087
RUN apt-get update && apt-get install -y --no-install-recommends git \
    && rm -rf /var/lib/apt/lists/*
WORKDIR /app
COPY requirements.txt ./
RUN pip install --no-cache-dir -r requirements.txt \
    && pip install --no-cache-dir "git+https://github.com/stoyky/mitre-attack-mcp.git@${MITRE_MCP_COMMIT}"
COPY app ./app
RUN useradd --system --uid 10002 --create-home bridge && mkdir -p /data/mitre && chown -R bridge:bridge /data/mitre
USER 10002
ENV ATTACK_MCP_COMMAND=mitre-attack-mcp \
    ATTACK_MCP_ARGS=--data-dir,/data/mitre \
    DEFAULT_DOMAIN=enterprise \
    REQUEST_TIMEOUT=60
EXPOSE 8011
CMD ["python", "-m", "uvicorn", "app.main:app", "--host", "0.0.0.0", "--port", "8011"]
