# Covenexa UI: static build served by nginx, which also proxies /api (Core) and /dps (DPS).
FROM node:24-alpine AS build
WORKDIR /src
COPY package.json package-lock.json ./
RUN npm ci --no-audit --no-fund
COPY . .
RUN npx vite build

# Runs as the unprivileged nginx user (101); the OS packages are upgraded so the image carries the
# latest Alpine security fixes, not only those of the base image's build date.
FROM nginxinc/nginx-unprivileged:1.30-alpine
ENV NGINX_ENTRYPOINT_LOCAL_RESOLVERS=1 CORE_UPSTREAM=http://core:9310 DPS_UPSTREAM=http://dps-api:9400 \
    OIDC_AUTHORITY="" OIDC_CLIENT_ID="" OIDC_SCOPE="openid profile" OIDC_ORIGIN=""
USER root
RUN apk upgrade --no-cache && rm -f /etc/nginx/conf.d/default.conf
COPY nginx.conf /etc/nginx/templates/default.conf.template
COPY --from=build /src/dist /usr/share/nginx/html
USER 101
EXPOSE 8080
