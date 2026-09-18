package me.code4me.research.runtime

import me.code4me.research.bootstrap.isSecretManifestKey
import me.code4me.research.bootstrap.looksSecretManifestValue
import me.code4me.research.telemetry.canonicalJson
import me.code4me.research.telemetry.parseCanonicalJson
import me.code4me.research.telemetry.sha256Hex
import java.nio.file.InvalidPathException
import java.nio.file.Paths

/**
 * `RuntimeManifestV2` (Issue 11).
 *
 * The package manifest is authoritative for the exact runtime contents and
 * integrity of a participant release. Every executable/adapter asset is
 * addressed by a *relative safe* path, version, OS/architecture, size, SHA-256,
 * and self-check. There is intentionally no notion of a host-machine lookup:
 * the resolver consumes only what this manifest declares.
 *
 * This module is pure JVM: it never imports the IntelliJ platform and never
 * touches the network or the filesystem.
 */

/** Raised when a runtime manifest document cannot be parsed into the typed model. */
class RuntimeManifestParseException(message: String, cause: Throwable? = null) :
    IllegalArgumentException(message, cause)

/** Stable machine-readable runtime-manifest validation reason. */
enum class RuntimeManifestErrorCode {
    /** `manifest_version` is not the one this plugin understands. */
    SCHEMA_VERSION_UNSUPPORTED,

    /** The stored digest does not match the recomputed canonical bytes. */
    DIGEST_MISMATCH,

    /** A required field is missing or has the wrong type. */
    MALFORMED,

    /** A declared asset path is absolute, non-normalized, has `..`, NUL, or shell metacharacters. */
    UNSAFE_PATH,

    /** A component SHA-256 is missing or not 64 lowercase hex characters. */
    INVALID_SHA256,

    /** A component size is missing or not strictly positive. */
    INVALID_SIZE,

    /** Two components declare the same `(os, arch)` platform pair. */
    DUPLICATE_PLATFORM,

    /** Two components share a component id. */
    DUPLICATE_COMPONENT_ID,

    /** The manifest declares no components at all. */
    EMPTY_COMPONENTS,

    /** The manifest declares the same supported platform twice. */
    DUPLICATE_SUPPORTED_PLATFORM,

    /** A component targets a platform the manifest does not list as supported. */
    COMPONENT_PLATFORM_NOT_SUPPORTED,

    /** Adapter id/version is missing. */
    MISSING_ADAPTER_IDENTITY,

    /** A component does not declare which executable it owns. */
    MISSING_COMPONENT_EXECUTABLE,

    /** An executable asset is present that no component declares. */
    UNDECLARED_EXECUTABLE,

    /** A credential-looking key or value is present anywhere in the document. */
    SECRET_DETECTED,

    /** A declared compatibility range cannot be parsed. */
    INVALID_COMPATIBILITY,
}

/** One typed validation finding. `field` points at the offending JSON path when known. */
data class RuntimeManifestIssue(
    val code: RuntimeManifestErrorCode,
    val message: String,
    val field: String? = null,
)

/** Typed aggregate result of [RuntimeManifestV2.validate]. */
data class RuntimeManifestValidation(val issues: List<RuntimeManifestIssue>) {
    val isValid: Boolean
        get() = issues.isEmpty()

    fun codes(): Set<RuntimeManifestErrorCode> = issues.map { it.code }.toSet()

    companion object {
        val OK: RuntimeManifestValidation = RuntimeManifestValidation(emptyList())
    }
}

/** An `(os, arch)` selection key. `key` is the `os-arch` tuple used in diagnostics. */
data class PlatformTriple(val os: String, val arch: String) {
    init {
        require(os.isNotBlank()) { "os must not be blank" }
        require(arch.isNotBlank()) { "arch must not be blank" }
    }

    val key: String
        get() = "$os-$arch"
}

/** A declared self-check command run against a staged component. */
data class SelfCheckSpec(
    val command: String,
    val args: List<String> = emptyList(),
    val expectedExitCode: Int = 0,
    val timeoutMs: Long? = null,
) {
    init {
        require(command.isNotBlank()) { "self-check command must not be blank" }
    }

    fun toCanonicalMap(): Map<String, Any?> =
        linkedMapOf(
            "command" to command,
            "args" to args,
            "expected_exit_code" to expectedExitCode.toLong(),
            "timeout_ms" to timeoutMs,
        )
}

