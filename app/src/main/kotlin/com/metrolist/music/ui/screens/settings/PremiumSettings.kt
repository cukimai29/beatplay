/**
 * BeatPlay Premium screen: status, plans, orders, proof upload.
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.ui.screens.settings

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import coil3.compose.AsyncImage
import com.metrolist.music.R
import com.metrolist.music.constants.AdEveryNSongsKey
import com.metrolist.music.constants.AdSecondsKey
import com.metrolist.music.constants.DEFAULT_AD_EVERY_N_SONGS
import com.metrolist.music.constants.DEFAULT_AD_SECONDS
import com.metrolist.music.constants.DEFAULT_PREMIUM_SERVER_URL
import com.metrolist.music.constants.IsPremiumKey
import com.metrolist.music.constants.PremiumDevModeKey
import com.metrolist.music.constants.PremiumEmailKey
import com.metrolist.music.constants.PremiumExpiryKey
import com.metrolist.music.constants.PremiumServerUrlKey
import com.metrolist.music.constants.AccountEmailKey
import com.metrolist.music.constants.SongsSinceAdKey
import com.metrolist.music.premium.AdState
import com.metrolist.music.premium.ManualQris
import com.metrolist.music.premium.OrderInfo
import com.metrolist.music.premium.Plan
import com.metrolist.music.premium.PremiumApi
import com.metrolist.music.premium.isValidPremiumEmail
import com.metrolist.music.premium.isLegacyPremiumServerUrl
import com.metrolist.music.premium.photoMimeFor
import com.metrolist.music.utils.rememberPreference
import com.metrolist.music.utils.safeDataStoreEdit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.NumberFormat
import java.util.Date
import java.util.Locale

private fun rupiah(n: Int): String = "Rp" + NumberFormat.getNumberInstance(Locale("id", "ID")).format(n)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PremiumSettingsScreen(
    navController: NavController,
    showAdvanced: Boolean = false,
) {
    val context = LocalContext.current
    val uriHandler = LocalUriHandler.current
    val scope = rememberCoroutineScope()
    val api = remember { PremiumApi() }
    DisposableEffect(api) { onDispose { api.close() } }

    var accountEmailPref by rememberPreference(AccountEmailKey, "")
    var isPremiumPref by rememberPreference(IsPremiumKey, false)
    var expiryPref by rememberPreference(PremiumExpiryKey, 0L)
    var songsPref by rememberPreference(SongsSinceAdKey, 0)
    var serverPref by rememberPreference(PremiumServerUrlKey, DEFAULT_PREMIUM_SERVER_URL)
    var devPref by rememberPreference(PremiumDevModeKey, false)
    var adEveryPref by rememberPreference(AdEveryNSongsKey, DEFAULT_AD_EVERY_N_SONGS)
    var adSecondsPref by rememberPreference(AdSecondsKey, DEFAULT_AD_SECONDS)

    // Premium follows the signed-in account. Typing an arbitrary premium email
    // here would otherwise unlock the device for anyone who knows one.
    val signedInEmail = remember(accountEmailPref) {
        accountEmailPref.trim().lowercase().takeIf(::isValidPremiumEmail)
    }
    val email = signedInEmail.orEmpty()
    var busy by remember { mutableStateOf(false) }
    var msg by remember { mutableStateOf<String?>(null) }
    var plans by remember { mutableStateOf<List<Plan>?>(null) }
    var provider by remember { mutableStateOf("manual") }
    var manual by remember { mutableStateOf<ManualQris?>(null) }
    var plansErr by remember { mutableStateOf<String?>(null) }
    var selected by remember { mutableStateOf<String?>(null) }
    var order by remember { mutableStateOf<OrderInfo?>(null) }
    var statusMsg by remember { mutableStateOf("") }
    var polling by remember { mutableStateOf(false) }
    var sender by remember { mutableStateOf("") }
    var note by remember { mutableStateOf("") }
    var photoUri by remember { mutableStateOf<Uri?>(null) }
    var photoBytes by remember { mutableStateOf<ByteArray?>(null) }
    var photoMime by remember { mutableStateOf<String?>(null) }
    var photoMsg by remember { mutableStateOf<String?>(null) }

    suspend fun applyPremiumResult(premium: Boolean, expiry: Long?) {
        // Entitlement always follows the login; a typed address is never trusted.
        val bound = signedInEmail ?: return
        context.safeDataStoreEdit {
            it[IsPremiumKey] = premium
            it[PremiumEmailKey] = bound
            if (premium) {
                if (expiry != null) it[PremiumExpiryKey] = expiry else it.remove(PremiumExpiryKey)
                it[SongsSinceAdKey] = 0
            } else {
                it.remove(PremiumExpiryKey)
            }
        }
    }

    LaunchedEffect(serverPref) {
        plansErr = null
        runCatching { api.fetchShop(serverPref) }
            .onSuccess { shop ->
                plans = shop.plans
                provider = shop.provider
                manual = shop.manual
                if (selected == null) selected = shop.plans.firstOrNull()?.id
            }
            .onFailure { plansErr = context.getString(R.string.premium_plans_failed, it.message ?: "?") }
    }

    LaunchedEffect(polling, order?.merchantRef) {
        val ref = order?.merchantRef ?: return@LaunchedEffect
        while (polling) {
            delay(5000)
            runCatching { api.orderStatus(serverPref, ref) }
                .onSuccess { st ->
                    order = st.order
                    if (st.premium) {
                        applyPremiumResult(true, st.expiry)
                        polling = false
                        statusMsg = context.getString(R.string.premium_paid)
                    } else if (st.order.status == "PENDING") {
                        statusMsg = context.getString(R.string.premium_pending_verify)
                    } else if (st.order.status != "UNPAID") {
                        polling = false
                        statusMsg = context.getString(R.string.premium_order_status, st.order.status)
                    }
                }
        }
    }

    val photoPicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val (bytes, mime) = withContext(Dispatchers.IO) {
                runCatching {
                    val resolver = context.contentResolver
                    val type = resolver.getType(uri) ?: ""
                    val ext = when (type) {
                        "image/jpeg" -> "x.jpg"
                        "image/png" -> "x.png"
                        else -> "x"
                    }
                    val data = resolver.openInputStream(uri)?.use { stream ->
                        val buf = mutableListOf<Byte>()
                        val tmp = ByteArray(8192)
                        var total = 0
                        while (true) {
                            val n = stream.read(tmp)
                            if (n <= 0) break
                            total += n
                            if (total > 4 * 1024 * 1024 + 1) break
                            for (i in 0 until n) buf.add(tmp[i])
                        }
                        buf.toByteArray()
                    } ?: ByteArray(0)
                    Triple(data, photoMimeFor(ext, data), type)
                }.getOrNull() ?: Triple(ByteArray(0), null, "")
            }
            if (mime == null) {
                photoMsg = context.getString(R.string.premium_photo_invalid)
            } else {
                photoUri = uri
                photoBytes = bytes
                photoMime = mime
                photoMsg = null
            }
        }
    }

    Column(Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text(stringResource(R.string.premium)) },
            navigationIcon = {
                androidx.compose.material3.IconButton(onClick = navController::navigateUp) {
                    Icon(painterResource(R.drawable.arrow_back), contentDescription = null)
                }
            },
        )
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
                .padding(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            ElevatedCard(Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp)) {
                Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        painterResource(if (isPremiumPref) R.drawable.check else R.drawable.account),
                        null, Modifier.size(36.dp), tint = MaterialTheme.colorScheme.primary,
                    )
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(
                            stringResource(if (isPremiumPref) R.string.premium_status_active else R.string.premium_status_free),
                            style = MaterialTheme.typography.titleMedium,
                        )
                        Text(
                            if (isPremiumPref) {
                                stringResource(R.string.premium_active_desc) +
                                    if (expiryPref > 0) " " + context.getString(R.string.premium_valid_until, Date(expiryPref * 1000).toString()) else ""
                            } else {
                                context.getString(R.string.premium_free_desc, adEveryPref) +
                                    " " + context.getString(R.string.premium_next_ad, (adEveryPref - songsPref).coerceAtLeast(0))
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            if (signedInEmail == null) {
                Text(
                    "Login YouTube/Google diperlukan agar email Premium terdeteksi otomatis.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            } else {
                Text("Akun login: $signedInEmail", style = MaterialTheme.typography.bodyMedium)
            }
            var server by remember(serverPref) { mutableStateOf(serverPref) }
            if (showAdvanced) {
                OutlinedTextField(
                    server, { server = it; msg = null }, Modifier.fillMaxWidth(),
                    label = { Text("Server Premium") },
                    supportingText = { Text("URL server BeatPlay (dari admin).") },
                    singleLine = true,
                )
            }
            if (showAdvanced) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Switch(devPref, { devPref = it })
                    Text(stringResource(R.string.premium_dev_mode), style = MaterialTheme.typography.bodySmall)
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Button({
                    busy = true
                    msg = null
                    scope.launch {
                        serverPref = if (isLegacyPremiumServerUrl(server)) DEFAULT_PREMIUM_SERVER_URL else server.trim()
                        runCatching { api.refresh(serverPref, email) }
                            .onSuccess { (premium, expiry) ->
                                applyPremiumResult(premium, expiry)
                                msg = if (premium) context.getString(R.string.premium_active_for, email)
                                else context.getString(R.string.premium_not_premium, email)
                            }
                            .onFailure { msg = context.getString(R.string.premium_check_failed, it.message ?: it.javaClass.simpleName) }
                        busy = false
                    }
                }, enabled = !busy && signedInEmail != null && server.isNotBlank()) {
                    Text(stringResource(if (busy) R.string.premium_checking else R.string.premium_check))
                }
                    if (isPremiumPref) {
                        TextButton({
                            scope.launch {
                                context.safeDataStoreEdit {
                                    it[IsPremiumKey] = false
                                    it.remove(PremiumEmailKey)
                                    it.remove(PremiumExpiryKey)
                                    it[SongsSinceAdKey] = 0
                                }
                            }
                        }) { Text(stringResource(R.string.premium_switch_account)) }
                    }
                }
            msg?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            if (devPref) {
                OutlinedButton({
                    scope.launch {
                        AdState.showAdBlocking(adSecondsPref, "BeatPlay Premium - dengarkan tanpa jeda iklan. (Tes)") {
                            scope.launch { context.safeDataStoreEdit { it[SongsSinceAdKey] = 0 } }
                        }
                    }
                }) { Text(context.getString(R.string.premium_test_ad, adSecondsPref)) }
            }

            if (plans == null && plansErr == null) Text(stringResource(R.string.premium_plans_loading), style = MaterialTheme.typography.bodySmall)
            plansErr?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
            plans?.forEach { p ->
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    androidx.compose.material3.RadioButton(selected == p.id, { selected = p.id })
                    Column(Modifier.weight(1f)) {
                        Text(p.title, style = MaterialTheme.typography.titleSmall)
                        Text(
                            "${rupiah(p.price)} / ${p.days} hari",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            val emailOk = signedInEmail != null
            if (!emailOk) Text("Login akun dulu untuk membeli Premium.", style = MaterialTheme.typography.bodySmall)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button({
                    val plan = selected ?: return@Button
                    busy = true
                    scope.launch {
                        runCatching { api.createOrder(serverPref, requireNotNull(signedInEmail), plan) }
                            .onSuccess {
                                order = it
                                polling = true
                                sender = ""
                                note = ""
                                photoUri = null
                                photoBytes = null
                                photoMime = null
                            }
                            .onFailure { statusMsg = context.getString(R.string.premium_create_failed, it.message ?: "?") }
                        busy = false
                    }
                }, enabled = !busy && emailOk && selected != null) {
                    Text(stringResource(if (busy) R.string.premium_creating else R.string.premium_create_order))
                }
            }
            if (provider == "manual") {
                manual?.let { m ->
                    Text(context.getString(R.string.premium_pay_to, m.accountName), style = MaterialTheme.typography.titleSmall)
                    Text(m.instructions, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (m.hasImage) {
                        OutlinedButton({ uriHandler.openUri("$serverPref/manual-qris.png") }) {
                            Text(stringResource(R.string.premium_open_qris_image))
                        }
                    }
                }
            }
            order?.let { o ->
                Text("Ref: ${o.merchantRef} • ${rupiah(o.amount)} • ${o.status}", style = MaterialTheme.typography.bodySmall)
                o.qrString?.let { Text("QR: $it", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    o.checkoutUrl?.let { url -> OutlinedButton({ uriHandler.openUri(url) }) { Text(url.take(24)) } }
                    o.qrUrl?.let { url -> OutlinedButton({ uriHandler.openUri(url) }) { Text("QR") } }
                }
                if (provider == "manual" && o.status != "PAID") {
                    OutlinedTextField(sender, { sender = it }, Modifier.fillMaxWidth(), label = { Text(stringResource(R.string.premium_sender_label)) }, singleLine = true)
                    OutlinedTextField(note, { note = it }, Modifier.fillMaxWidth(), label = { Text(stringResource(R.string.premium_note_label)) }, singleLine = true)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        OutlinedButton({ photoPicker.launch("image/*") }) {
                            Text(stringResource(if (photoBytes == null) R.string.premium_pick_proof else R.string.premium_change_photo))
                        }
                        photoBytes?.let { Text("${it.size / 1024} KB", style = MaterialTheme.typography.bodySmall) }
                    }
                    photoUri?.let { uri ->
                        AsyncImage(uri, "Preview bukti", Modifier.heightIn(max = 180.dp).fillMaxWidth(), contentScale = ContentScale.Fit)
                    }
                    photoMsg?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
                    Button({
                        busy = true
                        scope.launch {
                            runCatching { api.submitProof(serverPref, o.merchantRef, sender, note, photoBytes, photoMime) }
                                .onSuccess {
                                    order = it
                                    polling = true
                                    photoUri = null
                                    photoBytes = null
                                    photoMime = null
                                    statusMsg = context.getString(R.string.premium_proof_sent)
                                }
                                .onFailure { statusMsg = context.getString(R.string.premium_create_failed, it.message ?: "?") }
                            busy = false
                        }
                    }, enabled = !busy && sender.isNotBlank() && photoBytes != null) {
                        Text(stringResource(R.string.premium_send_proof))
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (polling) OutlinedButton({ polling = false }) { Text(stringResource(R.string.premium_stop_check)) }
                    else OutlinedButton({
                        polling = true
                        scope.launch {
                            runCatching { api.orderStatus(serverPref, o.merchantRef) }
                                .onSuccess {
                                    order = it.order
                                    if (it.premium) {
                                        applyPremiumResult(true, it.expiry)
                                        polling = false
                                        statusMsg = context.getString(R.string.premium_paid)
                                    } else if (it.order.status == "PENDING") {
                                        statusMsg = context.getString(R.string.premium_pending_verify)
                                    } else {
                                        statusMsg = context.getString(R.string.premium_order_status, it.order.status)
                                        if (it.order.status !in listOf("UNPAID", "PENDING")) polling = false
                                    }
                                }
                        }
                    }) { Text(stringResource(R.string.premium_check_status)) }
                }
            }
            if (statusMsg.isNotBlank()) Text(statusMsg, style = MaterialTheme.typography.bodySmall)
        }
    }
}
