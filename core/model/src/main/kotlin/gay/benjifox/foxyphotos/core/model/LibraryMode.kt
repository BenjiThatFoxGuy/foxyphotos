package gay.benjifox.foxyphotos.core.model

/** Main-library sources only; independent of app layout and backup authorization. */
enum class LibraryMode(
    val storedValue: String,
    val title: String,
    val sources: String,
    val includeLocal: Boolean,
    val includeGoogle: Boolean,
    val includeICloud: Boolean,
) {
    LOCAL_ONLY("local", "Local only", "Device photos only", true, false, false),
    GOOGLE_PHOTOS("google", "Google Photos", "Google Photos only · no device photos", false, true, false),
    GOOGLE_AND_ICLOUD("google-icloud", "Google Photos + iCloud", "Both clouds · no device photos", false, true, true),
    ICLOUD_ONLY("icloud", "iCloud only", "iCloud only · no device photos or Google", false, false, true);

    companion object {
        const val PREFERENCE_KEY = "main-library-mode"
        fun fromStored(value: String?): LibraryMode = entries.firstOrNull { it.storedValue == value } ?: LOCAL_ONLY
    }
}
