# Используем базовый образ OpenJDK
FROM openjdk:17-jdk-slim

WORKDIR /app
COPY target/swgoh-ds-tg-tickets-bot-0.0.1-SNAPSHOT.jar app.jar
EXPOSE 8080


# Указываем команду для запуска приложения
ENTRYPOINT ["java", "-jar", "/app.jar"]