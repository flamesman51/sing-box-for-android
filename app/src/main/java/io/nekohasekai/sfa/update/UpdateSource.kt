package io.nekohasekai.sfa.update

enum class UpdateSource {
    SERVER,
    GITHUB,
    FDROID,
    ;

    companion object {
        fun fromString(value: String): UpdateSource = when (value.lowercase()) {
            "github" -> GITHUB
            "fdroid" -> FDROID
            else -> SERVER
        }
    }
}
