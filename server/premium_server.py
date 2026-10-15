"""BeatPlay Premium server (QRIS Tripay/Xendit + manual admin).

Fitur:
- Premium ditentukan dari Gmail: manual (lifetime oleh admin) atau berbayar (expiry).
- Pembelian via provider QRIS: Tripay Closed Payment (QRIS2) atau Xendit
  Payments API v3 (channel QRIS). Mode "mock" untuk tes tanpa kredensial.
- Dashboard admin di / : kelola Gmail manual, lihat plans/orders, aktivasi manual.

Run lokal:  python3 premium_server.py [--port 8080] [--admin-token admin123]
Run VPS:    pastikan HTTPS + domain publik agar callback provider sampai,
            lalu isi server/tripay_config.json (jangan commit!) dan set
            --public-base https://domain-anda agar callback URL benar.

Kredensial Tripay: dashboard Tripay > Member > Merchant > Opsi > Edit
(production) atau API & Integrasi > Simulator > Merchant > Detail (sandbox).
Lihat https://tripay.co.id/developer untuk dokumentasi resmi.
Kredensial Xendit: dashboard Xendit > Settings > API keys (secret key) dan
Settings > Webhooks (token + URL callback). Lihat https://docs.xendit.co/
- Buat payment:  POST https://api.xendit.co/v3/payment_requests (Basic auth
  pakai secret key, header api-version: 2024-11-11), body type=PAY,
  channel_code=QRIS, channel_properties={expires_at, qr_string_type:DYNAMIC}.
  QR ada di actions[] (PRESENT_TO_CUSTOMER / QR_STRING).
- Cek status:   GET https://api.xendit.co/v3/payment_requests/{pr_id}.
- Webhook:      POST berisi {event, data{reference_id, payment_request_id,
  status}} + header x-callback-token (samakan dengan token dashboard).
- Test mode:    POST /v3/payment_requests/{pr_id}/simulate (ada endpoint admin).
"""
import argparse
import base64
import datetime
import hashlib
import hmac
import json
import os
import re
import secrets
import time
import urllib.parse
import urllib.request
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

EMAIL_RE = re.compile(r"^[^@\s]+@[^@\s]+\.[^@\s]+$")
SAFE_REF_RE = re.compile(r"[^A-Za-z0-9-]")
HERE = os.path.dirname(os.path.abspath(__file__))
PROOF_DIR = os.path.join(HERE, "proofs")
MAX_PHOTO_BYTES = 4 * 1024 * 1024
PHOTO_MIMES = {"image/jpeg": "jpg", "image/png": "png"}

TRIPAY_SANDBOX = "https://tripay.co.id/api-sandbox"
TRIPAY_PROD = "https://tripay.co.id/api"
XENDIT_API = "https://api.xendit.co"
XENDIT_API_VERSION = "2024-11-11"


def load_json(path, default):
    try:
        with open(path, encoding="utf-8") as f:
            return json.load(f)
    except (FileNotFoundError, ValueError):
        return default


def save_json(path, obj):
    tmp = path + ".tmp"
    with open(tmp, "w", encoding="utf-8") as f:
        json.dump(obj, f, indent=2)
    os.replace(tmp, path)


def tripay_signature(merchant_code, merchant_ref, amount, private_key):
    """HMAC-SHA256(merchant_code + merchant_ref + amount, private_key).

    Vektor uji dari docs Tripay: T0001 + INV55567 + 1500000 dengan key
    ytf6ooi2gmlNPfpchd94jDOk8hRWOu = 9f167eba844d1fcb...8c86a.
    """
    msg = "{}{}{}".format(merchant_code, merchant_ref, amount).encode("latin-1")
    return hmac.new(private_key.encode("latin-1"), msg, hashlib.sha256).hexdigest()


def verify_callback_signature(raw_body: bytes, signature: str, private_key: str) -> bool:
    expected = hmac.new(private_key.encode("latin-1"), raw_body, hashlib.sha256).hexdigest()
    return hmac.compare_digest(expected, (signature or "").strip().lower())


def xendit_extract_qr(data):
    """Ambil QR string dari respons payment request Xendit (actions[])."""
    for a in (data or {}).get("actions", []):
        if a.get("type") == "PRESENT_TO_CUSTOMER" and a.get("descriptor") == "QR_STRING" and a.get("value"):
            return a["value"]
    return None


def xendit_map_status(status):
    """Petakan status Xendit ke status order internal."""
    s = str(status or "").upper()
    if s == "SUCCEEDED":
        return "PAID"
    if s == "EXPIRED":
        return "EXPIRED"
    if s in ("FAILED", "CANCELED"):
        return "FAILED"
    return "UNPAID"


def xendit_expiry_iso(minutes):
    return (datetime.datetime.now(datetime.timezone.utc) +
            datetime.timedelta(minutes=minutes)).strftime("%Y-%m-%dT%H:%M:%SZ")


def validate_proof_photo(mime, raw):
    """Kembalikan ekstensi (jpg/png) bila foto valid, else None.

    Syarat: mime diizinkan, ukuran <= 4MB, magic bytes cocok
    (JPEG FFD8, PNG 89504E47). Mencegah upload non-gambar.
    """
    ext = PHOTO_MIMES.get((mime or "").lower())
    if not ext or not raw or len(raw) > MAX_PHOTO_BYTES:
        return None
    if ext == "jpg" and raw[:2] != b"\xff\xd8":
        return None
    if ext == "png" and raw[:4] != b"\x89PNG":
        return None
    return ext


def proof_filename(merchant_ref, ext):
    safe = SAFE_REF_RE.sub("", merchant_ref)[:64] or "order"
    return "{}.{}".format(safe, ext)


def build_multipart(fields, file_field, filename, file_mime, file_data, boundary):
    """Bangun body multipart/form-data (murni, untuk sendPhoto Telegram)."""
    out = bytearray()
    for k, v in fields.items():
        out += "--{}\r\n".format(boundary).encode()
        out += 'Content-Disposition: form-data; name="{}"\r\n\r\n'.format(k).encode()
        out += "{}\r\n".format(v).encode()
    out += "--{}\r\n".format(boundary).encode()
    out += 'Content-Disposition: form-data; name="{}"; filename="{}"\r\n'.format(file_field, filename).encode()
    out += "Content-Type: {}\r\n\r\n".format(file_mime).encode()
    out += file_data
    out += "\r\n--{}--\r\n".format(boundary).encode()
    return bytes(out)


