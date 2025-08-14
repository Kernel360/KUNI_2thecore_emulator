# syntax=docker/dockerfile:1.6

########## BUILD STAGE ##########
FROM gradle:8.4.0-jdk17 AS build
WORKDIR /workspace

# Gradle 캐시/메모리/병렬 제한(에이전트 자원 보호)
ENV GRADLE_USER_HOME=/home/gradle/.gradle \
    GRADLE_OPTS="-Dorg.gradle.workers.max=2 -Dorg.gradle.logging.stacktrace=full" \
    JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75.0 -XX:+UseStringDeduplication"

# 빌드 스크립트/래퍼 먼저 복사(단일 모듈)
COPY settings.gradle build.gradle gradlew gradle/ ./

# 의존성 프리페치(네트워크 이슈 시에도 다음 단계 진행)
RUN chmod +x ./gradlew
RUN --mount=type=cache,target=/home/gradle/.gradle \
    ./gradlew --no-daemon dependencies || true

# 전체 소스 복사 후 실제 빌드
COPY . .
RUN --mount=type=cache,target=/home/gradle/.gradle \
    ./gradlew --no-daemon bootJar -x test --retry 3

########## RUNTIME STAGE ##########
FROM eclipse-temurin:17-jre
ENV TZ=Asia/Seoul
WORKDIR /app

# 보안상 비루트 권장
RUN useradd -ms /bin/bash appuser
COPY --from=build /workspace/build/libs/*.jar app.jar
USER appuser

EXPOSE 8081
ENTRYPOINT ["java","-jar","/app/app.jar"]