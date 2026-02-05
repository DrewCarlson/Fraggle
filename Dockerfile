FROM azul/zulu-openjdk:21-jre-latest

WORKDIR /app

ARG PUID=1000
ARG PGID=1000
ARG USER=fraggle

ENV FRAGGLE_ROOT=/app/data

RUN apt-get update \
    && apt-get install -y \
    # Common
    wget \
    bash \
    && apt-get clean && rm -rf /var/lib/apt/lists/*

RUN addgroup --gid "$PGID" "$USER" \
    && adduser --gecos '' --uid "$PUID" --gid "$PGID" --disabled-password --shell /bin/bash "$USER" \
    && mkdir -p /app/data \
    && chown -R "$USER:$USER" /app

COPY --chown=$USER:$USER fraggle-cli/build/install/fraggle-shadow/ ./

USER $USER

VOLUME [ \
  "/app/data/config", \
  "/app/data/data", \
  "/app/data/logs" \
]

ENTRYPOINT ["./bin/fraggle"]
CMD ["run"]
