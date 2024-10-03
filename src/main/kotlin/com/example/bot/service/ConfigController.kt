package com.example.bot.service

import org.springframework.core.annotation.Order
import org.springframework.stereotype.Service
import java.io.File
import java.io.FileNotFoundException

@Service
@Order(1)
class ConfigController {
    private val fileName = "creds.txt"
    private val configMap: MutableMap<String, String> = mutableMapOf()

    init {
        loadConfigFromFile(fileName)
    }

    // Метод для загрузки данных из файла
    private fun loadConfigFromFile(fileName: String) {
        println("Конфиг загружаемс")
        val file = File(fileName)
        if (file.exists()) {
            file.forEachLine { line ->
                val parts = line.split("=").map { it.trim() }
                if (parts.size == 2) {
                    configMap[parts[0]] = parts[1]
                }
            }
        } else {
            throw FileNotFoundException("Configuration file $fileName not found")
        }
    }

    // Метод для получения значения по ключу
    fun get(key: String): String {
        return configMap[key]!!
    }


}