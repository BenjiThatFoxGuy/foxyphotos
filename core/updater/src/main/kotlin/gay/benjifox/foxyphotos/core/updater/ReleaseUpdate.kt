package gay.benjifox.foxyphotos.core.updater

import java.net.URI

/** Versioned release contract; intentionally independent of Android and provider accounts. */
data class ReleaseUpdate(
    val packageName: String, val versionName: String, val versionCode: Long, val minSdk: Int,
    val tag: String, val filename: String, val url: String, val size: Long,
    val sha256: String, val signingCertificateSha256: String,
) {
    fun validate() {
        require(versionCode in 1..2_100_000_000L && minSdk > 0 && versionName.isNotBlank())
        require(tag.startsWith("v") && tag.length <= 128)
        require(filename.matches(Regex("[A-Za-z0-9._-]+\\.apk")))
        require(size in 1..MAX_APK_BYTES)
        require(sha256.matches(Regex("[a-f0-9]{64}")) && signingCertificateSha256.matches(Regex("[a-f0-9]{64}")))
        val uri = URI(url)
        require(uri.scheme == "https" && uri.host == "github.com" && uri.port == -1 && uri.userInfo == null && uri.fragment == null && uri.query == null)
        require(uri.path == "/$REPOSITORY/releases/download/$tag/$filename")
    }
    fun available(installedPackage: String, installedCode: Long, sdk: Int): Boolean {
        validate()
        return packageName == installedPackage && versionCode > installedCode && minSdk <= sdk
    }
    companion object {
        const val REPOSITORY = "BenjiThatFoxGuy/foxyphotos"
        const val MAX_APK_BYTES = 256L * 1024 * 1024
        const val FEED_URL = "https://api.github.com/repos/$REPOSITORY/releases/latest"
        fun allowedTransport(url: String): Boolean = runCatching {
            val uri = URI(url)
            uri.scheme == "https" && uri.port == -1 && uri.userInfo == null && uri.fragment == null &&
                (uri.host == "release-assets.githubusercontent.com" || uri.host == "objects.githubusercontent.com" ||
                    (uri.host == "github.com" && uri.path.startsWith("/$REPOSITORY/releases/download/")) ||
                    (uri.host == "api.github.com" && uri.path == "/repos/$REPOSITORY/releases/latest"))
        }.getOrDefault(false)
    }
}
