package com.example.bot.service

import jakarta.annotation.PostConstruct
import org.springframework.context.annotation.Lazy
import org.springframework.stereotype.Service
import org.telegram.telegrambots.bots.TelegramLongPollingBot
import org.telegram.telegrambots.meta.TelegramBotsApi
import org.telegram.telegrambots.meta.api.methods.AnswerCallbackQuery
import org.telegram.telegrambots.meta.api.methods.commands.SetMyCommands
import org.telegram.telegrambots.meta.api.methods.groupadministration.GetChatAdministrators
import org.telegram.telegrambots.meta.api.methods.send.SendMessage
import org.telegram.telegrambots.meta.api.methods.updatingmessages.DeleteMessage
import org.telegram.telegrambots.meta.api.methods.updatingmessages.EditMessageText
import org.telegram.telegrambots.meta.api.objects.CallbackQuery
import org.telegram.telegrambots.meta.api.objects.Message
import org.telegram.telegrambots.meta.api.objects.Update
import org.telegram.telegrambots.meta.api.objects.User
import org.telegram.telegrambots.meta.api.objects.commands.BotCommand
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardButton
import org.telegram.telegrambots.meta.exceptions.TelegramApiException
import org.telegram.telegrambots.updatesreceivers.DefaultBotSession
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

