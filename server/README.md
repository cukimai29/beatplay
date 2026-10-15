# BeatPlay Premium server (QRIS manual + Tripay/Xendit + admin)

- Gratis: iklan audio tiap X lagu. Premium: tanpa iklan, berbasis Gmail + **expiry**.
- Default: **QRIS manual** — pembeli bayar ke QRIS statis kamu (DANA Bisnis),
  kirim bukti dari aplikasi, admin verifikasi di dashboard lalu aktifkan.
  Taruh scan QRIS di `server/manual_qris.png` + atur nama/instruksi di `plans.json`.
- Alternatif otomatis: **Tripay** (pendaftaran tutup sementara) atau **Xendit**
  (pilih via `provider` di `tripay_config.json`).
- Paket default di `plans.json`: 1 bln Rp20.000 / 3 bln Rp55.000 / 12 bln Rp199.000.

## Mode

- `mock` (default, tanpa kredensial): order dibuat lokal, admin klik **Simulasi bayar** di dashboard. Untuk tes flow end-to-end.
- `sandbox`: isi `tripay_config.json` dari dashboard Tripay (API & Integrasi > Simulator).
- `production`: isi dari Member > Merchant > Opsi > Edit + aktifkan channel QRIS2.

```bash
cp tripay_config.example.json tripay_config.json  # lalu isi, JANGAN commit
python3 premium_server.py --port 8080 --admin-token admin123
# VPS (callback provider butuh URL publik HTTPS):
python3 premium_server.py --port 8080 --admin-token TOKEN-KUAT --public-base https://pay.domain-anda
```

## Deploy ke VPS (Ubuntu, mis. Azure)

Mode manual tidak butuh callback/HTTPS — cukup HTTP + port terbuka.

```bash
# 1. Di VPS:
sudo apt update && sudo apt install -y python3 git
sudo useradd -r -m -d /opt/beatplay beatplay
sudo mkdir -p /opt/beatplay && sudo chown beatplay:beatplay /opt/beatplay

# 2. Dari PC (PowerShell), kirim folder server + logo QRIS:
scp -r server\*.py server\*.json server\*.example <user>@<ip-vps>:/tmp/server-new/
# lalu di VPS: pindahkan ke /opt/beatplay/server, samakan tripay_config.json
# (isi notify Telegram + provider manual), taruh manual_qris.png bila ada.

# 3. Env + service:
sudo cp server/premium.env.example /etc/beatplay-premium.env
sudo nano /etc/beatplay-premium.env   # isi ADMIN_TOKEN kuat
sudo chmod 600 /etc/beatplay-premium.env
sudo cp server/beatplay-premium.service /etc/systemd/system/
sudo systemctl daemon-reload
sudo systemctl enable --now beatplay-premium
systemctl status beatplay-premium --no-pager

# 4. Buka firewall:
sudo ufw allow 8080/tcp && sudo ufw enable
# + di Azure Portal: VM > Networking > NSG > Add inbound rule: TCP 8080 dari *.
# (NSG Azure default memblokir semua kecuali 22 — ini yang paling sering kelupaan.)

# 5. Tes dari PC:  curl http://<ip-vps>:8080/api/plans
# Lalu di app BeatPlay: Server Premium = http://<ip-vps>:8080
```

HTTPS (wajib nanti untuk callback Tripay/Xendit, opsional untuk manual):
arahkan domain ke IP VPS, lalu pasang reverse proxy (Caddy 1 baris:
`pay.domain-anda { reverse_proxy 127.0.0.1:8080 }`), dan set
`PUBLIC_BASE=https://pay.domain-anda` di env + callback URL di dashboard provider.

- Tripay: di dashboard Tripay set callback URL ke `https://pay.domain-anda/api/payments/tripay/callback`.
- Xendit: di dashboard Xendit (Settings > Webhooks) set URL ke
  `https://pay.domain-anda/api/payments/xendit/callback` + samakan token.
  Di test mode, admin bisa picu simulasi bayar dari dashboard (endpoint
  `/api/admin/xendit/simulate`) — hasil masuk via webhook seperti asli.

## API

- `GET /api/plans` -> `{plans, tripay_method, provider, manual:{account_name,instructions,has_image}}`
- `POST /api/orders/create` `{"email","plan_id","customer_name"?}` -> `{order}`
- `POST /api/orders/proof` `{"merchant_ref","sender_name","note"?}` -> order `PENDING` (QRIS manual)
- `GET /api/manual-qris` -> info QRIS statis; `GET /manual-qris.png` -> gambar (taro `manual_qris.png` di folder server)
- `POST /api/orders/create` `{"email","plan_id","customer_name"?}` -> `{order:{merchant_ref,reference,amount,status,checkout_url,qr_string,qr_url,expired_at}}`
- `GET /api/orders/status?merchant_ref=` -> `{order, premium, expiry}`
- `POST /api/payments/tripay/callback` (dari Tripay, header `X-Callback-Signature/Event`) -> `{success:true}`
- `POST /api/payments/xendit/callback` (dari Xendit, header `x-callback-token`) -> `{success:true}`
- `GET /api/premium?email=` -> `{email, premium, expiry?}` (`expiry` epoch detik; absen = lifetime manual)
- Admin (`X-Admin-Token`): `GET /api/admin/list`, `GET /api/admin/orders`, `POST /api/admin/add|remove|revoke|activate`, `POST /api/orders/mock-pay` (mock saja), `POST /api/admin/notify/test` (kirim pesan tes ke Telegram)

File runtime (`orders.json`, `premium_expiry.json`, `tripay_config.json`) di-gitignore.
Tes logika: `python3 test_tripay.py`.

## Notif HP (Telegram, tiap ada bukti masuk)

1. Di HP: chat ke `@BotFather` → `/newbot` → dapat **token** (`123:ABC...`).
2. Chat ke `@userinfobot` (atau buka `https://api.telegram.org/bot<token>/getUpdates` setelah chat bot kamu) → dapat **chat ID** angka.
3. Isi di `tripay_config.json`: `notify.telegram_bot_token` + `notify.telegram_chat_id`, restart server.
4. Di dashboard admin klik **Tes notif** — kalau masuk ke HP berarti siap.
   Tiap bukti bayar masuk (dan tiap pembayaran auto lunas) kamu dapat pesan
   berisi ref, email, paket, nama pengirim + catatan.