/** A license entry shipped beside the component (never a credential). */
data class RuntimeLicense(
    val licenseId: String,
    val spdx: String? = null,
    val path: String? = null,
) {
    init {
        require(licenseId.isNotBlank()) { "licenseId must not be blank" }
    }
}

/**
 * One platform-specific runtime component.
 *
 * @property componentId stable component identity.
 * @property path relative path of the component payload inside the package.
 * @property sha256 lowercase hex SHA-256 of the payload bytes.
 * @property size payload size in bytes; must be strictly positive.
 * @property os target operating system (for example `macos`, `linux`, `windows`).
 * @property arch target architecture (for example `arm64`, `x64`).
 * @property executable relative path of the executable this component owns.
 * @property argsTemplate argument array prefix; never a shell string.
 * @property signature optional detached signature over the component identity.
 * @property license optional license id/SPDX/path bundled with the component.
 * @property selfCheck optional self-check command run after staging.
 */
data class RuntimeComponent(
    val componentId: String,
    val path: String,
    val sha256: String,
    val size: Long,
    val os: String,
    val arch: String,
    val executable: String,
    val argsTemplate: List<String> = emptyList(),
    val signature: String? = null,
    val license: RuntimeLicense? = null,
    val selfCheck: SelfCheckSpec? = null,
) {
    val platform: PlatformTriple
        get() = PlatformTriple(os, arch)

    fun toCanonicalMap(): Map<String, Any?> =
        linkedMapOf(
            "component_id" to componentId,
            "path" to path,
            "sha256" to sha256,
            "size" to size,
            "os" to os,
            "arch" to arch,
            "executable" to executable,
            "args_template" to argsTemplate,
            "signature" to signature,
            "license" to
                license?.let {
                    linkedMapOf("license_id" to it.licenseId, "spdx" to it.spdx, "path" to it.path)
                },
            "self_check" to selfCheck?.toCanonicalMap(),
        )
}

/** Declared compatibility constraints (name -> version range text). */
data class CompatibilityConstraints(val constraints: Map<String, String> = emptyMap()) {
    fun toCanonicalMap(): Map<String, Any?> = linkedMapOf("constraints" to constraints)
}

/**
 * Immutable `RuntimeManifestV2`.
 *
 * [raw] is the exact parsed JSON document. It is retained so the plugin can
 * recompute the manifest digest and scan the *complete* received document for
 * embedded secrets and undeclared executable assets, including keys the typed
 * model ignores.
 */