def build_proof_message(order, plan):
    lines = ["Bukti bayar premium baru",
             "Ref: {}".format(order.get("merchant_ref", "-")),
             "Email: {}".format(order.get("email", "-")),
             "Paket: {} (Rp{:,})".format((plan or {}).get("title", order.get("plan_id", "-")), order.get("amount", 0)).replace(",", ".")]
    proof = order.get("proof", {})
    lines.append("Dari: {}".format(proof.get("sender", "-")))
    if proof.get("note"):
        lines.append("Catatan: {}".format(proof["note"]))
    lines.append("Verifikasi di dashboard admin, lalu Aktifkan.")
    return "\n".join(lines)


def build_paid_message(order, plan):
    return "\n".join(["Premium lunas (otomatis)",
                      "Ref: {}".format(order.get("merchant_ref", "-")),
                      "Email: {}".format(order.get("email", "-")),
                      "Paket: {}".format((plan or {}).get("title", order.get("plan_id", "-")))])


def send_telegram(bot_token, chat_id, text, photo_path=None):
    """Kirim pesan Telegram (foto + caption bila ada), fire-and-forget di thread."""
    import threading

    def _do():
        try:
            if photo_path and os.path.exists(photo_path):
                with open(photo_path, "rb") as f:
                    data = f.read()
                mime = "image/png" if photo_path.lower().endswith(".png") else "image/jpeg"
                boundary = "BeatPlay{}".format(secrets.token_hex(8))
                body = build_multipart(
                    {"chat_id": chat_id, "caption": text[:1000]}, "photo",
                    os.path.basename(photo_path), mime, data, boundary)
                req = urllib.request.Request(
                    "https://api.telegram.org/bot{}/sendPhoto".format(bot_token), data=body,
                    headers={"Content-Type": "multipart/form-data; boundary=" + boundary}, method="POST")
            else:
                payload = urllib.parse.urlencode({"chat_id": chat_id, "text": text}).encode()
                req = urllib.request.Request(
                    "https://api.telegram.org/bot{}/sendMessage".format(bot_token),
                    data=payload, method="POST")
            with urllib.request.urlopen(req, timeout=30) as r:
                r.read()
        except Exception as e:
            print("Telegram notify gagal: {}".format(e))

    threading.Thread(target=_do, daemon=True).start()


