FROM eclipse-temurin:17-jre
WORKDIR /app

COPY release/emulator/app.jar app.jar

EXPOSE 8081
ENTRYPOINT ["java","-jar","app.jar"]