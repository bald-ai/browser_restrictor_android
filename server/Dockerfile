FROM node:22-bookworm-slim

RUN apt-get update \
    && apt-get install -y --no-install-recommends ffmpeg ca-certificates \
    && rm -rf /var/lib/apt/lists/*

WORKDIR /app
COPY --chown=node:node package.json ./
COPY --chown=node:node src ./src
COPY --chown=node:node test ./test
USER node
RUN npm test

ENV NODE_ENV=production
ENV VERIFY_HOST=0.0.0.0
CMD ["node", "src/server.mjs"]
