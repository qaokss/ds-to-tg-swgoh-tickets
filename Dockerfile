FROM eclipse-temurin:17-jdk-alpine

WORKDIR /app
COPY creds.txt creds.txt
COPY swgoh-ds-tg-tickets-bot-0.0.1-SNAPSHOT.jar app.jar
ENTRYPOINT ["java", "-jar", "app.jar"]

