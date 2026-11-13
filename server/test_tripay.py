"""Self-test server premium (tanpa network): signature Tripay/Xendit + aktivasi."""
import sys, os, time, json
import hmac, hashlib
from threading import RLock
import urllib.request, urllib.error
from http.server import ThreadingHTTPServer
from threading import Thread
from concurrent.futures import ThreadPoolExecutor
from unittest.mock import patch
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from premium_server import (tripay_signature, verify_callback_signature, PremiumStore,
                            xendit_extract_qr, xendit_map_status, xendit_expiry_iso,
                             build_proof_message, build_paid_message, validate_proof_photo,
                             proof_filename, build_multipart,
                             resolve_provider, TripayClient, XenditClient, BuatQrisClient, OrderStore,
                             verify_buatqris_signature, buatqris_map_status, Handler,
                             refresh_pending_buatqris_orders)

# 1. Vektor resmi docs Tripay
sig = tripay_signature("T0001", "INV55567", 1500000, "ytf6ooi2gmlNPfpchd94jDOk8hRWOu")
assert sig == "9f167eba844d1fcb369404e2bda53702e2f78f7aa12e91da6715414e65b8c86a", sig
print("signature vector OK")

# 2. Callback verify round-trip
raw = b'{"merchant_ref":"INV1","status":"PAID"}'
good = hmac.new(b"key123", raw, hashlib.sha256).hexdigest()
assert verify_callback_signature(raw, good, "key123")
assert not verify_callback_signature(raw, "0" * 64, "key123")
print("callback verify OK")

# 2b. BuatQris HMAC header uses the exact raw JSON body and sha256= prefix.
bq_raw = b'{"event":"payment.success","transaction_id":"1001","status":"success","amount":20000}'
bq_secret = "sandbox-signing-secret"
bq_sig = "sha256=" + hmac.new(bq_secret.encode(), bq_raw, hashlib.sha256).hexdigest()
assert verify_buatqris_signature(bq_raw, bq_sig, bq_secret)
assert verify_buatqris_signature(bq_raw, bq_sig.upper(), bq_secret)
assert not verify_buatqris_signature(bq_raw + b" ", bq_sig, bq_secret)
assert not verify_buatqris_signature(bq_raw, bq_sig, "wrong-secret")
assert not verify_buatqris_signature(bq_raw, bq_sig, "")
assert buatqris_map_status("success") == "PAID"
assert buatqris_map_status("expired") == "EXPIRED"
assert buatqris_map_status("failed") == "FAILED"
assert buatqris_map_status("pending") == "UNPAID"
print("BuatQris signature/status helpers OK")

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

# 6. Provider resolution
empty_bq = BuatQrisClient({})
ready_bq = BuatQrisClient({"account_id": "acct", "secret_token": "api-secret", "signing_secret": "hook-secret"})
assert resolve_provider({"provider": "xendit"}, TripayClient({}), XenditClient({}), empty_bq) == "xendit"
assert resolve_provider({}, TripayClient({}), XenditClient({"secret_key": "sk"}), empty_bq) == "xendit"
assert resolve_provider({}, TripayClient({"mode": "sandbox", "api_key": "a", "private_key": "p", "merchant_code": "m"}), XenditClient({}), empty_bq) == "tripay"
assert resolve_provider({"provider": "buatqris"}, TripayClient({}), XenditClient({}), empty_bq) == "buatqris"
assert resolve_provider({}, TripayClient({}), XenditClient({}), ready_bq) == "buatqris"
assert resolve_provider({}, TripayClient({}), XenditClient({}), empty_bq) == "manual"
assert resolve_provider({"provider": "mock"}, TripayClient({}), XenditClient({}), empty_bq) == "mock"
assert not XenditClient({}).enabled and XenditClient({"secret_key": "sk"}).enabled
assert not empty_bq.enabled and ready_bq.enabled and ready_bq.test_mode
assert not BuatQrisClient({"mode": "production"}).test_mode
assert not BuatQrisClient({"account_id": "acct", "secret_token": "api-secret",
                           "signing_secret": "hook-secret", "mode": "prodction"}).valid_mode
print("provider resolution OK")

# 6b. Sandbox create request includes the test marker and callback; real mode omits it.
class FakeResponse:
    status = 200
    def __enter__(self): return self
    def __exit__(self, *args): return False
    def read(self): return b'{"success":true,"data":{"transaction_id":"1001","status":"pending","total_amount":20019}}'

