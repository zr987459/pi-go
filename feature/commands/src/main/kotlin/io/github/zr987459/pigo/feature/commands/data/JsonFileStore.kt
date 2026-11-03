package io.github.zr987459.pigo.feature.commands.data

import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

/** 宿主侧的小 JSON 文件：原子写（先写 .tmp 再改名），读坏了当作空。只在宿主，不进容器、不改 pi 文件。 */
internal class JsonFileStore(private val file: File) {
    @Synchronized
    fun read(): JsonObject = try {
        if (file.isFile) Json.parseToJsonElement(file.readText()).jsonObject else JsonObject(emptyMap())
    } catch (_: Exception) {
        JsonObject(emptyMap())
    }

    @Synchronized
    fun write(o: JsonObject) {
        file.parentFile?.mkdirs()
        val tmp = File(file.path + ".tmp")
        tmp.writeText(o.toString())
        if (!tmp.renameTo(file)) {
            file.delete()
            tmp.renameTo(file)
        }
    }
}
