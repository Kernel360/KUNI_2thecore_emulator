FROM eclipse-temurin:17-jre
WORKDIR /app

# JAR과 ENV 함께 복사
COPY release/emulator/app.jar app.jar
COPY release/emulator/prod.env prod.env

EXPOSE 8081
ENTRYPOINT ["java","-jar","app.jar"]
