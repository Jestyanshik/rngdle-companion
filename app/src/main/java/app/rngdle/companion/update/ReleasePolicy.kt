package app.rngdle.companion.update

object ReleasePolicy {
    private val apkPattern = Regex("rngdle-companion-vc([1-9][0-9]*)\\.apk")
    private val digestPattern = Regex("sha256:([a-fA-F0-9]{64})")

    fun versionCode(assetName: String): Long? =
        apkPattern.matchEntire(assetName)?.groupValues?.get(1)?.toLongOrNull()

    fun sha256(digest: String): String? =
        digestPattern.matchEntire(digest)?.groupValues?.get(1)?.lowercase()

    fun isUpdate(installed: Long, offered: Long): Boolean = offered > installed

    fun isRollback(installed: Long, offered: Long): Boolean = offered < installed
}