requests = []
def fake_urlopen(req, timeout):
    requests.append((req, timeout))
    return FakeResponse()

with patch("premium_server.urllib.request.urlopen", side_effect=fake_urlopen):
    sandbox_bq = BuatQrisClient({"account_id": "acct", "secret_token": "api-secret",
                                 "signing_secret": "hook-secret", "mode": "sandbox"})
    code, response = sandbox_bq.create("BP-TEST", 20000, "Premium 1 bulan",
                                       "https://premium.example/api/payments/buatqris/callback")
assert code == 200 and response["success"]
assert requests[0][0].full_url == "https://api.buatqris.site"
sent = dict(__import__("urllib.parse", fromlist=["parse_qsl"]).parse_qsl(requests[0][0].data.decode()))
assert sent["action"] == "api_create_qris" and sent["amount"] == "20000"
assert sent["test"] == "1" and sent["fee_by"] == "user"
assert sent["callback_url"].endswith("/api/payments/buatqris/callback")
assert "secret_token" in sent and requests[0][1] == 25
with patch("premium_server.urllib.request.urlopen", side_effect=fake_urlopen):
    status_code, status_response = sandbox_bq.check_status("1001")
assert status_code == 200 and status_response["success"]
assert requests[-1][0].full_url == "https://api.buatqris.site" and requests[-1][1] == 15
sent_status = dict(__import__("urllib.parse", fromlist=["parse_qsl"]).parse_qsl(requests[-1][0].data.decode()))
assert sent_status == {"action": "api_check_status", "account_id": "acct",
                       "secret_token": "api-secret", "transaction_id": "1001"}
with patch("premium_server.urllib.request.urlopen", side_effect=fake_urlopen):
    live_bq = BuatQrisClient({"account_id": "acct", "secret_token": "api-secret",
                              "signing_secret": "hook-secret", "mode": "production"})
    live_bq.create("BP-LIVE", 20000, "Premium 1 bulan", "https://premium.example/callback")
sent_live = dict(__import__("urllib.parse", fromlist=["parse_qsl"]).parse_qsl(requests[-1][0].data.decode()))
assert "test" not in sent_live
print("BuatQris create request OK")

# 6c. End-to-end callback check: bad signature/amount cannot activate; valid is idempotent.
class MemoryOrders:
    def __init__(self, orders): self.orders = orders
    def save(self): pass

class CallbackHandler(Handler):
    pass

callback_dir = os.path.join(base, "callback")
os.makedirs(callback_dir, exist_ok=True)
callback_premium = PremiumStore(os.path.join(callback_dir, "manual.json"),
                                os.path.join(callback_dir, "expiry.json"))
callback_secret = "callback-test-secret"
CallbackHandler.provider = "buatqris"
CallbackHandler.buatqris = BuatQrisClient({"account_id": "acct", "secret_token": "api-secret",
                                           "signing_secret": callback_secret, "mode": "sandbox"})
CallbackHandler.orders = MemoryOrders({
    "BP-CALLBACK": {"merchant_ref": "BP-CALLBACK", "reference": "txn-valid", "status": "UNPAID",
                     "provider": "buatqris", "email": "callback@example.com", "plan_id": "monthly",
                     "amount": 20000, "gateway_total_amount": 20019, "test_mode": True},
    "BP-WRONG-AMOUNT": {"merchant_ref": "BP-WRONG-AMOUNT", "reference": "txn-wrong", "status": "UNPAID",
                         "provider": "buatqris", "email": "wrong@example.com", "plan_id": "monthly",
                         "amount": 20000, "gateway_total_amount": 20019, "test_mode": True},
})
CallbackHandler.premium = callback_premium
CallbackHandler.plans = {"plans": [{"id": "monthly", "title": "Premium bulanan", "days": 30}],
                         "manual": {"account_name": "jangan ditampilkan", "instructions": "QRIS statis"}}
CallbackHandler.pay_cfg = {}
callback_server = ThreadingHTTPServer(("127.0.0.1", 0), CallbackHandler)
callback_thread = Thread(target=callback_server.serve_forever, daemon=True)
callback_thread.start()
callback_base = "http://127.0.0.1:{}".format(callback_server.server_port)
callback_url = callback_base + "/api/payments/buatqris/callback"

def post_callback(payload, signature):
    body = json.dumps(payload, separators=(",", ":")).encode()
    req = urllib.request.Request(callback_url, data=body,
                                 headers={"Content-Type": "application/json", "X-BuatQris-Signature": signature},
                                 method="POST")
    try:
        with urllib.request.urlopen(req, timeout=3) as res:
            return res.status
    except urllib.error.HTTPError as e:
        return e.code

