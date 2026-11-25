package com.example.bot.service

import java.time.LocalDate
import java.time.format.DateTimeFormatter

/**
 * Статистика энки по дням и разница составов гильдии. Чистая логика без сети и Телеграма.
 *
 * Снимок за дату D — для каждого игрока "base": сколько он сдал за всё время на момент среза D.
 * Сдано за сутки (срез D-1 → срез D) = base(D) - base(D-1).
 */
object TicketStats {

    data class Entry(val playerId: String, val name: String, val base: Long)
    data class Snapshot(val date: LocalDate, val entries: List<Entry>)

    data class PlayerWeek(val name: String, val days: Int, val fullDays: Int, val total: Long) {
        val missed get() = days - fullDays
    }

    // ---------- файл истории: "дата<TAB>playerId<TAB>base<TAB>ник" ----------

    fun toLines(snapshots: List<Snapshot>): List<String> =
        snapshots.sortedBy { it.date }.flatMap { s -> s.entries.map { "${s.date}\t${it.playerId}\t${it.base}\t${it.name}" } }

    fun fromLines(lines: List<String>): List<Snapshot> =
        lines.mapNotNull { line ->
            val p = line.split("\t", limit = 4)
            if (p.size < 4) return@mapNotNull null
            val date = runCatching { LocalDate.parse(p[0]) }.getOrNull() ?: return@mapNotNull null
            val base = p[2].toLongOrNull() ?: return@mapNotNull null
            date to Entry(p[1], p[3], base)
        }.groupBy({ it.first }, { it.second }).map { (d, e) -> Snapshot(d, e) }.sortedBy { it.date }

    // ---------- подсчёт ----------

    /** Итоги по игрокам из последнего снимка за суточные окна, закончившиеся в (from, to]. */
    fun weekly(snapshots: List<Snapshot>, from: LocalDate, to: LocalDate, max: Long): Map<String, PlayerWeek> {
        val byDate = snapshots.associateBy { it.date }
        val latest = snapshots.filter { it.date <= to }.maxByOrNull { it.date } ?: return emptyMap()
        val result = mutableMapOf<String, PlayerWeek>()
        var day = from.plusDays(1)
        while (day <= to) {
            val prev = byDate[day.minusDays(1)]
            val cur = byDate[day]
            if (prev != null && cur != null) {
                val prevBase = prev.entries.associate { it.playerId to it.base }
                for (e in cur.entries) {
                    val before = prevBase[e.playerId] ?: continue // новенький — день вступления не считаем
                    if (e.base < before) continue // счётчик обнулился (ушёл и вернулся) — этот день не считаем
                    val given = (e.base - before).coerceAtMost(max)
                    val w = result[e.playerId] ?: PlayerWeek(e.name, 0, 0, 0)
                    result[e.playerId] = w.copy(days = w.days + 1, fullDays = w.fullDays + if (given >= max) 1 else 0, total = w.total + given)
                }
            }
            day = day.plusDays(1)
        }
        // только те, кто сейчас в гильдии, с актуальным ником
        val current = latest.entries.associate { it.playerId to it.name }
        return result.filterKeys { it in current }.mapValues { (id, w) -> w.copy(name = current.getValue(id)) }
    }

    /** Сколько полных суток (пар соседних срезов) есть за последние [days] дней до последнего среза. */
    fun coveredDays(snapshots: List<Snapshot>, days: Long): Int {
        val dates = snapshots.map { it.date }.toSet()
        val to = dates.maxOrNull() ?: return 0
        return (0 until days).count { i -> to.minusDays(i) in dates && to.minusDays(i + 1) in dates }
    }

    fun weeklyReport(snapshots: List<Snapshot>, to: LocalDate, max: Long, days: Long = 7): String {
        val from = to.minusDays(days)
        val stats = weekly(snapshots, from, to, max)
        val fmt = DateTimeFormatter.ofPattern("dd.MM")
        if (stats.isEmpty()) {
            val first = snapshots.minByOrNull { it.date }?.date
            return "📊 Статистика по энке пока копится" + (first?.let { " (с ${it.format(fmt)})" } ?: "") +
                ". Нужно хотя бы два среза подряд."
        }
        val counted = stats.values.maxOf { it.days }
        val bad = stats.values.filter { it.missed > 0 }.sortedWith(compareByDescending<PlayerWeek> { it.missed }.thenBy { it.total })
        val sb = StringBuilder("📊 Энка за ${from.format(fmt)}–${to.format(fmt)}")
        if (counted < days) sb.append(" (есть данные за $counted дн.)")
        sb.append("\n")
        if (bad.isEmpty()) {
            sb.append("\n🎉 Все сдавали полностью каждый день!")
        } else {
            sb.append("\nНедосдавали:")
            bad.forEach { sb.append("\n${it.name} — ${it.missed} из ${it.days} дн. (сдано ${it.total}/${it.days * max})") }
            sb.append("\n\n✅ Без пропусков: ${stats.size - bad.size} из ${stats.size}")
        }
        return sb.toString()
    }

    // ---------- состав гильдии ----------

    data class RosterDiff(val joined: List<String>, val left: List<String>, val renamed: List<Pair<String, String>>)

    /** old/new: playerId -> ник */
    fun diff(old: Map<String, String>, new: Map<String, String>): RosterDiff = RosterDiff(
        joined = new.filterKeys { it !in old }.values.sortedBy { it.lowercase() },
        left = old.filterKeys { it !in new }.values.sortedBy { it.lowercase() },
        renamed = new.filter { (id, name) -> old[id] != null && old[id] != name }.map { (id, name) -> old.getValue(id) to name },
    )
}
