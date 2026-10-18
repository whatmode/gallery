package ua.volodymyr.nanocrew.ui

/**
 * The crew's own FileProvider (shares edited zips and the debug log). A subclass, so the manifest
 * merger can't fold it into another library's FileProvider entry and lose its paths.
 */
class CrewFileProvider : androidx.core.content.FileProvider() {
    companion object {
        fun authority(context: android.content.Context) = context.packageName + ".crewfiles"
    }
}
