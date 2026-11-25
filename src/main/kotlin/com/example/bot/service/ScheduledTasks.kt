package com.example.bot.service

import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Service

@Service
class ScheduledTasks(
    private val comlinkService: ComlinkService,
    private val telegramBotService: TelegramBotService
) {

    var firstReminderTxt = mutableListOf<String>(
        "Эночку на базу, господа!",
        "Раз-два-три, эночка прийди!",
        "Рейд не ждет, тратим энку, не задерживаем очередь",
        "Кто не сдаст энку - тот редиска",
        "Энергетическая тревога!",
        "Энку на стол!",
        "Время не ждет, а рейд тем более — потратьте эночку",
        "Энка ждет своих героев! На базу!",
        "Делаем мир лучше! Начни с сдачи энергии!",
        "Тратишь энергию — спасаешь гох!",
        "Первый-первый, я второй, срочно тратим энку, как слышно, приём",
        "Энки не хватает в нашей копилочке",
        "Энка или жизнь?",
    )

    var secondReminderTxt = mutableListOf<String>(
        "30 минут до среза. Срочно сдайте энку",
        "Последнее китайское предупреждение. Время уходит — полчаса до среза",
        "Сдаём купончики, осталось 30 минут до среза",
        "Напоминаю, что осталось полчаса до среза купончиков",
        "Ваш вклад важен — сдайте эночку в ближайшие 30 минут",
        "Серьёзно, времени мало. Сдайте эночку до среза",
        "Срочно тратим энку. У вас осталось 30 минут до среза",
        "Внимание-внимание! Полчаса до среза. Не забудьте сдать энку",
        "Последний шанс потратить энку!",
        "Шутки в сторону, энку на стол!"
    )

    // Срез гильдии в 13:30 UTC. Время в cron — UTC.
    @Scheduled(cron = "0 0 12 * * *", zone = "UTC")  // за 1,5 часа до среза
    fun sendFirstReminder() {
        println("Погнали: 1,5 часа до среза")
        val playersWithLostTickets = comlinkService.extractPlayerNames()
        println("Будем тегать вот этих товарищей$playersWithLostTickets")
        if (playersWithLostTickets.isNotEmpty()) {
            telegramBotService.sendRaidReminder(firstReminderTxt.random(), playersWithLostTickets)
        }
    }

    @Scheduled(cron = "0 0 13 * * *", zone = "UTC")  // за 30 минут до среза
    fun sendSecondReminder() {
        println("Погнали: 30 минут до среза")
        val playersWithLostTickets = comlinkService.extractPlayerNames()
        println("Будем тегать вот этих товарищей$playersWithLostTickets")
        if (playersWithLostTickets.isNotEmpty()) {
            telegramBotService.sendRaidReminder(secondReminderTxt.random(), playersWithLostTickets)
        }
    }
}