try:
    with urllib.request.urlopen(callback_base + "/api/plans", timeout=3) as res:
        plans_response = json.loads(res.read().decode())
    assert plans_response["provider"] == "buatqris" and "manual" not in plans_response
    try:
        urllib.request.urlopen(callback_base + "/api/manual-qris", timeout=3)
        raise AssertionError("manual QRIS endpoint must be hidden for automatic providers")
    except urllib.error.HTTPError as e:
        assert e.code == 404

    success_event = {"event": "payment.success", "transaction_id": "txn-valid", "status": "success",
                     "amount": 20000, "total_amount": 20019, "is_test": True}
    success_raw = json.dumps(success_event, separators=(",", ":")).encode()
    valid_signature = "sha256=" + hmac.new(callback_secret.encode(), success_raw, hashlib.sha256).hexdigest()
    assert post_callback(success_event, "sha256=" + "0" * 64) == 401
    assert CallbackHandler.orders.orders["BP-CALLBACK"]["status"] == "UNPAID"
    bad_amount = dict(success_event, transaction_id="txn-wrong", total_amount=20018)
    bad_amount_raw = json.dumps(bad_amount, separators=(",", ":")).encode()
    bad_amount_signature = "sha256=" + hmac.new(callback_secret.encode(), bad_amount_raw, hashlib.sha256).hexdigest()
    assert post_callback(bad_amount, bad_amount_signature) == 400
    assert not callback_premium.status("wrong@example.com")[0]
    assert post_callback(success_event, valid_signature) == 200
    assert CallbackHandler.orders.orders["BP-CALLBACK"]["status"] == "PAID"
    assert callback_premium.status("callback@example.com")[0]
    expiry = callback_premium.expiry["callback@example.com"]
    with ThreadPoolExecutor(max_workers=6) as pool:
        duplicate_results = list(pool.map(lambda _: post_callback(success_event, valid_signature), range(8)))
    assert duplicate_results == [200] * 8
    assert callback_premium.expiry["callback@example.com"] == expiry
finally:
    callback_server.shutdown()
    callback_server.server_close()
    callback_thread.join(timeout=3)
print("BuatQris signed callback activation/idempotency OK")

# 6d. Status API fallback validates order data and persists the 20s throttle.
class FakeStatusClient:
    enabled = True
    test_mode = True
    def __init__(self, result):
        self.result = result
        self.calls = []
    def check_status(self, transaction_id):
        self.calls.append(transaction_id)
        return self.result

class StatusRefreshHarness:
    _refresh_order = Handler._refresh_order
    def __init__(self, order_store, client, premium_store):
        self.orders = order_store
        self.buatqris = client
        self.premium = premium_store
        self.payment_lock = RLock()
        self.plans = {"plans": [{"id": "monthly", "title": "Premium bulanan", "days": 30}]}
        self.pay_cfg = {}
    def _mark_paid(self, order, extra=None):
        with self.payment_lock:
            if order["status"] != "PAID":
                order["status"] = "PAID"
                order["paid_at"] = int(time.time())
                if extra:
                    order.update(extra)
                self.premium.extend(order["email"], 30)
                self.orders.save()

status_order = {"merchant_ref": "BP-STATUS", "reference": "txn-status", "status": "UNPAID",
                "provider": "buatqris", "email": "status@example.com", "plan_id": "monthly",
                "amount": 20000, "gateway_total_amount": 20019, "test_mode": True}
status_path = os.path.join(base, "buatqris-status-orders.json")
disk_orders = OrderStore(status_path)
disk_orders.orders = {"BP-STATUS": status_order}
disk_orders.save()
status_premium = PremiumStore(os.path.join(base, "status-manual.json"),
                              os.path.join(base, "status-expiry.json"))
status_success = {"success": True, "data": {"transaction_id": "txn-status", "status": "success",
                                              "amount": 20000, "total_amount": 20019}}
status_client = FakeStatusClient((200, status_success))
status_harness = StatusRefreshHarness(OrderStore(status_path), status_client, status_premium)
status_harness._refresh_order(status_harness.orders.orders["BP-STATUS"])
assert status_harness.orders.orders["BP-STATUS"]["status"] == "PAID"
assert status_premium.status("status@example.com")[0]
assert status_client.calls == ["txn-status"]
status_harness._refresh_order(status_harness.orders.orders["BP-STATUS"])
assert status_client.calls == ["txn-status"]  # a paid order is never checked/activated twice

