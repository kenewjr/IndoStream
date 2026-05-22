package com.dutamovie

import com.lagradost.cloudstream3.extractors.JWPlayer

// Sebelumnya ketiga class di-deklarasikan dengan nama yang sama (Embedfirex),
// menyebabkan compile error "Redeclaration". Tiap kelas sekarang punya nama
// unik sesuai service yang diwakilkan.

class Embedpyrox : JWPlayer() {
    override var name = "embedpyrox"
    override var mainUrl = "https://embedpyrox.xyz"
}

class Helvid : JWPlayer() {
    override var name = "helvid"
    override var mainUrl = "https://helvid.net"
}

class P2pplay : JWPlayer() {
    override var name = "p2pplay"
    override var mainUrl = "https://pm21.p2pplay.pro"
}
