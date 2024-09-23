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
        val raidMessage = discordService.getRaidMessage()
        if (raidMessage != null) {
            telegramBotService.sendRaidReminder("EliriumPolaris", "Эночку сдаём, господа")
        }
    }

    @Scheduled(cron = "0 2 16 * * *")  // Ежедневно в 16:02
    fun sendSecondReminder() {
        val raidMessage = discordService.getRaidMessage()
        if (raidMessage != null) {
            telegramBotService.sendRaidReminder("EliriumPolaris", "Энку на стол!")
        }
    }
}