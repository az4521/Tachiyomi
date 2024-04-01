package eu.kanade.tachiyomi.extension.api

import com.jakewharton.retrofit2.converter.kotlinx.serialization.asConverterFactory
import eu.kanade.tachiyomi.network.NetworkHelper
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import okhttp3.MediaType.Companion.toMediaType
import retrofit2.Retrofit
import retrofit2.http.GET
import retrofit2.http.Url
import uy.kohesive.injekt.injectLazy

/**
 * Used to get the extension repo listing from GitHub.
 */
interface ExtensionGithubService {
    companion object {
        @OptIn(ExperimentalSerializationApi::class)
        fun create(): ExtensionGithubService {
            val network: NetworkHelper by injectLazy()
            val adapter =
                Retrofit.Builder()
                    .baseUrl(ExtensionGithubApi.BASE_URL)
                    .addConverterFactory(Json.asConverterFactory("application/json".toMediaType()))
                    .client(network.client)
                    .build()

            return adapter.create(ExtensionGithubService::class.java)
        }
    }

    @GET
    suspend fun getRepo(
        @Url url: String
    ): JsonArray
}
