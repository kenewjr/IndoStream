version = 22

android {

    namespace = "com.animepahe"
    buildFeatures {
        buildConfig = true
        viewBinding = true
    }
}

dependencies {
    implementation("com.google.android.material:material:1.13.0")
}

cloudstream {
    language = "en"

    description = "Animes (SUB/DUB)"
    authors = listOf("kenewjr")

    status = 1
    tvTypes =
        listOf(
            "AnimeMovie",
            "Anime",
            "OVA",
        )
    iconUrl = "https://raw.githubusercontent.com/phisher98/TVVVV/refs/heads/main/Icons/animepahe.png"

    requiresResources = true
    isCrossPlatform = false
}
