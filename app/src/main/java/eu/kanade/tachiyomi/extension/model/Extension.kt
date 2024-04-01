package eu.kanade.tachiyomi.extension.model

import eu.kanade.tachiyomi.source.Source

sealed class Extension {
    abstract val name: String
    abstract val pkgName: String
    abstract val versionName: String
    abstract val versionCode: Int
    abstract val libVersion: Double
    abstract val lang: String?
    abstract val isNsfw: Boolean

    data class Installed(
        override val name: String,
        override val pkgName: String,
        override val versionName: String,
        override val versionCode: Int,
        override val libVersion: Double,
        override val lang: String,
        override val isNsfw: Boolean,
        val sources: List<Source>,
        val hasUpdate: Boolean = false,
        val isObsolete: Boolean = false,
        val isUnofficial: Boolean = false,
        val isRedundant: Boolean = false
    ) : Extension()

    data class Available(
        override val name: String,
        override val pkgName: String,
        override val versionName: String,
        override val versionCode: Int,
        override val libVersion: Double,
        override val lang: String,
        override val isNsfw: Boolean,
        val apkName: String,
        val iconUrl: String,
        // SY -->
        val repoUrl: String
        // SY <--
    ) : Extension()

    data class Untrusted(
        override val name: String,
        override val pkgName: String,
        override val versionName: String,
        override val versionCode: Int,
        override val libVersion: Double,
        val signatureHash: String,
        override val lang: String? = null,
        override val isNsfw: Boolean = false
    ) : Extension()
}
