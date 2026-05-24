# IndoStream: Kumpulan Ekstensi CloudStream untuk Konten Indonesia

IndoStream adalah kumpulan ekstensi CloudStream yang berfokus pada penyediaan konten streaming dari berbagai sumber di Indonesia. Repositori ini berisi ekstensi yang memperluas fungsionalitas aplikasi CloudStream, memungkinkan pengguna untuk menikmati berbagai macam film, serial, dan anime dari situs-situs populer di Indonesia.

## Fitur Utama

*   **Beragam Sumber Konten:** Akses konten dari berbagai situs streaming populer di Indonesia.
*   **Mudah Digunakan:** Instalasi dan penggunaan yang sederhana melalui aplikasi CloudStream.
*   **Pembaruan Reguler:** Ekstensi diperbarui secara berkala untuk memastikan kompatibilitas dan ketersediaan konten.
*   **Fokus pada Konten Indonesia:** Kumpulan ekstensi ini berfokus pada konten yang relevan dengan pengguna di Indonesia.

## Daftar Ekstensi

| Nama Ekstensi     | Status |
| ----------------- | ------ |
| AnimeIndo         | Jalan |
| AnimePahe         | Jalan|
| Anoboy            | Jalan |
| Dubbindo          | Jalan |
| IdlixProvider     | Jalan |
| LayarKacaProvider | Jalan |
| Nekopoi           | Jalan |
| Pencurimovie      | Jalan |
| Rebahin           | Jalan |
| Samehadaku        | Jalan |

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

## Membangun Proyek

Proyek menggunakan Gradle wrapper. Untuk build lokal:

```sh
./gradlew make makePluginsJson ensureJarCompatibility
```

CI build otomatis (GitHub Actions) mem-publish artefak `.cs3` + `plugins.json` + `repo.json` ke branch `builds`.

## Kontribusi

Kami menyambut baik kontribusi dari komunitas! Jika Anda ingin menambahkan ekstensi baru, memperbaiki bug, atau meningkatkan dokumentasi, silakan buat *pull request* atau buka *issue*.