data class RuntimeManifestV2(
    val schema: String,
    val version: String,
    val digest: String,
    val releaseId: String,
    val adapterId: String,
    val adapterVersion: String,
    val components: List<RuntimeComponent>,
    val platforms: List<PlatformTriple>,
    val licenses: List<RuntimeLicense> = emptyList(),
    val compatibility: CompatibilityConstraints = CompatibilityConstraints(),
    val selfCheck: SelfCheckSpec? = null,
    val signature: String? = null,
    val raw: Map<String, Any?> = emptyMap(),
) {
    /** Component whose `(os, arch)` exactly matches, or `null`. */
    fun componentFor(
        os: String,
        arch: String,
    ): RuntimeComponent? = components.firstOrNull { it.os == os && it.arch == arch }

    /** SHA-256 over the canonical document with the digest field removed. */
    fun computedDigest(): String = computeDigest(raw)

    /** True when the stored digest matches the recomputed canonical bytes. */
    fun digestMatches(): Boolean = digest.isNotEmpty() && computedDigest() == digest

    /** Canonical map form of the typed fields (diagnostics / re-serialization). */
    fun toCanonicalMap(): Map<String, Any?> =
        linkedMapOf(
            "manifest_schema" to schema,
            "manifest_version" to version,
            "manifest_digest" to digest,
            "release_id" to releaseId,
            "adapter_id" to adapterId,
            "adapter_version" to adapterVersion,
            "components" to components.map { it.toCanonicalMap() },
            "supported_platforms" to
                platforms.map {
                    linkedMapOf("os" to it.os, "arch" to it.arch)
                },
            "licenses" to
                licenses.map {
                    linkedMapOf("license_id" to it.licenseId, "spdx" to it.spdx, "path" to it.path)
                },
            "compatibility" to compatibility.toCanonicalMap(),
            "self_check" to selfCheck?.toCanonicalMap(),
            "signature" to signature,
        )

    /** Deterministic canonical JSON of the typed model. */
    fun toCanonicalJson(): String = canonicalJson(toCanonicalMap())

    /**
     * Validate the document structurally and semantically.
     *
     * Every check is collected (not short-circuited) so a builder sees all
     * problems at once. All findings are typed, so callers branch on
     * [RuntimeManifestErrorCode] rather than matching message text.
     */
    fun validate(): RuntimeManifestValidation {
        val issues = mutableListOf<RuntimeManifestIssue>()

        if (version != SUPPORTED_MANIFEST_VERSION) {
            issues +=
                RuntimeManifestIssue(
                    RuntimeManifestErrorCode.SCHEMA_VERSION_UNSUPPORTED,
                    "manifest version '$version' is not supported",
                    "manifest_version",
                )
        }
        if (schema.isNotBlank() && schema != MANIFEST_SCHEMA) {
            issues +=
                RuntimeManifestIssue(
                    RuntimeManifestErrorCode.SCHEMA_VERSION_UNSUPPORTED,
                    "manifest schema '$schema' is not supported",
                    "manifest_schema",
                )
        }

        scanSecrets(raw, "")?.let { findings ->
            findings.forEach { issues += it }
        }

        if (adapterId.isBlank() || adapterVersion.isBlank()) {
            issues +=
                RuntimeManifestIssue(
                    RuntimeManifestErrorCode.MISSING_ADAPTER_IDENTITY,
                    "adapter id and version must both be declared",
                    "adapter_id",
                )
        }

        if (!isLowercaseSha256(digest)) {
            issues +=
                RuntimeManifestIssue(
                    RuntimeManifestErrorCode.DIGEST_MISMATCH,
                    "manifest digest is missing or not 64 lowercase hex characters",
                    "manifest_digest",
                )
        } else if (computedDigest() != digest) {
            issues +=
                RuntimeManifestIssue(
                    RuntimeManifestErrorCode.DIGEST_MISMATCH,
                    "manifest content does not match its digest",
                    "manifest_digest",
                )
        }

        if (components.isEmpty()) {
            issues +=
                RuntimeManifestIssue(
                    RuntimeManifestErrorCode.EMPTY_COMPONENTS,
                    "manifest declares no components",
                    "components",
                )
        }

        if (platforms.isEmpty()) {
            issues +=
                RuntimeManifestIssue(
                    RuntimeManifestErrorCode.MALFORMED,
                    "manifest declares no supported platforms",
                    "supported_platforms",
                )
        }

        platforms
            .groupBy { it.key }
            .filterValues { it.size > 1 }
            .forEach { (key, _) ->
                issues +=
                    RuntimeManifestIssue(
                        RuntimeManifestErrorCode.DUPLICATE_SUPPORTED_PLATFORM,
                        "supported platform '$key' is declared more than once",
                        "supported_platforms",
                    )
            }
        val supportedPlatformKeys = platforms.map { it.key }.toSet()

        components
            .groupBy { it.platform.key }
            .filterValues { it.size > 1 }
            .forEach { (key, _) ->
                issues +=
                    RuntimeManifestIssue(
                        RuntimeManifestErrorCode.DUPLICATE_PLATFORM,
                        "component platform '$key' is declared more than once",
                        "components",
                    )
            }

        components
            .groupBy { it.componentId }
            .filterValues { it.size > 1 }
            .forEach { (id, _) ->
                issues +=
                    RuntimeManifestIssue(
                        RuntimeManifestErrorCode.DUPLICATE_COMPONENT_ID,
                        "component id '$id' is declared more than once",
                        "components",
                    )
            }

        components.forEachIndexed { index, component ->
            val prefix = "components[$index]"
            if (component.componentId.isBlank()) {
                issues +=
                    RuntimeManifestIssue(
                        RuntimeManifestErrorCode.MALFORMED,
                        "component id must not be blank",
                        "$prefix.component_id",
                    )
            }
            if (component.os.isBlank() || component.arch.isBlank()) {
                issues +=
                    RuntimeManifestIssue(
                        RuntimeManifestErrorCode.MALFORMED,
                        "component os/arch must not be blank",
                        prefix,
                    )
            } else if (supportedPlatformKeys.isNotEmpty() &&
                component.platform.key !in supportedPlatformKeys
            ) {
                issues +=
                    RuntimeManifestIssue(
                        RuntimeManifestErrorCode.COMPONENT_PLATFORM_NOT_SUPPORTED,
                        "component platform '${component.platform.key}' is not in supported_platforms",
                        prefix,
                    )
            }
            pathSafety(component.path)?.let {
                issues +=
                    RuntimeManifestIssue(
                        RuntimeManifestErrorCode.UNSAFE_PATH,
                        "component path is unsafe: $it",
                        "$prefix.path",
                    )
            }
            if (component.executable.isBlank()) {
                issues +=
                    RuntimeManifestIssue(
                        RuntimeManifestErrorCode.MISSING_COMPONENT_EXECUTABLE,
                        "component must declare its executable",
                        "$prefix.executable",
                    )
            } else {
                pathSafety(component.executable)?.let {
                    issues +=
                        RuntimeManifestIssue(
                            RuntimeManifestErrorCode.UNSAFE_PATH,
                            "component executable path is unsafe: $it",
                            "$prefix.executable",
                        )
                }
            }
            if (!isLowercaseSha256(component.sha256)) {
                issues +=
                    RuntimeManifestIssue(
                        RuntimeManifestErrorCode.INVALID_SHA256,
                        "component sha256 must be 64 lowercase hex characters",
                        "$prefix.sha256",
                    )
            }
            if (component.size < 0L) {
                issues +=
                    RuntimeManifestIssue(
                        RuntimeManifestErrorCode.INVALID_SIZE,
                        "declared size must not be negative",
                        "$prefix.size",
                    )
            }
            component.argsTemplate.forEachIndexed { argIndex, arg ->
                if (arg.indexOf('\u0000') >= 0) {
                    issues +=
                        RuntimeManifestIssue(
                            RuntimeManifestErrorCode.UNSAFE_PATH,
                            "argument template contains a NUL byte",
                            "$prefix.args_template[$argIndex]",
                        )
                }
            }
            component.selfCheck?.let { spec ->
                pathSafety(spec.command)?.let {
                    issues +=
                        RuntimeManifestIssue(
                            RuntimeManifestErrorCode.UNSAFE_PATH,
                            "self-check command path is unsafe: $it",
                            "$prefix.self_check.command",
                        )
                }
            }
            component.license?.path?.let { licensePath ->
                pathSafety(licensePath)?.let {
                    issues +=
                        RuntimeManifestIssue(
                            RuntimeManifestErrorCode.UNSAFE_PATH,
                            "license path is unsafe: $it",
                            "$prefix.license.path",
                        )
                }
            }
        }

        licenses.forEachIndexed { index, license ->
            license.path?.let { licensePath ->
                pathSafety(licensePath)?.let {
                    issues +=
                        RuntimeManifestIssue(
                            RuntimeManifestErrorCode.UNSAFE_PATH,
                            "license path is unsafe: $it",
                            "licenses[$index].path",
                        )
                }
            }
        }

        compatibility.constraints.forEach { (name, range) ->
            if (name.isBlank()) {
                issues +=
                    RuntimeManifestIssue(
                        RuntimeManifestErrorCode.INVALID_COMPATIBILITY,
                        "compatibility constraint name must not be blank",
                        "compatibility",
                    )
            }
            if (VersionRange.parse(range) == null) {
                issues +=
                    RuntimeManifestIssue(
                        RuntimeManifestErrorCode.INVALID_COMPATIBILITY,
                        "compatibility constraint '$name' has an unparseable range '$range'",
                        "compatibility.$name",
                    )
            }
        }

        undeclaredExecutables()?.let { undeclared ->
            undeclared.forEach { entry ->
                issues +=
                    RuntimeManifestIssue(
                        RuntimeManifestErrorCode.UNDECLARED_EXECUTABLE,
                        "executable asset '$entry' is not declared by any component",
                        "executables",
                    )
            }
        }

        return RuntimeManifestValidation(issues)
    }

    /**
     * Executable-looking asset entries present in the raw document that no
     * component declares. Returns `null` when the document carries no such list.
     */
    private fun undeclaredExecutables(): List<String>? {
        val declared = components.flatMap { listOfNotNull(it.executable.ifBlank { null }, it.path.ifBlank { null }) }.toSet()
        val keyed =
            listOf("executables", "assets", "binaries")
                .flatMap { key ->
                    val value = raw[key]
                    val entries =
                        when (value) {
                            is List<*> -> value.mapNotNull { it as? String }
                            is Map<*, *> -> value.values.mapNotNull { it as? String }
                            else -> emptyList()
                        }
                    entries.map { key to it }
                }
        if (keyed.isEmpty()) return null
        return keyed.filterNot { (_, asset) ->
            // A declared component path or executable might be a super-path/prefix.
            declared.any { it == asset || asset.endsWith("/$it") || it.endsWith("/$asset") }
        }.map { it.second }
    }

    companion object {
        /** JSON schema marker for this manifest family. */
        const val MANIFEST_SCHEMA = "code4me.runtime.manifest.v2"

        /** The only manifest version this plugin understands. */
        const val SUPPORTED_MANIFEST_VERSION = "2"

        /** SHA-256 of the canonical document with `manifest_digest` removed. */
        fun computeDigest(document: Map<String, Any?>): String = sha256Hex(canonicalJson(document.filterKeys { it != "manifest_digest" }))

        /**
         * Parse a `RuntimeManifestV2` document.
         *
         * Throws [RuntimeManifestParseException] for malformed JSON or a missing
         * required field. Unknown/extra keys are retained in [raw] and scanned by
         * [validate]; they are never silently trusted.
         */
        fun parse(json: String): RuntimeManifestV2 {
            val parsed =
                try {
                    parseCanonicalJson(json)
                } catch (exception: Exception) {
                    throw RuntimeManifestParseException("Runtime manifest is not valid JSON", exception)
                }
            if (parsed !is Map<*, *>) {
                throw RuntimeManifestParseException("Runtime manifest JSON must be an object")
            }
            val map = stringKeyedMap(parsed)
            val componentsRaw = map["components"]
            if (componentsRaw !is List<*>) {
                throw RuntimeManifestParseException("Runtime manifest field 'components' is required and must be an array")
            }
            return RuntimeManifestV2(
                schema = map["manifest_schema"] as? String ?: MANIFEST_SCHEMA,
                version = requiredString(map, "manifest_version"),
                digest = requiredString(map, "manifest_digest"),
                releaseId = requiredString(map, "release_id"),
                adapterId = requiredString(map, "adapter_id"),
                adapterVersion = requiredString(map, "adapter_version"),
                components = componentsRaw.mapIndexed { index, item -> parseComponent(item, index) },
                platforms = parsePlatforms(map["supported_platforms"]),
                licenses = parseLicenses(map["licenses"]),
                compatibility = parseCompatibility(map["compatibility"]),
                selfCheck = (map["self_check"] as? Map<*, *>)?.let { parseSelfCheck(stringKeyedMap(it)) },
                signature = map["signature"] as? String,
                raw = map,
            )
        }

        private fun parseComponent(
            value: Any?,
            index: Int,
        ): RuntimeComponent {
            if (value !is Map<*, *>) {
                throw RuntimeManifestParseException("components[$index] must be an object")
            }
            val map = stringKeyedMap(value)
            val size =
                (map["size"] as? Number)?.toLong()
                    ?: throw RuntimeManifestParseException("components[$index].size is required and must be a number")
            val license =
                (map["license"] as? Map<*, *>)?.let { rawLicense ->
                    val licenseMap = stringKeyedMap(rawLicense)
                    RuntimeLicense(
                        licenseId =
                            licenseMap["license_id"] as? String
                                ?: throw RuntimeManifestParseException("components[$index].license.license_id is required"),
                        spdx = licenseMap["spdx"] as? String,
                        path = licenseMap["path"] as? String,
                    )
                }
            return RuntimeComponent(
                componentId = requiredString(map, "component_id", "components[$index]"),
                path =
                    map["path"] as? String
                        ?: throw RuntimeManifestParseException("components[$index].path is required"),
                sha256 = requiredString(map, "sha256", "components[$index]"),
                size = size,
                os = requiredString(map, "os", "components[$index]"),
                arch = requiredString(map, "arch", "components[$index]"),
                executable = map["executable"] as? String ?: "",
                argsTemplate = (map["args_template"] as? List<*>)?.mapNotNull { it as? String } ?: emptyList(),
                signature = map["signature"] as? String,
                license = license,
                selfCheck = (map["self_check"] as? Map<*, *>)?.let { parseSelfCheck(stringKeyedMap(it)) },
            )
        }

        private fun parsePlatforms(value: Any?): List<PlatformTriple> {
            val list = value as? List<*> ?: return emptyList()
            return list.mapIndexedNotNull { index, item ->
                when (item) {
                    is Map<*, *> -> {
                        val map = stringKeyedMap(item)
                        val os = map["os"] as? String
                        val arch = map["arch"] as? String
                        if (os.isNullOrBlank() || arch.isNullOrBlank()) {
                            throw RuntimeManifestParseException(
                                "supported_platforms[$index] must declare non-blank os and arch",
                            )
                        }
                        PlatformTriple(os, arch)
                    }
                    is String -> parsePlatformKey(item, index)
                    else -> throw RuntimeManifestParseException(
                        "supported_platforms[$index] must be an object or 'os-arch' string",
                    )
                }
            }
        }

        private fun parsePlatformKey(
            value: String,
            index: Int,
        ): PlatformTriple {
            val separator = value.indexOf('-')
            if (separator <= 0 || separator == value.length - 1) {
                throw RuntimeManifestParseException("supported_platforms[$index] '$value' is not an 'os-arch' tuple")
            }
            return PlatformTriple(value.substring(0, separator), value.substring(separator + 1))
        }

        private fun parseLicenses(value: Any?): List<RuntimeLicense> {
            val list = value as? List<*> ?: return emptyList()
            return list.mapIndexed { index, item ->
                if (item !is Map<*, *>) {
                    throw RuntimeManifestParseException("licenses[$index] must be an object")
                }
                val map = stringKeyedMap(item)
                RuntimeLicense(
                    licenseId = requiredString(map, "license_id", "licenses[$index]"),
                    spdx = map["spdx"] as? String,
                    path = map["path"] as? String,
                )
            }
        }

        private fun parseCompatibility(value: Any?): CompatibilityConstraints {
            val map = (value as? Map<*, *>)?.let { stringKeyedMap(it) } ?: return CompatibilityConstraints()
            val nested = (map["constraints"] as? Map<*, *>)?.let { stringKeyedMap(it) }
            val constraints =
                (nested ?: map).entries
                    .filter { it.value is String }
                    .associate { it.key to it.value as String }
            return CompatibilityConstraints(constraints)
        }

        private fun parseSelfCheck(map: Map<String, Any?>): SelfCheckSpec =
            SelfCheckSpec(
                command = requiredString(map, "command", "self_check"),
                args = (map["args"] as? List<*>)?.mapNotNull { it as? String } ?: emptyList(),
                expectedExitCode = (map["expected_exit_code"] as? Number)?.toInt() ?: 0,
                timeoutMs = (map["timeout_ms"] as? Number)?.toLong(),
            )
    }
}

