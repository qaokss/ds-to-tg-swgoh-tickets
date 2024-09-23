package com.example.bot.service

import jakarta.annotation.PostConstruct
import net.dv8tion.jda.api.JDA
import net.dv8tion.jda.api.JDABuilder
import net.dv8tion.jda.api.entities.Message
import net.dv8tion.jda.api.entities.TextChannel
import okhttp3.internal.filterList
import org.springframework.stereotype.Service
import javax.security.auth.login.LoginException

@Service
class DiscordService {


    private lateinit var jda: JDA

    @PostConstruct
    fun init() {
        try {
            jda = JDABuilder.createDefault("DISCORD_BOT_TOKEN").build().awaitReady()
        } catch (e: LoginException) {
            e.printStackTrace()
        }
    }

    fun getRaidMessage(): String?  {
        val channel: TextChannel = jda.getTextChannelById("DISCORD_CHANNEL_ID") ?: return null
        val messages = channel.history.retrievePast(1).complete()
        return messages.first { it.contentRaw.contains("TICKET WARNING") }?.contentRaw
    }

}