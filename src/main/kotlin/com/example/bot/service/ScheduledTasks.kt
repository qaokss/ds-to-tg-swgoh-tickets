package com.example.bot.service

import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Service

@Service
class ScheduledTasks(
    private val discordService: DiscordService,
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
        "28 минут до среза. Срочно сдайте энку",
        "Последнее китайское предупреждение. Время уходит — 28 минут до среза",
        "Сдаём купончики, осталось  28 минут до среза",
        "Напоминаю, что осталось 28 минут до среза купончиков",
        "Ваш вклад важен — сдайте эночку в ближайшие 28 минут",
        "Серьёзно, времени мало. Сдайте эночку до среза",
        "Срочно тратим энку. У вас осталось 28 минут до среза",
        "Внимание-внимание! 28 минут до среза. Не забудьте сдать энку ",
        "Последний шанс потратить энку!",
        "Шутки в сторону, энку на стол!"
    )

    @Scheduled(cron = "0 2 12 * * *")  // Ежедневно в 15:02
    fun sendFirstReminder() {
        println("Погнали 15:02")
        val playersWithLostTickets = discordService.extractPlayerNames()
        println("Будем тегать вот этих товарищей$playersWithLostTickets")
        if (playersWithLostTickets.isNotEmpty()) {
            telegramBotService.sendRaidReminder(firstReminderTxt.random(), playersWithLostTickets)
        }
    }

    @Scheduled(cron = "0 2 13 * * *")  // Ежедневно в 16:02
    fun sendSecondReminder() {
        println("Погнали 16:02")
        val playersWithLostTickets = discordService.extractPlayerNames()
        println("Будем тегать вот этих товарищей$playersWithLostTickets")
        if (playersWithLostTickets.isNotEmpty()) {
            telegramBotService.sendRaidReminder(secondReminderTxt.random(), playersWithLostTickets)
        }
    }
}