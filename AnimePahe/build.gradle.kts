// use an integer for version numbers
version = 22

android {
    // Override root namespace ("com.tekuma25") so AGP generates BuildConfig
    // in com.animepahe, matching the package declared in source files.
    // Without this, references like BuildConfig.LIBRARY_PACKAGE_NAME fail
    // with "Unresolved reference".
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
    // All of these properties are optional, you can safely remove them

    description = "Animes (SUB/DUB)"
    authors = listOf("kenewjr")

    /**
     * Status int as the following:
     * 0: Down
     * 1: Ok
     * 2: Slow
     * 3: Beta only
     * */
    status = 1 // will be 3 if unspecified
    tvTypes = listOf(
        "AnimeMovie",
        "Anime",
        "OVA",
    )
    iconUrl = "https://raw.githubusercontent.com/phisher98/TVVVV/refs/heads/main/Icons/animepahe.png"

    requiresResources = true
    isCrossPlatform = false
}
