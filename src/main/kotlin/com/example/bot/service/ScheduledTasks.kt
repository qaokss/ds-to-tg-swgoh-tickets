package com.example.bot.service

import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Service

@Service
class ScheduledTasks(
    private val discordService: DiscordService,
    private val telegramBotService: TelegramBotService
) {

    val firstReminderTxt = listOf(
        "Эночку на базу, господа!",
        "Энку на стол, не задерживаем!",
        "Ра-два-три, эночка прийди!",
        "Энергия не ждет, сдаем, не задерживаем очередь",
        "Кто не сдал энку, тот редиска",
        "Энергетическая тревога! Срочно сдаем!",
        "Энку на стол, мои чемпионы!",
        "Завтрак чемпиона — энергия!",
        "Эночка на базе ждет тебя",
        "Энергия — это жизнь! Отдаем немедленно!",
        "Время не ждет, а рейд тем более — потратьте эночку",
        "Энка ждет своих героев! На базу!",
        "Делаем мир лучше! Начни с сдачи энергии!",
        "Сдаешь энергию — спасаешь мир!",
        "Первый-первый, я второй, срочно тратим энку, как слышно, приём",
        "Йо, чюваки и чювакессы, шото энки не хватает в нашей копилочке",
        "Энка или жизнь?",
    ).random()

    val secondReinderTxt = listOf(
        "28 минут до среза. Срочно сдайте энку",
        "Энергия не сдана. Время уходит — 28 минут до среза",
        "Пожалуйста, сдайте энергию. Время на исходе — 28 минут до среза",
        "Не забываем про энергию. Осталось всего 28 минут до среза",
        "Напоминаю, что осталось 28 минут для сдачи эночки",
        "Ваш вклад важен — сдайте эночку в ближайшие 28 минут",
        "Серьёзно, времени мало. Сдайте эночку до среза",
        "Энергия нужна сейчас. У вас осталось 28 минут до среза",
        "Внимание! 28 минут до среза. Не забудьте сдать энку ",
        "Да что ж такое твориться то, баатюшки. 28 минут, а кастрюля с энкой ещё не полная",
        "Последний шанс потратить энку! Не подведи!",
        "Шутки в сторону, энку на стол!"
    ).random()

    @Scheduled(cron = "0 2 15 * * *")  // Ежедневно в 15:02
    fun sendFirstReminder() {
        println("Погнали 15:02")
        val playersWithLostTickets = discordService.extractPlayerNames()
        println("Будем тегать вот этих товарищей$playersWithLostTickets")
        if (playersWithLostTickets.isNotEmpty()) {
            telegramBotService.sendRaidReminder(firstReminderTxt, playersWithLostTickets)
        }
    }

    @Scheduled(cron = "0 2 16 * * *")  // Ежедневно в 16:02
    fun sendSecondReminder() {
        println("Погнали 16:02")
        val playersWithLostTickets = discordService.extractPlayerNames()
        println("Будем тегать вот этих товарищей$playersWithLostTickets")
        if (playersWithLostTickets.isNotEmpty()) {
            telegramBotService.sendRaidReminder(secondReinderTxt, playersWithLostTickets)
        }
    }
}