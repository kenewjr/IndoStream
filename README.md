# IndoStream: Kumpulan Ekstensi CloudStream untuk Konten Indonesia

IndoStream adalah kumpulan ekstensi CloudStream yang berfokus pada penyediaan konten streaming dari berbagai sumber di Indonesia. Repositori ini berisi ekstensi yang memperluas fungsionalitas aplikasi CloudStream serta fork yang kompatibel (mis. **Kototoro**), memungkinkan pengguna menikmati film, serial, dan anime dari situs-situs populer di Indonesia.

## Fitur Utama

* **Beragam Sumber Konten** — Akses konten dari berbagai situs streaming populer di Indonesia.
* **Mudah Digunakan** — Instalasi sederhana melalui aplikasi CloudStream / Kototoro.
* **Pembaruan Reguler** — Domain situs di-track dan diperbarui begitu sumber upstream pindah.
* **Kompatibel Kototoro** — Repo ini langsung bisa dipakai di aplikasi Kototoro lewat menu *Add CloudStream Repository*.

## Daftar Ekstensi

| Nama Ekstensi | Status |
| ------------- | ------ |
| AnimeIndo     | Jalan |
| Anoboy        | Jalan |
| Dubbindo      | Jalan |
| Kuramanime    | Jalan |
| Nekopoi       | Jalan |
| Oploverz      | Jalan |
| Otakudesu     | Jalan |
| Pencurimovie  | Jalan |
| Rebahin       | Jalan |
| Samehadaku    | Jalan |

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
