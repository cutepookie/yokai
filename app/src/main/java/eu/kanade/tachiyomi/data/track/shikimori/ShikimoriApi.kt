package eu.kanade.tachiyomi.data.track.shikimori

import android.net.Uri
import androidx.core.net.toUri
import eu.kanade.tachiyomi.data.database.models.Track
import eu.kanade.tachiyomi.data.track.TrackManager
import eu.kanade.tachiyomi.data.track.model.TrackSearch
import eu.kanade.tachiyomi.network.DELETE
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.POST
import eu.kanade.tachiyomi.network.awaitSuccess
import eu.kanade.tachiyomi.network.jsonMime
import eu.kanade.tachiyomi.network.parseAs
import eu.kanade.tachiyomi.util.system.withIOContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.float
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.RequestBody.Companion.toRequestBody
import timber.log.Timber
import uy.kohesive.injekt.injectLazy

class ShikimoriApi(private val client: OkHttpClient, interceptor: ShikimoriInterceptor) {

    private val json: Json by injectLazy()

    private val authClient = client.newBuilder().addInterceptor(interceptor).build()

    suspend fun addLibManga(track: Track, user_id: String): Track {
        return withIOContext {
            val payload = buildJsonObject {
                putJsonObject("user_rate") {
                    put("user_id", user_id)
                    put("target_id", track.media_id)
                    put("target_type", "Manga")
                    put("chapters", track.last_chapter_read.toInt())
                    put("score", track.score.toInt())
                    put("status", track.toShikimoriStatus())
                }
            }
            authClient.newCall(
                POST(
                    "$API_URL/v2/user_rates",
                    body = payload.toString().toRequestBody(jsonMime),
                ),
            ).awaitSuccess()
            track
        }
    }

    suspend fun updateLibManga(track: Track, user_id: String): Track = addLibManga(track, user_id)

    suspend fun search(search: String): List<TrackSearch> {
        return withIOContext {
            val url = "$API_URL/mangas".toUri().buildUpon()
                .appendQueryParameter("order", "popularity")
                .appendQueryParameter("search", search)
                .appendQueryParameter("limit", "20")
                .build()
            with(json) {
                authClient.newCall(GET(url.toString()))
                    .awaitSuccess()
                    .parseAs<JsonArray>()
                    .let { response ->
                        response.map {
                            jsonToSearch(it.jsonObject)
                        }
                    }
            }
        }
    }

    suspend fun remove(track: Track, user_id: String): Boolean {
        return withIOContext {
            try {
                val rates = getUserRates(track, user_id)
                val id = rates.last().jsonObject["id"]!!.jsonPrimitive.content
                val url = "$API_URL/v2/user_rates/$id"
                authClient.newCall(DELETE(url)).awaitSuccess()
                true
            } catch (e: Exception) {
                Timber.w(e)
                false
            }
        }
    }

    private fun jsonToSearch(obj: JsonObject): TrackSearch {
        return TrackSearch.create(TrackManager.SHIKIMORI).apply {
            media_id = obj["id"]!!.jsonPrimitive.long
            title = obj["name"]!!.jsonPrimitive.content
            total_chapters = obj["chapters"]!!.jsonPrimitive.int
            cover_url = BASE_URL + obj["image"]!!.jsonObject["preview"]!!.jsonPrimitive.content
            summary = ""
            tracking_url = BASE_URL + obj["url"]!!.jsonPrimitive.content
            publishing_status = obj["status"]!!.jsonPrimitive.content
            publishing_type = obj["kind"]!!.jsonPrimitive.content
            start_date = obj["aired_on"]?.jsonPrimitive?.contentOrNull ?: ""
        }
    }

