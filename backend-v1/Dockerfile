FROM node:22-bookworm-slim
RUN apt-get update && apt-get install -y --no-install-recommends ffmpeg ca-certificates && rm -rf /var/lib/apt/lists/*
WORKDIR /app
COPY package*.json ./
RUN npm ci --omit=dev
COPY . .
# Fail the build if the upload omitted the runtime documentation.
COPY docs/app-guide.md docs/app-overview.md ./docs/
ENV NODE_ENV=production DATA_DIR=/data HOST=0.0.0.0
EXPOSE 8080
CMD ["node","server.mjs"]