// ---------------------------------------------------------------------------
// Shared helpers
// ---------------------------------------------------------------------------

private val SHA256_PATTERN = Regex("^[0-9a-f]{64}$")

internal fun isLowercaseSha256(value: String?): Boolean = value != null && SHA256_PATTERN.matches(value)

/**
 * Shell metacharacters that must never appear in a declared asset path. The
 * launcher always passes an argument array (never a shell string), so this is
 * defence in depth against a manifest trying to smuggle interpolation.
 */
private val SHELL_METACHARACTERS = ";&|\$`<>(){}[]*?!#~\"'\\\n\r\t\u0000"

/**
 * Return a human-readable reason when [path] is not a safe, normalized relative
 * path, or `null` when it is safe.
 *
 * A safe path:
 * - is non-blank and contains no NUL or shell metacharacter;
 * - is not absolute (`/...`, `\\...`, `C:\...`, or `C:/...`);
 * - is already normalized (no `.`, `..`, empty, leading, or trailing segments);
 * - contains no `..` traversal segment.
 */
internal fun pathSafety(path: String): String? {
    if (path.isBlank()) return "path must not be blank"
    if (path.indexOf('\u0000') >= 0) return "path must not contain NUL"
    for (character in path) {
        if (SHELL_METACHARACTERS.indexOf(character) >= 0) {
            return "path must not contain shell metacharacter '${character.code.toString(16)}'"
        }
    }
    if (path.startsWith("/") || path.startsWith("\\")) return "path must be relative"
    if (path.length >= 2 && path[0].isLetter() && path[1] == ':') return "path must be relative"
    val segments = path.split('/')
    if (segments.any { it == ".." }) return "path must not contain '..'"
    if (segments.any { it.isEmpty() || it == "." }) return "path must be normalized (no empty or '.' segments)"
    val normalized =
        try {
            Paths.get(path).normalize().toString().replace('\\', '/')
        } catch (exception: InvalidPathException) {
            return "path is not a valid path: ${exception.message}"
        }
    if (normalized != path) return "path must be normalized (expected '$normalized')"
    return null
}

