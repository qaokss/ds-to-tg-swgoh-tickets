package com.example.bot.service

import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.annotation.Lazy
import org.springframework.context.event.EventListener
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Service
import java.io.File
import java.time.LocalDate
import java.time.ZoneOffset

/**
 * Раз в час смотрит гильдию:
 *  - раз за сутки сохраняет снимок "сколько каждый сдал за всё время на момент среза" — из них считается недельная статистика;
 *  - сравнивает состав: новеньких зовёт в /add_me, ушедших убирает из списка ников, смену ника правит.
 */
@Service
class GuildTracker(
    cfg: ConfigController,
    private val comlinkService: ComlinkService,
    @Lazy private val telegramBotService: TelegramBotService,
) {
    private val dataDir = File(cfg.getOrDefault("dataDir", "data")).apply { mkdirs() }
    private val historyFile = File(dataDir, "ticket_history.tsv")
    private val rosterFile = File(dataDir, "guild_members.tsv")
    private val ticketsMax = cfg.getOrDefault("ticketsMax", "600").toLong()
    private val keepDays = 60L

    @EventListener(ApplicationReadyEvent::class)
    fun onStart() = hourly()

    @Scheduled(cron = "0 10 * * * *", zone = "UTC")
    fun hourly() {
        try {
            check()
        } catch (e: Exception) {
            println("Проверка гильдии не удалась: ${e.message}")
        }
    }

    // Понедельник, сразу после среза в 13:30 UTC
    @Scheduled(cron = "0 35 13 * * MON", zone = "UTC")
    fun weeklyPost() {
        try {
            check()
            // автоматически шлём только полную неделю; неполную можно посмотреть через /week
            val days = TicketStats.coveredDays(loadHistory(), 7)
            if (days < 7) {
                println("Неполная неделя ($days из 7 дн.) — недельный отчёт не отправляю")
                return
            }
            telegramBotService.postToGuild(weekReport())
        } catch (e: Exception) {
            println("Недельный отчёт не удался: ${e.message}")
        }
    }

    private var lastDiskAlert = 0L

    /** Если на диске сервера почти не осталось места — раз в сутки пишем офицерам в личку. */
    private fun checkDisk() {
        val freeMb = dataDir.usableSpace / 1024 / 1024
        if (freeMb >= 150) return
        println("Мало места на диске: $freeMb МБ")
        if (System.currentTimeMillis() - lastDiskAlert < 24 * 3600 * 1000L) return
        lastDiskAlert = System.currentTimeMillis()
        telegramBotService.alertAdmins("⚠️ На сервере бота осталось $freeMb МБ места. Если диск заполнится, бот перестанет сохранять данные.")
    }

    @Synchronized
    fun check() {
        checkDisk()
        val guild = comlinkService.getGuildTickets()
        saveSnapshot(guild)
        checkRoster(guild)
    }

    @Synchronized
    fun weekReport(): String {
        val snapshots = loadHistory()
        val to = snapshots.maxOfOrNull { it.date } ?: LocalDate.now(ZoneOffset.UTC)
        return TicketStats.weeklyReport(snapshots, to, ticketsMax)
    }

    // ---------- снимки энки ----------

    private fun saveSnapshot(guild: ComlinkService.GuildTickets) {
        val nextReset = guild.nextReset ?: return println("Нет времени ресета — снимок не сохраняю")
        // дата последнего среза
        val resetDate = nextReset.minusSeconds(86_400).atOffset(ZoneOffset.UTC).toLocalDate()
        val history = loadHistory().toMutableList()
        if (history.any { it.date == resetDate }) return
        history += TicketStats.Snapshot(resetDate, guild.members.map { TicketStats.Entry(it.playerId, it.name, it.base) })
        val kept = history.filter { it.date >= resetDate.minusDays(keepDays) }
        SafeFiles.write(historyFile, TicketStats.toLines(kept).joinToString("\n", postfix = "\n"))
        println("Сохранил снимок энки за $resetDate (${guild.members.size} игроков)")
    }

    private fun loadHistory(): List<TicketStats.Snapshot> =
        if (historyFile.isFile) TicketStats.fromLines(historyFile.readLines()) else emptyList()

    // ---------- состав ----------

    private fun checkRoster(guild: ComlinkService.GuildTickets) {
        val now = guild.members.filter { it.playerId.isNotBlank() }.associate { it.playerId to it.name }
        val old = loadRoster()
        SafeFiles.write(rosterFile, now.entries.joinToString("\n", postfix = "\n") { "${it.key}\t${it.value}" })
        if (old.isEmpty()) {
            println("Запомнил состав гильдии: ${now.size} игроков")
            return
        }
        val diff = TicketStats.diff(old, now)
        if (diff.joined.isEmpty() && diff.left.isEmpty() && diff.renamed.isEmpty()) return
        if (diff.left.size > 10) {
            println("Подозрительно много ушедших (${diff.left.size}) — ничего не делаю: ${diff.left}")
            return
        }
        println("Изменения в гильдии: $diff")
        telegramBotService.applyRosterChanges(diff.left, diff.renamed)

        val sb = StringBuilder()
        if (diff.joined.isNotEmpty()) {
            sb.append("👋 Новенькие в гильдии: ${diff.joined.joinToString(", ")}\n")
            sb.append("Нажмите /add_me и выберите свой ник — тогда бот сможет напоминать вам про энку.")
        }
        if (diff.left.isNotEmpty()) {
            if (sb.isNotEmpty()) sb.append("\n\n")
            sb.append("🚪 Ушли из гильдии: ${diff.left.joinToString(", ")} (убрал из списка ников)")
        }
        if (diff.renamed.isNotEmpty()) {
            if (sb.isNotEmpty()) sb.append("\n\n")
            sb.append("✏️ Сменили ник: ${diff.renamed.joinToString(", ") { "${it.first} → ${it.second}" }} (список обновил)")
        }
        telegramBotService.postRosterNews(sb.toString())
    }

    private fun loadRoster(): Map<String, String> =
        if (!rosterFile.isFile) emptyMap()
        else rosterFile.readLines().mapNotNull { line ->
            val p = line.split("\t", limit = 2)
            if (p.size == 2 && p[0].isNotBlank()) p[0] to p[1] else null
        }.toMap()
}
