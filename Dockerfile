FROM gradle:8.4.0-jdk17 AS build
WORKDIR /workspace

# 캐시 최적화: Gradle 메타 먼저
COPY settings.gradle build.gradle gradlew gradle/ ./

# gradlew 실행권한 및 의존성 선다운로드(실패해도 계속 진행)
RUN chmod +x ./gradlew && ./gradlew --no-daemon dependencies || true

# 소스 복사 후 빌드
COPY . .
RUN ./gradlew --no-daemon bootJar -x test

FROM eclipse-temurin:17-jre
WORKDIR /app
COPY --from=build /workspace/build/libs/*.jar app.jar
EXPOSE 8081
ENTRYPOINT ["java","-jar","app.jar"]