package com.example.bot.service

import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Service

@Service
class ScheduledTasks(
    private val discordService: DiscordService,
    private val telegramBotService: TelegramBotService
) {

    @Scheduled(cron = "0 2 15 * * *")  // Ежедневно в 15:02
    fun sendFirstReminder() {
        val playersWithLostTickets = discordService.extractPlayerNames()
        if (playersWithLostTickets.isNotEmpty()) {
            telegramBotService.sendRaidReminder( "\nЭночку на базу, господа! \n", playersWithLostTickets)
        }
    }

    @Scheduled(cron = "0 2 16 * * *")  // Ежедневно в 16:02
    fun sendSecondReminder() {
        val playersWithLostTickets = discordService.extractPlayerNames()
        if (playersWithLostTickets.isNotEmpty()) {
            telegramBotService.sendRaidReminder( "Шутки в сторону, энку на стол!", playersWithLostTickets)
        }
    }
}