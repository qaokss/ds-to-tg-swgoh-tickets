package com.example.bot.service

/** Чистая логика разбора команд и ников — без Телеграма, чтобы легко тестировать. */
object NickCommands {

    data class Command(val name: String, val args: String)

    /** "/add-me@MyBot Мышь" -> Command("add_me", "Мышь"). Дефис и подчёркивание считаются одним и тем же. */
    fun parseCommand(text: String): Command? {
        val m = Regex("""^/([A-Za-z][A-Za-z0-9_\-]*)(?:@\w+)?(?:\s+(.*))?$""", RegexOption.DOT_MATCHES_ALL)
            .find(text.trim()) ?: return null
        return Command(m.groupValues[1].lowercase().replace("-", "_"), m.groupValues[2].trim())
    }

    /** Приводит ник телеги к виду "@Nick". null — если ник некорректный. */
    fun normalizeTg(raw: String): String? {
        val nick = raw.trim().trimStart('@')
        return if (Regex("""[A-Za-z0-9_]{3,32}""").matches(nick)) "@$nick" else null
    }

    /** "tg:@Nick game:Игровой Ник" (в любом порядке) -> Pair("@Nick", "Игровой Ник"). */
    fun parseAddUser(args: String): Pair<String, String>? {
        val tg = Regex("""(?i)\btg:\s*(@?\w+)""").find(args)?.groupValues?.get(1)?.let { normalizeTg(it) } ?: return null
        val game = Regex("""(?i)\bgame:\s*(.+?)\s*(?=\btg:|$)""").find(args)?.groupValues?.get(1)?.trim()
        if (game.isNullOrEmpty()) return null
        return tg to game
    }

    /**
     * Строка файла ников: "Игровой Ник @Nick" или "Игровой Ник id:123456" (для тех, у кого нет @username).
     * Старый формат "@@Nick" тоже понимаем.
     */
    fun parseNickLine(line: String): Player? {
        val m = Regex("""^(.*\S)\s+(?:@+(\w+)|id:(\d+))\s*$""").find(line.trim()) ?: return null
        val tg = if (m.groupValues[2].isNotEmpty()) "@${m.groupValues[2]}" else "id:${m.groupValues[3]}"
        return Player(m.groupValues[1].trim(), tg)
    }

    // ---------- идентификатор в Телеге: "@nick" или "id:123" ----------

    /** Как бот будет звать человека: по @username, а если его нет — по id. */
    fun tgFor(username: String?, userId: Long): String = username?.let { normalizeTg(it) } ?: "id:$userId"

    fun tgUserId(tg: String): Long? = tg.removePrefix("id:").takeIf { tg.startsWith("id:") }?.toLongOrNull()

    fun sameTg(a: String, b: String) = a.equals(b, ignoreCase = true)

    fun escapeHtml(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

    /** HTML-тег человека: "@nick" или ссылка-упоминание по id с подписью label. */
    fun mentionHtml(tg: String, label: String): String =
        tgUserId(tg)?.let { "<a href=\"tg://user?id=$it\">${escapeHtml(label)}</a>" } ?: escapeHtml(tg)

    fun key(s: String) = s.trim().replace(Regex("""\s+"""), " ").lowercase().replace(Regex("[ёëèéê]"), "е") // е/ё и похожие латинские буквы считаем одинаковыми

    fun sameName(a: String, b: String) = key(a) == key(b)

    sealed class Match {
        data class Found(val name: String) : Match()
        data class NotFound(val suggestions: List<String>) : Match()
    }

    /** Ищет ник среди участников гильдии без учёта регистра и лишних пробелов. */
    fun matchGameName(input: String, members: List<String>): Match {
        members.firstOrNull { sameName(it, input) }?.let { return Match.Found(it) }
        val k = key(input)
        val similar = members.filter { key(it).contains(k) || k.contains(key(it)) }.take(5)
        return Match.NotFound(similar)
    }

    /** Страница списка: (элементы страницы, номер страницы после ограничения, всего страниц). */
    fun <T> page(items: List<T>, page: Int, size: Int): Triple<List<T>, Int, Int> {
        val pages = maxOf(1, (items.size + size - 1) / size)
        val p = page.coerceIn(0, pages - 1)
        return Triple(items.drop(p * size).take(size), p, pages)
    }

    /** Короткий стабильный id для имени (влезает в callback_data). */
    fun nameId(name: String): String = Integer.toHexString(key(name).hashCode())
}
