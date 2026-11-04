# Covenant Core (Spring Boot). Build: docker build -t covenant-core core/backend
FROM maven:3.9.9-eclipse-temurin-17 AS build
WORKDIR /src
COPY pom.xml .
RUN mvn -B -q dependency:go-offline
COPY src ./src
RUN mvn -B -q -P fast -DskipTests package

FROM eclipse-temurin:17-jre-jammy
RUN useradd --system --uid 10001 covenant
WORKDIR /app
COPY --from=build /src/target/covenant-service-*.jar app.jar
USER 10001
EXPOSE 9310 9320
# The bank's time zone: the 06:00 daily run, due dates and "today" all follow it (the container clock is UTC).
ENV SPRING_PROFILES_ACTIVE=prod JAVA_OPTS="-XX:MaxRAMPercentage=75" COVENANT_TIME_ZONE=Asia/Kolkata
ENTRYPOINT ["sh", "-c", "exec java -Duser.timezone=$COVENANT_TIME_ZONE $JAVA_OPTS -jar /app/app.jar"]
