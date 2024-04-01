package eu.kanade.tachiyomi.extension.api

import android.content.Context
import eu.kanade.tachiyomi.data.preference.PreferencesHelper
import eu.kanade.tachiyomi.extension.model.Extension
import eu.kanade.tachiyomi.extension.model.LoadResult
import eu.kanade.tachiyomi.extension.util.ExtensionLoader
import exh.source.BlacklistedSources
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import uy.kohesive.injekt.injectLazy
import java.util.Date

internal class ExtensionGithubApi {
    private val preferences: PreferencesHelper by injectLazy()

    suspend fun findExtensions(): List<Extension.Available> {
        val service: ExtensionGithubService = ExtensionGithubService.create()

        return preferences.extensionRepos().get().flatMap {
            try {
                if (it.endsWith(".json")) {
                    val response = service.getRepo(it)
                    parseResponse(response, it)
                } else {
                    val url = "$BASE_URL$it/repo/"
                    val response = service.getRepo("${url}index.min.json")
                    parseResponse(response, url)
                }
            } catch (e: Exception) {
                emptyList()
            }
        }
    }

    suspend fun checkForUpdates(context: Context): List<Extension.Installed> {
        val extensions = findExtensions()

        preferences.lastExtCheck().set(Date().time)

        // SY -->
        val blacklistEnabled = preferences.eh_enableSourceBlacklist().get()
        // SY <--

        val installedExtensions =
            ExtensionLoader.loadExtensions(context)
                .filterIsInstance<LoadResult.Success>()
                .map { it.extension }
                // SY -->
                .filterNot { it.isBlacklisted(blacklistEnabled) }
        // SY <--

        val extensionsWithUpdate = mutableListOf<Extension.Installed>()
        for (installedExt in installedExtensions) {
            val pkgName = installedExt.pkgName
            val availableExt = extensions.find { it.pkgName == pkgName } ?: continue

            val hasUpdate = availableExt.versionCode > installedExt.versionCode || availableExt.libVersion > installedExt.libVersion
            if (hasUpdate) {
                extensionsWithUpdate.add(installedExt)
            }
        }

        return extensionsWithUpdate
    }

    private fun parseResponse(
        json: JsonArray,
        repoUrl: String
    ): List<Extension.Available> {
        return json
            .filter { element ->
                val versionName = element.jsonObject["version"]!!.jsonPrimitive.content
                val libVersion = versionName.substringBeforeLast('.').toDouble()
                libVersion >= ExtensionLoader.LIB_VERSION_MIN && libVersion <= ExtensionLoader.LIB_VERSION_MAX
            }
            .map { element ->
                val name = element.jsonObject["name"]!!.jsonPrimitive.content.substringAfter("Tachiyomi: ")
                val pkgName = element.jsonObject["pkg"]!!.jsonPrimitive.content
                val apkName = element.jsonObject["apk"]!!.jsonPrimitive.content
                val versionName = element.jsonObject["version"]!!.jsonPrimitive.content
                val versionCode = element.jsonObject["code"]!!.jsonPrimitive.int
                val libVersion = versionName.substringBeforeLast('.').toDouble()
                val lang = element.jsonObject["lang"]!!.jsonPrimitive.content
                val nsfw = element.jsonObject["nsfw"]!!.jsonPrimitive.int == 1
                // SY -->
                val icon = "${repoUrl.substringBeforeLast("index.min.json")}icon/$pkgName.png"
                // SY <--

                Extension.Available(name, pkgName, versionName, versionCode, libVersion, lang, nsfw, apkName, icon /* SY --> */, repoUrl /* SY <-- */)
            }
    }

    fun getApkUrl(extension: Extension.Available): String {
        return "${extension.repoUrl.substringBeforeLast("index.min.json")}apk/${extension.apkName}"
    }

    private fun Extension.isBlacklisted(blacklistEnabled: Boolean = preferences.eh_enableSourceBlacklist().get()): Boolean {
        return pkgName in BlacklistedSources.BLACKLISTED_EXTENSIONS && blacklistEnabled
    }

    companion object {
        const val BASE_URL = "https://raw.githubusercontent.com/"
    }
}
