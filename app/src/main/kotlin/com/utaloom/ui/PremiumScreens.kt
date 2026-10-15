package com.utaloom.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.utaloom.data.Stores
import com.utaloom.platform.Updates
import com.utaloom.premium.AdManager
import com.utaloom.premium.ManualQris
import com.utaloom.premium.OrderInfo
import com.utaloom.premium.Plan
import com.utaloom.premium.PremiumRepository
import com.utaloom.premium.isDevMode
import com.utaloom.premium.isValidEmail
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.NumberFormat
import java.util.Locale

@Composable
fun PremiumScreen() {
    VScrollColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 32.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        item { PremiumSection() }
        item { BuyPremiumSection() }
    }
}

@Composable
fun PremiumSection() {
    val s by Stores.settings.state.collectAsState()
    val scope = rememberCoroutineScope()
    var email by remember(s.premiumEmail) { mutableStateOf(s.premiumEmail ?: "") }
    var busy by remember { mutableStateOf(false) }
    var msg by remember { mutableStateOf<String?>(null) }
    var server by remember(s.premiumServerUrl) { mutableStateOf(s.premiumServerUrl) }
    val dev by remember { mutableStateOf(isDevMode()) }

    SectionTitle("Premium")
    Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        ElevatedCard(Modifier.fillMaxWidth()) {
            Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Icon(
                    if (s.isPremium) Icons.Default.CheckCircle else Icons.Default.MusicNote,
                    null, Modifier.size(36.dp), tint = MaterialTheme.colorScheme.primary,
                )
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        if (s.isPremium) "Premium aktif" else "Paket gratis",
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Text(
                        if (s.isPremium) "Tanpa iklan audio." + (s.premiumExpiry?.let { " Berlaku s/d ${java.util.Date(it * 1000)}." } ?: "")
                        else "Iklan audio tiap ${s.adEveryNSongs} lagu. Upgrade untuk mendengar tanpa jeda.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        OutlinedTextField(email, { email = it; msg = null }, Modifier.fillMaxWidth(), label = { Text("Email akun") }, supportingText = { Text("Gunakan email yang sama dengan pembayaran.") }, singleLine = true, isError = email.isNotBlank() && !isValidEmail(email))
        OutlinedTextField(server, { server = it; msg = null }, Modifier.fillMaxWidth(), label = { Text("Server Premium") }, supportingText = { Text("URL server BeatPlay (dari admin).") }, singleLine = true)
        if (dev) {
            var adVideo by remember(s.adVideoPath) { mutableStateOf(s.adVideoPath) }
            OutlinedTextField(adVideo, {
                adVideo = it
                Stores.settings.update { cur -> cur.copy(adVideoPath = it.trim()) }
            }, Modifier.fillMaxWidth(), label = { Text("Video iklan (path file, kosong = bawaan)") }, singleLine = true)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Button({
                busy = true; msg = null
                scope.launch {
                    Stores.settings.update { it.copy(premiumServerUrl = server.trim()) }
                    val r = PremiumRepository.refresh(email, server.trim())
                    msg = if (r.isSuccess && r.getOrNull() == true) "Premium aktif untuk $email."
                    else if (r.isSuccess) "$email belum premium."
                    else "Gagal cek: ${r.exceptionOrNull()?.message}"
                    busy = false
                }
            }, enabled = !busy && isValidEmail(email)) { Text(if (busy) "Mengecek…" else "Cek status") }
            if (s.isPremium) TextButton({ Stores.settings.update { it.copy(isPremium = false, premiumEmail = null, premiumExpiry = null, songsSinceAd = 0) } }) { Text("Ganti akun") }
        }
        msg?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        if (dev) {
            OutlinedButton({
                scope.launch { AdManager.showAdBlocking(s.adSeconds, "BeatPlay Premium — dengarkan tanpa jeda iklan. (Tes tampilan)") }
            }) { Text("Tes iklan ${s.adSeconds} dtk") }
        }
    }
}

private fun rupiah(n: Int) = "Rp" + NumberFormat.getNumberInstance(Locale("id", "ID")).format(n)

@Composable
fun BuyPremiumSection() {
    val s by Stores.settings.state.collectAsState()
    val scope = rememberCoroutineScope()
    var plans by remember { mutableStateOf<List<Plan>?>(null) }
    var provider by remember { mutableStateOf("mock") }
    var manual by remember { mutableStateOf<ManualQris?>(null) }
    var shopBase by remember(s.premiumServerUrl) { mutableStateOf(s.premiumServerUrl.trim().trimEnd('/')) }
    var plansErr by remember { mutableStateOf<String?>(null) }
    var selected by remember { mutableStateOf<String?>(null) }
    var order by remember { mutableStateOf<OrderInfo?>(null) }
    var statusMsg by remember { mutableStateOf("Bayar via QRIS. Setelah bayar, akun otomatis premium.") }
    var busy by remember { mutableStateOf(false) }
    var polling by remember { mutableStateOf(false) }
    var sender by remember { mutableStateOf("") }
    var note by remember { mutableStateOf("") }
    var photoFile by remember { mutableStateOf<java.io.File?>(null) }
    var photoMsg by remember { mutableStateOf<String?>(null) }
    fun openLink(url: String) {
        runCatching { Updates.openExternal(url) }
            .onFailure { statusMsg = "Tidak bisa membuka browser: ${it.message}" }
    }

    LaunchedEffect(s.premiumServerUrl) {
        plansErr = null
        PremiumRepository.fetchShop(s.premiumServerUrl).onSuccess { shop ->
            plans = shop.plans; provider = shop.provider; manual = shop.manual; shopBase = shop.baseUrl
            if (selected == null) selected = shop.plans.firstOrNull()?.id
            statusMsg = if (shop.provider == "manual") "Bayar ke QRIS merchant di bawah, lalu kirim bukti. Admin verifikasi, premium aktif."
            else "Bayar via QRIS. Setelah bayar, akun otomatis premium."
        }.onFailure { plansErr = "Gagal muat paket: ${it.message}" }
    }
    LaunchedEffect(polling, order?.merchantRef) {
        val ref = order?.merchantRef ?: return@LaunchedEffect
        while (polling) {
            delay(5000)
            PremiumRepository.orderStatus(ref, s.premiumServerUrl).onSuccess { st ->
                order = st.order
                if (st.premium) {
                    polling = false
                    statusMsg = "Pembayaran lunas. Premium aktif s/d ${st.expiry?.let { java.util.Date(it * 1000) } ?: "-"}."
                } else if (st.order.status == "PENDING") {
                    statusMsg = "Bukti diterima. Menunggu verifikasi admin."
                } else if (st.order.status != "UNPAID") {
                    polling = false
                    statusMsg = "Status order: ${st.order.status}."
                }
            }
        }
    }

    SectionTitle("Beli Premium")
    Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (plans == null && plansErr == null) Text("Memuat paket…", style = MaterialTheme.typography.bodySmall)
        plansErr?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
        plans?.forEach { p ->
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                RadioButton(selected == p.id, { selected = p.id })
                Column(Modifier.weight(1f)) {
                    Text(p.title, style = MaterialTheme.typography.titleSmall)
                    Text("${rupiah(p.price)} / ${p.days} hari", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        val emailOk = s.premiumEmail?.let(::isValidEmail) == true
        if (!emailOk) Text("Isi Gmail di bagian Premium di atas dulu, lalu klik Cek premium.", style = MaterialTheme.typography.bodySmall)
        Button({
            val email = s.premiumEmail ?: return@Button
            val plan = selected ?: return@Button
            busy = true; statusMsg = "Membuat order…"
            scope.launch {
                PremiumRepository.createOrder(email, plan, s.premiumServerUrl).onSuccess {
                    order = it; polling = true
                    photoFile = null; sender = ""; note = ""
                    statusMsg = if (provider == "manual")
                        "Order ${it.merchantRef} (${rupiah(it.amount)}). Bayar ke QRIS di bawah, lalu kirim bukti."
                    else "Order ${it.merchantRef} (${rupiah(it.amount)}). Scan QR / buka halaman bayar, lalu tunggu status."
                }.onFailure { statusMsg = "Gagal: ${it.message}" }
                busy = false
            }
        }, enabled = !busy && emailOk && selected != null) { Text(if (busy) "Membuat…" else if (provider == "manual") "Buat order" else "Buat QRIS") }
        if (provider == "manual") manual?.let { m ->
            Text("Bayar ke: ${m.accountName}", style = MaterialTheme.typography.titleSmall)
            Text(m.instructions, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (m.hasImage) OutlinedButton({ openLink("$shopBase/manual-qris.png") }) { Text("Buka gambar QRIS") }
        }
        order?.let { o ->
            Text("Ref: ${o.merchantRef} • ${rupiah(o.amount)} • ${o.status}", style = MaterialTheme.typography.bodySmall)
            o.qrString?.let { Text("QR payload: $it", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            if (provider == "manual" && o.status != "PAID") {
                OutlinedTextField(sender, { sender = it }, Modifier.fillMaxWidth(), label = { Text("Nama pengirim DANA") }, singleLine = true)
                OutlinedTextField(note, { note = it }, Modifier.fillMaxWidth(), label = { Text("Catatan (opsional)") }, singleLine = true)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    OutlinedButton({
                        scope.launch {
                            val f = com.utaloom.platform.chooseProofPhoto()
                            if (f == null) photoMsg = "Foto tidak valid (JPG/PNG, maks 4MB)."
                            else { photoFile = f; photoMsg = null }
                        }
                    }) { Text(if (photoFile == null) "Pilih foto bukti (wajib)" else "Ganti foto") }
                    photoFile?.let { Text("${it.name} (${it.length() / 1024} KB)", style = MaterialTheme.typography.bodySmall) }
                }
                photoFile?.let { pf ->
                    coil3.compose.AsyncImage(pf, "Preview bukti", Modifier.heightIn(max = 180.dp).fillMaxWidth(), contentScale = ContentScale.Fit)
                }
                photoMsg?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
                Button({
                    busy = true
                    val photo = photoFile
                    scope.launch {
                        PremiumRepository.submitProof(o.merchantRef, sender, note, photo, s.premiumServerUrl).onSuccess {
                            order = it; polling = true
                            photoFile = null
                            statusMsg = "Bukti + foto terkirim. Menunggu verifikasi admin."
                        }.onFailure { statusMsg = "Gagal: ${it.message}" }
                        busy = false
                    }
                }, enabled = !busy && sender.isNotBlank() && photoFile != null) { Text("Kirim bukti bayar") }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                o.checkoutUrl?.let { url ->
                    OutlinedButton({ openLink(url) }) { Text("Buka halaman bayar") }
                }
                o.qrUrl?.let { url ->
                    OutlinedButton({ openLink(url) }) { Text("Buka gambar QR") }
                }
                if (polling) OutlinedButton({ polling = false }) { Text("Stop cek") }
                else OutlinedButton({
                    polling = true
                    scope.launch {
                        PremiumRepository.orderStatus(o.merchantRef, s.premiumServerUrl).onSuccess {
                            order = it.order
                            statusMsg = if (it.premium) "Pembayaran lunas. Premium aktif."
                            else if (it.order.status == "PENDING") "Bukti diterima. Menunggu verifikasi admin."
                            else "Status: ${it.order.status}."
                            if (it.premium || it.order.status !in listOf("UNPAID", "PENDING")) polling = false
                        }
                    }
                }) { Text("Cek status") }
            }
        }
        Text(statusMsg, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
fun AdOverlay() {
    val showing by AdManager.showing.collectAsState()
    if (!showing) return
    val left by AdManager.secondsLeft.collectAsState()
    val text by AdManager.text.collectAsState()
    Dialog(onDismissRequest = {}) {
        Surface(shape = MaterialTheme.shapes.large, tonalElevation = 6.dp) {
            Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text("Iklan", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                run {
                    val videoPath by AdManager.videoPath.collectAsState()
                    if (videoPath != null) AdVideoView(videoPath) else Text(text, style = MaterialTheme.typography.titleMedium)
                }
                Text("Lagu berikutnya dalam $left dtk… (tidak bisa di-skip di akun gratis)", style = MaterialTheme.typography.bodySmall)
                LinearProgressIndicator(Modifier.fillMaxWidth())
                OutlinedButton({}, enabled = false) { Text("Upgrade ke Premium di Settings") }
            }
        }
    }
}

@Composable
private fun AdVideoView(path: String?) {
    if (path == null) return
    val settings by Stores.settings.state.collectAsState()
    val canvas = androidx.compose.runtime.remember(path) { com.utaloom.playback.VideoAdPlayer.surface() }
    androidx.compose.runtime.DisposableEffect(path) {
        onDispose { com.utaloom.playback.VideoAdPlayer.release() }
    }
    androidx.compose.runtime.LaunchedEffect(path) {
        val f = java.io.File(path)
        if (com.utaloom.playback.AdVideo.isCandidate(f)) {
            com.utaloom.playback.VideoAdPlayer.play(
                path = f.absolutePath,
                volume = settings.volume,
                onStarted = { hasVideo -> if (!hasVideo) AdManager.setVideoAd(null) },
                onFinished = { AdManager.finishNow() },
            )
        } else {
            AdManager.setVideoAd(null)
        }
    }
    if (canvas == null) {
        Text("Memuat video…", style = MaterialTheme.typography.bodySmall)
    } else {
        androidx.compose.ui.awt.SwingPanel(
            factory = { canvas },
            modifier = Modifier.fillMaxWidth().aspectRatio(16f / 9f),
        )
    }
}
