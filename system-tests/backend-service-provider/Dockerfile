FROM eclipse-temurin:17.0.20_8-jdk-alpine-3.23

# Optional JVM arguments, such as memory settings
ARG JVM_ARGS=""

# Install curl, then delete apk cache to save image space
RUN apk update \
    && apk add --no-cache curl \
    && rm -rf /var/cache/apk/*

WORKDIR /app

COPY ./build/libs/backendservice-provider.jar /app

# health status is determined by the availability of the /health endpoint
HEALTHCHECK --interval=5s --timeout=5s --retries=10 CMD curl --fail http://localhost:8080/api/check/health

ENV WEB_HTTP_PORT="8080"
ENV WEB_HTTP_PATH="/api"

# Use "exec" for graceful termination (SIGINT) to reach JVM.
# ARG can not be used in ENTRYPOINT so storing values in ENV variables
ENV JVM_ARGS=$JVM_ARGS
ENTRYPOINT [ "sh", "-c", \
    "exec java $JVM_ARGS -jar backendservice-provider.jar"]