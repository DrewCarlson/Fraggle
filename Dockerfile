FROM azul/zulu-openjdk:21-jre-latest

WORKDIR /app

ARG PUID=1000
ARG PGID=1000
ARG USER=fraggle

ENV FRAGGLE_ROOT=/app/data

RUN addgroup --gid "$PGID" "$USER" \
    && adduser --gecos '' --uid "$PUID" --gid "$PGID" --disabled-password --shell /bin/bash "$USER" \
    && mkdir -p /app/data \
    && chown -R "$USER:$USER" /app

COPY --chown=$USER:$USER app/build/install/fraggle-shadow/ ./

USER $USER

ENTRYPOINT ["./bin/fraggle"]
CMD ["run"]
