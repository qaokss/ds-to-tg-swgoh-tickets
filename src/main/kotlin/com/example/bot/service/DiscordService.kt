package com.example.bot.service

import jakarta.annotation.PostConstruct
import net.dv8tion.jda.api.JDA
import net.dv8tion.jda.api.JDABuilder
import net.dv8tion.jda.api.entities.TextChannel
import org.springframework.stereotype.Service
import javax.security.auth.login.LoginException

@Service
class DiscordService(
    cfgController: ConfigController
) {
    private lateinit var jda: JDA
    private val hotBotChannelId = cfgController.get("hotBotChannelId")
    private val botToken = cfgController.get("discordBotToken")

    @PostConstruct
    fun init() {
        try {
            jda = JDABuilder.createDefault(botToken).build().awaitReady()
        } catch (e: LoginException) {
            e.printStackTrace()
        }
    }

    fun getTickersWarningMessage(): String? {
        val channel: TextChannel = jda.getTextChannelById(hotBotChannelId) ?: return null
        val messages = channel.history.retrievePast(1).complete()
        messages.forEach { println("Full message object: ${it.contentRaw}") }
      //  return messages.first { it.contentRaw.contains("TICKET WARNING") }?.contentRaw
        return messages.first { it.contentRaw.contains("TICKET VIOLATIONS") }?.contentRaw
    }


    fun extractPlayerNames(): List<String> {
        val message = getTickersWarningMessage()
        // Регулярное выражение для поиска имен пользователей перед скобками с очками
        val regex = Regex("""([^\*\n]+)\s\(\*\*\d+\*\*/\d+\)""")

        var pl = mutableListOf<String>()
        // Ищем все совпадения по регулярному выражению
        if (message != null) {
            if (message.isNotEmpty()) {
                val matches = regex.findAll(message)
                // Преобразуем найденные имена игроков в список
                matches.forEach { pl.add(it.groupValues[1].trim()) }
            }
        }
        println("Негодяи с несданной энкой: $pl")
        return pl
    }

}