ADMIN_HTML = """<!doctype html><html lang="id"><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<title>BeatPlay Admin - Premium</title>
<style>
:root{--bg:#0a0f1e;--card:#131a2e;--line:#26314d;--txt:#e8ecf5;--mut:#9aa4bf;--pink:#f471d6;--cyan:#38e1ff;--green:#3ddc84;--amber:#ffb020;--red:#ff6b6b}
*{box-sizing:border-box}
body{margin:0;min-height:100vh;color:var(--txt);font-family:system-ui,-apple-system,"Segoe UI",Roboto,sans-serif;background:radial-gradient(1100px 480px at 50% -8%,#1c2650 0%,var(--bg) 62%) fixed,var(--bg)}
.wrap{max-width:980px;margin:0 auto;padding:28px 18px 70px}
header.top{display:flex;align-items:center;gap:14px}
.logo{width:48px;height:48px;border-radius:15px;background:linear-gradient(135deg,#1b2a63,#0e1530);display:flex;align-items:center;justify-content:center;box-shadow:0 0 26px #f471d655;flex:none}
.logo svg{width:28px;height:28px}
h1{font-size:23px;margin:0}
.grad{background:linear-gradient(90deg,var(--cyan),var(--pink));-webkit-background-clip:text;background-clip:text;color:transparent}
.sub{color:var(--mut);font-size:13px;margin:8px 0 0}
.sub code{background:#0d1330;border:1px solid var(--line);border-radius:6px;padding:1px 7px;font-size:12px}
.card{background:linear-gradient(180deg,#151d33,#0f1528);border:1px solid var(--line);border-radius:16px;padding:18px;margin-top:16px;box-shadow:0 10px 30px #0007}
.card h2{margin:0 0 12px;font-size:15px;letter-spacing:.3px}
.row{display:flex;gap:8px;flex-wrap:wrap;align-items:center}
input,select{background:#0b1124;border:1px solid var(--line);color:var(--txt);border-radius:10px;padding:9px 12px;font-size:14px;outline:none}
input:focus,select:focus{border-color:var(--cyan);box-shadow:0 0 0 3px #38e1ff33}
button{cursor:pointer;border:none;border-radius:10px;padding:9px 16px;font-size:14px;font-weight:600}
.btn{background:linear-gradient(90deg,#1f9df0,#7b5cff);color:#fff}
.btn-green{background:linear-gradient(90deg,#1faa6b,#3ddc84);color:#04240f}
.btn-ghost{background:#0b1124;color:var(--txt);border:1px solid var(--line)}
.btn-mini{padding:5px 12px;font-size:12px;border-radius:8px}
.danger{background:#2a1420;color:var(--red);border:1px solid #5c2436}
button:hover{filter:brightness(1.12)}
.grid2{display:grid;grid-template-columns:1fr 1fr;gap:16px}
.grid2 .card{margin-top:16px}
@media(max-width:760px){.grid2{grid-template-columns:1fr}}
ul.clean{list-style:none;margin:12px 0 0;padding:0;display:flex;flex-direction:column;gap:8px}
ul.clean li{background:#0b1124;border:1px solid var(--line);border-radius:10px;padding:8px 12px;display:flex;align-items:center;gap:10px;font-size:14px}
ul.clean li .grow{flex:1;overflow:hidden;text-overflow:ellipsis;white-space:nowrap}
ul.clean li.section{background:none;border:none;color:var(--mut);font-weight:700;padding:8px 2px 0}
table.tbl{border-collapse:separate;border-spacing:0;width:100%;font-size:13px;border:1px solid var(--line);border-radius:12px;overflow:hidden}
.tbl th{background:#1a2340;text-align:left;padding:9px 10px;color:var(--mut);font-weight:600}
.tbl td{padding:9px 10px;border-top:1px solid var(--line);vertical-align:middle}
.tbl tr:hover td{background:#ffffff08}
.tbl code{background:#0b1124;padding:2px 7px;border-radius:6px;font-size:12px}
.pill{display:inline-block;padding:3px 11px;border-radius:99px;font-size:12px;font-weight:700}
.st-UNPAID{background:#2a2f45;color:#aeb6cf}
.st-PENDING{background:#3a2a10;color:var(--amber);animation:pulse 1.6s infinite}
.st-PAID{background:#0f3524;color:var(--green)}
@keyframes pulse{50%{opacity:.55}}
img.proof{max-height:110px;border-radius:10px;border:1px solid var(--line);display:block;margin-top:6px}
.mut{color:var(--mut);font-size:13px}
a{color:var(--cyan)}
footer{color:var(--mut);font-size:12px;text-align:center;margin-top:28px}
</style>
</head><body><div class="wrap">
<header class="top">
<div class="logo"><svg viewBox="0 0 24 24" fill="none"><path d="M6 4.5v15l13-7.5z" fill="#38e1ff"/><path d="M17 4v16M20.5 7v10" stroke="#f471d6" stroke-width="2.2" stroke-linecap="round"/></svg></div>
<div><h1>Beat<span class="grad">Play</span> Admin</h1><p class="sub">Token admin via header <code>X-Admin-Token</code> &bull; Provider: <b id="tmode">?</b> &bull; Notif HP: <b id="nmode">?</b></p></div>
</header>
<div class="card"><div class="row">
<input id="token" type="password" placeholder="Admin token" style="width:200px" autocomplete="off">
<button class="btn" onclick="refreshAll()">Refresh</button>
<button class="btn-ghost" onclick="testNotify()">Tes notif</button>
</div></div>
<div class="grid2">
<div class="card"><h2>Manual lifetime</h2>
<div class="row"><input id="email" placeholder="nama@gmail.com" style="flex:1;min-width:180px"><button class="btn" onclick="add()">Tambah premium</button></div>
<ul id="list" class="clean"></ul></div>
<div class="card"><h2>Aktivasi berbayar manual</h2>
<div class="row"><input id="memail" placeholder="nama@gmail.com" style="flex:1;min-width:160px">
<select id="mdays"><option value="30">30 hari</option><option value="90">90 hari</option><option value="365">365 hari</option></select>
<button class="btn-green" onclick="activate()">Aktifkan</button></div>
<p class="mut">Cek mutasi DANA dulu, lalu aktifkan. Email langsung premium + expiry.</p></div>
</div>
<div class="card"><h2>Paket</h2><div id="plans"></div></div>
<div class="card"><h2>Order 20 terakhir</h2><div id="orders"></div></div>
<div class="card"><h2>QRIS manual</h2><div id="manualbox"></div></div>
<footer>BeatPlay Premium Server &bull; bayar lunas otomatis untuk provider auto, manual via tombol Aktifkan</footer>
</div>
<script>
let PROVIDER = "mock";
const esc = s => String(s ?? "").replace(/[&<>"']/g, c => ({"&":"&amp;","<":"&lt;",">":"&gt;",'"':"&quot;","'":"&#39;"}[c]));
async function api(path, opts={}) {
  opts.headers = Object.assign({}, opts.headers||{}, {"X-Admin-Token": document.getElementById("token").value});
  const r = await fetch(path, opts);
  if (!r.ok) { alert("HTTP "+r.status+": "+await r.text()); return null; }
  return r.json();
}
function fmtExp(s){ if(!s) return "lifetime"; return new Date(s*1000).toLocaleString("id-ID"); }
function tokenQS(){ return encodeURIComponent(document.getElementById("token").value); }
async function refreshAll(){
  const t = await api("/api/admin/list"); if(!t) return;
  PROVIDER = t.provider || "mock";
  document.getElementById("tmode").textContent = PROVIDER + (t.provider_configured ? " (terkonfigurasi)" : " (mock/belum isi kredensial)");
  document.getElementById("nmode").textContent = t.notify_configured ? "aktif (Telegram)" : "mati (isi notify di tripay_config.json)";
  const ul = document.getElementById("list"); ul.innerHTML = "";
  t.manual.forEach(e => {
    const li=document.createElement("li");
    const sp=document.createElement("span"); sp.className="grow"; sp.textContent=e;
    const b=document.createElement("button"); b.className="btn-mini danger"; b.textContent="Hapus";
    b.onclick=async()=>{await api("/api/admin/remove",{method:"POST",headers:{"Content-Type":"application/json"},body:JSON.stringify({email:e})});refreshAll();};
    li.append(sp,b); ul.appendChild(li);
  });
  const ex = document.createElement("li"); ex.className="section"; ex.textContent="Berbayar (expiry):";
  ul.appendChild(ex);
  Object.entries(t.expiry).forEach(([e,s])=>{
    const li=document.createElement("li");
    const sp=document.createElement("span"); sp.className="grow"; sp.textContent=e+"  ·  s/d "+fmtExp(s);
    const b=document.createElement("button"); b.className="btn-mini danger"; b.textContent="Cabut";
    b.onclick=async()=>{await api("/api/admin/revoke",{method:"POST",headers:{"Content-Type":"application/json"},body:JSON.stringify({email:e})});refreshAll();};
    li.append(sp,b); ul.appendChild(li);
  });
  const p = await api("/api/plans");
  document.getElementById("plans").innerHTML = '<table class="tbl"><tr><th>ID</th><th>Paket</th><th>Harga</th><th>Hari</th></tr>'+
    p.plans.map(x=>`<tr><td><code>${esc(x.id)}</code></td><td>${esc(x.title)}</td><td>Rp${x.price.toLocaleString("id-ID")}</td><td>${x.days}</td></tr>`).join("")+"</table>";
  const o = await api("/api/admin/orders");
  const plans = p.plans;
  const daysOf = (pid) => (plans.find(x=>x.id===pid)||{}).days || 30;
  const rows = o.orders.map(x=>{
    let btn = "";
    let proof = '<span class="mut">—</span>';
    if (x.proof) {
      proof = `dari <b>${esc(x.proof.sender)}</b>${x.proof.note ? " ("+esc(x.proof.note)+")" : ""}`;
      if (x.proof.photo) {
        const imgUrl = `/api/admin/proof-photo?merchant_ref=${encodeURIComponent(x.merchant_ref)}&token=${tokenQS()}`;
        proof += `<br><a href="${imgUrl}" target="_blank"><img class="proof" src="${imgUrl}" loading="lazy"></a>`;
      }
    }
    if (x.status==="UNPAID" && PROVIDER==="mock") btn = `<button class="btn-mini btn-ghost" onclick="mockpay('${x.merchant_ref}')">Simulasi bayar</button>`;
    else if (x.status==="UNPAID" && PROVIDER==="xendit") btn = `<button class="btn-mini btn-ghost" onclick="xsimulate('${x.merchant_ref}')">Simulasi (test)</button>`;
    else if (x.status==="PENDING") btn = `<button class="btn-mini btn-green" onclick="activateOrder('${esc(x.email)}','${esc(x.plan_id)}')">Aktifkan ${daysOf(x.plan_id)} hari</button>`;
    return `<tr><td><code>${esc(x.merchant_ref)}</code></td><td>${esc(x.email)}</td><td>${esc(x.plan_id)}</td><td>Rp${x.amount.toLocaleString("id-ID")}</td><td><span class="pill st-${x.status}">${x.status}</span></td><td>${proof}<div style="margin-top:6px">${btn}</div></td></tr>`;
  }).join("");
  document.getElementById("orders").innerHTML = '<table class="tbl"><tr><th>Ref</th><th>Email</th><th>Paket</th><th>Nominal</th><th>Status</th><th>Bukti / Aksi</th></tr>'+rows+"</table>";
  const m = await api("/api/manual-qris");
  document.getElementById("manualbox").innerHTML =
    `<p><b>${esc(m.account_name)||"(belum diisi di plans.json)"}</b><br><span class="mut">${esc(m.instructions)||""}</span></p>`+
    (m.has_image ? `<a href="/manual-qris.png" target="_blank"><img class="proof" src="/manual-qris.png"></a><p class="mut">Taro file <code>manual_qris.png</code> di folder server untuk mengganti.</p>` : `<p class="mut">Belum ada gambar — taro file <code>manual_qris.png</code> di folder server agar tampil di aplikasi.</p>`);
}
async function add(){
  const email=document.getElementById("email").value;
  await api("/api/admin/add",{method:"POST",headers:{"Content-Type":"application/json"},body:JSON.stringify({email})});
  document.getElementById("email").value=""; refreshAll();
}
async function activate(){
  const email=document.getElementById("memail").value, days=+document.getElementById("mdays").value;
  await api("/api/admin/activate",{method:"POST",headers:{"Content-Type":"application/json"},body:JSON.stringify({email,days})});
  refreshAll();
}
async function mockpay(ref){
  await api("/api/orders/mock-pay",{method:"POST",headers:{"Content-Type":"application/json"},body:JSON.stringify({merchant_ref:ref})});
  refreshAll();
}
async function testNotify(){
  const r = await api("/api/admin/notify/test",{method:"POST"});
  if (r) alert("Pesan tes dikirim. Cek Telegram di HP.");
}
async function activateOrder(email, planId){
  const days = ({"monthly":30,"quarterly":90,"yearly":365})[planId] || 30;
  if (!confirm(`Aktifkan ${email} ${days} hari? Pastikan pembayaran ke DANA sudah masuk.`)) return;
  await api("/api/admin/activate",{method:"POST",headers:{"Content-Type":"application/json"},body:JSON.stringify({email,days})});
  refreshAll();
}
async function xsimulate(ref){
  await api("/api/admin/xendit/simulate",{method:"POST",headers:{"Content-Type":"application/json"},body:JSON.stringify({merchant_ref:ref})});
  alert("Simulasi dikirim (test mode). Tunggu webhook, lalu Refresh.");
  refreshAll();
}
document.getElementById("token").value="admin123";
refreshAll();
</script></body></html>"""


