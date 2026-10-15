"""Self-test server premium (tanpa network): signature Tripay/Xendit + aktivasi."""
import sys, os, time, json
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from premium_server import (tripay_signature, verify_callback_signature, PremiumStore,
                            xendit_extract_qr, xendit_map_status, xendit_expiry_iso,
                            build_proof_message, build_paid_message, validate_proof_photo,
                            proof_filename, build_multipart,
                            resolve_provider, TripayClient, XenditClient)

# 1. Vektor resmi docs Tripay
sig = tripay_signature("T0001", "INV55567", 1500000, "ytf6ooi2gmlNPfpchd94jDOk8hRWOu")
assert sig == "9f167eba844d1fcb369404e2bda53702e2f78f7aa12e91da6715414e65b8c86a", sig
print("signature vector OK")

# 2. Callback verify round-trip
raw = b'{"merchant_ref":"INV1","status":"PAID"}'
import hmac, hashlib
good = hmac.new(b"key123", raw, hashlib.sha256).hexdigest()
assert verify_callback_signature(raw, good, "key123")
assert not verify_callback_signature(raw, "0" * 64, "key123")
print("callback verify OK")

# 3. Expiry extend (isolasi file tmp, bersihkan dulu sisa run lama)
import shutil
base = os.path.join(os.path.dirname(os.path.abspath(__file__)), "__test_tmp")
shutil.rmtree(base, ignore_errors=True)
os.makedirs(base, exist_ok=True)
ps = PremiumStore(os.path.join(base, "m.json"), os.path.join(base, "e.json"))
ok, _ = ps.status("a@gmail.com"); assert not ok
e1 = ps.extend("a@gmail.com", 30)
ok, exp = ps.status("a@gmail.com"); assert ok and exp == e1
e2 = ps.extend("a@gmail.com", 30)  # perpanjang dari expiry, bukan dari sekarang
assert e2 > e1 and e2 - e1 == 30 * 86400, (e1, e2)
ps.manual.add("b@gmail.com")
ok, exp = ps.status("b@gmail.com"); assert ok and exp is None
print("premium store OK")

# 4. Plans valid
plans = json.load(open(os.path.join(os.path.dirname(os.path.abspath(__file__)), "plans.json")))
assert plans["plans"][0]["price"] == 20000, plans
assert all(p["price"] >= 1000 for p in plans["plans"])  # min QRIS Tripay
print("plans OK")

# 5. Xendit: ambil QR dari contoh respons docs (actions PRESENT_TO_CUSTOMER/QR_STRING)
sample = {"actions": [{"type": "PRESENT_TO_CUSTOMER", "descriptor": "QR_STRING", "value": "QRIS-TEST-123"}]}
assert xendit_extract_qr(sample) == "QRIS-TEST-123"
assert xendit_extract_qr({"actions": []}) is None
assert xendit_map_status("SUCCEEDED") == "PAID"
assert xendit_map_status("EXPIRED") == "EXPIRED"
assert xendit_map_status("FAILED") == "FAILED"
assert xendit_map_status("CANCELED") == "FAILED"
assert xendit_map_status("REQUIRES_ACTION") == "UNPAID"
assert xendit_expiry_iso(30).endswith("Z") and "T" in xendit_expiry_iso(30)
print("xendit helpers OK")

# 6. Provider resolutionassert resolve_provider({"provider": "xendit"}, TripayClient({}), XenditClient({})) == "xendit"
assert resolve_provider({}, TripayClient({}), XenditClient({"secret_key": "sk"})) == "xendit"
assert resolve_provider({}, TripayClient({"mode": "sandbox", "api_key": "a", "private_key": "p", "merchant_code": "m"}), XenditClient({})) == "tripay"
assert resolve_provider({}, TripayClient({}), XenditClient({})) == "manual"
assert resolve_provider({"provider": "mock"}, TripayClient({}), XenditClient({})) == "mock"
assert not XenditClient({}).enabled and XenditClient({"secret_key": "sk"}).enabled
print("provider resolution OK")

# 7. Isi pesan notif HP
o = {"merchant_ref": "BP-1", "email": "a@gmail.com", "plan_id": "monthly",
     "amount": 20000, "proof": {"sender": "Budi DANA", "note": "lunas ya"}}
msg = build_proof_message(o, {"title": "Premium 1 bulan"})
assert "BP-1" in msg and "Budi DANA" in msg and "20.000" in msg and "a@gmail.com" in msg, msg
o2 = {"merchant_ref": "BP-2", "email": "b@gmail.com", "plan_id": "monthly",
      "amount": 20000, "proof": {"sender": "Ani", "note": ""}}
assert "Catatan" not in build_proof_message(o2, {"title": "Premium 1 bulan"})
paid = build_paid_message(o2, {"title": "Premium 1 bulan"})
assert "BP-2" in paid and "otomatis" in paid
print("notify messages OK")

# 8. Validasi foto bukti
assert validate_proof_photo("image/jpeg", b"\xff\xd8\xff\x00") == "jpg"
assert validate_proof_photo("image/png", b"\x89PNG\r\n") == "png"
assert validate_proof_photo("image/png", b"\xff\xd8\xff") is None  # magic tak cocok
assert validate_proof_photo("image/gif", b"GIF89a") is None  # tipe tak didukung
assert validate_proof_photo("image/jpeg", b"") is None
assert validate_proof_photo("image/jpeg", b"\xff\xd8" + b"x" * (4 * 1024 * 1024)) is None  # > 4MB
assert proof_filename("BP-261015015446-9E8A8F", "jpg") == "BP-261015015446-9E8A8F.jpg"
assert proof_filename("../../etc/passwd", "png") == "etcpasswd.png"  # path traversal diblok
print("proof photo validation OK")

# 9. Multipart sendPhoto Telegram
body = build_multipart({"chat_id": "1", "caption": "halo"}, "photo", "BP-1.jpg", "image/jpeg", b"\xff\xd8\xff", "BND")
assert b'name="caption"' in body and b"halo" in body
assert b'filename="BP-1.jpg"' in body and b"image/jpeg" in body and b"\xff\xd8\xff" in body
assert body.startswith(b"--BND\r\n") and body.endswith(b"--BND--\r\n")
print("telegram multipart OK")
print("ALL SERVER SELF-TESTS PASSED")
