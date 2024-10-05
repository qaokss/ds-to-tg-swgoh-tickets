# Используем базовый образ OpenJDK
FROM openjdk:17-jdk-slim

ARG JAR_FILE=build/libs/*.jar
COPY ${JAR_FILE} app.jar
EXPOSE 8080

# Указываем команду для запуска приложения
ENTRYPOINT ["java", "-jar", "/app.jar"]