class PremiumStore:
    """Gmail manual (lifetime) + expiry berbayar (epoch detik)."""

    def __init__(self, manual_path, expiry_path):
        self.manual_path = manual_path
        self.expiry_path = expiry_path
        self.manual = set()
        self.expiry = {}
        self.load()

    def load(self):
        data = load_json(self.manual_path, {"premium_emails": []})
        self.manual = {str(e).strip().lower() for e in data.get("premium_emails", []) if str(e).strip()}
        self.expiry = {str(k).lower(): int(v) for k, v in load_json(self.expiry_path, {}).items()}

    def save(self):
        save_json(self.manual_path, {"premium_emails": sorted(self.manual)})
        save_json(self.expiry_path, self.expiry)

    def status(self, email):
        email = email.strip().lower()
        if email in self.manual:
            return True, None
        exp = self.expiry.get(email, 0)
        if exp > time.time():
            return True, exp
        return False, None

    def extend(self, email, days):
        email = email.strip().lower()
        base = max(time.time(), self.expiry.get(email, 0))
        self.expiry[email] = int(base + days * 86400)
        self.save()
        return self.expiry[email]


class OrderStore:
    def __init__(self, path):
        self.path = path
        self.orders = {}
        self.load()

    def load(self):
        self.orders = load_json(self.path, {})

    def save(self):
        save_json(self.path, self.orders)


