FROM azul/zulu-openjdk:21-jre-latest

WORKDIR /app

ARG PUID=1000
ARG PGID=1000
ARG USER=fraggle

ENV FRAGGLE_ROOT=/app/data

ENV PLAYWRIGHT_SKIP_BROWSER_DOWNLOAD=1
ENV PLAYWRIGHT_SKIP_VALIDATE_HOST_REQUIREMENTS=1

RUN apt-get update \
    && apt-get install -y \
    # Common
    wget \
    bash \
    # Python 3 for skill venvs and script execution
    python3 \
    python3-venv \
    python3-pip \
    && apt-get clean && rm -rf /var/lib/apt/lists/*

RUN addgroup --gid "$PGID" "$USER" \
    && adduser --gecos '' --uid "$PUID" --gid "$PGID" --disabled-password --shell /bin/bash "$USER" \
    && mkdir -p /app/data \
    && chown -R "$USER:$USER" /app

COPY --chown=$USER:$USER fraggle-cli/build/install/fraggle/ ./

USER $USER

VOLUME [ \
  "/app/data/config", \
  "/app/data/data", \
  "/app/data/logs", \
  "/app/data/secrets", \
  "/app/data/venvs" \
]

ENTRYPOINT ["./bin/fraggle"]
CMD ["run"]