    private fun jsonToTrack(obj: JsonObject, mangas: JsonObject): Track {
        return Track.create(TrackManager.SHIKIMORI).apply {
            title = mangas["name"]!!.jsonPrimitive.content
            media_id = obj["id"]!!.jsonPrimitive.long
            total_chapters = mangas["chapters"]!!.jsonPrimitive.int
            last_chapter_read = obj["chapters"]!!.jsonPrimitive.float
            score = (obj["score"]!!.jsonPrimitive.int).toFloat()
            status = toTrackStatus(obj["status"]!!.jsonPrimitive.content)
            tracking_url = BASE_URL + mangas["url"]!!.jsonPrimitive.content
        }
    }

    private fun getUserRates(track: Track, user_id: String): JsonArray {
        val url = "$API_URL/v2/user_rates".toUri().buildUpon()
            .appendQueryParameter("user_id", user_id)
            .appendQueryParameter("target_id", track.media_id.toString())
            .appendQueryParameter("target_type", "Manga")
            .build()
        return with(json) {
            authClient.newCall(GET(url.toString()))
                .execute()
                .parseAs()
        }
    }

    suspend fun findLibManga(track: Track, user_id: String): Track? {
        return withIOContext {
            val urlMangas = "$API_URL/mangas".toUri().buildUpon()
                .appendPath(track.media_id.toString())
                .build()
            val mangas = with(json) {
                authClient.newCall(GET(urlMangas.toString()))
                    .awaitSuccess()
                    .parseAs<JsonObject>()
            }

            val entry = getUserRates(track, user_id)
            if (entry.size > 1) {
                throw Exception("Too much mangas in response")
            }
            entry.map {
                jsonToTrack(it.jsonObject, mangas)
            }.firstOrNull()
        }
    }

    suspend fun getCurrentUser(): Int {
        return withIOContext {
            with(json) {
                authClient.newCall(GET("$API_URL/users/whoami"))
                    .awaitSuccess()
                    .parseAs<JsonObject>()
                    .let {
                        it["id"]!!.jsonPrimitive.int
                    }
            }
        }
    }

    suspend fun accessToken(code: String): OAuth {
        return withIOContext {
            with(json) {
                client.newCall(accessTokenRequest(code))
                    .awaitSuccess()
                    .parseAs()
            }
        }
    }

    private fun accessTokenRequest(code: String) = POST(
        OAUTH_URL,
        body = FormBody.Builder()
            .add("grant_type", "authorization_code")
            .add("client_id", CLIENT_ID)
            .add("client_secret", CLIENT_SECRET)
            .add("code", code)
            .add("redirect_uri", REDIRECT_URL)
            .build(),
    )

    companion object {
        private const val CLIENT_ID = "zU0wHfXbpx2GwVBK7jILx6druyPdmp0J8bLUSH9NBFc"
        private const val CLIENT_SECRET = "t-I_sBzlWAbJPjkO9EYnqBpXYdPhAxjxRuoTSZgiJPg"

        private const val BASE_URL = "https://shikimori.one"
        private const val API_URL = "$BASE_URL/api"
        private const val OAUTH_URL = "$BASE_URL/oauth/token"
        private const val LOGIN_URL = "$BASE_URL/oauth/authorize"

        private const val REDIRECT_URL = "yokai://shikimori-auth"
        private const val BASE_MANGA_URL = "$API_URL/mangas"

        fun mangaUrl(remoteId: Int): String {
            return "$BASE_MANGA_URL/$remoteId"
        }

        fun authUrl(): Uri =
            LOGIN_URL.toUri().buildUpon()
                .appendQueryParameter("client_id", CLIENT_ID)
                .appendQueryParameter("redirect_uri", REDIRECT_URL)
                .appendQueryParameter("response_type", "code")
                .build()

        fun refreshTokenRequest(token: String) = POST(
            OAUTH_URL,
            body = FormBody.Builder()
                .add("grant_type", "refresh_token")
                .add("client_id", CLIENT_ID)
                .add("client_secret", CLIENT_SECRET)
                .add("refresh_token", token)
                .build(),
        )
    }
}
