FROM eclipse-temurin:17-jre
WORKDIR /app

# Jenkins에서 미리 빌드된 산출물 사용 (이중 빌드 제거)
COPY build/libs/*.jar app.jar

EXPOSE 8081
ENTRYPOINT ["java","-jar","app.jar"]