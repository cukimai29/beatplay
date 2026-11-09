# Web BeatPlay (landing page statis)

Folder ini berisi `index.html`, `download.html`, stylesheet bersama di
`assets/site.css`, dan gambar di `img/` — tanpa build step dan tanpa framework.

## Link Android

Link APK ada di `download.html` dan mengarah langsung ke GitHub Release:

- `ANDROID_APK_URL` — URL aset GitHub Release
- `ANDROID_APK_NAME` — nama file yang ditampilkan
- `ANDROID_APK_SIZE` — ukuran yang ditampilkan

APK resmi saat ini:
`https://github.com/cukimai29/beatplay/releases/download/android-v1.3.2/BeatPlay-1.3.2-android.apk`

Jika ada APK baru, unggah ke release `android-v*`, lalu ganti tiga konstanta
tersebut di `download.html`. CSS dan HTML perlu disalin ke `BeatPlay-website/`
sebelum membuat archive deployment.

## Deploy InfinityFree

1. Login ke vPanel InfinityFree, buka **File Manager**.
2. Masuk ke folder `htdocs`. Hapus `index2.html` bawaan bila ada.
3. Upload `BeatPlay-website.zip` ke `htdocs`.
4. Klik kanan zip tersebut, pilih **Extract**, lalu hapus zip-nya. Pastikan
   `index.html`, `download.html`, folder `img/`, dan folder `assets/` berada
   langsung di `htdocs` (bukan di dalam subfolder).
5. Buka domainnya, cek halaman utama dan tab **Android → Download**.
6. Buka **SSL/TLS → Free SSL Certificates**, pilih domain, lalu install
   sertifikat gratis.
7. Setelah sertifikat aktif, aktifkan **Force HTTPS** di menu SSL.
8. Bila halaman lama masih muncul, tunggu beberapa menit lalu hard-refresh
   (`Ctrl+F5`).

## Deploy alternatif

- **VPS yang sama (Nginx):**
  ```bash
  sudo mkdir -p /var/www/beatplay && sudo cp -r web/* /var/www/beatplay/
  ```
  Tambah server block Nginx untuk domain, root `/var/www/beatplay`, lalu HTTPS
  via certbot. Bisa digabung di server yang sama dengan API premium.
- **Cloudflare Pages / Netlify Drop:** drag folder `web/` — langsung online
  dengan HTTPS otomatis.
