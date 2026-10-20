# Web BeatPlay (landing page statis)

Folder ini berisi `index.html`, `download.html`, dan gambar di `img/` — tanpa
build step dan tanpa framework.

## Link Android

Link APK ada di `download.html`:

- `ANDROID_APK_URL` — endpoint `download-apk.php` (resolver MediaFire)
- `ANDROID_APK_NAME` — nama file yang ditampilkan
- `ANDROID_APK_SIZE` — ukuran yang ditampilkan

APK resmi saat ini:
`https://www.mediafire.com/file/qh977ew8l7rqk0y/BeatPlay-1.2.1-android.apk/file`

MediaFire gratis tidak memberi direct-download permanen. `download-apk.php`
mengambil link CDN sementara dari halaman file MediaFire, lalu mengalihkan
browser ke link itu. Bila gagal, browser diarahkan ke halaman file MediaFire
sebagai fallback. Ganti `MEDIAFIRE_FILE_PAGE` di `download-apk.php` dan
`ANDROID_APK_NAME`/`ANDROID_APK_SIZE` di `download.html` setiap kali ada APK
baru. Unggah APK ke MediaFire lebih dulu.

## Deploy InfinityFree

1. Login ke vPanel InfinityFree, buka **File Manager**.
2. Masuk ke folder `htdocs`. Hapus `index2.html` bawaan bila ada.
3. Upload `BeatPlay-website.zip` ke `htdocs`.
4. Klik kanan zip tersebut, pilih **Extract**, lalu hapus zip-nya. Pastikan
   `index.html`, `download.html`, `download-apk.php`, dan folder `img/`
   berada langsung di `htdocs` (bukan di dalam subfolder).
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
