package com.example.bot.service

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import org.springframework.stereotype.Service
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.time.Instant

/**
 * Берёт данные гильдии напрямую из игры через swgoh-comlink
 * (https://github.com/swgoh-utils/swgoh-comlink) и сам считает, кто не сдал энку.
 *
 * В ответе /guild у каждого участника есть memberContribution:
 *   type 1 — гильдейские активности, type 2 — рейдовые билеты (энка), type 3 — пожертвования.
 * currentValue — сколько сдано с последнего ресета гильдии (обнуляется в ресет).
 */
@Service
class ComlinkService(cfg: ConfigController) {
    private val baseUrl = cfg.getOrDefault("comlinkUrl", "http://swgoh-comlink:3000").trimEnd('/')
    private val allyCode = cfg.getOrNull("allyCode")?.replace("-", "")
    private var guildId: String? = cfg.getOrNull("guildId")
    private val ticketsMax = cfg.getOrDefault("ticketsMax", "600").toInt()

    private val http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build()
    private val mapper = jacksonObjectMapper()

    /** tickets — сдано с последнего среза; base — сколько всего сдано за всё время на момент последнего среза. */
    /** level — звание в гильдии: 2 участник, 3 офицер, 4 лидер. */
    data class MemberTickets(
        val name: String, val tickets: Long, val playerId: String = "", val base: Long = 0, val level: Int = 2
    )
    data class GuildTickets(val guildName: String, val members: List<MemberTickets>, val nextReset: Instant?)

    private fun post(path: String, payload: Map<String, Any>): JsonNode {
        val body = mapper.writeValueAsString(mapOf("payload" to payload, "enums" to false))
        val request = HttpRequest.newBuilder(URI.create("$baseUrl$path"))
            .header("Content-Type", "application/json")
            .timeout(Duration.ofSeconds(60))
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build()
        val response = http.send(request, HttpResponse.BodyHandlers.ofString())
        if (response.statusCode() != 200) {
            throw IllegalStateException("comlink $path вернул ${response.statusCode()}: ${response.body().take(500)}")
        }
        return mapper.readTree(response.body())
    }

    private fun resolveGuildId(): String {
        guildId?.let { return it }
        val code = allyCode ?: throw IllegalStateException("В creds.txt нужен guildId или allyCode")
        val id = post("/player", mapOf("allyCode" to code)).path("guildId").asText("")
        if (id.isBlank()) throw IllegalStateException("Игрок $code не состоит в гильдии")
        println("guildId по коду союзника $code: $id")
        guildId = id
        return id
    }

    fun getGuildTickets(): GuildTickets {
        val guild = post(
            "/guild",
            mapOf("guildId" to resolveGuildId(), "includeRecentGuildActivityInfo" to true)
        ).path("guild")
        val members = guild.path("member").map { m ->
            val contribution = m.path("memberContribution").firstOrNull { it.path("type").asInt() == 2 }
            val tickets = contribution?.path("currentValue")?.asLong() ?: 0L
            val lifetime = contribution?.path("lifetimeValue")?.asLong() ?: 0L
            MemberTickets(m.path("playerName").asText().trim(), tickets, m.path("playerId").asText(), lifetime - tickets, m.path("memberLevel").asInt(2))
        }
        if (members.isEmpty()) throw IllegalStateException("comlink вернул гильдию без участников")
        // время ресета лежит в guild.nextChallengesRefresh (на всякий случай смотрим и в profile)
        val resetEpoch = guild.path("nextChallengesRefresh").asLong(0).takeIf { it > 0 }
            ?: guild.path("profile").path("nextChallengesRefresh").asLong(0).takeIf { it > 0 }
        val reset = resetEpoch?.let { Instant.ofEpochSecond(it) }
        return GuildTickets(guild.path("profile").path("name").asText(), members, reset)
    }

    /** Все ники участников гильдии, как они записаны в игре. */
    fun memberNames(): List<String> = getGuildTickets().members.map { it.name }

    /** Участники гильдии с playerId — для кнопок. */
    fun members(): List<MemberTickets> = getGuildTickets().members

    /** Ники (как в игре) тех, кто сдал меньше ticketsMax. При ошибке — пустой список, чтобы не тегать всех подряд. */
    fun extractPlayerNames(): List<String> {
        return try {
            val status = getGuildTickets()
            val lazy = status.members.filter { it.tickets < ticketsMax }
            println("Гильдия ${status.guildName}: ${status.members.size} участников, ресет ${status.nextReset}")
            println("Негодяи с несданной энкой: ${lazy.map { "${it.name} (${it.tickets}/$ticketsMax)" }}")
            lazy.map { it.name }
        } catch (e: Exception) {
            println("Не удалось получить данные гильдии из comlink: ${e.message}")
            e.printStackTrace()
            emptyList()
        }
    }

    /** Текстовая сводка для ручной проверки командой /energy. */
    fun statusReport(): String {
        val status = getGuildTickets()
        val lazy = status.members.filter { it.tickets < ticketsMax }.sortedBy { it.tickets }
        val total = status.members.sumOf { it.tickets }
        val sb = StringBuilder(
            "${status.guildName}: сдано $total энки, " +
                "${status.members.size - lazy.size}/${status.members.size} сдали полностью"
        )
        status.nextReset?.let { sb.append("\nРесет: $it (UTC)") }
        if (lazy.isEmpty()) sb.append("\nВсе молодцы!")
        else lazy.forEach { sb.append("\n${it.name}: ${it.tickets}/$ticketsMax") }
        return sb.toString()
    }
}