class TripayClient:
    def __init__(self, cfg):
        self.cfg = cfg

    @property
    def enabled(self):
        c = self.cfg
        return c.get("mode") in ("sandbox", "production") and bool(c.get("api_key") and c.get("private_key") and c.get("merchant_code"))

    @property
    def base(self):
        return TRIPAY_SANDBOX if self.cfg.get("mode") == "sandbox" else TRIPAY_PROD

    def create(self, method, merchant_ref, amount, name, email, plan_title, expired_ts, callback_url, return_url):
        c = self.cfg
        sig = tripay_signature(c["merchant_code"], merchant_ref, amount, c["private_key"])
        fields = {
            "method": method,
            "merchant_ref": merchant_ref,
            "amount": str(amount),
            "customer_name": name,
            "customer_email": email,
            "order_items[0][sku]": "PREMIUM",
            "order_items[0][name]": plan_title,
            "order_items[0][price]": str(amount),
            "order_items[0][quantity]": "1",
            "expired_time": str(expired_ts),
            "signature": sig,
        }
        if callback_url:
            fields["callback_url"] = callback_url
        if return_url:
            fields["return_url"] = return_url
        req = urllib.request.Request(
            self.base + "/transaction/create",
            data=urllib.parse.urlencode(fields).encode(),
            headers={"Authorization": "Bearer " + c["api_key"]},
            method="POST",
        )
        with urllib.request.urlopen(req, timeout=20) as r:
            return json.loads(r.read().decode())

    def detail(self, reference):
        req = urllib.request.Request(
            self.base + "/transaction/detail?reference=" + urllib.parse.quote(reference),
            headers={"Authorization": "Bearer " + self.cfg["api_key"]},
            method="GET",
        )
        with urllib.request.urlopen(req, timeout=20) as r:
            return json.loads(r.read().decode())


class XenditClient:
    """Xendit Payments API v3 untuk QRIS. Lihat https://docs.xendit.co/."""

    def __init__(self, cfg):
        self.cfg = cfg or {}

    @property
    def enabled(self):
        return bool(self.cfg.get("secret_key"))

    def _headers(self):
        basic = base64.b64encode((self.cfg["secret_key"] + ":").encode()).decode()
        return {"Authorization": "Basic " + basic, "Content-Type": "application/json",
                "api-version": XENDIT_API_VERSION}

    def _call(self, method, path, payload=None):
        req = urllib.request.Request(
            XENDIT_API + path,
            data=json.dumps(payload).encode() if payload is not None else None,
            headers=self._headers(), method=method)
        try:
            with urllib.request.urlopen(req, timeout=20) as r:
                return r.status, json.loads(r.read().decode() or "{}")
        except urllib.error.HTTPError as e:
            try:
                return e.code, json.loads(e.read().decode() or "{}")
            except ValueError:
                return e.code, {"message": "HTTP {}".format(e.code)}

    def create(self, reference_id, amount, email, plan_title):
        minutes = int(self.cfg.get("expiry_minutes", 30))
        payload = {
            "reference_id": reference_id,
            "type": "PAY",
            "country": "ID",
            "currency": "IDR",
            "channel_code": self.cfg.get("channel", "QRIS"),
            "channel_properties": {"expires_at": xendit_expiry_iso(minutes), "qr_string_type": "DYNAMIC"},
            "request_amount": amount,
            "capture_method": "AUTOMATIC",
            "description": plan_title,
            "metadata": {"email": email},
        }
        return self._call("POST", "/v3/payment_requests", payload)

    def get(self, payment_request_id):
        return self._call("GET", "/v3/payment_requests/" + urllib.parse.quote(payment_request_id))

    def simulate(self, payment_request_id, amount):
        return self._call("POST", "/v3/payment_requests/" + urllib.parse.quote(payment_request_id) + "/simulate",
                          {"amount": amount})


def resolve_provider(cfg, tripay, xendit):
    p = (cfg.get("provider") or "").strip().lower()
    if p in ("tripay", "xendit", "mock", "manual"):
        return p
    if xendit.enabled:
        return "xendit"
    if tripay.enabled:
        return "tripay"
    return "manual"