background_order = {"merchant_ref": "BP-AUTO", "reference": "txn-auto", "status": "UNPAID",
                    "provider": "buatqris", "email": "auto@example.com", "plan_id": "monthly",
                    "amount": 20000, "gateway_total_amount": 20019, "test_mode": True}
background_client = FakeStatusClient((200, {"success": True, "data": {
    "transaction_id": "txn-auto", "status": "success", "amount": 20000, "total_amount": 20019}}))
background_harness = StatusRefreshHarness(MemoryOrders({"BP-AUTO": background_order}),
                                          background_client, status_premium)
refresh_pending_buatqris_orders(background_harness)
assert background_order["status"] == "PAID" and status_premium.status("auto@example.com")[0]
assert background_client.calls == ["txn-auto"]

pending_order = {"merchant_ref": "BP-THROTTLE", "reference": "txn-throttle", "status": "UNPAID",
                 "provider": "buatqris", "email": "throttle@example.com", "plan_id": "monthly",
                 "amount": 20000, "gateway_total_amount": 20019, "test_mode": True}
pending_path = os.path.join(base, "buatqris-throttle-orders.json")
pending_store = OrderStore(pending_path)
pending_store.orders = {"BP-THROTTLE": pending_order}
pending_store.save()
pending_client = FakeStatusClient((200, {"success": True, "data": {
    "transaction_id": "txn-throttle", "status": "pending"}}))
pending_harness = StatusRefreshHarness(OrderStore(pending_path), pending_client, status_premium)
now = 1_800_000_000.0
with patch("premium_server.time.time", return_value=now):
    pending_harness._refresh_order(pending_harness.orders.orders["BP-THROTTLE"])
    pending_harness._refresh_order(pending_harness.orders.orders["BP-THROTTLE"])
assert pending_client.calls == ["txn-throttle"]
restarted_harness = StatusRefreshHarness(OrderStore(pending_path), pending_client, status_premium)
with patch("premium_server.time.time", return_value=now + 19.99):
    restarted_harness._refresh_order(restarted_harness.orders.orders["BP-THROTTLE"])
assert pending_client.calls == ["txn-throttle"]  # timestamp survived loading the order file
with patch("premium_server.time.time", return_value=now + 20):
    restarted_harness._refresh_order(restarted_harness.orders.orders["BP-THROTTLE"])
assert pending_client.calls == ["txn-throttle", "txn-throttle"]

bad_status_order = {"merchant_ref": "BP-STATUS-BAD", "reference": "txn-status-bad", "status": "UNPAID",
                    "provider": "buatqris", "email": "bad-status@example.com", "plan_id": "monthly",
                    "amount": 20000, "gateway_total_amount": 20019, "test_mode": True}
bad_status_client = FakeStatusClient((200, {"success": True, "data": {
    "transaction_id": "txn-status-bad", "status": "success", "amount": 20000, "total_amount": 20018}}))
bad_status_harness = StatusRefreshHarness(MemoryOrders({"BP-STATUS-BAD": bad_status_order}),
                                          bad_status_client, status_premium)
bad_status_harness._refresh_order(bad_status_order)
assert bad_status_order["status"] == "UNPAID"
assert bad_status_order["provider_check_error"] == "BuatQris status amount/total_amount tidak cocok"
assert not status_premium.status("bad-status@example.com")[0]

wrong_mode_order = {"merchant_ref": "BP-WRONG-MODE", "reference": "txn-wrong-mode", "status": "UNPAID",
                    "provider": "buatqris", "email": "wrong-mode@example.com", "plan_id": "monthly",
                    "amount": 20000, "gateway_total_amount": 20019, "test_mode": True}
wrong_mode_client = FakeStatusClient((200, status_success))
wrong_mode_harness = StatusRefreshHarness(MemoryOrders({"BP-WRONG-MODE": wrong_mode_order}),
                                          wrong_mode_client, status_premium)
wrong_mode_harness.buatqris = BuatQrisClient({"account_id": "acct", "secret_token": "api-secret",
                                              "signing_secret": "hook-secret", "mode": "production"})
wrong_mode_harness._refresh_order(wrong_mode_order)
assert wrong_mode_client.calls == []
print("BuatQris background/status fallback, validation, mode isolation, and persistent 20s throttle OK")

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
