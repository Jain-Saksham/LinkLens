# syntax=docker/dockerfile:1

# --- Build the static bundle ---
FROM node:22-alpine AS build
WORKDIR /src
COPY package.json package-lock.json ./
RUN npm ci --no-audit --no-fund
COPY . .
# Baked in at build time (Vite inlines import.meta.env). Empty API base = same origin (/api via nginx).
ARG VITE_API_BASE_URL=""
ARG VITE_SHORT_BASE_URL="http://localhost:8080"
ENV VITE_API_BASE_URL=$VITE_API_BASE_URL VITE_SHORT_BASE_URL=$VITE_SHORT_BASE_URL
RUN npm run build

# --- Serve with nginx: SPA fallback + /api reverse proxy ---
FROM nginx:1.27-alpine
# The official image renders /etc/nginx/templates/*.template with envsubst at startup.
COPY nginx.conf.template /etc/nginx/templates/default.conf.template
COPY --from=build /src/dist /usr/share/nginx/html
ENV API_UPSTREAM=http://backend:8080
EXPOSE 80
