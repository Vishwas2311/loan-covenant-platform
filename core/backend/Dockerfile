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
EXPOSE 9310
ENV SPRING_PROFILES_ACTIVE=prod JAVA_OPTS="-XX:MaxRAMPercentage=75"
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/app.jar"]
