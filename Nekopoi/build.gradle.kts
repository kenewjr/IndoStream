// use an integer for version numbers
version = 8


cloudstream {
    language = "id"
    description = "Nekopoi.care provider — hentai, JAV, 3D, dan cosplay sub Indo. " +
        "Dukungan smart-search dengan prefix (tag:, jav:, hentai:, uncensored:, dst.), " +
        "rekomendasi otomatis, thumbnail per-episode."

    authors = listOf("Sora", "TeKuma25")

    /**
     * Status int as the following:
     * 0: Down
     * 1: Ok
     * 2: Slow
     * 3: Beta only
     * */
    status = 1 // will be 3 if unspecified
    tvTypes = listOf(
        "NSFW",
    )

    iconUrl = "https://www.google.com/s2/favicons?domain=nekopoi.care&sz=%size%"
}