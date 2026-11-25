package com.example.bot.service

import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * Безопасная запись: сначала во временный файл рядом, потом атомарная замена.
 * Если места на диске нет — старый файл остаётся целым, а не обнуляется.
 */
object SafeFiles {
    fun write(file: File, text: String) {
        val dir = file.absoluteFile.parentFile.apply { mkdirs() }
        val tmp = File(dir, ".${file.name}.tmp")
        try {
            tmp.writeText(text)
            Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        } catch (e: Exception) {
            tmp.delete()
            throw e
        }
    }

    /** Переносит старый файл в новое место, если там ещё ничего нет. */
    fun migrate(old: File, new: File) {
        if (!new.exists() && old.isFile && old.length() > 0) {
            write(new, old.readText())
            println("Перенёс ${old.path} -> ${new.path}")
        }
    }
}
