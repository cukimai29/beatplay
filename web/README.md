# Web BeatPlay (landing page statis)

Folder ini berisi `index.html`, `download.html`, dan gambar di `img/` — tanpa
build step dan tanpa framework.

## Link Android

Link APK ada di `download.html`:

- `ANDROID_APK_URL` — URL file APK
- `ANDROID_APK_NAME` — nama file yang ditampilkan
- `ANDROID_APK_SIZE` — ukuran yang ditampilkan

Ganti ketiganya setiap kali ada APK baru. Untuk mirror MediaFire, cukup ganti
`ANDROID_APK_URL` dengan link download MediaFire.

## Deploy InfinityFree

1. Login ke vPanel InfinityFree, buka **File Manager**.
2. Masuk ke folder `htdocs`.
3. Upload isi folder `web/` (atau `BeatPlay-website.zip`, lalu extract di
   `htdocs` dan hapus zip-nya).
4. Buka **SSL/TLS → Free SSL Certificates**, pilih domain, lalu install
   sertifikat gratis.
5. Aktifkan **Force HTTPS** di menu SSL setelah sertifikat aktif.

## Deploy alternatif

- **VPS yang sama (Nginx):**
  ```bash
  sudo mkdir -p /var/www/beatplay && sudo cp -r web/* /var/www/beatplay/
  ```
  Tambah server block Nginx untuk domain, root `/var/www/beatplay`, lalu HTTPS
  via certbot. Bisa digabung di server yang sama dengan API premium.
- **Cloudflare Pages / Netlify Drop:** drag folder `web/` — langsung online
  dengan HTTPS otomatis.
