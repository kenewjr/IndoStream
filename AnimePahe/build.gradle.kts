version = 28

android {
    namespace = "com.animepahe"
}

dependencies {
    implementation("com.google.android.material:material:1.14.0")
}

cloudstream {
    language = "en"

    description = "Animes (SUB/DUB)"
    authors = listOf("phisher98")

    status = 1
    tvTypes =
        listOf(
            "AnimeMovie",
            "Anime",
            "OVA",
        )
    iconUrl = "https://raw.githubusercontent.com/phisher98/TVVVV/refs/heads/main/Icons/animepahe.png"
}
