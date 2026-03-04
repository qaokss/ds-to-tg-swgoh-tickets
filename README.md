# ds-to-tg-swgoh-tickets

Установка:
1. Собрать через build
2. Отправить на сервер scp build/libs/swgoh-ds-tg-tickets-bot-0.0.1-SNAPSHOT.jar root@45.150.192.216:/home/
3. На сервере в папке рядом с jar'ником создать докерфайл (nano Dockerfile) и скопировать туда текст из текущего докерфайла
4. Там же создать creds.txt, воткнуть туда креды
5. Собрать образ docker build -t swgoh-app .
6. Стартануть контейнер  docker run -d --name swgoh-app swgoh-app
7. Проверить через docker ps, логи в docker logs swgoh-app