@Service
class TelegramBotService(
    cfgController: ConfigController,
    private val comlinkService: ComlinkService,
    @Lazy private val guildTracker: GuildTracker,
) : TelegramLongPollingBot() {
    val alderaanChatId = cfgController.get("alderaanChatId")
    val chatForTagging = cfgController.get("chatForTagging").toInt()
    val botId = cfgController.get("tgBotId").toLong()
    val nicknamesThread = cfgController.get("nicknamesThread").toInt()
    var allTelegramNicks = mutableListOf<Player>()
    // все данные бота — в папке data (на сервере это /home/botdata)
    private val nicknamesFilePath = "data/player_nicks.txt"

    // Все, кого бот видел в чатах (Telegram не даёт получить список участников группы): id -> username
    private val seenUsersFilePath = "data/seen_users.txt"

    // Куда писать о новеньких/ушедших: null — в тему с напоминаниями, "general" — в общую тему, иначе id темы
    private val settingsFile = File("data/settings.txt")
    @Volatile private var rosterThread: String? = null
    private data class SeenUser(val username: String?, val firstName: String) {
        fun tg(id: Long) = NickCommands.tgFor(username, id)
        fun label() = username?.let { "@$it" } ?: "👤 $firstName"
    }
    private val seenUsers = mutableMapOf<Long, SeenUser>()

    /** Как показать привязку в списках (без @, чтобы никого не тегать). */
    /**
     * Выгрузка в формате прежнего списка: сверху офицеры (по званию в игре), ниже участники.
     * Строки "Ник @username" — такой текст можно прислать боту обратно, он обновит список.
     */
    private fun exportText(): String {
        val members = try {
            comlinkService.members()
        } catch (e: Exception) {
            return "Не удалось получить список гильдии: ${e.message}"
        }
        val nicks = synchronized(this) { ensureLoaded(); allTelegramNicks.toList() }
        fun line(m: ComlinkService.MemberTickets): String {
            val tg = nicks.firstOrNull { NickCommands.sameName(it.name, m.name) }?.telegramNickname
            return when {
                tg == null -> "${m.name} — не привязан"
                NickCommands.tgUserId(tg) != null -> "${m.name} — ${tgDisplay(tg)}"
                else -> "${m.name} $tg"
            }
        }
        // как в прежнем списке: сначала кириллица, потом латиница
        val sortKey = { m: ComlinkService.MemberTickets ->
            (if ((m.name.firstOrNull()?.code ?: 0) < 128) "1" else "0") + m.name.lowercase()
        }
        val officers = members.filter { it.level >= 3 }
            .sortedWith(compareByDescending<ComlinkService.MemberTickets> { it.level }.thenBy(sortKey))
        val rest = members.filter { it.level < 3 }.sortedBy(sortKey)
        return "🔹Офицерский состав:\n" + officers.joinToString("\n") { line(it) } +
            "\n\n🔹Участники гильдии:\n" + rest.joinToString("\n") { line(it) }
    }

    private fun tgDisplay(tg: String): String =
        NickCommands.tgUserId(tg)?.let { id -> "без ника (${seenUsers[id]?.firstName ?: "id $id"})" } ?: tg.removePrefix("@")
    val botName = cfgController.get("tgBotName")
    private val botToken = cfgController.get("tgBotToken")

    // Кто может пользоваться офицерскими командами (Telegram user id через запятую)
    private val adminIds: Set<Long> = cfgController.getOrDefault("adminIds", "439358235,838835251") // Компот, Сержант
        .split(",").mapNotNull { it.trim().toLongOrNull() }.toSet()

    private val menuText = """
        🤖 Бот следит за энкой гильдии и тегает тех, кто не сдал.

        🙋 «Добавить себя» — выбери свой ник из списка гильдии, и бот запомнит твой Телеграм.

        Для офицеров:
        /add_user — выбрать кнопками ник в игре, потом человека в Телеге
        /add_user tg:@НикТелеги — выбрать, к кому из гильдии его привязать
        /add_user tg:@НикТелеги game:НикВИгре — то же самое текстом
        /remove_user — убрать кого-то из списка
    """.trimIndent()

    override fun getBotUsername(): String = botName
    override fun getBotToken(): String = botToken

    override fun onUpdateReceived(update: Update?) {
        if (update == null) return
        if (update.hasCallbackQuery()) {
            try {
                onCallback(update.callbackQuery)
            } catch (e: Exception) {
                e.printStackTrace()
                answer(update.callbackQuery, "Ошибка: ${e.message}", alert = true)
            }
            return
        }
        // Проверяем, есть ли сообщение в обновлении
        println("это пришло в апдейте: ${update.message?.text}")
        update.message?.let { m -> rememberUsers(listOfNotNull(m.from) + (m.newChatMembers ?: emptyList())) }
        if (!update.hasMessage() || !update.message.hasText()) return
        val message = update.message
        val messageText = message.text
        val isAdmin = isAdmin(message.chatId, message.from?.id)

        val command = NickCommands.parseCommand(messageText)
        if (command != null) {
            handleCommand(message, command, isAdmin)
            return
        }

        // Старый способ: офицер присылает весь список целиком
        if (isAdmin) {
            if (messageText.lowercase().contains("офицеры по вг") ||
                messageText.lowercase().contains("участники гильдии") ||
                messageText.lowercase().contains("офицерский состав")
            ) {
                synchronized(this) {
                    allTelegramNicks = getActualTgNicks(messageText)
                    savePlayerNicksToFile()
                }
                println("Список игроков обновлен: $allTelegramNicks")
                reply(message, "📋 Список ников обновлён: ${allTelegramNicks.size} чел.")
            }
        }
    }

    private fun isAdmin(chatId: Long?, userId: Long?) = chatId == botId || userId in adminIds

    // ---------- текстовые команды ----------

    private fun handleCommand(message: Message, command: NickCommands.Command, isAdmin: Boolean) {
        when (command.name) {
            "start", "help", "menu" -> reply(message, menuText, mainMenu())
            "add_me", "addme" ->
                if (command.args.isBlank()) {
                    // саму команду в чате гильдии убираем через минуту, чтобы не засорять
                    if (isGroup(message)) deleteLater(message.chatId, message.messageId, TEMP_SECONDS)
                    sendSelfPicker(message, message.from)
                } else reply(message, linkByText(message.from, command.args))
            "add_user", "adduser" -> when {
                !isAdmin -> replyTemp(message, ONLY_OFFICERS)
                else -> {
                    val full = NickCommands.parseAddUser(command.args)
                    val tgOnly = Regex("""(?i)\btg:\s*(@?\w+)""").find(command.args)
                        ?.groupValues?.get(1)?.let { NickCommands.normalizeTg(it) }
                    when {
                        full != null -> reply(message, addPlayerByName(full.second, full.first, byOfficer = true))
                        tgOnly != null -> sendMemberPicker(message, "ua:${tgOnly.removePrefix("@")}", 0)
                        command.args.isBlank() -> sendMemberPicker(message, "ag", 0)
                        else -> reply(message, "Напиши так: /add_user — и выбери кнопками\nили /add_user tg:@НикТелеги game:НикВИгре")
                    }
                }
            }
            "remove_user", "removeuser" -> when {
                !isAdmin -> replyTemp(message, ONLY_OFFICERS)
                command.args.isBlank() -> reply(message, "🗑 Кого убрать из списка?", removeKeyboard(0))
                else -> reply(message, removeByText(command.args))
            }
            "list" -> if (isAdmin) reply(message, listText()) else replyTemp(message, ONLY_OFFICERS)
            "export", "roster" -> if (isAdmin) reply(message, exportText()) else replyTemp(message, ONLY_OFFICERS)
            "energy" -> if (isAdmin) reply(message, energyReport()) else replyTemp(message, ONLY_OFFICERS)
            "week" -> if (isAdmin) reply(message, weekReport()) else replyTemp(message, ONLY_OFFICERS)
            "news_here", "newshere" -> when {
                !isAdmin -> replyTemp(message, ONLY_OFFICERS)
                message.chatId.toString() != alderaanChatId -> replyTemp(message, "Эту команду нужно писать в нужной теме чата гильдии")
                else -> {
                    rosterThread = if (message.isTopicMessage()) message.messageThreadId.toString() else "general"
                    try {
                        SafeFiles.write(settingsFile, "rosterThreadId=$rosterThread\n")
                        replyTemp(message, "👍 Буду писать сюда о новеньких и ушедших из гильдии")
                    } catch (e: Exception) {
                        replyTemp(message, "Не смог сохранить настройку: ${e.message}")
                    }
                }
            }
            "unlinked" -> reply(message, unlinkedText())
            // чужие команды — молчим
        }
    }

    // ---------- кнопки ----------

    private fun mainMenu() = keyboard(
        listOf(button("🙋 Добавить себя", "m:me")),
        listOf(button("⚡ Энка сейчас", "m:energy"), button("📊 За неделю", "m:week")),
        listOf(button("👻 Кто без Телеги", "m:unl")),
        listOf(button("➕ Привязать (офицеры)", "m:ag")),
        listOf(button("📋 Список", "m:list"), button("📤 Выгрузка", "m:exp")),
        listOf(button("🗑 Убрать", "rp:0")),
    )

    /**
     * Клавиатура с участниками гильдии.
     * mode = "me" — нажавший привязывает себя; mode = "ua:<tg>" — офицер привязывает @tg к выбранному.
     */
    private fun memberKeyboard(mode: String, page: Int): InlineKeyboardMarkup {
        val linked = synchronized(this) { ensureLoaded(); allTelegramNicks.map { NickCommands.key(it.name) }.toSet() }
        // в /add_me показываем только свободные ники; офицеру — всех (✅ = уже привязан, можно перепривязать)
        val members = comlinkService.members()
            .filter { (mode != "me" && mode != "ag") || NickCommands.key(it.name) !in linked }
            .sortedBy { it.name.lowercase() }
        val (items, p, pages) = NickCommands.page(members, page, PAGE_SIZE)
        val pick = "$mode:"
        val rows = items.map { m ->
            val mark = if (NickCommands.key(m.name) in linked) "✅ " else ""
            button(mark + m.name, "$pick${m.playerId}")
        }.chunked(2).toMutableList()
        rows += navRow(p, pages) {
            when (mode) {
                "me" -> "mp:$it"
                "ag" -> "agp:$it"
                else -> "up:$it:${mode.removePrefix("ua:")}"
            }
        }
        return InlineKeyboardMarkup(rows)
    }

    /** Непривязанные @username из тех, кого бот видел в чатах. */
    /** Непривязанные люди из тех, кого бот видел в чатах: (tg-идентификатор, подпись кнопки). */
    private fun unlinkedTgUsers(): List<Pair<String, String>> = synchronized(this) {
        ensureLoaded()
        val linked = allTelegramNicks.map { it.telegramNickname.lowercase() }.toSet()
        seenUsers.map { (id, u) -> u.tg(id) to u.label() }
            .distinctBy { it.first.lowercase() }
            .filter { it.first.lowercase() !in linked }
            .sortedBy { it.second.removePrefix("👤 ").removePrefix("@").lowercase() }
    }

    /** Шаг 2 офицерской привязки: выбрать @username для игрока pid. */
    private fun tgKeyboard(pid: String, page: Int): InlineKeyboardMarkup {
        val (items, p, pages) = NickCommands.page(unlinkedTgUsers(), page, PAGE_SIZE)
        // в callback: "at:<pid>:<username>" или "at:<pid>:#<id>"
        val rows = items.map { (tg, label) ->
            button(label, "at:$pid:" + (NickCommands.tgUserId(tg)?.let { "#$it" } ?: tg.removePrefix("@")))
        }.chunked(2).toMutableList()
        rows += listOf(button("✍️ Нет в списке", "atm:$pid"))
        rows += navRow(p, pages) { "atp:$it:$pid" }
        return InlineKeyboardMarkup(rows)
    }

    private fun removeKeyboard(page: Int): InlineKeyboardMarkup {
        val nicks = synchronized(this) { ensureLoaded(); allTelegramNicks.sortedBy { it.name.lowercase() } }
        val (items, p, pages) = NickCommands.page(nicks, page, PAGE_SIZE)
        val rows = items.map { button("❌ ${it.name}", "rm:${NickCommands.nameId(it.name)}") }.chunked(2).toMutableList()
        rows += navRow(p, pages) { "rp:$it" }
        return InlineKeyboardMarkup(rows)
    }

    private fun navRow(p: Int, pages: Int, cb: (Int) -> String): List<List<InlineKeyboardButton>> {
        val nav = mutableListOf<InlineKeyboardButton>()
        if (p > 0) nav += button("◀️", cb(p - 1))
        if (pages > 1) nav += button("${p + 1}/$pages", "noop")
        if (p < pages - 1) nav += button("▶️", cb(p + 1))
        return listOfNotNull(nav.takeIf { it.isNotEmpty() }, listOf(button("✖️ Закрыть", "close")))
    }

    /**
     * «Добавить себя»: кто уже привязан — получает «ты уже привязан» (и всё равно может выбрать твинка),
     * если свободных ников нет — ничего лишнего в чат. Из кнопки ответы идут всплывашкой только нажавшему.
     */
    private fun sendSelfPicker(message: Message, from: User?, cq: CallbackQuery? = null) {
        val mine = from?.let { linkedNamesOf(it) } ?: emptyList()
        val hasFree = try {
            memberKeyboard("me", 0).keyboard.flatten().any { it.callbackData.startsWith("me:") }
        } catch (e: Exception) {
            val err = "Не удалось получить список гильдии: ${e.message}"
            if (cq != null) answer(cq, err, alert = true) else replyTemp(message, err)
            return
        }
        val already = if (mine.isNotEmpty()) "✅ Ты уже привязан: ${mine.joinToString(", ")}" else null
        if (!hasFree) {
            val text = already ?: "Все ники гильдии уже заняты. Если твой занят по ошибке — напиши офицеру."
            if (cq != null) answer(cq, text, alert = true) else replyTemp(message, text)
            return
        }
        cq?.let { answer(it, already) }
        val header = already?.let { "$it\nЕсли есть ещё аккаунт — выбери его:" }
        val sent = sendMemberPicker(message, "me", 0, header)
        if (sent != null && isGroup(message)) deleteLater(sent.chatId, sent.messageId, PICKER_SECONDS)
    }

    private fun linkedNamesOf(user: User): List<String> = synchronized(this) {
        ensureLoaded()
        val ids = listOf(NickCommands.tgFor(user.userName, user.id), "id:${user.id}")
        allTelegramNicks.filter { p -> ids.any { NickCommands.sameTg(p.telegramNickname, it) } }.map { it.name }
    }

    private fun sendMemberPicker(message: Message, mode: String, page: Int, header: String? = null): Message? {
        val title = header ?: when (mode) {
            "me" -> "🙋 Найди себя в списке и нажми на свой ник:"
            "ag" -> "➕ Кого привязать? Свободные ники в игре:"
            else -> "👤 К кому привязать @${mode.removePrefix("ua:")}?"
        }
        val kb = try {
            memberKeyboard(mode, page)
        } catch (e: Exception) {
            replyTemp(message, "Не удалось получить список гильдии: ${e.message}\nМожно текстом: /add_me НикВИгре")
            return null
        }
        if (kb.keyboard.flatten().none { it.callbackData.startsWith("$mode:") }) {
            replyTemp(message, if (mode == "ag") "🎉 Все ники гильдии уже привязаны. Перепривязать: /add_user tg:@НикТелеги"
                else "Все ники гильдии уже заняты. Если твой занят по ошибке — напиши офицеру.")
            return null
        }
        return send(message.chatId.toString(), threadOf(message), title, kb)
    }

    private fun onCallback(cq: CallbackQuery) {
        val data = cq.data ?: return
        val msg = cq.message
        val admin = isAdmin(null, cq.from.id) || msg?.chatId == botId
        val parts = data.split(":")
        when (parts[0]) {
            "noop" -> answer(cq)
            "close" -> { edit(msg, "👌", null); answer(cq) }

            // главное меню
            "m" -> {
                val item = parts.getOrNull(1)
                if (item in setOf("ag", "energy", "week", "list", "exp") && !admin) return answer(cq, ONLY_OFFICERS, alert = true)
                if (item == "me") {
                    msg?.let { sendSelfPicker(it, cq.from, cq) } ?: answer(cq)
                    return
                }
                answer(cq)
                when (item) {
                    "ag" -> msg?.let { sendMemberPicker(it, "ag", 0) }
                    "energy" -> msg?.let { send(it.chatId.toString(), threadOf(it), energyReport()) }
                    "week" -> msg?.let { send(it.chatId.toString(), threadOf(it), weekReport()) }
                    "list" -> msg?.let { send(it.chatId.toString(), threadOf(it), listText()) }
                    "exp" -> msg?.let { send(it.chatId.toString(), threadOf(it), exportText()) }
                    "unl" -> msg?.let { send(it.chatId.toString(), threadOf(it), unlinkedText()) }
                }
            }

            // привязать себя
            "mp" -> { editMarkup(msg, memberKeyboard("me", parts[1].toInt())); answer(cq) }
            "me" -> {
                rememberUsers(listOf(cq.from))
                val member = comlinkService.members().firstOrNull { it.playerId == parts[1] }
                    ?: return answer(cq, "Этого игрока уже нет в гильдии", alert = true)
                val result = savePlayer(member.name, NickCommands.tgFor(cq.from.userName, cq.from.id), byOfficer = false)
                if (!result.ok) return answer(cq, result.text, alert = true)
                answer(cq, result.text, alert = false)
                // публично, чтобы все видели, кто к кому привязался
                msg?.let { send(it.chatId.toString(), threadOf(it), "✅ ${result.text}") }
                editMarkup(msg, memberKeyboard("me", currentPage(msg)))
            }

            // офицер привязывает кого-то: ua:<tg>:<playerId>, листание up:<page>:<tg>
            "up" -> { editMarkup(msg, memberKeyboard("ua:${parts[2]}", parts[1].toInt())); answer(cq) }
            "ua" -> {
                if (!admin) return answer(cq, ONLY_OFFICERS, alert = true)
                val member = comlinkService.members().firstOrNull { it.playerId == parts[2] }
                    ?: return answer(cq, "Этого игрока уже нет в гильдии", alert = true)
                val result = savePlayer(member.name, "@${parts[1]}", byOfficer = true)
                edit(msg, "✅ ${result.text}", null)
                answer(cq)
            }

            // офицер: шаг 1 — ник в игре (ag:<pid>), шаг 2 — @username (at:<pid>:<username>)
            "agp" -> {
                if (!admin) return answer(cq, ONLY_OFFICERS, alert = true)
                editMarkup(msg, memberKeyboard("ag", parts[1].toInt())); answer(cq)
            }
            "ag" -> {
                if (!admin) return answer(cq, ONLY_OFFICERS, alert = true)
                val member = comlinkService.members().firstOrNull { it.playerId == parts[1] }
                    ?: return answer(cq, "Этого игрока уже нет в гильдии", alert = true)
                val text = if (unlinkedTgUsers().isEmpty())
                    "👤 ${member.name} — кто это в Телеге?\nСвободных людей пока нет: бот запоминает тех, кто пишет в чат."
                else "👤 ${member.name} — кто это в Телеге?"
                edit(msg, text, tgKeyboard(member.playerId, 0))
                answer(cq)
            }
            "atp" -> {
                if (!admin) return answer(cq, ONLY_OFFICERS, alert = true)
                editMarkup(msg, tgKeyboard(parts[2], parts[1].toInt())); answer(cq)
            }
            "atm" -> {
                if (!admin) return answer(cq, ONLY_OFFICERS, alert = true)
                val name = comlinkService.members().firstOrNull { it.playerId == parts[1] }?.name ?: "НикВИгре"
                edit(msg, "✍️ Напиши:\n/add_user tg:@НикТелеги game:$name", null)
                answer(cq)
            }
            "at" -> {
                if (!admin) return answer(cq, ONLY_OFFICERS, alert = true)
                val member = comlinkService.members().firstOrNull { it.playerId == parts[1] }
                    ?: return answer(cq, "Этого игрока уже нет в гильдии", alert = true)
                val tg = if (parts[2].startsWith("#")) "id:${parts[2].removePrefix("#")}" else "@${parts[2]}"
                val result = savePlayer(member.name, tg, byOfficer = true)
                edit(msg, "✅ ${result.text}", null)
                answer(cq)
            }

            // удаление
            "rp" -> {
                if (!admin) return answer(cq, ONLY_OFFICERS, alert = true)
                val page = parts[1].toInt()
                if (msg != null && msg.text?.startsWith("🗑") == true) editMarkup(msg, removeKeyboard(page))
                else msg?.let { send(it.chatId.toString(), threadOf(it), "🗑 Кого убрать из списка?", removeKeyboard(0)) }
                answer(cq)
            }
            "rm" -> {
                if (!admin) return answer(cq, ONLY_OFFICERS, alert = true)
                val removed = synchronized(this) {
                    ensureLoaded()
                    val r = allTelegramNicks.filter { NickCommands.nameId(it.name) == parts[1] }
                    allTelegramNicks.removeAll(r)
                    if (r.isNotEmpty()) savePlayerNicksToFile()
                    r
                }
                answer(cq, if (removed.isEmpty()) "Уже удалён" else "Удалил: ${removed.joinToString { it.name }}")
                editMarkup(msg, removeKeyboard(currentPage(msg)))
            }
            else -> answer(cq)
        }
    }

    /** Номер текущей страницы из кнопки вида "2/3" в клавиатуре сообщения. */
    private fun currentPage(msg: Message?): Int =
        msg?.replyMarkup?.keyboard?.flatten()?.firstOrNull { it.callbackData == "noop" }
            ?.text?.substringBefore("/")?.toIntOrNull()?.minus(1) ?: 0

    // ---------- логика списка ----------

    private fun linkByText(from: User?, gameInput: String): String {
        from ?: return "Не понял, кто пишет"
        return addPlayerByName(gameInput, NickCommands.tgFor(from.userName, from.id), byOfficer = false)
    }

    private fun addPlayerByName(gameInput: String, tg: String, byOfficer: Boolean): String {
        val members = try {
            comlinkService.memberNames()
        } catch (e: Exception) {
            println("Не смогли проверить ник в гильдии: ${e.message}")
            null
        }
        if (members == null) {
            val r = savePlayer(gameInput.trim(), tg, byOfficer)
            return if (r.ok) "✅ ${r.text}\n(Проверить ник в гильдии не получилось — сохранил как написано.)" else r.text
        }
        return when (val match = NickCommands.matchGameName(gameInput, members)) {
            is NickCommands.Match.Found -> savePlayer(match.name, tg, byOfficer).let { if (it.ok) "✅ ${it.text}" else it.text }
            is NickCommands.Match.NotFound -> "🤷 В гильдии нет игрока «${gameInput.trim()}»." +
                (if (match.suggestions.isNotEmpty()) " Может, ${match.suggestions.joinToString(", ") { "«$it»" }}?" else "") +
                "\nПроще выбрать кнопкой: /add_me"
        }
    }

    private data class SaveResult(val ok: Boolean, val text: String)

    /**
     * Сохраняет связку ник в игре → @телега.
     * Обычный участник не может перепривязать ник, который уже занят другим @username, — только офицер.
     */
    private fun savePlayer(gameName: String, tg: String, byOfficer: Boolean): SaveResult {
        val previous = synchronized(this) {
            ensureLoaded()
            val old = allTelegramNicks.firstOrNull { NickCommands.sameName(it.name, gameName) }
            if (!byOfficer && old != null && !NickCommands.sameTg(old.telegramNickname, tg)) {
                println("Попытка перепривязать $gameName: было ${old.telegramNickname}, пытался $tg")
                return SaveResult(
                    false,
                    "🔒 «${old.name}» уже привязан к другому человеку. Если это твой ник " +
                        "(например, ты сменил @username) — попроси офицера перепривязать."
                )
            }
            allTelegramNicks.removeAll { NickCommands.sameName(it.name, gameName) }
            allTelegramNicks.add(Player(gameName, tg))
            savePlayerNicksToFile()
            old
        }
        val shownTg = tgDisplay(tg)
        return SaveResult(
            true,
            if (previous != null && !NickCommands.sameTg(previous.telegramNickname, tg))
                "$gameName → $shownTg (было ${tgDisplay(previous.telegramNickname)})"
            else "$gameName → $shownTg"
        )
    }

    private fun removeByText(args: String): String {
        val target = args.replace(Regex("""(?i)^game:\s*"""), "").trim()
        val removed = synchronized(this) {
            ensureLoaded()
            val byTg = target.startsWith("@")
            val toRemove = allTelegramNicks.filter {
                if (byTg) it.telegramNickname.equals(target, ignoreCase = true) else NickCommands.sameName(it.name, target)
            }
            allTelegramNicks.removeAll(toRemove)
            if (toRemove.isNotEmpty()) savePlayerNicksToFile()
            toRemove
        }
        return if (removed.isEmpty()) "Не нашёл «$target» в списке"
        else "🗑 Удалил: " + removed.joinToString(", ") { it.name }
    }

    // в списках ники без @, чтобы не тегать всех подряд
    private fun listText(): String = synchronized(this) {
        ensureLoaded()
        if (allTelegramNicks.isEmpty()) "Список пуст"
        else "📋 В списке ${allTelegramNicks.size} чел.:\n" +
            allTelegramNicks.sortedBy { it.name.lowercase() }
                .joinToString("\n") { "${it.name} — ${tgDisplay(it.telegramNickname)}" }
    }

    private fun unlinkedText(): String {
        val members = try {
            comlinkService.memberNames()
        } catch (e: Exception) {
            return "Не удалось получить список гильдии: ${e.message}"
        }
        val linked = synchronized(this) { ensureLoaded(); allTelegramNicks.map { NickCommands.key(it.name) }.toSet() }
        val missing = members.filter { NickCommands.key(it) !in linked }.sortedBy { it.lowercase() }
        return if (missing.isEmpty()) "🎉 Все участники гильдии привязаны к Телеграму"
        else "👻 Не привязаны к Телеге (${missing.size}):\n" + missing.joinToString("\n") +
            "\n\nНажмите /add_me и выберите себя."
    }

    private fun weekReport(): String = try {
        guildTracker.weekReport()
    } catch (e: Exception) {
        e.printStackTrace()
        "Не удалось собрать статистику: ${e.message}"
    }

    /** Личное сообщение офицерам (если они хоть раз писали боту в личку). */
    fun alertAdmins(text: String) = adminIds.forEach { send(it.toString(), null, text) }

    /** Новости о составе — в тему, выбранную командой /news_here (по умолчанию — туда же, где напоминания). */
    fun postRosterNews(text: String) {
        when (val t = rosterThread) {
            null -> postToGuild(text)
            "general" -> send(alderaanChatId, null, text)
            else -> send(alderaanChatId, t.toIntOrNull(), text)
        }
    }

    /** Сообщение в тред гильдии, где тегаем про энку. */
    fun postToGuild(text: String) = sendMessageToThread(alderaanChatId, chatForTagging, text)

    /** Ушедших убираем из списка ников, сменившим ник — переименовываем. */
    fun applyRosterChanges(left: List<String>, renamed: List<Pair<String, String>>) = synchronized(this) {
        ensureLoaded()
        allTelegramNicks.removeAll { p -> left.any { NickCommands.sameName(it, p.name) } }
        renamed.forEach { (old, new) ->
            allTelegramNicks.replaceAll { if (NickCommands.sameName(it.name, old)) it.copy(name = new) else it }
        }
        savePlayerNicksToFile()
    }

    private fun energyReport(): String = try {
        "⚡ " + comlinkService.statusReport()
    } catch (e: Exception) {
        e.printStackTrace()
        "Не удалось получить данные гильдии: ${e.message}"
    }

    // ---------- отправка ----------

    private fun button(text: String, data: String): InlineKeyboardButton =
        InlineKeyboardButton.builder().text(text).callbackData(data).build()

    private fun keyboard(vararg rows: List<InlineKeyboardButton>) = InlineKeyboardMarkup(rows.toList())

    private fun threadOf(message: Message): Int? = if (message.isTopicMessage()) message.messageThreadId else null

    private fun reply(message: Message, text: String, markup: InlineKeyboardMarkup? = null): Message? =
        send(message.chatId.toString(), threadOf(message), text, markup, message.messageId)

    private val cleaner = Executors.newSingleThreadScheduledExecutor()

    private fun isGroup(message: Message) = message.chatId < 0

    /** Служебный ответ: в группе удаляется через минуту вместе с командой, в личке остаётся. */
    private fun replyTemp(message: Message, text: String) {
        val sent = reply(message, text)
        if (isGroup(message)) {
            deleteLater(message.chatId, sent?.messageId, TEMP_SECONDS)
            if (message.from?.isBot != true) deleteLater(message.chatId, message.messageId, TEMP_SECONDS)
        }
    }

    private fun deleteLater(chatId: Long, messageId: Int?, seconds: Long) {
        messageId ?: return
        cleaner.schedule({
            try {
                execute(DeleteMessage(chatId.toString(), messageId))
            } catch (e: Exception) {
                println("Не смог удалить сообщение $messageId: ${e.message}")
            }
        }, seconds, TimeUnit.SECONDS)
    }

    private fun send(chatId: String, threadId: Int?, text: String, markup: InlineKeyboardMarkup? = null, replyTo: Int? = null): Message? {
        val r = SendMessage(chatId, text)
        r.messageThreadId = threadId
        r.replyToMessageId = replyTo
        r.allowSendingWithoutReply = true
        r.replyMarkup = markup
        return try {
            execute(r)
        } catch (e: TelegramApiException) {
            e.printStackTrace()
            null
        }
    }

    private fun edit(msg: Message?, text: String, markup: InlineKeyboardMarkup?) {
        msg ?: return
        try {
            execute(
                EditMessageText.builder().chatId(msg.chatId.toString()).messageId(msg.messageId)
                    .text(text).replyMarkup(markup).build()
            )
        } catch (e: TelegramApiException) {
            println("Не смог отредактировать сообщение: ${e.message}")
        }
    }

    private fun editMarkup(msg: Message?, markup: InlineKeyboardMarkup) {
        msg ?: return
        edit(msg, msg.text ?: "", markup)
    }

    private fun answer(cq: CallbackQuery, text: String? = null, alert: Boolean = false) {
        try {
            execute(AnswerCallbackQuery.builder().callbackQueryId(cq.id).text(text).showAlert(alert).build())
        } catch (e: TelegramApiException) {
            println("Не смог ответить на кнопку: ${e.message}")
        }
    }

    @PostConstruct
    fun init() {
        // раньше файлы лежали рядом с jar — переносим в data
        SafeFiles.migrate(File("player_nicks.txt"), File(nicknamesFilePath))
        SafeFiles.migrate(File("seen_users.txt"), File(seenUsersFilePath))
        if (settingsFile.isFile) {
            rosterThread = settingsFile.readLines().firstOrNull { it.startsWith("rosterThreadId=") }
                ?.substringAfter("=")?.trim()?.takeIf { it.isNotEmpty() }
            println("Новости о составе пишу в: ${rosterThread ?: "тему с напоминаниями"}")
        }
        loadPlayerNicksFromFile()
        loadSeenUsers()
        val telegramApi = TelegramBotsApi(DefaultBotSession::class.java)
        try {
            telegramApi.registerBot(this)
        } catch (e: TelegramApiException) {
            e.printStackTrace()
        }
        // Админов чата гильдии знаем сразу
        try {
            rememberUsers(execute(GetChatAdministrators.builder().chatId(alderaanChatId).build()).map { it.user })
        } catch (e: TelegramApiException) {
            println("Не смог получить админов чата: ${e.message}")
        }
        // Меню команд, которое Телеграм показывает по кнопке «/»
        try {
            execute(
                SetMyCommands.builder().commands(
                    listOf(
                        BotCommand("menu", "Меню бота"),
                        BotCommand("add_me", "Добавить себя"),
                        BotCommand("unlinked", "Кто из гильдии не привязан"),
                        BotCommand("energy", "Кто сколько энки сдал (офицеры)"),
                        BotCommand("week", "Энка за неделю (офицеры)"),
                        BotCommand("add_user", "Привязать кого-то (офицеры)"),
                        BotCommand("remove_user", "Убрать из списка (офицеры)"),
                        BotCommand("list", "Весь список (офицеры)"),
                        BotCommand("export", "Выгрузка: ник в игре — Телега (офицеры)"),
                    )
                ).build()
            )
        } catch (e: TelegramApiException) {
            println("Не смог установить меню команд: ${e.message}")
        }
    }

    // ---------- файл ников ----------

    private fun rememberUsers(users: List<User>) {
        val fresh = users.filter { it.isBot != true }
            .map { it.id to SeenUser(it.userName?.takeIf { n -> n.isNotBlank() }, it.firstName ?: "") }
            .filter { (id, u) -> seenUsers[id] != u }
        if (fresh.isEmpty()) return
        synchronized(this) {
            fresh.forEach { (id, u) -> seenUsers[id] = u }
            try {
                SafeFiles.write(File(seenUsersFilePath), seenUsers.entries.joinToString("") { (id, u) ->
                    "$id\t${u.username ?: "-"}\t${u.firstName.replace("\t", " ")}\n"
                })
            } catch (e: Exception) {
                println("Не смог сохранить $seenUsersFilePath: ${e.message}")
            }
        }
    }

    private fun loadSeenUsers() {
        val file = File(seenUsersFilePath)
        if (!file.isFile) return
        file.readLines().filter { it.isNotBlank() }.forEach { line ->
            // "id<TAB>username|-<TAB>имя" (старый формат: "id username")
            val parts = if ('\t' in line) line.split("\t", limit = 3) else line.trim().split(Regex("""\s+"""), limit = 2)
            val id = parts.getOrNull(0)?.trim()?.toLongOrNull() ?: return@forEach
            val username = parts.getOrNull(1)?.trim()?.takeIf { it.isNotEmpty() && it != "-" }
            seenUsers[id] = SeenUser(username, parts.getOrNull(2)?.trim() ?: "")
        }
        println("Знакомых пользователей Телеги: ${seenUsers.size}")
    }

    private fun ensureLoaded() {
        if (allTelegramNicks.isEmpty()) loadPlayerNicksFromFile()
    }

    // Функция для записи списка в файл
    @Synchronized
    fun savePlayerNicksToFile() {
        println("Начинаем запись в файлик")
        try {
            SafeFiles.write(File(nicknamesFilePath), allTelegramNicks.joinToString("") { "${it.name} ${it.telegramNickname}\n" })
            println("Завершили запись в файлик")
        } catch (e: Exception) {
            println("Не смог сохранить список ников: ${e.message}")
        }
    }

    @Synchronized
    fun loadPlayerNicksFromFile() {
        println("Начинаем читать из файлика")
        val file = File(nicknamesFilePath)
        if (file.isFile) {
            allTelegramNicks = file.readLines()
                .filter { it.isNotBlank() }
                .mapNotNull { line ->
                    NickCommands.parseNickLine(line) ?: run { println("Не понял строку в файле ников: $line"); null }
                }.toMutableList()
        }
        println("Прочитали из файлика: \n $allTelegramNicks")
    }


    fun getActualTgNicks(message: String): MutableList<Player> {
        // Парсим сообщение
        val regex = Regex("""([^\n]+)\s(@\w+)""")
        val matches = regex.findAll(message)

        val players = matches.mapNotNull {
            val (name, nickname) = it.destructured
            NickCommands.normalizeTg(nickname)?.let { tg -> Player(name.replace("\n", "").trim(), tg) }
        }.toMutableList()

        // Выводим список игроков
        players.forEach {
            println("Name: ${it.name}, Telegram: ${it.telegramNickname}")
        }
        return players
    }

    fun sendRaidReminder(message: String, playersWithLostTickets: List<String>) {
        val nicks = synchronized(this) {
            ensureLoaded()
            allTelegramNicks.toList()
        }
        val playersForTagging = playersWithLostTickets
            .flatMap { pl -> nicks.filter { NickCommands.sameName(it.name, pl) } }.toMutableList()

        // если нового чувака не нашли в списке ников
        val playersWithoutNicks = playersWithLostTickets.filterNot { pl ->
            playersForTagging.any { NickCommands.sameName(it.name, pl) }
        }
        if (playersWithoutNicks.isNotEmpty()) {
            println("Этих чуваков нет в списке ников, надобно добавить: $playersWithoutNicks")
            playersWithoutNicks.forEach { playersForTagging.add(Player(it, "")) }
        }

        // HTML: тех, у кого нет @username, тегаем ссылкой-упоминанием по id (подпись — ник в игре)
        val prettyPlayersForTagging = playersForTagging.joinToString("") {
            val tg = it.telegramNickname.replace("@@", "@")
            when {
                tg.isBlank() -> "\n${NickCommands.escapeHtml(it.name)}"
                NickCommands.tgUserId(tg) != null -> "\n" + NickCommands.mentionHtml(tg, it.name)
                else -> "\n${NickCommands.escapeHtml(it.name)} ${NickCommands.escapeHtml(tg)}"
            }
        }
        println("Players for tagging: $prettyPlayersForTagging")

        var fullMessage = "${NickCommands.escapeHtml(message)} $prettyPlayersForTagging"
        if (playersWithoutNicks.isNotEmpty()) {
            fullMessage += "\n\n👻 Кого не смог тегнуть — нажмите /add_me и выберите себя"
        }
        println("Это будем отправлять \"\n$fullMessage \"")
        sendMessageToThread(alderaanChatId, chatForTagging, fullMessage, html = true)

    }

    fun sendMessageToThread(chatId: String, threadId: Int, text: String, html: Boolean = false) {
        val message = SendMessage()
        message.chatId = chatId
        message.text = text
        message.messageThreadId = threadId
        if (html) message.parseMode = "HTML"
        try {
            execute(message)
            println("Сообщение успешно отправлено в тред $threadId, \n$text")
        } catch (e: TelegramApiException) {
            e.printStackTrace()
            println("Ошибка при отправке сообщения: ${e.message}")
        }
    }

    companion object {
        private const val ONLY_OFFICERS = "🔒 Эта команда только для офицеров"
        private const val PAGE_SIZE = 20
        private const val TEMP_SECONDS = 60L
        private const val PICKER_SECONDS = 300L
    }
}

// Список объектов Player
data class Player(val name: String, val telegramNickname: String)
