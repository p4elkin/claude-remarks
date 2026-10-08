package dev.sasha.clauderemarks.review

import com.google.gson.JsonParser
import java.nio.file.Files
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.nio.file.attribute.PosixFileAttributes
import java.nio.file.attribute.PosixFilePermission

/** A missing hook is silent; an unusable hook carries a reason for the caller's warning log. */
sealed interface HookDecision {
    data class Use(val argv: List<String>, val label: String) : HookDecision
    data class Skip(val reason: String?) : HookDecision
}

/**
 * Reads the launcher-owned hook without executing it. The real file's owner and permissions guard
 * its argv, and the bound server port keeps another IDE on this checkout from invoking it.
 * Launcher fields `owner` and `state` are deliberately not interpreted here.
 */
fun readHook(file: Path, expectedPort: Int, currentUser: String): HookDecision {
    val attributes = try {
        Files.readAttributes(file, PosixFileAttributes::class.java)
    } catch (_: NoSuchFileException) {
        return HookDecision.Skip(null)
    } catch (e: Exception) {
        return HookDecision.Skip("Cannot read hook attributes: ${e.message}")
    }
    if (attributes.owner().name != currentUser) {
        return HookDecision.Skip("Hook file is not owned by $currentUser")
    }
    if (PosixFilePermission.GROUP_WRITE in attributes.permissions() ||
        PosixFilePermission.OTHERS_WRITE in attributes.permissions()) {
        return HookDecision.Skip("Hook file is writable by group or others")
    }

    return try {
        val json = JsonParser.parseString(Files.readString(file))
        if (!json.isJsonObject) return HookDecision.Skip("Hook JSON must be an object")
        val fields = json.asJsonObject
        val args = fields.get("argv")
        if (args == null || !args.isJsonArray || args.asJsonArray.size() == 0) {
            return HookDecision.Skip("Hook argv must be a non-empty array of strings")
        }
        val argv = args.asJsonArray.map {
            if (!it.isJsonPrimitive || !it.asJsonPrimitive.isString) {
                return HookDecision.Skip("Hook argv must contain only strings")
            }
            it.asString
        }
        if (!Path.of(argv.first()).isAbsolute) {
            return HookDecision.Skip("Hook executable must be an absolute path")
        }
        val port = fields.get("port")
        if (port == null || !port.isJsonPrimitive || !port.asJsonPrimitive.isNumber ||
            port.asString.toIntOrNull() != expectedPort) {
            return HookDecision.Skip("Hook port must equal this IDE's port ($expectedPort)")
        }
        val label = fields.get("label")
        if (label != null && (!label.isJsonPrimitive || !label.asJsonPrimitive.isString)) {
            return HookDecision.Skip("Hook label must be a string")
        }
        HookDecision.Use(argv, label?.asString?.takeUnless { it.isEmpty() } ?: "live review")
    } catch (e: Exception) {
        HookDecision.Skip("Cannot read hook JSON: ${e.message}")
    }
}
