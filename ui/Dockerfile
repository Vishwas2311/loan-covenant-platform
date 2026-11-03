# Covenant Platform UI: static build served by nginx, which also proxies /api (Core) and /dps (DPS).
FROM node:24-alpine AS build
WORKDIR /src
COPY package.json package-lock.json ./
RUN npm ci --no-audit --no-fund
COPY . .
RUN npx vite build

FROM nginx:1.27-alpine
ENV NGINX_ENTRYPOINT_LOCAL_RESOLVERS=1 CORE_UPSTREAM=http://core:9310 DPS_UPSTREAM=http://dps-api:9400
RUN rm /etc/nginx/conf.d/default.conf
COPY nginx.conf /etc/nginx/templates/default.conf.template
COPY --from=build /src/dist /usr/share/nginx/html
EXPOSE 8080
