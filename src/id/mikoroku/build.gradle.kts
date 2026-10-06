import io.github.keiyoushi.gradle.api.ContentWarning

plugins {
    alias(kei.plugins.extension)
}

keiyoushi {
    name = "MikoRoku"
    versionCode = 27
    contentWarning = ContentWarning.MIXED
    libVersion = "1.6"

    source {
        lang = "id"
        baseUrl {
            mirrors(
                "https://mikoroku.top",
                "https://mikoroku.com",
            )
        }
        versionId = 2
    }

    deeplink {
        host("mikoroku.top")
        host("www.mikoroku.top")
        host("mikoroku.com")
        host("www.mikoroku.com")
        path("/detail.html")
    }
}
