FROM ibmjava:jre

COPY ./creds.txt creds.txt
RUN ./gradlew build
COPY --from=build /app/target/myapp.jar /app/myapp.jar
EXPOSE 8080

# Запускаем приложение
CMD ["java", "-jar", "myapp.jar"]

# Указываем команду для запуска приложения
ENTRYPOINT ["java", "-jar", "./app.jar"]

