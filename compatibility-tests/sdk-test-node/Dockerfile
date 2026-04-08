FROM node:22-slim
WORKDIR /app

COPY package*.json ./
RUN npm install --no-fund --no-audit

COPY tsconfig.json vitest.config.ts ./
COPY tests/ tests/

ENV FLOCI_ENDPOINT=http://floci:4566
ENV TEST_RESULTS_DIR=/results

RUN mkdir -p /results
ENTRYPOINT ["npm", "test"]
