FROM gradle:8.4.0-jdk17 AS build
WORKDIR /workspace

ENV GRADLE_USER_HOME=/home/gradle/.gradle \
    GRADLE_OPTS="-Dorg.gradle.workers.max=1 -Dorg.gradle.logging.stacktrace=full -Dorg.gradle.vfs.watch=false" \
    JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75.0 -XX:+UseStringDeduplication"

# 래퍼 실행권한/의존성 프리페치
RUN chmod +x ./gradlew
RUN --mount=type=cache,target=/home/gradle/.gradle \
    /bin/sh -c 'set -e; timeout 600s ./gradlew --no-daemon --console=plain --info --stacktrace dependencies || true'

# 소스 복사 후 실제 빌드(타임아웃 + 상세로그)
COPY . .
RUN --mount=type=cache,target=/home/gradle/.gradle \
    /bin/sh -c 'set -e; timeout 900s ./gradlew --no-daemon --console=plain --info --stacktrace bootJar -x test'

FROM eclipse-temurin:17-jre
WORKDIR /app
RUN useradd -ms /bin/bash appuser
COPY --from=build /workspace/build/libs/*.jar app.jar
USER appuser
EXPOSE 8081
ENTRYPOINT ["java","-jar","/app/app.jar"]