package io.github.arturaragao.bancocbr

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateSourceTest {
    private val release = "https://github.com/arturaragao/banco-cbr-android/releases/download/v1.1.8/banco-cbr.apk"

    @Test fun acceptsOnlyTheRepositoryReleaseApk() {
        assertTrue(UpdateSource.isTrustedApkUrl(release))
        listOf(
            release.replace("https:", "http:"),
            release.replace("github.com", "github.com.evil.example"),
            release.replace("github.com", "github.com@evil.example"),
            release.replace("github.com", "evil.example@github.com"),
            release.replace("github.com", "github.com:8080"),
            release.replace("arturaragao/", "other/"),
            release.replace("banco-cbr-android/", "other/"),
            release.replace(".apk", ".html"),
            "$release?redirect=other", "$release#fragment", "not a URL"
        ).forEach { assertFalse(it, UpdateSource.isTrustedApkUrl(it)) }
    }

    @Test fun rejectsTraversalAndMissingReleaseSegments() {
        listOf("../banco-cbr.apk", "%2e%2e/banco-cbr.apk", "v1.1.8%2f../banco-cbr.apk", "v1.1.8/nested/banco-cbr.apk", "/banco-cbr.apk", "banco-cbr.apk")
            .forEach { suffix ->
                assertFalse(suffix, UpdateSource.isTrustedApkUrl(release.substringBefore("v1.1.8/") + suffix))
            }
    }
}
