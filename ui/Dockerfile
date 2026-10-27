# Console UI image: static `dist` behind nginx-unprivileged, SPA fallback, port 8080 (ADR-H-IPASS-01 Decision 4A).
# Traefik sends /api /oauth2 /login /logout to the app; everything else lands here. Build ui first:
#   (cd ui && npm ci && npx vite build)   then   docker build -f ui/Dockerfile -t ipass/ui:<short-commit> .   (root context)
FROM nginxinc/nginx-unprivileged:1.30.6-alpine@sha256:f5116ed4606f3eb015dd4a8e28cb9b351476dc0ad4bc6e9f93b19cec130cdf03
COPY deploy/hostinger/ui/default.conf /etc/nginx/conf.d/default.conf
COPY ui/dist/ /usr/share/nginx/html/
EXPOSE 8080
HEALTHCHECK --interval=30s --timeout=5s --retries=3 CMD wget -qO- http://127.0.0.1:8080/healthz | grep -q ok || exit 1
