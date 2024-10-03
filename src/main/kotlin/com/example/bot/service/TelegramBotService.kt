package com.example.bot.service

import jakarta.annotation.PostConstruct
import org.springframework.stereotype.Service
import org.telegram.telegrambots.bots.TelegramLongPollingBot
import org.telegram.telegrambots.meta.TelegramBotsApi
import org.telegram.telegrambots.meta.api.methods.send.SendMessage
import org.telegram.telegrambots.meta.api.objects.Update
import org.telegram.telegrambots.meta.exceptions.TelegramApiException
import org.telegram.telegrambots.updatesreceivers.DefaultBotSession
import java.io.File

@Service
class TelegramBotService(cfgController: ConfigController) : TelegramLongPollingBot() {
    val alderaanChatId = cfgController.get("alderaanChatId")
    val chatForTagging = cfgController.get("chatForTagging").toInt()
    val botId = cfgController.get("tgBotId").toLong()
    val nicknamesThread = cfgController.get("nicknamesThread").toInt()
    var allTelegramNicks = mutableListOf<Player>()
    private val nicknamesFilePath = "player_nicks.txt"
    val botName = cfgController.get("tgBotName")
    private val botToken = cfgController.get("tgBotToken")


    override fun getBotUsername(): String = botName
    override fun getBotToken(): String = botToken
    override fun onUpdateReceived(update: Update?) {
        // Проверяем, есть ли сообщение в обновлении
        println("это пришло в апдейте${update?.message?.text}")
        if (update != null && update.hasMessage()) {
            val message = update.message
            // Проверяем, что сообщение пришло из нужного чата
            if (message.chatId == botId ||
                message.chatId == 439358235L || //Компот
                message.chatId == 446455795L || // Саня Марек
                message.messageThreadId == nicknamesThread ||
                message.chatId == 286682568L || //Гуся
                message.chatId == 838835251L // Сержант
            ) {
                // Проверяем, содержит ли сообщение текст
                if (message.hasText()) {
                    val messageText = message.text
                    // Проверяем, есть ли ключевые слова для списка игроков
                    if (messageText.contains("Офицеры по ВГ")) {
                        // Парсим список игроков
                        allTelegramNicks = getActualTgNicks(messageText)
                        savePlayerNicksToFile()
                        println("Список игроков обновлен: $allTelegramNicks")
                    }
                }
            }
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

    // Функция для записи списка в файл
    fun savePlayerNicksToFile() {
        println("Начинаем запись в файлик")
        val file = File(nicknamesFilePath)
        file.printWriter().use { out ->
            allTelegramNicks.forEach { player ->
                out.println("${player.name} ${player.telegramNickname}")
            }
        }
        println("Завершили запись в файлик")
    }

    fun loadPlayerNicksFromFile() {
        println("Начинаем читать из файлика")
        val file = File(nicknamesFilePath)
        if (file.exists()) {
            val lines = file.readLines()
            allTelegramNicks = lines.map { line ->
                val parts = line.split(" ")
                Player(parts[0], parts[1])
            }.toMutableList()
        }
        println("Прочитали из файлика: \n $allTelegramNicks")
    }


    fun getActualTgNicks(message: String): MutableList<Player> {
        // Парсим сообщение
        val regex = Regex("""([^\n]+)\s(@\w+)""")
        val matches = regex.findAll(message)

        // Мапа с именами и никнеймами
        allTelegramNicks = matches.map {
            val (name, nickname) = it.destructured
            Player(name.replace("\n", ""), nickname)
        }.toList().toMutableList()

        // Выводим список игроков
        allTelegramNicks.forEach {
            println("Name: ${it.name}, Telegram: ${it.telegramNickname}")
        }
        return allTelegramNicks
    }

    fun sendRaidReminder(message: String, playersWithLostTickets: List<String>) {
        if (allTelegramNicks.isEmpty()) {
            loadPlayerNicksFromFile()
        }
        println("это все тг ники, что остались в памяти: $allTelegramNicks")
        val playersForTagging = playersWithLostTickets
            .flatMap { pl -> allTelegramNicks.filter { it.name == pl } }

        if (playersForTagging.size > playersForTagging.size) {
            val playersWithoutNicks =
                playersWithLostTickets.filterNot { pl -> playersForTagging.map { it.name }.contains(pl) }
            playersWithoutNicks.forEach { playersForTagging.plus(Player(it, "")) }
        }

        val prettyPlayersForTagging = StringBuilder()
            .append(playersForTagging.map { "\n${it.name} ${it.telegramNickname}" })
        println("Players for tagging: $prettyPlayersForTagging")

        val fullMessage = "$message $prettyPlayersForTagging"
            .replace("[", "")
            .replace("]", "")
        println("Это будем отправлять \"\n$fullMessage \"")
        sendMessageToThread(alderaanChatId, chatForTagging, fullMessage)

    }

    fun sendMessageToThread(chatId: String, threadId: Int, text: String) {
        val message = SendMessage()
        message.chatId = chatId
        message.text = text
        message.messageThreadId = threadId
        try {
            execute(message)
            println("Сообщение успешно отправлено в тред $threadId, \n$text")
        } catch (e: TelegramApiException) {
            e.printStackTrace()
            println("Ошибка при отправке сообщения: ${e.message}")
        }
    }

}

// Список объектов Player
data class Player(val name: String, val telegramNickname: String)