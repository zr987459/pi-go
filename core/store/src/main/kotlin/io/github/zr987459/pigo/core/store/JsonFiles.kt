package io.github.zr987459.pigo.core.store

import java.io.File
import java.io.IOException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.PosixFilePermission
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/** 合并写 pi 配置文件用的小工具：读不懂就拒绝写（不覆盖用户内容），写入走临时文件 + 原子替换，权限 600。 */
internal object JsonFiles {
    private val reader = Json { isLenient = true }
    private val writer = Json { prettyPrint = true }

    class BrokenConfigException(val file: File, cause: Throwable?) :
        IllegalStateException("${file.name} 不是有效的 JSON，为避免覆盖你的配置已停止写入。请先修正或删除该文件。", cause)

    /** 文件不存在或为空 → 空对象；内容不是 JSON 对象 → [BrokenConfigException]。 */
    fun readObject(file: File): JsonObject {
        if (!file.exists()) return JsonObject(emptyMap())
        val text = file.readText(Charsets.UTF_8)
        if (text.isBlank()) return JsonObject(emptyMap())
        val el = try {
            reader.parseToJsonElement(text)
        } catch (e: Exception) {
            throw BrokenConfigException(file, e)
        }
        return el as? JsonObject ?: throw BrokenConfigException(file, null)
    }

    fun writeObject(file: File, obj: JsonObject) {
        val dir = file.parentFile ?: throw IOException("无效路径：$file")
        if (!dir.isDirectory && !dir.mkdirs()) throw IOException("无法创建目录：$dir")
        val tmp = File(dir, ".${file.name}.pigo-tmp")
        tmp.delete()
        tmp.createNewFile()
        setOwnerOnly(tmp) // 先收紧权限再写内容
        tmp.writeText(writer.encodeToString(JsonElement.serializer(), obj) + "\n", Charsets.UTF_8)
        try {
            Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
        setOwnerOnly(file)
    }

    /** 是否已经是 600（不支持 POSIX 权限的文件系统上视为是）。 */
    fun isOwnerOnly(f: File): Boolean = try {
        Files.getPosixFilePermissions(f.toPath()) == setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE)
    } catch (_: UnsupportedOperationException) {
        true
    }

    /** chmod 600。 */
    fun setOwnerOnly(f: File) {
        try {
            Files.setPosixFilePermissions(
                f.toPath(),
                setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE),
            )
        } catch (_: UnsupportedOperationException) {
            f.setReadable(false, false); f.setWritable(false, false); f.setExecutable(false, false)
            f.setReadable(true, true); f.setWritable(true, true)
        }
    }
}
