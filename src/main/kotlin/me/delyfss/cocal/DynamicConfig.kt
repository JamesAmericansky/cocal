package me.delyfss.cocal

import com.typesafe.config.Config
import com.typesafe.config.ConfigFactory
import com.typesafe.config.ConfigRenderOptions
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.reflect.full.findAnnotation
import kotlin.reflect.jvm.isAccessible
import kotlin.reflect.full.memberProperties
import kotlin.collections.linkedMapOf
import kotlin.reflect.jvm.javaField

abstract class DynamicConfig(
    val folder: File,
    val fileName: String,
    val options: Options = Options()
) {

    data class Options(
        val header: List<String> = emptyList(),
        val prettyPrint: Boolean = true,
        val debounceDelayMs: Long = 1000L
    )

    private val scheduler = Executors.newSingleThreadScheduledExecutor()
    private var saveTask: java.util.concurrent.ScheduledFuture<*>? = null

    private val properties by lazy {
        this::class.memberProperties
            .filter { it.javaField?.declaringClass == this::class.java }
            .onEach { it.isAccessible = true }
    }

    fun update(block: () -> Unit) {
        block()
        scheduleSave()
    }

    fun scheduleSave() {
        synchronized(this) {
            saveTask?.cancel(false)
            saveTask = scheduler.schedule({ save() }, options.debounceDelayMs, TimeUnit.MILLISECONDS)
        }
    }

    @Synchronized
    fun save() {
        val file = File(folder, fileName)
        file.writeText(renderConfig(buildCurrentConfig()))
    }

    private fun buildCurrentConfig(): Config {
        val tree = linkedMapOf<String, Any?>()
        properties.forEach { prop ->
            val path = prop.findAnnotation<Path>()?.value ?: prop.name
            val value = prop.getter.call(this)
            insertValue(tree, path.split('.'), convertValue(value))
        }
        return ConfigFactory.parseMap(tree)
    }

    private fun convertValue(value: Any?): Any? = when (value) {
        null -> null
        is String, is Number, is Boolean -> value
        is Enum<*> -> value.name
        is List<*> -> value.map { convertValue(it) }
        is Set<*> -> value.map { convertValue(it) }
        is Map<*, *> -> value.entries.associate { (key, entryValue) ->
            key.toString() to convertValue(entryValue)
        }
        else -> if (value::class.isData) {
            snapshotDataClass(value)
        } else {
            value.toString()
        }
    }

    private fun snapshotDataClass(value: Any): Map<String, Any?> {
        val nested = linkedMapOf<String, Any?>()
        collectCurrent(value, emptyList(), nested)
        return nested
    }

    private fun collectCurrent(
        instance: Any,
        prefix: List<String>,
        result: MutableMap<String, Any?>
    ) {
        val klass = instance::class
        val properties = klass.memberProperties
            .filter { it.javaField?.declaringClass == klass.java }
            .onEach { it.isAccessible = true }

        properties.forEach { prop ->
            val pathSegments = (prefix + (prop.findAnnotation<Path>()?.value ?: prop.name)
                .split('.')
                .filter { it.isNotBlank() })

            when (val value = prop.getter.call(instance)) {
                null -> insertValue(result, pathSegments, null)
                is Map<*, *> -> {
                    val convertedMap = value.entries.associate { (k, v) ->
                        k.toString() to convertValue(v)
                    }
                    insertValue(result, pathSegments, convertedMap)
                }
                else -> if (value::class.isData) {
                    collectCurrent(value, pathSegments, result)
                } else {
                    insertValue(result, pathSegments, convertValue(value))
                }
            }
        }
    }

    private fun insertValue(target: MutableMap<String, Any?>, path: List<String>, rawValue: Any?) {
        if (path.isEmpty()) return
        var cursor = target
        path.dropLast(1).forEach { key ->
            cursor = cursor.getOrPut(key) { linkedMapOf<String, Any?>() } as MutableMap<String, Any?>
        }
        cursor[path.last()] = rawValue
    }

    private fun renderConfig(config: Config): String {
        val renderOptions = ConfigRenderOptions.defaults()
            .setComments(false)
            .setOriginComments(false)
            .setJson(false)
            .setFormatted(options.prettyPrint)

        val rendered = config.root().render(renderOptions)
        if (options.header.isEmpty()) return rendered

        val header = options.header.joinToString("\n") { if (it.startsWith("#")) it else "# $it" }
        return "$header\n\n$rendered"
    }
}