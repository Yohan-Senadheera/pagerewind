# One image definition for every service. The jar is built first with:
#   ./mvnw -DskipTests package
# then, for example:
#   docker build --build-arg SERVICE=incident -t pagerewind-incident .
FROM eclipse-temurin:21-jre-noble

ARG SERVICE
RUN groupadd --system --gid 10001 app \
 && useradd --system --uid 10001 --gid app --no-create-home --shell /usr/sbin/nologin app

WORKDIR /app
COPY services/${SERVICE}/target/${SERVICE}-*.jar /app/app.jar

USER 10001:10001
ENV SERVER_PORT=8080 \
    JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75 -XX:+ExitOnOutOfMemoryError"
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
