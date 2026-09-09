FROM ubuntu:24.04

ARG DEBIAN_FRONTEND=noninteractive
RUN apt-get update \
    && apt-get install -y --no-install-recommends python3 plocate ca-certificates \
    && rm -rf /var/lib/apt/lists/*

WORKDIR /app
COPY nasfind/ /app/nasfind/
COPY docker/entrypoint.py docker/healthcheck.py /app/docker/
COPY LICENSE /app/LICENSE
ENV PYTHONUNBUFFERED=1 PYTHONDONTWRITEBYTECODE=1
LABEL org.opencontainers.image.title="NAS Find" \
      org.opencontainers.image.source="https://github.com/chess99/nas-find" \
      org.opencontainers.image.licenses="AGPL-3.0-only"
EXPOSE 8765
HEALTHCHECK --interval=30s --timeout=5s --start-period=20s --retries=3 \
    CMD ["python3", "/app/docker/healthcheck.py"]
ENTRYPOINT ["python3", "/app/docker/entrypoint.py"]
