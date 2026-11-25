# swgoh-tickets-bot

Бот сам проверяет, кто в гильдии не сдал энку (рейдовые билеты, меньше 600 за сутки),
и тегает этих людей в Telegram. Данные берутся напрямую из игры через
[swgoh-comlink](https://github.com/swgoh-utils/swgoh-comlink) — Discord и сторонние боты больше не нужны.

## creds.txt

```
tgBotToken = ...
tgBotName = ...
tgBotId = ...
alderaanChatId = ...
chatForTagging = ...
nicknamesThread = ...

# Достаточно одного из двух: код союзника любого члена гильдии ИЛИ guildId
allyCode = 123456789
#guildId = ...

# Необязательно:
#comlinkUrl = http://swgoh-comlink:3000
#ticketsMax = 600
#adminIds = 439358235,838835251
```

`hotBotChannelId` и `discordBotToken` больше не используются, их можно удалить.

## Установка

На сервере всё лежит в `/home`: jar, `Dockerfile`, `creds.txt` и папка `botdata` с данными бота
(привязки ников, история энки, состав гильдии).

1. Собрать: `./gradlew bootJar`
2. Залить jar: `scp build/libs/swgoh-ds-tg-tickets-bot-0.0.1-SNAPSHOT.jar root@45.150.192.216:/home/`
3. Первый раз — сеть и comlink:
   ```
   docker network create swgoh
   docker run -d --name swgoh-comlink --network swgoh --restart always \
     --log-opt max-size=10m --log-opt max-file=2 \
     -e APP_NAME=alderaan-tickets-bot ghcr.io/swgoh-utils/swgoh-comlink:latest
   ```
4. Собрать и (пере)запустить бота:
   ```
   cd /home && docker build -t swgoh-app:new .
   docker rm -f swgoh-app
   docker run -d --name swgoh-app --network swgoh --restart always \
     --log-opt max-size=10m --log-opt max-file=2 \
     -v /home/creds.txt:/app/creds.txt -v /home/botdata:/app/data swgoh-app:new
   ```
5. Проверить: `docker ps`, `docker logs swgoh-app`

`docker-compose.yml` — то же самое для машин, где есть `docker compose`.

## Команды бота

Меню открывается командой `/menu` или кнопкой «/» в чате.

Для всех:
- `/add_me` — бот покажет кнопками список гильдии (ники берутся из игры), жмёшь на себя — твой @username привязан.
  Можно и текстом: `/add_me НикВИгре`.
- `/unlinked` — кто из гильдии ещё не привязан к Телеграму.

Для офицеров:
- `/energy` — кто сколько энки сдал прямо сейчас.
- `/add_user tg:@НикТелеги` — кнопками выбрать, к кому из гильдии привязать. Или сразу `/add_user tg:@НикТелеги game:НикВИгре`.
- `/remove_user` — кнопками убрать человека из списка (или `/remove_user НикВИгре`).
- `/list` — весь список привязок.
- `/export` — выгрузка «ник в игре @телега»: сверху офицеры (по званию в игре), ниже участники.
- `/week` — кто сколько дней недосдавал энку за неделю. Автоматически — по понедельникам после среза, если есть данные за все 7 дней.
- `/news_here` — написать в теме чата, куда слать новости о новеньких и ушедших.
- Старый способ тоже работает: сообщение со словами «участники гильдии» / «офицеры по вг» / «офицерский состав»
  и строками `НикВИгре @telegram` целиком заменяет список.

Офицеры — это Telegram user id из `adminIds` в `creds.txt` (через запятую),
по умолчанию Компот и Сержант. У человека должен быть задан @username, иначе тегнуть его нельзя — бот подскажет.

Если бот в группе с включённым privacy mode — команды и кнопки всё равно работают.
