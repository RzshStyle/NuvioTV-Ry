package com.nuvio.tv.updater

import com.nuvio.tv.BuildConfig
import com.nuvio.tv.data.remote.api.GitHubReleaseApi
import com.nuvio.tv.updater.model.AppUpdate
import java.net.URLEncoder
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject

internal class NoEligibleUpdateException(channel: UpdateChannel) :
    IllegalStateException("No compatible APK release found for ${channel.storedValue} channel")

@Singleton
class UpdateRepository @Inject constructor(
    private val gitHubReleaseApi: GitHubReleaseApi,
    private val okHttpClient: OkHttpClient
) {

    suspend fun getLatestUpdate(channel: UpdateChannel): Result<AppUpdate> {
        // Test builds are their own app: they update only from the tester-only service, never from
        // the public releases.
        if (BuildConfig.NUVIO_CHANNEL == "test") {
            return runCatching { latestTestBuild(channel) }
        }
        // Ry builds: the fixed "plus-tv-test" release carries a version file next to the APK.
        if (BuildConfig.RY_UPDATE_URL.isNotBlank()) {
            return runCatching { latestRyBuild(channel) }
        }
        return runCatching {
            val owner = BuildConfig.GITHUB_OWNER
            val repo = BuildConfig.GITHUB_REPO

            val releases = when (channel) {
                UpdateChannel.STABLE -> {
                    val response = gitHubReleaseApi.getLatestRelease(owner = owner, repo = repo)
                    if (!response.isSuccessful) {
                        error("GitHub API error: ${response.code()}")
                    }
                    listOf(response.body() ?: error("Empty GitHub release response"))
                }
                UpdateChannel.BETA -> {
                    val response = gitHubReleaseApi.getReleases(owner = owner, repo = repo)
                    if (!response.isSuccessful) {
                        error("GitHub API error: ${response.code()}")
                    }
                    response.body() ?: error("Empty GitHub release response")
                }
            }
            val releaseWithAsset = ReleaseSelector
                .eligibleReleases(releases, channel)
                .firstNotNullOfOrNull { release ->
                    AbiSelector.chooseBestApkAsset(release.assets)?.let { asset ->
                        release to asset
                    }
                }
                ?: throw NoEligibleUpdateException(channel)
            val (dto, asset) = releaseWithAsset

            val tag = dto.tagName?.takeIf { it.isNotBlank() }
                ?: dto.name?.takeIf { it.isNotBlank() }
                ?: error("Release has no tag/name")

            AppUpdate(
                tag = tag,
                title = dto.name?.takeIf { it.isNotBlank() } ?: tag,
                notes = dto.body.orEmpty(),
                releaseUrl = dto.htmlUrl,
                assetName = asset.name,
                assetUrl = asset.browserDownloadUrl,
                assetSizeBytes = asset.size,
                assetSha256 = Checksum.fromDigest(asset.digest)
            )
        }
    }

    /**
     * The newest Ry build, from the version file the build workflow publishes next to the APK:
     * {"versionName": "1.2.0-ry.57+beta.4", "versionCode": 108357, "notes": "...",
     *  "apk": {"name": "NuvioPlus-TV.apk", "url": "https://github.com/...", "size": 123, "sha256": "..."}}
     * The version name is compared like any release tag, so the rest of the updater works unchanged.
     */
    private suspend fun latestRyBuild(channel: UpdateChannel): AppUpdate = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url(BuildConfig.RY_UPDATE_URL)
            .header("Cache-Control", "no-cache")
            .build()
        okHttpClient.newCall(request).execute().use { response ->
            // 404: the release is being replaced by a new build right now, or there is none yet.
            if (response.code == 404) throw NoEligibleUpdateException(channel)
            if (!response.isSuccessful) error("Couldn't check for updates (${response.code})")
            val json = JSONObject(response.body?.string() ?: error("Empty update response"))
            val versionName = json.optString("versionName")
            if (VersionUtils.parse(versionName) == null) error("Update has no usable version")
            // An older build (a lower version code) is never offered, even if its name sorts higher.
            val versionCode = json.optLong("versionCode", -1L)
            if (versionCode in 0..BuildConfig.VERSION_CODE.toLong()) throw NoEligibleUpdateException(channel)
            // 32-bit-only devices (Chromecast with Google TV and many sticks) take the smaller
            // 32-bit APK when the build has one; everything else the all-in-one APK.
            val only32Bit = android.os.Build.SUPPORTED_64_BIT_ABIS.isNullOrEmpty()
            val apk = json.optJSONObject("apk32")?.takeIf { only32Bit } ?: json.getJSONObject("apk")
            val url = apk.optString("url").takeIf { it.startsWith("https://github.com/") }
                ?: error("Update has no download address")
            AppUpdate(
                tag = versionName,
                title = "Nuvio + IPTV $versionName",
                notes = json.optString("notes"),
                releaseUrl = json.optString("releaseUrl").takeIf { it.startsWith("https://") },
                assetName = apk.optString("name").ifBlank { "NuvioPlus-TV.apk" },
                assetUrl = url,
                assetSizeBytes = apk.optLong("size", -1L).takeIf { it > 0 },
                assetSha256 = Checksum.fromDigest(apk.optString("sha256"))
            )
        }
    }

    /** The newest test build, from the tester-only service (needs the tester key the build was made with). */
    private suspend fun latestTestBuild(channel: UpdateChannel): AppUpdate = withContext(Dispatchers.IO) {
        val key = BuildConfig.TESTER_KEY
        if (key.isBlank()) throw NoEligibleUpdateException(channel)
        val base = BuildConfig.TEST_UPDATES_URL.trimEnd('/')
        val request = Request.Builder()
            .url("$base/v1/updates/test/latest")
            .header("X-Tester-Key", key)
            .build()
        okHttpClient.newCall(request).execute().use { response ->
            // 404: no test build yet, or this build's key is no longer accepted.
            if (response.code == 404) throw NoEligibleUpdateException(channel)
            if (!response.isSuccessful) error("Couldn't check for test builds (${response.code})")
            val json = JSONObject(response.body?.string() ?: error("Empty test update response"))
            val versionName = json.optString("versionName")
            if (VersionUtils.parse(versionName) == null) error("Test update has no usable version")
            val apk = json.getJSONObject("apk")
            val url = apk.optString("url").takeIf { it.startsWith("https://") }
                ?: error("Test update has no download address")
            AppUpdate(
                tag = versionName,
                title = versionName,
                notes = json.optString("notes"),
                releaseUrl = null,
                assetName = apk.optString("name").ifBlank { "NuvioIPTV-Test.apk" },
                // The download needs the key too; it is sent in the address because the downloader
                // makes a plain request.
                assetUrl = url + (if ('?' in url) "&" else "?") + "key=" + URLEncoder.encode(key, "UTF-8"),
                assetSizeBytes = apk.optLong("size", -1L).takeIf { it > 0 },
                assetSha256 = Checksum.fromDigest(apk.optString("sha256"))
            )
        }
    }
}
