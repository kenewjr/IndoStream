// use an integer for version numbers
version = 14


cloudstream {
    language = "id"
    description = "Minioppai - Hentai/JAV stream provider. Domain " +
        "https://minioppai.org/ aktif (terkonfirmasi user audit Mei 2026, " +
        "diakses lewat browser). Beberapa region/ISP mungkin perlu VPN."

     authors = listOf("Sora", "TeKuma25")

    /**
     * Status int as the following:
     * 0: Down
     * 1: Ok
     * 2: Slow
     * 3: Beta only
     * */
    // [FIXED]: status dikembalikan ke 1 — domain aktif, sebelumnya
    // ditandai down karena sandbox audit tidak bisa resolve port 443
    // (ISP-level block), bukan karena domain mati.
    status = 1
    tvTypes = listOf(
        "NSFW",
    )

    iconUrl = "https://www.google.com/s2/favicons?domain=minioppai.org&sz=%size%"
}
