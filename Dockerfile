FROM openjdk:17-jdk-slim

COPY ./swgoh-ds-tg-tickets-bot-0.0.1-SNAPSHOT.jar app.jar
COPY ./creds.txt creds.txt
EXPOSE 8080


# Указываем команду для запуска приложения
ENTRYPOINT ["java", "-jar", "./app.jar"]

