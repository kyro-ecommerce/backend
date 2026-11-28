# syntax=docker/dockerfile:1
FROM eclipse-temurin:21-jdk-jammy AS builder
ARG SERVICE
WORKDIR /build

COPY mvnw pom.xml ./
COPY .mvn .mvn
COPY ${SERVICE}/pom.xml ${SERVICE}/pom.xml
COPY reliability reliability
RUN sed -i 's/\r$//' mvnw && chmod +x mvnw
RUN --mount=type=cache,target=/root/.m2 ./mvnw -B -N install && ./mvnw -B -f reliability/pom.xml install -Dmaven.test.skip=true
RUN --mount=type=cache,target=/root/.m2 ./mvnw -B -f ${SERVICE}/pom.xml dependency:go-offline

COPY ${SERVICE}/src ${SERVICE}/src
RUN --mount=type=cache,target=/root/.m2 ./mvnw -B -f ${SERVICE}/pom.xml package -Dmaven.test.skip=true

FROM eclipse-temurin:21-jre-jammy AS extractor
ARG SERVICE
WORKDIR /extract
COPY --from=builder /build/${SERVICE}/target/*.jar app.jar
RUN java -Djarmode=tools -jar app.jar extract --layers --launcher --destination extracted

FROM eclipse-temurin:21-jre-jammy
ARG SERVICE
WORKDIR /app
ENV MALLOC_ARENA_MAX=2
COPY --from=extractor /extract/extracted/dependencies/ ./
COPY --from=extractor /extract/extracted/spring-boot-loader/ ./
COPY --from=extractor /extract/extracted/snapshot-dependencies/ ./
COPY --from=extractor /extract/extracted/application/ ./
ENTRYPOINT ["java", "org.springframework.boot.loader.launch.JarLauncher"]
