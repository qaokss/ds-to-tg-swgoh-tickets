package com.example.bot.service

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class NickCommandsTest {
    @Test fun commands() {
        assertEquals(NickCommands.Command("add_me", "Malinovyi Kompot"), NickCommands.parseCommand("/add-me Malinovyi Kompot"))
        assertEquals(NickCommands.Command("add_me", "Мышь"), NickCommands.parseCommand("/add_me@AlderaanBot Мышь"))
        assertEquals(NickCommands.Command("add_me", ""), NickCommands.parseCommand("/add_me"))
        assertNull(NickCommands.parseCommand("просто текст"))
    }
    @Test fun addUser() {
        assertEquals("@mysroman" to "Мышь", NickCommands.parseAddUser("tg:@mysroman game:Мышь"))
        assertEquals("@Yury_Kaval" to "Песнь Пустоты", NickCommands.parseAddUser("game:Песнь Пустоты tg:Yury_Kaval"))
        assertEquals("@Horror_of_the_refrigerator" to "TEЕ Ужас ХоЛоДиЛьНиКа",
            NickCommands.parseAddUser("tg: @Horror_of_the_refrigerator  game: TEЕ Ужас ХоЛоДиЛьНиКа "))
        assertNull(NickCommands.parseAddUser("tg:@mysroman"))
    }
    @Test fun fileLines() {
        assertEquals(Player("Sergant", "@NSergey8710"), NickCommands.parseNickLine("Sergant @@NSergey8710"))
        assertEquals(Player("Malinovyi Kompot", "@piupiumurmur"), NickCommands.parseNickLine("Malinovyi Kompot @piupiumurmur"))
        assertEquals(Player("乃丹乚仨乂", "@balexul"), NickCommands.parseNickLine("乃丹乚仨乂 @@balexul"))
        assertEquals(Player("Скиф 30", "id:123456789"), NickCommands.parseNickLine("Скиф 30 id:123456789"))
        assertEquals("id:42", NickCommands.tgFor(null, 42))
        assertEquals("@Nick", NickCommands.tgFor("Nick", 42))
        assertEquals("""<a href="tg://user?id=42">A&lt;B</a>""", NickCommands.mentionHtml("id:42", "A<B"))
        assertEquals("@Nick", NickCommands.mentionHtml("@Nick", "x"))
    }
    @Test fun matching() {
        val members = listOf("Malinovyi Kompot", "Мышь", "Скиф 30")
        assertEquals(NickCommands.Match.Found("Malinovyi Kompot"), NickCommands.matchGameName("malinovyi  kompot", members))
        assertEquals(NickCommands.Match.Found("ГусëнаБум"), NickCommands.matchGameName("ГусенаБум", listOf("ГусëнаБум")))
        assertEquals(NickCommands.Match.NotFound(listOf("Скиф 30")), NickCommands.matchGameName("скиф", members))
        val (items, p, pages) = NickCommands.page((1..49).toList(), 5, 20)
        assertEquals(Triple((41..49).toList(), 2, 3), Triple(items, p, pages))
    }
}
