# IndoStream: Kumpulan Ekstensi CloudStream untuk Konten Indonesia

IndoStream adalah kumpulan ekstensi CloudStream yang berfokus pada penyediaan konten streaming dari berbagai sumber di Indonesia. Repositori ini berisi ekstensi yang memperluas fungsionalitas aplikasi CloudStream serta fork yang kompatibel (mis. **Kototoro**), memungkinkan pengguna menikmati film, serial, dan anime dari situs-situs populer di Indonesia.

## Fitur Utama

* **Beragam Sumber Konten** — Akses konten dari berbagai situs streaming populer di Indonesia.
* **Mudah Digunakan** — Instalasi sederhana melalui aplikasi CloudStream / Kototoro.
* **Pembaruan Reguler** — Domain situs di-track dan diperbarui begitu sumber upstream pindah.
* **Kompatibel Kototoro** — Repo ini langsung bisa dipakai di aplikasi Kototoro lewat menu *Add CloudStream Repository*.

## Daftar Ekstensi

Status terakhir diverifikasi: **2026-05-24** (audit menyeluruh + bug fix sweep).

| Nama Ekstensi | Domain Aktif | Status |
| ------------- | ------------ | ------ |
| Animasu       | v1.animasu.work               | Jalan, error handling diperbaiki |
| AnimeIndo     | gomunime.top                  | Jalan, ditulis ulang untuk layout Tailwind/Laravel modern |
| AnimeSail     | 154.26.137.28                 | Jalan (memerlukan WebView untuk Cloudflare Turnstile) |
| Anoboy        | anoboy.my.id                  | Jalan, parsing JSON dihilangkan, scrape HTML langsung |
| Dramaid       | dramaid.online                | Jalan, episode selector multi-fallback |
| DramaSerial   | tv44.juragan.film             | Jalan, NPE line 74 fixed, URL kategori diperbaiki |
| Dubbindo      | www.dubbindo.site             | Jalan, atribut quality `res` diperbaiki |
| Dutamovie     | 167.99.77.142                 | Jalan, deteksi tipe konten lebih akurat |
| Kuramanime    | v18.kuramanime.ing            | Jalan, loadLinks diimplementasi |
| Kuronime      | kuronime.net                  | Jalan, episode selector multi-fallback |
| LayarKaca     | tv1.lk21official.love         | Jalan, episode selector multi-fallback |
| Minioppai     | minioppai.org                 | Jalan (terkonfirmasi user, audit sandbox tidak bisa connect) |
| Nekopoi       | nekopoi.care                  | Jalan, multi-stream support + smart-search |
| Neonime       | otakupoi.org/neonime          | Jalan, episode null-safety diperbaiki |
| Ngefilm       | new35.ngefilm.site            | Jalan, semua fungsi diimplementasi (sebelumnya stub) |
| Nimegami      | nimegami.id                   | Jalan, fallback iframe extractor |
| Oploverz      | vip.oploverz.ltd              | Jalan, episode regex robust |
| Otakudesu     | otakudesu.blog                | Jalan, search pagination penuh (10 halaman) |
| Pencurimovie  | ww11.pencurimovie.sbs         | Jalan |
| Pusatfilm     | v3.pusatfilm21info.com        | Jalan, super.load() bug fixed, loadLinks diimplementasi |
| Rebahin       | rebahin.ink                   | Jalan, baseLink null-safety diperbaiki |
| Samehadaku    | v2.samehadaku.how             | Jalan, ditambah filter genre/status/tahun + jadwal rilis |

### Penambahan Fitur

* **Otakudesu** — Pencarian sekarang mengembalikan **semua hasil** (sampai 10 halaman pagination), bukan hanya halaman pertama.
* **Samehadaku** — Browse rows ditambah: filter berdasarkan **status** (ongoing/completed), **tipe** (TV/Movie/OVA), **genre** populer (Action, Adventure, Comedy, dst.), **tahun** (2024–2026), dan **jadwal rilis**.
* **Nekopoi** — Multi-stream extraction: tiap episode sekarang menampilkan Server 1/2/3 streaming + Pixeldrain direct stream untuk semua resolusi (4K, 1080p, 720p, 480p, 360p).

### Plugin Dihapus

* **Oppadrama** — Dihapus karena domain bare-IP `45.11.57.64` tidak resolve.
* Ekstensi yang sebelumnya dihapus dari repo ini: Funmovieslix, Gomov, Gomunime, Idlix, IndoTV, Nodrakorid, NontonAnimeID, Raveeflix.

## Cara Menggunakan

### CloudStream

1. Buka aplikasi CloudStream.
2. Buka menu **Ekstensi**.
3. Klik **Tambahkan Repositori**.
4. Masukkan URL repositori IndoStream:
   ```
   https://raw.githubusercontent.com/kenewjr/IndoStream/builds/repo.json
   ```
5. Klik **Tambahkan**, lalu pilih ekstensi yang ingin di-instal.

### Kototoro

1. Buka aplikasi Kototoro.
2. Masuk ke menu *Sources / Extensions*, pilih kategori **CloudStream**.
3. Tambahkan URL yang sama dengan langkah CloudStream di atas.

## Membangun Proyek

Proyek menggunakan Gradle wrapper. Untuk build lokal:

```sh
./gradlew make makePluginsJson ensureJarCompatibility
```

CI build otomatis (GitHub Actions) mem-publish artefak `.cs3` + `plugins.json` + `repo.json` ke branch `builds`.

## Kontribusi

Kontribusi disambut. Untuk menambah ekstensi baru, memperbaiki bug, atau melaporkan domain mati, silakan buka *issue* atau kirim *pull request*.

## Catatan Audit Mei 2026

Audit menyeluruh dilakukan pada 18 plugin. Bug yang umum ditemukan:

1. **Selector outdated** — Banyak plugin pakai selector spesifik (`div.bixbox.bxcl > ul > li`, `.eplister > ul > li`, dll.) yang tidak lagi cocok ketika theme upstream berubah. Solusi: relax dengan multi-fallback selector chain.
2. **NPE pada `!!` operator** — Beberapa plugin pakai `!!` pada `selectFirst()` yang bisa null. Solusi: ganti dengan `?:` fallback chain.
3. **Stub implementations** — Pusatfilm dan Ngefilm punya `super.load()` atau tidak punya implementasi sama sekali, sehingga throw `NotImplementedError`. Solusi: tulis implementasi penuh.
4. **Outdated AJAX endpoint** — Anoboy memanggil `/my-ajax` yang sekarang 404. Solusi: scrape HTML langsung.
5. **Domain migration** — Otakudesu pindah dari `.cloud` ke `.blog`. Animasu dari `.top` ke `.work` untuk konten. Solusi: update `mainUrl`.
