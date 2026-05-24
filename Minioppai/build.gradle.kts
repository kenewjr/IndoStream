// use an integer for version numbers
version = 13


cloudstream {
    language = "id"
    description = "Source mati permanen — minioppai.org tidak resolve. " +
        "Plugin tetap dipertahankan untuk historikal namun status diset Down."

     authors = listOf("Sora", "TeKuma25")

    /**
     * Status int as the following:
     * 0: Down
     * 1: Ok
     * 2: Slow
     * 3: Beta only
     * */
    status = 0 // [FIXED]: domain DEAD per audit Mei 2026
    tvTypes = listOf(
        "NSFW",
    )

    iconUrl = "https://www.google.com/s2/favicons?domain=minioppai.org&sz=%size%"
}