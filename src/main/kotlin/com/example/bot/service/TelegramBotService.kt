package com.example.bot.service

import jakarta.annotation.PostConstruct
import org.springframework.stereotype.Service
import org.telegram.telegrambots.bots.TelegramLongPollingBot
import org.telegram.telegrambots.meta.TelegramBotsApi
import org.telegram.telegrambots.meta.api.methods.groupadministration.GetChat
import org.telegram.telegrambots.meta.api.methods.send.SendMessage
import org.telegram.telegrambots.meta.api.objects.Chat
import org.telegram.telegrambots.meta.api.objects.Update
import org.telegram.telegrambots.meta.exceptions.TelegramApiException
import org.telegram.telegrambots.updatesreceivers.DefaultBotSession

@Service
class TelegramBotService : TelegramLongPollingBot() {

    // Словарь для хранения сообщений по (chatId, messageId)
    private val messagesMap: MutableMap<Pair<String, String>, String> = mutableMapOf()
    val alderaanChatId = "-1001335300126"
    val nicknamesThread = "286395"
    val messageId = "339752"
    val chatForTagging =  229165


    override fun getBotUsername(): String = "AlderaanTicketsBot"
    override fun getBotToken(): String = "<token>"
    override fun onUpdateReceived(update: Update?) {
        if (update?.hasMessage() == true) {
            val messageId = update.message.messageId
            println("Message ID: $messageId")
        }
    }

    @PostConstruct
    fun init() {
        val telegramApi = TelegramBotsApi(DefaultBotSession::class.java)
        try {
            telegramApi.registerBot(this)
        } catch (e: TelegramApiException) {
            e.printStackTrace()
        }
    }

    fun getMessageWithNicks(chatId: String, messageId: String): String? {
        val getChat = GetChat()
        getChat.chatId = chatId
        // Выполнение запроса и получение объекта Chat
        val chat: Chat = execute(getChat)

        // Проверяем, есть ли закрепленное сообщение
        val pinnedMessage = chat.pinnedMessage
        println(pinnedMessage)
        if (pinnedMessage != null) {
            // Извлекаем и выводим текст закрепленного сообщения
            val pinnedMessageText = pinnedMessage.text
            print("Pinned message text: $pinnedMessageText")
        } else {
            println("No pinned message in this chat.")
        }

        return pinnedMessage.text

    }


    fun getActualTgNicks(): List<Player> {
        // Получаем сообщение по его ID
        val message = getMessageWithNicks(alderaanChatId, messageId)

// Парсим сообщение
        val regex = Regex("""([^\n]+)\s(@\w+)""")
        val matches = regex.findAll(message!!)

// Мапа с именами и никнеймами
        val players = matches.map {
            val (name, nickname) = it.destructured
            Player(name.replace("\n", ""), "@$nickname")
        }.toList()

// Выводим список игроков
        players.forEach {
            println("Name: ${it.name}, Telegram: ${it.telegramNickname}")
        }
        return players
    }

    fun sendRaidReminder(message: String, playersWithLostTickets: List<String>) {
        val allTelegramNicks = getActualTgNicks()
        val playersForTagging = playersWithLostTickets
            .flatMap { pl -> allTelegramNicks.filter { it.name == pl } }

        if (playersForTagging.size > playersForTagging.size) {
            val playersWithoutNicks =
                playersWithLostTickets.filterNot { pl -> playersForTagging.map { it.name }.contains(pl) }
            playersWithoutNicks.forEach { playersForTagging.plus(Player(it, "")) }
        }

        val prettyPlayersForTagging = StringBuilder()
            .append(playersForTagging.map { "\n${it.name} ${it.telegramNickname}" })

        val fullMessage = "$message $prettyPlayersForTagging"
            .replace("[", "")
            .replace("]", "")
            .replace("@@", "@")
        sendMessageToThread(alderaanChatId, chatForTagging, fullMessage)

    }

    fun sendMessageToThread(chatId: String, threadId: Int, text: String) {
        val message = SendMessage()
        message.chatId = chatId
        message.text = text
        message.messageThreadId = threadId
        try {
            execute(message)
            println("Сообщение успешно отправлено в тред $threadId")
        } catch (e: TelegramApiException) {
            e.printStackTrace()
            println("Ошибка при отправке сообщения: ${e.message}")
        }

    }

}

// Список объектов Player
data class Player(val name: String, val telegramNickname: String)