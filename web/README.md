# Web BeatPlay (landing page statis)

Satu file `index.html` + folder `img/` (tanpa build step, tanpa JS framework).

## Isi link Android

Buka `index.html`, cari komentar `GANTI href di bawah dengan link MediaFire APK kamu`,
ganti `href="#"` dengan link MediaFire-nya.

## Deploy (pilih satu)

- **VPS yang sama (Nginx):**
  ```bash
  sudo mkdir -p /var/www/beatplay && sudo cp -r web/* /var/www/beatplay/
  ```
  Tambah server block Nginx untuk domain (mis. `beatplay.domain-anda`) root
  `/var/www/beatplay`, lalu HTTPS via certbot. Bisa digabung di server yang
  sama dengan Caddy/Nginx yang mem-proxy API premium.
- **Cloudflare Pages / Netlify Drop:** drag folder `web/` — jadi dalam 1 menit,
  gratis, HTTPS otomatis.