@Suppress("UNCHECKED_CAST")
internal fun stringKeyedMap(value: Map<*, *>): Map<String, Any?> {
    val result = LinkedHashMap<String, Any?>(value.size)
    for ((key, item) in value) {
        val name = key?.toString()
        if (name != null) result[name] = item
    }
    return result
}

internal fun requiredString(
    map: Map<String, Any?>,
    key: String,
    fieldPrefix: String? = null,
): String {
    val value = map[key]
    if (value !is String || value.isEmpty()) {
        val path = if (fieldPrefix.isNullOrEmpty()) key else "$fieldPrefix.$key"
        throw RuntimeManifestParseException("Runtime manifest field '$path' is required and must be a non-empty string")
    }
    return value
}

private fun scanSecrets(
    value: Any?,
    path: String,
): List<RuntimeManifestIssue>? {
    val issues = mutableListOf<RuntimeManifestIssue>()
    scanSecretsInto(value, path, issues)
    return issues.ifEmpty { null }
}

@Suppress("UNCHECKED_CAST")
private fun scanSecretsInto(
    value: Any?,
    path: String,
    issues: MutableList<RuntimeManifestIssue>,
) {
    when (value) {
        is Map<*, *> -> {
            for ((rawKey, item) in value) {
                val key = rawKey?.toString() ?: continue
                val childPath = if (path.isEmpty()) key else "$path.$key"
                if (isSecretManifestKey(key)) {
                    issues +=
                        RuntimeManifestIssue(
                            RuntimeManifestErrorCode.SECRET_DETECTED,
                            "manifest contains secret-shaped key at $childPath",
                            childPath,
                        )
                    continue
                }
                scanSecretsInto(item, childPath, issues)
            }
        }
        is List<*> -> {
            value.forEachIndexed { index, item ->
                scanSecretsInto(item, "$path[$index]", issues)
            }
        }
        is String -> {
            if (looksSecretManifestValue(value)) {
                issues +=
                    RuntimeManifestIssue(
                        RuntimeManifestErrorCode.SECRET_DETECTED,
                        "manifest contains secret-shaped value at $path",
                        path,
                    )
            }
        }
        else -> Unit
    }
}

