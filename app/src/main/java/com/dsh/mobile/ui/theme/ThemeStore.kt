package com.dsh.mobile.ui.theme

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStream
import java.util.zip.ZipInputStream

/**
 * 自定义主题文件存取：filesDir/themes/<id>/（theme.json + 可选 preview.png + README.md）
 * 兼容旧版单文件 filesDir/themes/<id>.json。
 * 主题包格式：zip（theme.json 在根目录，可选 preview.png 预览图、README.md 说明），
 * 同 id 导入即覆盖（热替换，运行时立即生效）。
 */
class ThemeStore(private val context: Context) {

    private val root: File = File(context.filesDir, "themes")

    fun list(): List<ThemeDef> {
        val out = mutableListOf<ThemeDef>()
        root.listFiles()?.filter { it.isDirectory }?.forEach { d ->
            val tf = File(d, "theme.json")
            if (tf.exists()) {
                runCatching { ThemeRegistry.parseJson(tf.readText()) }.getOrNull()?.let { out += it }
            }
        }
        // 兼容旧版单文件存储
        root.listFiles()?.filter { it.isFile && it.extension == "json" }?.forEach { f ->
            runCatching { ThemeRegistry.parseJson(f.readText()) }.getOrNull()?.let { out += it }
        }
        return out
    }

    /** 保存单主题 JSON（旧版单文件格式，兼容保留） */
    fun saveJson(def: ThemeDef): Boolean = runCatching {
        root.mkdirs()
        val payload = buildString {
            append("{\"id\":\"").append(def.id).append("\",\"name\":\"").append(def.name)
            append("\",\"light\":").append(def.light)
            def.version?.let { append(",\"version\":\"").append(it).append("\"") }
            append(",\"colors\":{")
            val c = def.colors
            append("\"background\":\"").append(hex(c.background)).append("\",")
            append("\"surface\":\"").append(hex(c.surface)).append("\",")
            append("\"surfaceHigh\":\"").append(hex(c.surfaceHigh)).append("\",")
            append("\"brand\":\"").append(hex(c.brand)).append("\",")
            append("\"brandSoft\":\"").append(hex(c.brandSoft)).append("\",")
            append("\"textPrimary\":\"").append(hex(c.textPrimary)).append("\",")
            append("\"textSecondary\":\"").append(hex(c.textSecondary)).append("\",")
            append("\"border\":\"").append(hex(c.border)).append("\",")
            append("\"success\":\"").append(hex(c.success)).append("\",")
            append("\"warn\":\"").append(hex(c.warn)).append("\",")
            append("\"error\":\"").append(hex(c.error)).append("\"}")
            append("}")
        }
        File(root, def.id + ".json").writeText(payload)
        true
    }.getOrDefault(false)

    private fun hex(color: androidx.compose.ui.graphics.Color): String {
        val r = (color.red * 255).toInt().coerceIn(0, 255)
        val g = (color.green * 255).toInt().coerceIn(0, 255)
        val b = (color.blue * 255).toInt().coerceIn(0, 255)
        return String.format("#%02X%02X%02X", r, g, b)
    }

    /** 主题预览图（zip 包内 preview.png），无则返回 null */
    fun previewFile(id: String): File? {
        val p = File(File(root, id), "preview.png")
        return if (p.exists()) p else null
    }

    /** 从 zip 主题包导入（PK 魔数校验由调用方做）；同名 id 覆盖 = 热替换更新 */
    fun importZip(bytes: ByteArray): ThemeDef? = runCatching {
        val zis = ZipInputStream(ByteArrayInputStream(bytes))
        var json: String? = null
        val extras = mutableMapOf<String, ByteArray>()
        var total = 0L
        while (true) {
            val e = zis.nextEntry ?: break
            val name = e.name
            if (name.contains("..") || name.startsWith("/") || name.contains("\\")) {
                zis.closeEntry()
                continue
            }
            val isThemeJson = name == "theme.json"
            val isPreview = name == "preview.png" && (e.size < 0 || e.size <= 2_000_000)
            val isReadme = name == "README.md" && (e.size < 0 || e.size <= 100_000)
            if (isThemeJson || isPreview || isReadme) {
                val buf = readFully(zis, 2_500_000) ?: return@runCatching null
                total += buf.size
                if (total > 10_000_000) return@runCatching null
                when {
                    isThemeJson -> json = buf.toString(Charsets.UTF_8)
                    isPreview -> extras["preview.png"] = buf
                    else -> extras["README.md"] = buf
                }
            }
            zis.closeEntry()
        }
        zis.close()
        if (json == null) return@runCatching null
        val def = ThemeRegistry.parseJson(json) ?: return@runCatching null
        // 同名覆盖：先删旧目录再写入（热替换语义）
        val dir = File(root, def.id)
        dir.deleteRecursively()
        dir.mkdirs()
        File(dir, "theme.json").writeText(json)
        extras.forEach { (n, b) -> File(dir, n).writeBytes(b) }
        def
    }.getOrNull()

    fun delete(id: String) {
        File(root, id).deleteRecursively()
        File(root, id + ".json").delete()
    }

    private fun readFully(input: InputStream, max: Int): ByteArray? {
        val buf = ByteArray(max)
        var pos = 0
        while (pos < max) {
            val n = input.read(buf, pos, max - pos)
            if (n < 0) break
            pos += n
        }
        if (pos == 0) return null
        return buf.copyOf(pos)
    }
}

/** 全局自定义主题仓库（MainActivity 与 Settings 共享，导入/删除即时生效 = 热加载） */
object ThemeRepository {
    private val _themes = MutableStateFlow<List<ThemeDef>>(emptyList())
    val themes: StateFlow<List<ThemeDef>> = _themes

    private var initialized = false

    @Synchronized
    fun init(context: Context) {
        if (initialized) return
        initialized = true
        _themes.value = ThemeStore(context.applicationContext).list()
    }

    /** 导入主题包（zip）或 JSON 文本；成功返回主题定义，失败返回 null */
    fun importPayload(bytes: ByteArray, context: Context): ThemeDef? {
        val store = ThemeStore(context.applicationContext)
        val isZip = bytes.size > 4 &&
            bytes[0] == 0x50.toByte() && bytes[1] == 0x4B.toByte() &&
            bytes[2] == 0x03.toByte() && bytes[3] == 0x04.toByte()
        val def = if (isZip) {
            store.importZip(bytes)
        } else {
            val text = runCatching { bytes.toString(Charsets.UTF_8) }.getOrNull() ?: return null
            ThemeRegistry.parseJson(text)?.also { d ->
                if (store.importZip(buildZip(bytes)) == null) return null
            }
        } ?: return null
        _themes.value = _themes.value.filterNot { it.id == def.id } + def
        return def
    }

    fun add(def: ThemeDef, context: Context): Boolean {
        if (!ThemeStore(context.applicationContext).saveJson(def)) return false
        _themes.value = _themes.value.filterNot { it.id == def.id } + def
        return true
    }

    fun remove(id: String, context: Context) {
        ThemeStore(context.applicationContext).delete(id)
        _themes.value = _themes.value.filterNot { it.id == id }
    }

    private fun buildZip(bytes: ByteArray): ByteArray {
        // JSON 文本 → 直接作为 theme.json 写入 zip 单文件包
        val bos = java.io.ByteArrayOutputStream()
        val zos = java.util.zip.ZipOutputStream(bos)
        zos.putNextEntry(java.util.zip.ZipEntry("theme.json"))
        zos.write(bytes)
        zos.closeEntry()
        zos.close()
        return bos.toByteArray()
    }
}
