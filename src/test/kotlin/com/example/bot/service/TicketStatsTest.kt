package com.example.bot.service

import com.example.bot.service.TicketStats.Entry
import com.example.bot.service.TicketStats.Snapshot
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TicketStatsTest {
    private val d0 = LocalDate.parse("2026-11-02")

    @Test fun weeklyCountsMissedDays() {
        // A сдаёт 600 каждый день; B — 600, 0, 300; C пришёл на второй день
        val snaps = listOf(
            Snapshot(d0, listOf(Entry("a", "Alpha", 1000), Entry("b", "Беta", 5000))),
            Snapshot(d0.plusDays(1), listOf(Entry("a", "Alpha", 1600), Entry("b", "Беta", 5600), Entry("c", "Gamma", 0))),
            Snapshot(d0.plusDays(2), listOf(Entry("a", "Alpha", 2200), Entry("b", "Беta", 5600), Entry("c", "Gamma", 600))),
            Snapshot(d0.plusDays(3), listOf(Entry("a", "Alpha", 2800), Entry("b", "B-new", 5900), Entry("c", "Gamma", 1200))),
        )
        val w = TicketStats.weekly(snaps, d0, d0.plusDays(3), 600)
        assertEquals(TicketStats.PlayerWeek("Alpha", 3, 3, 1800), w["a"])
        assertEquals(TicketStats.PlayerWeek("B-new", 3, 1, 900), w["b"])
        assertEquals(TicketStats.PlayerWeek("Gamma", 2, 2, 1200), w["c"])
        val report = TicketStats.weeklyReport(snaps, d0.plusDays(3), 600)
        println(report)
        assertTrue(report.contains("B-new — 2 из 3 дн. (сдано 900/1800)"))
        assertTrue(report.contains("Без пропусков: 2 из 3"))
        // файл туда-обратно
        assertEquals(snaps, TicketStats.fromLines(TicketStats.toLines(snaps)))
    }

    @Test fun rejoinedPlayerDayIsSkipped() {
        val snaps = listOf(
            Snapshot(d0, listOf(Entry("a", "A", 5000))),
            Snapshot(d0.plusDays(1), listOf(Entry("a", "A", 100))),   // вернулся, счётчик сбросился
            Snapshot(d0.plusDays(2), listOf(Entry("a", "A", 700))),
        )
        assertEquals(TicketStats.PlayerWeek("A", 1, 1, 600), TicketStats.weekly(snaps, d0, d0.plusDays(2), 600)["a"])
    }

    @Test fun rosterDiff() {
        val d = TicketStats.diff(mapOf("1" to "Мышь", "2" to "Ololo", "3" to "Old"), mapOf("1" to "Мышь", "3" to "New", "4" to "Newbie"))
        assertEquals(listOf("Newbie"), d.joined)
        assertEquals(listOf("Ololo"), d.left)
        assertEquals(listOf("Old" to "New"), d.renamed)
    }

    @Test fun coveredDays() {
        val snaps = (0..7L).map { Snapshot(d0.plusDays(it), listOf(Entry("a", "A", it * 600))) }
        assertEquals(7, TicketStats.coveredDays(snaps, 7))
        assertEquals(1, TicketStats.coveredDays(snaps.takeLast(2), 7))
        assertEquals(0, TicketStats.coveredDays(snaps.take(1), 7))
    }

    @Test fun notEnoughData() {
        val r = TicketStats.weeklyReport(listOf(Snapshot(d0, listOf(Entry("a", "A", 1)))), d0, 600)
        assertTrue(r.contains("копится"))
    }
}