class Handler(BaseHTTPRequestHandler):
    premium = None
    orders = None
    plans = {"plans": [], "tripay_method": "QRIS2"}
    pay_cfg = {"provider": "mock", "mode": "mock"}
    tripay = None
    xendit = None
    provider = "mock"
    admin_token = "admin123"
    public_base = ""

    def log_message(self, *a):
        pass

    def _send(self, code, obj, ctype="application/json"):
        body = obj if isinstance(obj, bytes) else (json.dumps(obj).encode() if ctype == "application/json" else obj.encode())
        self.send_response(code)
        self.send_header("Content-Type", ctype + "; charset=utf-8")
        self.send_header("Access-Control-Allow-Origin", "*")
        self.send_header("Access-Control-Allow-Headers", "Content-Type, X-Admin-Token, X-Callback-Signature, X-Callback-Event, x-callback-token")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def _body_json(self):
        try:
            n = int(self.headers.get("Content-Length", "0"))
            raw = self.rfile.read(n) if n > 0 else b"{}"
            return raw, json.loads(raw.decode() or "{}")
        except Exception:
            return b"{}", None

    def _need_admin(self):
        if self.headers.get("X-Admin-Token") != self.admin_token:
            self._send(401, {"error": "unauthorized"})
            return False
        return True

    def _provider_configured(self):
        if self.provider == "xendit":
            return self.xendit.enabled
        if self.provider == "tripay":
            return self.tripay.enabled
        return True  # mock & manual selalu bisa dipakai

    def _manual_info(self):
        m = dict(self.plans.get("manual", {}))
        m["has_image"] = os.path.exists(os.path.join(HERE, "manual_qris.png"))
        return m

    def _notify_cfg(self):
        return self.pay_cfg.get("notify", {}) if isinstance(self.pay_cfg.get("notify"), dict) else {}

    def _notify(self, text, photo_path=None):
        n = self._notify_cfg()
        if n.get("telegram_bot_token") and n.get("telegram_chat_id"):
            send_telegram(n["telegram_bot_token"], n["telegram_chat_id"], text, photo_path)

    def _proof_photo_path(self, order):
        fn = (order.get("proof") or {}).get("photo")
        if not fn:
            return None
        p = os.path.join(PROOF_DIR, os.path.basename(fn))
        return p if os.path.exists(p) else None

    # ---- GET ----
    def do_OPTIONS(self):
        self._send(200, b"", ctype="text/plain")

    def do_GET(self):
        url = urllib.parse.urlparse(self.path)
        qs = urllib.parse.parse_qs(url.query)
        if url.path == "/":
            return self._send(200, ADMIN_HTML, ctype="text/html")
        if url.path == "/api/premium":
            email = (qs.get("email", [""])[0] or "").strip().lower()
            if not EMAIL_RE.match(email):
                return self._send(400, {"error": "email tidak valid"})
            ok, exp = self.premium.status(email)
            out = {"email": email, "premium": ok}
            if exp:
                out["expiry"] = exp
            return self._send(200, out)
        if url.path == "/api/plans":
            out = dict(self.plans)
            out["provider"] = self.provider
            out["manual"] = self._manual_info()
            return self._send(200, out)
        if url.path == "/api/manual-qris":
            return self._send(200, self._manual_info())
        if url.path == "/manual-qris.png":
            img = os.path.join(HERE, "manual_qris.png")
            if not os.path.exists(img):
                return self._send(404, {"error": "belum ada gambar QRIS (taro manual_qris.png di folder server)"})
            with open(img, "rb") as f:
                data = f.read()
            self.send_response(200)
            self.send_header("Content-Type", "image/png")
            self.send_header("Content-Length", str(len(data)))
            self.end_headers()
            self.wfile.write(data)
            return
        if url.path == "/api/orders/status":
            ref = (qs.get("merchant_ref", qs.get("ref", [""]))[0] or "").strip()
            o = self.orders.orders.get(ref)
            if not o:
                return self._send(404, {"error": "order tidak ditemukan"})
            if o["status"] == "UNPAID":
                self._refresh_order(o)
            ok, exp = self.premium.status(o["email"])
            return self._send(200, {"order": o, "premium": ok, "expiry": exp})
        if url.path == "/api/admin/list":
            if not self._need_admin():
                return
            return self._send(200, {
                "manual": sorted(self.premium.manual),
                "expiry": self.premium.expiry,
                "provider": self.provider,
                "provider_configured": self._provider_configured(),
                "notify_configured": bool(self._notify_cfg().get("telegram_bot_token") and self._notify_cfg().get("telegram_chat_id")),
                "tripay_mode": self.pay_cfg.get("mode", "mock"),  # kompat lama
                "tripay_configured": self.tripay.enabled,
            })
        if url.path == "/api/admin/orders":
            if not self._need_admin():
                return
            items = sorted(self.orders.orders.values(), key=lambda o: o.get("created_at", 0), reverse=True)[:20]
            return self._send(200, {"orders": items})
        if url.path == "/api/admin/proof-photo":
            token = self.headers.get("X-Admin-Token") or (qs.get("token", [""])[0] or "")
            if token != self.admin_token:
                return self._send(401, {"error": "unauthorized"})
            ref = (qs.get("merchant_ref", [""])[0] or "").strip()
            o = self.orders.orders.get(ref)
            fn = (o.get("proof") or {}).get("photo") if o else None
            p = os.path.join(PROOF_DIR, os.path.basename(fn or ""))
            if not fn or not os.path.exists(p):
                return self._send(404, {"error": "foto tidak ada"})
            with open(p, "rb") as f:
                data = f.read()
            self.send_response(200)
            self.send_header("Content-Type", "image/png" if p.lower().endswith(".png") else "image/jpeg")
            self.send_header("Content-Length", str(len(data)))
            self.end_headers()
            self.wfile.write(data)
            return
        if url.path == "/api/ads/config":
            return self._send(200, {"adEveryNSongs": 3, "adSeconds": 15,
                                    "adText": "BeatPlay Premium - dengarkan tanpa jeda iklan."})
        return self._send(404, {"error": "not found"})

    # ---- POST ----
    def do_POST(self):
        url = urllib.parse.urlparse(self.path)
        if url.path == "/api/payments/tripay/callback":
            return self._tripay_callback()
        if url.path == "/api/payments/xendit/callback":
            return self._xendit_callback()
        if url.path not in ("/api/admin/add", "/api/admin/remove", "/api/admin/revoke",
                            "/api/admin/activate", "/api/admin/orders", "/api/admin/xendit/simulate",
                            "/api/admin/notify/test",
                            "/api/orders/mock-pay", "/api/orders/create", "/api/orders/proof"):
            return self._send(404, {"error": "not found"})
        raw, data = self._body_json()
        if data is None:
            return self._send(400, {"error": "body harus JSON"})

        if url.path == "/api/orders/create":
            return self._create_order(data)

        if url.path == "/api/orders/proof":
            ref = str(data.get("merchant_ref", "")).strip()
            sender = str(data.get("sender_name", "")).strip()
            note = str(data.get("note", "")).strip()[:300]
            o = self.orders.orders.get(ref)
            if not o:
                return self._send(404, {"error": "order tidak ditemukan"})
            if o.get("provider") != "manual":
                return self._send(400, {"error": "order ini bukan QRIS manual"})
            if o["status"] == "PAID":
                return self._send(400, {"error": "order sudah lunas"})
            if not sender:
                return self._send(400, {"error": "nama pengirim wajib diisi"})
            photo_b64 = data.get("photo_base64") or ""
            photo_mime = data.get("photo_mime") or ""
            raw = None
            if photo_b64:
                try:
                    raw = base64.b64decode(photo_b64, validate=True)
                except Exception:
                    return self._send(400, {"error": "foto tidak valid (base64 rusak)"})
            ext = validate_proof_photo(photo_mime, raw) if raw else None
            if o.get("provider") == "manual" and ext is None:
                if not photo_b64:
                    return self._send(400, {"error": "foto bukti wajib diunggah (JPG/PNG, maks 4MB)"})
                return self._send(400, {"error": "foto tidak valid (harus JPG/PNG asli, maks 4MB)"})
            fn = None
            if ext:
                os.makedirs(PROOF_DIR, exist_ok=True)
                fn = proof_filename(ref, ext)
                with open(os.path.join(PROOF_DIR, fn), "wb") as f:
                    f.write(raw)
            o["status"] = "PENDING"
            o["proof"] = {"sender": sender, "note": note, "at": int(time.time()), "photo": fn}
            self.orders.save()
            self._notify(build_proof_message(o, self._plan(o.get("plan_id", ""))), self._proof_photo_path(o))
            return self._send(200, {"ok": True, "order": o})

        if not self._need_admin():
            return
        if url.path == "/api/admin/add":
            email = str(data.get("email", "")).strip().lower()
            if not EMAIL_RE.match(email):
                return self._send(400, {"error": "email tidak valid"})
            self.premium.manual.add(email)
            self.premium.save()
            return self._send(200, {"ok": True})
        if url.path == "/api/admin/remove":
            self.premium.manual.discard(str(data.get("email", "")).strip().lower())
            self.premium.save()
            return self._send(200, {"ok": True})
        if url.path == "/api/admin/revoke":
            self.premium.expiry.pop(str(data.get("email", "")).strip().lower(), None)
            self.premium.manual.discard(str(data.get("email", "")).strip().lower())
            self.premium.save()
            return self._send(200, {"ok": True})
        if url.path == "/api/admin/activate":
            email = str(data.get("email", "")).strip().lower()
            try:
                days = int(data.get("days", 30))
            except (TypeError, ValueError):
                return self._send(400, {"error": "days harus angka"})
            if not EMAIL_RE.match(email) or not (1 <= days <= 730):
                return self._send(400, {"error": "email/days tidak valid"})
            exp = self.premium.extend(email, days)
            return self._send(200, {"ok": True, "email": email, "expiry": exp})
        if url.path == "/api/orders/mock-pay":
            if self.provider != "mock":
                return self._send(400, {"error": "hanya tersedia di mode mock"})
            ref = str(data.get("merchant_ref", "")).strip()
            o = self.orders.orders.get(ref)
            if not o:
                return self._send(404, {"error": "order tidak ditemukan"})
            o["status"] = "PAID"
            o["paid_at"] = int(time.time())
            self._activate(o)
            return self._send(200, {"ok": True, "order": o})
        if url.path == "/api/admin/xendit/simulate":
            if self.provider != "xendit" or not self.xendit.enabled:
                return self._send(400, {"error": "provider xendit belum dikonfigurasi"})
            ref = str(data.get("merchant_ref", "")).strip()
            o = self.orders.orders.get(ref)
            if not o or not o.get("reference"):
                return self._send(404, {"error": "order tidak ditemukan"})
            code, resp = self.xendit.simulate(o["reference"], o["amount"])
            if code != 200:
                return self._send(502, {"error": "Xendit simulate gagal: {}".format(resp)})
            return self._send(200, {"ok": True, "resp": resp})
        if url.path == "/api/admin/notify/test":
            n = self._notify_cfg()
            if not (n.get("telegram_bot_token") and n.get("telegram_chat_id")):
                return self._send(400, {"error": "isi notify.telegram_bot_token + telegram_chat_id di tripay_config.json"})
            self._notify("Tes notif BeatPlay: kalau pesan ini masuk ke HP, notif bukti bayar sudah siap.")
            return self._send(200, {"ok": True})
        return self._send(404, {"error": "not found"})

    # ---- helpers ----
    def _plan(self, plan_id):
        for p in self.plans.get("plans", []):
            if p.get("id") == plan_id:
                return p
        return None

    def _activate(self, order):
        plan = self._plan(order.get("plan_id", "")) or {}
        days = int(plan.get("days", 30))
        exp = self.premium.extend(order["email"], days)
        order["expiry"] = exp
        self.orders.save()
        return exp

    def _mark_paid(self, order, extra=None):
        """Tandai lunas + aktifkan + notif (untuk callback auto/mock)."""
        if order["status"] != "PAID":
            order["status"] = "PAID"
            order["paid_at"] = int(time.time())
            if extra:
                order.update(extra)
            self._activate(order)
            self._notify(build_paid_message(order, self._plan(order.get("plan_id", ""))),
                         self._proof_photo_path(order))

    def _refresh_order(self, o):
        """Sinkron status sekali dari provider bila masih UNPAID."""
        try:
            if o.get("provider") == "xendit" and self.xendit.enabled and o.get("reference"):
                code, d = self.xendit.get(o["reference"])
                if code == 200:
                    st = xendit_map_status(d.get("status"))
                    if st != o["status"]:
                        if st == "PAID":
                            self._mark_paid(o)
                        else:
                            o["status"] = st
                            self.orders.save()
            elif o.get("provider") == "tripay" and self.tripay.enabled and o.get("reference"):
                d = self.tripay.detail(o["reference"])
                if d.get("success"):
                    st = (d.get("data") or {}).get("status", o["status"])
                    if st != o["status"]:
                        if st == "PAID":
                            self._mark_paid(o)
                        else:
                            o["status"] = st
                            self.orders.save()
        except Exception as e:
            o["provider_check_error"] = str(e)[:200]

    def _create_order(self, data):
        email = str(data.get("email", "")).strip().lower()
        plan_id = str(data.get("plan_id", "")).strip()
        name = str(data.get("customer_name", "")).strip() or email.split("@")[0]
        if not EMAIL_RE.match(email):
            return self._send(400, {"error": "email tidak valid"})
        plan = self._plan(plan_id)
        if not plan:
            return self._send(400, {"error": "plan_id tidak dikenal", "plans": [p.get("id") for p in self.plans.get("plans", [])]})
        amount = int(plan["price"])
        merchant_ref = "BP-" + time.strftime("%y%m%d%H%M%S") + "-" + secrets.token_hex(3).upper()
        base_order = {"merchant_ref": merchant_ref, "email": email, "plan_id": plan_id,
                      "amount": amount, "status": "UNPAID", "provider": self.provider,
                      "created_at": int(time.time())}

        if self.provider == "xendit":
            if not self.xendit.enabled:
                return self._send(502, {"error": "Xendit belum dikonfigurasi (isi secret_key di tripay_config.json)"})
            code, r = self.xendit.create(merchant_ref, amount, email, str(plan.get("title", plan_id)))
            if code not in (200, 201):
                return self._send(502, {"error": "Xendit menolak: {}".format(r)})
            qr = xendit_extract_qr(r)
            order = dict(base_order, reference=r.get("payment_request_id"), status=xendit_map_status(r.get("status")),
                         checkout_url=None, qr_string=qr, qr_url=None, pay_code=None,
                         expired_at=int(time.time()) + int(self.xendit.cfg.get("expiry_minutes", 30)) * 60)
        elif self.provider == "tripay":
            if not self.tripay.enabled:
                return self._send(502, {"error": "Tripay belum dikonfigurasi"})
            expired_ts = int(time.time()) + 30 * 60  # QRIS2: 10 mnt - 24 jam
            cb = (self.pay_cfg.get("callback_url") or (self.public_base.rstrip("/") + "/api/payments/tripay/callback") if self.public_base else "")
            ret = self.pay_cfg.get("return_url") or ""
            try:
                r = self.tripay.create(self.plans.get("tripay_method", "QRIS2"), merchant_ref, amount,
                                       name, email, str(plan.get("title", plan_id)), expired_ts, cb, ret)
            except Exception as e:
                return self._send(502, {"error": "Tripay error: {}".format(str(e)[:300])})
            if not r.get("success"):
                return self._send(502, {"error": "Tripay menolak: {}".format(r.get("message", "?"))})
            d = r["data"]
            order = dict(base_order, reference=d.get("reference"), status=d.get("status", "UNPAID"),
                         checkout_url=d.get("checkout_url"), qr_string=d.get("qr_string"),
                         qr_url=d.get("qr_url"), pay_code=d.get("pay_code"), expired_at=expired_ts)
        else:
            if self.provider == "mock":
                order = dict(base_order, reference="MOCK-" + merchant_ref,
                             checkout_url=None, qr_string="MOCK-QRIS:" + merchant_ref,
                             qr_url=None, pay_code=None, mock=True,
                             expired_at=int(time.time()) + 30 * 60)
            else:  # manual: bayar ke QRIS statis merchant, kirim bukti, admin aktifkan
                order = dict(base_order, reference="MANUAL-" + merchant_ref,
                             checkout_url=None, qr_string=None,
                             qr_url=None, pay_code=None, manual=True,
                             expired_at=int(time.time()) + 24 * 3600)
        self.orders.orders[merchant_ref] = order
        self.orders.save()
        return self._send(200, {"order": order})

    def _tripay_callback(self):
        n = int(self.headers.get("Content-Length", "0") or 0)
        raw = self.rfile.read(n) if n > 0 else b"{}"
        if self.headers.get("X-Callback-Event") != "payment_status":
            return self._send(400, {"success": False, "message": "event tidak dikenal"})
        if not self.tripay.enabled:
            return self._send(400, {"success": False, "message": "Tripay belum dikonfigurasi"})
        if not verify_callback_signature(raw, self.headers.get("X-Callback-Signature"), self.pay_cfg.get("private_key", "")):
            return self._send(400, {"success": False, "message": "signature tidak valid"})
        try:
            data = json.loads(raw.decode())
        except ValueError:
            return self._send(400, {"success": False, "message": "body bukan JSON"})
        if str(data.get("status", "")).upper() != "PAID":
            return self._send(200, {"success": True})  # EXPIRED/FAILED/REFUND: ack saja
        ref = str(data.get("merchant_ref", ""))
        o = self.orders.orders.get(ref)
        if not o:
            return self._send(200, {"success": True})  # ack agar Tripay tidak retry
        if o["status"] != "PAID":
            self._mark_paid(o, {"tripay_reference": data.get("reference")})
        return self._send(200, {"success": True})

    def _xendit_callback(self):
        n = int(self.headers.get("Content-Length", "0") or 0)
        raw = self.rfile.read(n) if n > 0 else b"{}"
        token = self.xendit.cfg.get("webhook_token", "") if self.xendit else ""
        if not token:
            return self._send(400, {"success": False, "message": "webhook_token belum dikonfigurasi"})
        if not hmac.compare_digest(str(self.headers.get("x-callback-token") or ""), token):
            return self._send(401, {"success": False, "message": "token tidak valid"})
        try:
            data = json.loads(raw.decode())
        except ValueError:
            return self._send(400, {"success": False, "message": "body bukan JSON"})
        if data.get("event") == "payment.capture":
            d = data.get("data", {})
            if str(d.get("status", "")).upper() == "SUCCEEDED":
                o = self.orders.orders.get(str(d.get("reference_id", "")))
                if o is None:  # fallback cocokkan payment_request_id
                    for cand in self.orders.orders.values():
                        if cand.get("reference") == d.get("payment_request_id"):
                            o = cand
                            break
                if o is not None and o["status"] != "PAID":
                    self._mark_paid(o, {"xendit_payment_id": d.get("payment_id")})
        return self._send(200, {"success": True})


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--port", type=int, default=8080)
    ap.add_argument("--admin-token", default="admin123")
    ap.add_argument("--store", default=os.path.join(HERE, "premium_emails.json"))
    ap.add_argument("--expiry-store", default=os.path.join(HERE, "premium_expiry.json"))
    ap.add_argument("--orders-store", default=os.path.join(HERE, "orders.json"))
    ap.add_argument("--plans", default=os.path.join(HERE, "plans.json"))
    ap.add_argument("--tripay-config", default=os.path.join(HERE, "tripay_config.json"))
    ap.add_argument("--public-base", default="", help="cth https://pay.domain-anda (untuk callback provider)")
    args = ap.parse_args()
    Handler.premium = PremiumStore(args.store, args.expiry_store)
    Handler.orders = OrderStore(args.orders_store)
    Handler.plans = load_json(args.plans, {"plans": [], "tripay_method": "QRIS2"})
    Handler.pay_cfg = load_json(args.tripay_config, {"mode": "mock"})
    Handler.tripay = TripayClient(Handler.pay_cfg)
    Handler.xendit = XenditClient(Handler.pay_cfg.get("xendit", {}))
    Handler.provider = resolve_provider(Handler.pay_cfg, Handler.tripay, Handler.xendit)
    Handler.admin_token = args.admin_token
    Handler.public_base = args.public_base
    # Selalu dengar di semua interface agar bisa diakses dari luar (NSG/ufw yang membatasi).
    srv = ThreadingHTTPServer(("0.0.0.0", args.port), Handler)
    print("Premium server: http://127.0.0.1:{}/ (provider: {}, configured: {})".format(
        args.port, Handler.provider,
        Handler.xendit.enabled if Handler.provider == "xendit" else Handler.tripay.enabled if Handler.provider == "tripay" else False))
    srv.serve_forever()


if __name__ == "__main__":
    main()
