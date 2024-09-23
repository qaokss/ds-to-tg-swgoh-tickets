package com.example.bot.service

import jakarta.annotation.PostConstruct
import org.springframework.stereotype.Service
import org.telegram.telegrambots.bots.TelegramLongPollingBot
import org.telegram.telegrambots.meta.TelegramBotsApi
import org.telegram.telegrambots.meta.api.methods.send.SendMessage
import org.telegram.telegrambots.meta.exceptions.TelegramApiException
import org.telegram.telegrambots.updatesreceivers.DefaultBotSession

@Service
class TelegramBotService : TelegramLongPollingBot() {

    private val playerToTelegramMap = mapOf(
        "EliriumPolaris" to "@piupiumurmur",

    )

    override fun getBotUsername(): String = "YourTelegramBot"
    override fun getBotToken(): String = "TELEGRAM_BOT_TOKEN"

    @PostConstruct
    fun init() {
        val telegramApi = TelegramBotsApi(DefaultBotSession::class.java)
        try {
            telegramApi.registerBot(this)
        } catch (e: TelegramApiException) {
            e.printStackTrace()
        }
    }

    fun sendRaidReminder(playerName: String, message: String) {
        val chatId = "TELEGRAM_CHAT_ID"
        val telegramNick = playerToTelegramMap[playerName] ?: playerName
        val fullMessage = "$message @$telegramNick"
        val sendMessage = SendMessage(chatId, fullMessage)

        try {
            execute(sendMessage)
        } catch (e: TelegramApiException) {
            e.printStackTrace()
        }
    }

    override fun onUpdateReceived(update: org.telegram.telegrambots.meta.api.objects.Update?) {
        // Не используем
    }
}