// ---------------------------------------------------------------------------
// Version ranges
// ---------------------------------------------------------------------------

/** A parsed semantic version (`major.minor.patch[-pre][+build]`). */
data class SemanticVersion(
    val major: Int,
    val minor: Int = 0,
    val patch: Int = 0,
    val preRelease: String? = null,
) : Comparable<SemanticVersion> {
    override fun compareTo(other: SemanticVersion): Int {
        val majorDiff = major - other.major
        if (majorDiff != 0) return majorDiff
        val minorDiff = minor - other.minor
        if (minorDiff != 0) return minorDiff
        val patchDiff = patch - other.patch
        if (patchDiff != 0) return patchDiff
        return when {
            preRelease == null && other.preRelease == null -> 0
            preRelease == null -> 1
            other.preRelease == null -> -1
            else -> preRelease.compareTo(other.preRelease)
        }
    }

    companion object {
        private val PATTERN = Regex("^(\\d+)(?:\\.(\\d+))?(?:\\.(\\d+))?(?:-([0-9A-Za-z.-]+))?(?:\\+[0-9A-Za-z.-]+)?$")

        fun parse(text: String): SemanticVersion? {
            val match = PATTERN.matchEntire(text.trim()) ?: return null
            return SemanticVersion(
                major = match.groupValues[1].toInt(),
                minor = match.groupValues[2].ifEmpty { "0" }.toInt(),
                patch = match.groupValues[3].ifEmpty { "0" }.toInt(),
                preRelease = match.groupValues[4].ifEmpty { null },
            )
        }
    }
}

