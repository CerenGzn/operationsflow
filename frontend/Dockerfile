FROM node:22-alpine AS build
WORKDIR /build
COPY package.json package-lock.json ./
RUN npm ci
COPY index.html tsconfig.json vite.config.ts ./
COPY src/ src/
RUN npm run build

FROM nginxinc/nginx-unprivileged:1.28-alpine AS runtime
COPY nginx.conf /etc/nginx/conf.d/default.conf
COPY --from=build /build/dist/ /usr/share/nginx/html/
USER 101
EXPOSE 8080
HEALTHCHECK --interval=10s --timeout=5s --retries=6 \
  CMD wget -q -O /dev/null http://127.0.0.1:8080/healthz || exit 1
