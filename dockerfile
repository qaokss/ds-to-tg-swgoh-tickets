# Используем базовый образ OpenJDK
FROM openjdk:17-jdk-slim

# Устанавливаем рабочую директорию
WORKDIR /app

# Копируем файл jar
COPY build/libs/your-app-0.0.1-SNAPSHOT.jar /app/app.jar

# Указываем команду для запуска приложения
ENTRYPOINT ["java", "-jar", "/app/app.jar"]