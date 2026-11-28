# syntax=docker/dockerfile:1
# Multi-arch index: linux/amd64 -> 21.0.13.1_2-jdk-jammy (CVE-2026-114938 patched), linux/arm64 -> 21.0.13_9-jdk-jammy
FROM eclipse-temurin:21-jdk-jammy@sha256:b2711c65970c1227197c29c46e669bfdbb137524e9da0e664703a140ec25a040 AS builder
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

# Multi-arch index: linux/amd64 -> 21.0.13.1_2-jre-jammy (CVE-2026-114938 patched), linux/arm64 -> 21.0.13_9-jre-jammy
FROM eclipse-temurin:21-jre-jammy@sha256:a8bff88f760e87e0c8844f8461f807c203fd5e8f78c951c843024647e965eaf5 AS extractor
ARG SERVICE
WORKDIR /extract
COPY --from=builder /build/${SERVICE}/target/*.jar app.jar
RUN java -Djarmode=tools -jar app.jar extract --layers --launcher --destination extracted

FROM eclipse-temurin:21-jre-jammy@sha256:a8bff88f760e87e0c8844f8461f807c203fd5e8f78c951c843024647e965eaf5
ARG SERVICE
WORKDIR /app
ENV MALLOC_ARENA_MAX=2
COPY --from=extractor /extract/extracted/dependencies/ ./
COPY --from=extractor /extract/extracted/spring-boot-loader/ ./
COPY --from=extractor /extract/extracted/snapshot-dependencies/ ./
COPY --from=extractor /extract/extracted/application/ ./
ENTRYPOINT ["java", "-XX:-UseBASE64Intrinsics", "org.springframework.boot.loader.launch.JarLauncher"]