/** A conjunction of comparator clauses over [SemanticVersion]s. */
class VersionRange private constructor(
    private val clauses: List<Clause>,
    val original: String,
) {
    fun satisfies(version: SemanticVersion): Boolean = clauses.all { it.matches(version) }

    private sealed interface Clause {
        fun matches(version: SemanticVersion): Boolean

        data class Comparator(val operator: String, val bound: SemanticVersion) : Clause {
            override fun matches(version: SemanticVersion): Boolean {
                val comparison = version.compareTo(bound)
                return when (operator) {
                    ">=" -> comparison >= 0
                    ">" -> comparison > 0
                    "<=" -> comparison <= 0
                    "<" -> comparison < 0
                    "=", "==" -> comparison == 0
                    else -> false
                }
            }
        }

        data class Caret(val bound: SemanticVersion) : Clause {
            override fun matches(version: SemanticVersion): Boolean {
                if (version < bound) return false
                val upper =
                    when {
                        bound.major > 0 -> SemanticVersion(bound.major + 1, 0, 0)
                        bound.minor > 0 -> SemanticVersion(0, bound.minor + 1, 0)
                        else -> SemanticVersion(0, 0, bound.patch + 1)
                    }
                return version < upper
            }
        }

        data class Tilde(val bound: SemanticVersion) : Clause {
            override fun matches(version: SemanticVersion): Boolean {
                if (version < bound) return false
                val upper = SemanticVersion(bound.major, bound.minor + 1, 0)
                return version < upper
            }
        }
    }

    companion object {
        /** Parse a comparator list (`>=1.0.0 <2.0.0`, `^1.2.0`, `1.2.3`) or `null`. */
        fun parse(text: String): VersionRange? {
            val tokens = text.trim().replace(',', ' ').split(Regex("\\s+")).filter { it.isNotEmpty() }
            if (tokens.isEmpty()) return null
            val clauses = mutableListOf<Clause>()
            for (token in tokens) {
                val (operator, versionText) = splitOperator(token)
                val version = SemanticVersion.parse(versionText) ?: return null
                val clause =
                    when (operator) {
                        ">=", ">", "<=", "<", "=", "==" -> Clause.Comparator(operator, version)
                        "^" -> Clause.Caret(version)
                        "~" -> Clause.Tilde(version)
                        "" -> Clause.Comparator("=", version)
                        else -> return null
                    }
                clauses += clause
            }
            return VersionRange(clauses, text)
        }

        private fun splitOperator(token: String): Pair<String, String> {
            val operators = listOf(">=", "<=", "==", ">", "<", "=", "^", "~")
            for (operator in operators) {
                if (token.startsWith(operator)) return operator to token.substring(operator.length)
            }
            return "" to token
        }
    }
}
