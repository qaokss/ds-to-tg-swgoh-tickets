FROM eclipse-temurin:17-jdk-alpine

COPY ./creds.txt creds.txt
COPY . /app
WORKDIR /app
RUN ./gradlew build --no-daemon --stacktrace --info
EXPOSE 8080

# Запускаем приложение
CMD ["java", "-jar", "myapp.jar"]


