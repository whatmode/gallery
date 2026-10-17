package ua.volodymyr.nanocrew.engine

sealed interface ModelStatus {
    data object Checking : ModelStatus
    data class Unavailable(val reason: String) : ModelStatus
    data object Downloadable : ModelStatus
    data class Downloading(val bytes: Long) : ModelStatus
    data class Ready(val modelName: String, val tokenLimit: Int, val systemPrompts: Boolean) : ModelStatus
    data class Failed(val message: String) : ModelStatus
    /** A model the app runs itself (Gemma) isn't loaded yet: what's needed or happening. */
    data class Preparing(val title: String, val detail: String, val progress: Float? = null, val canImport: Boolean = false) : ModelStatus
}

/** Thrown with a message that is safe to show the user as-is. */
class NanoException(message: String) : Exception(message)

