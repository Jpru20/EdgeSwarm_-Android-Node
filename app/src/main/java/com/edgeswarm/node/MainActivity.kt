package com.edgeswarm.node

import android.Manifest
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.AccountBox
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat

import io.github.jan.supabase.createSupabaseClient
import io.github.jan.supabase.auth.Auth
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.auth.providers.builtin.Email
import io.github.jan.supabase.postgrest.Postgrest
import io.github.jan.supabase.postgrest.postgrest

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import org.web3j.crypto.Credentials
import org.web3j.crypto.Keys
import java.net.URLEncoder
import java.util.Locale

private val API_BASE_URL = EdgeSwarmConfig.apiBaseUrl

@Serializable
data class WorkerWallet(
    val email: String,
    val private_key: String
)

data class LedgeItem(
    val taskId: String,
    val worker: String,
    val score: String,
    val txHash: String,
    val proofStatus: String = "",
    val createdAt: String = ""
)

val supabase = createSupabaseClient(
    supabaseUrl = EdgeSwarmConfig.supabaseUrl,
    supabaseKey = EdgeSwarmConfig.supabaseAnonKey
) {
    install(Auth)
    install(Postgrest)
}

class MainActivity : ComponentActivity() {

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        Log.d("EdgeSwarm", "System security clearances updated: $permissions")
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        checkRequiredPermissions()

        setContent {
            val context = LocalContext.current
            val scope = rememberCoroutineScope()
            val sharedPrefs = context.getSharedPreferences("EdgePrefs", MODE_PRIVATE)

            val savedEmail = sharedPrefs.getString("auth_email", null)
            var isLoggedIn by remember { mutableStateOf(savedEmail != null) }
            var authenticatedUserEmail by remember { mutableStateOf(savedEmail ?: "") }

            var earningsUsd by remember {
                mutableStateOf(sharedPrefs.getString("earnings_usd", "0.00") ?: "0.00")
            }
            var isSyncing by remember { mutableStateOf(false) }
            var selectedTab by remember { mutableIntStateOf(2) }

            LaunchedEffect(isLoggedIn, authenticatedUserEmail) {
                if (isLoggedIn && authenticatedUserEmail.isNotEmpty()) {
                    val walletReady = syncWalletKey(authenticatedUserEmail)
                    if (!walletReady) {
                        Log.w("EdgeSwarm", "Wallet sync is not ready for $authenticatedUserEmail")
                    }

                    earningsUsd = refreshUsdEarningsForUser(
                        authenticatedUserEmail,
                        sharedPrefs
                    )
                }
            }

            Surface(
                modifier = Modifier.fillMaxSize(),
                color = Color(0xFF0B0E11)
            ) {
                if (!isLoggedIn) {
                    LoginPortalScreen(
                        onAuthSuccess = { verifiedEmail ->
                            scope.launch {
                                Toast.makeText(context, "Syncing secure wallet...", Toast.LENGTH_SHORT).show()

                                val walletReady = syncWalletKey(verifiedEmail)
                                if (!walletReady) {
                                    Toast.makeText(
                                        context,
                                        "Login succeeded, but wallet provisioning failed. Please try again.",
                                        Toast.LENGTH_LONG
                                    ).show()
                                    return@launch
                                }

                                authenticatedUserEmail = verifiedEmail
                                isLoggedIn = true
                                sharedPrefs.edit().putString("auth_email", verifiedEmail).apply()

                                earningsUsd = refreshUsdEarningsForUser(
                                    verifiedEmail,
                                    sharedPrefs
                                )

                                Toast.makeText(context, "Identity and wallet synced.", Toast.LENGTH_SHORT).show()
                            }
                        }
                    )
                } else {
                    // SWARM_SYSTEM_STATUS_BAR_INSET_V1
                    // Keep Swarm below the Android status bar while
                    // preserving the dark system-bar background.
                    Scaffold(
                        modifier = Modifier
                            .fillMaxSize()
                            .statusBarsPadding(),
                        bottomBar = {},
                        containerColor = Color(0xFFF7F7FA),
                        contentWindowInsets =
                            WindowInsets.safeDrawing.only(
                                WindowInsetsSides.Horizontal +
                                    WindowInsetsSides.Bottom
                            )
                    ) { innerPadding ->
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(innerPadding)
                                .background(Color(0xFFF7F7FA))
                        ) {
                            when (selectedTab) {
                                0 -> LedgeScreen(authenticatedUserEmail)
                                1 -> TokenDashboard(earningsUsd, isSyncing) {
                                    isSyncing = true
                                    scope.launch {
                                        earningsUsd = refreshUsdEarningsForUser(
                                            authenticatedUserEmail,
                                            sharedPrefs
                                        )
                                        isSyncing = false
                                    }
                                }
                                2 -> SentinelScreen(
                                    userEmail = authenticatedUserEmail,
                                    earningsUsd = earningsUsd,
                                    onSignOut = {
                                        scope.launch {
                                            context.getSharedPreferences(
                                                "EdgeSwarmNodeSettings",
                                                MODE_PRIVATE
                                            ).edit()
                                                .putBoolean(
                                                    "node_enabled",
                                                    false
                                                )
                                                .apply()

                                            context.stopService(
                                                Intent(
                                                    context,
                                                    SentinelService::class.java
                                                )
                                            )

                                            runCatching {
                                                withContext(Dispatchers.IO) {
                                                    supabase.auth.signOut()
                                                }
                                            }.onFailure { error ->
                                                Log.w(
                                                    "EdgeSwarm",
                                                    "Supabase sign-out warning: ${error.message}"
                                                )
                                            }

                                            sharedPrefs.edit()
                                                .remove("auth_email")
                                                .remove("balance")
                                                .remove("usd")
                                                .remove("earnings_usd")
                                                .apply()

                                            authenticatedUserEmail = ""
                                            isLoggedIn = false
                                        }
                                    }
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    private suspend fun syncWalletKey(email: String): Boolean {
        val nodePrefs = getSharedPreferences("EdgeSwarmNode", MODE_PRIVATE)

        if (WalletVault.hasPrivateKey(this, email)) {
            Log.d("EdgeSwarm", "Wallet already protected by Android Keystore for $email")
            return true
        }

        return try {
            val legacyLocalKey = nodePrefs.getString("private_key_$email", null)

            if (!legacyLocalKey.isNullOrBlank()) {
                WalletVault.storePrivateKey(this, email, legacyLocalKey)
                Log.d("EdgeSwarm", "Legacy wallet migrated into Android Keystore for $email")
                return true
            }

            Log.d("EdgeSwarm", "Searching cloud for existing wallet...")

            val result = supabase.postgrest["worker_wallets"]
                .select { filter { eq("email", email) } }
                .decodeSingleOrNull<WorkerWallet>()

            if (result != null) {
                WalletVault.storePrivateKey(this, email, result.private_key)
                Log.d("EdgeSwarm", "Wallet restored into Android Keystore for $email")
            } else {
                val ecKeyPair = Keys.createEcKeyPair()
                val newPrivateKey = ecKeyPair.privateKey.toString(16)

                WalletVault.storePrivateKey(this, email, newPrivateKey)

                val newWallet = WorkerWallet(email, newPrivateKey)
                supabase.postgrest["worker_wallets"].insert(newWallet)

                Log.d("EdgeSwarm", "New wallet generated, protected, and backed up for $email")
            }

            true
        } catch (e: Exception) {
            Log.e("EdgeSwarm", "Cloud wallet sync failed: ${e.message}", e)
            false
        }
    }

    private suspend fun refreshUsdEarningsForUser(
        email: String,
        sharedPrefs: android.content.SharedPreferences
    ): String {
        val earningsUsd = fetchProviderUsdEarnings(email)
        val earningsText = String.format(Locale.US, "%.4f", earningsUsd)

        sharedPrefs.edit()
            .putString("earnings_usd", earningsText)
            .remove("balance")
            .remove("usd")
            .apply()

        return earningsText
    }

    private suspend fun fetchProviderUsdEarnings(email: String): Double =
        withContext(Dispatchers.IO) {
            val client = OkHttpClient()
            val encodedEmail = encodeUrl(email)
            val url =
                "$API_BASE_URL/v1/provider/ledger/me" +
                    "?providerEmail=$encodedEmail" +
                    "&limit=20" +
                    "&t=${System.currentTimeMillis()}"

            try {
                var accessToken = supabase.auth.currentSessionOrNull()
                    ?.accessToken
                    ?.trim()
                    ?.takeIf { it.isNotEmpty() }

                if (accessToken == null) {
                    Log.w("EdgeSwarm", "USD earnings sync skipped: authenticated session unavailable.")
                    return@withContext 0.0
                }

                for (attempt in 0..1) {
                    val request = Request.Builder()
                        .url(url)
                        .header("Cache-Control", "no-cache")
                        .header("Authorization", "Bearer $accessToken")
                        .build()

                    client.newCall(request).execute().use { response ->
                        val body = response.body?.string().orEmpty()

                        if (response.code == 401 && attempt == 0) {
                            runCatching {
                                supabase.auth.refreshCurrentSession()
                            }.onFailure { error ->
                                Log.w("EdgeSwarm", "USD earnings bearer refresh failed: ${error.message}")
                            }

                            accessToken = supabase.auth.currentSessionOrNull()
                                ?.accessToken
                                ?.trim()
                                ?.takeIf { it.isNotEmpty() }

                            if (accessToken != null) {
                                continue
                            }
                        }

                        if (!response.isSuccessful) {
                            Log.w("EdgeSwarm", "USD earnings sync failed: HTTP ${response.code} - $body")
                            return@withContext 0.0
                        }

                        val json = JSONObject(body)
                        val totalEarnedUsd = json.optDouble("totalEarnedUsd", Double.NaN)

                        if (!totalEarnedUsd.isNaN()) {
                            Log.d("EdgeSwarm", "Provider USD earnings synced: $totalEarnedUsd")
                            return@withContext totalEarnedUsd
                        }

                        val summaryTotal = json.optJSONObject("usdSummary")
                            ?.optDouble("totalEarnedUsd", Double.NaN)
                            ?: Double.NaN

                        if (!summaryTotal.isNaN()) {
                            Log.d("EdgeSwarm", "Provider USD summary synced: $summaryTotal")
                            return@withContext summaryTotal
                        }

                        Log.w("EdgeSwarm", "USD earnings response missing totalEarnedUsd.")
                        return@withContext 0.0
                    }
                }

                0.0
            } catch (error: Exception) {
                Log.w("EdgeSwarm", "USD earnings sync failed.", error)
                0.0
            }
        }

    private fun checkRequiredPermissions() {
        val permissions = mutableListOf<String>()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.POST_NOTIFICATIONS)
        }

        if (permissions.isNotEmpty()) {
            requestPermissionLauncher.launch(permissions.toTypedArray())
        }
    }
}

@Composable
fun LoginPortalScreen(onAuthSuccess: (String) -> Unit) {
    var inputEmail by remember { mutableStateOf("") }
    var inputPassword by remember { mutableStateOf("") }
    var totpCode by remember { mutableStateOf("") }
    var currentFactorId by remember { mutableStateOf("") }

    var isPasswordVisible by remember { mutableStateOf(false) }
    var isMfaRequired by remember { mutableStateOf(false) }
    var loading by remember { mutableStateOf(false) }
    var statusMsg by remember { mutableStateOf("") }

    val scope = rememberCoroutineScope()
    val uriHandler = LocalUriHandler.current

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF0B0E11))
            .padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(
            Icons.Default.Lock,
            contentDescription = null,
            tint = Color(0xFF03DAC5),
            modifier = Modifier.size(64.dp)
        )

        Spacer(modifier = Modifier.height(16.dp))

        Text(
            "EDGE SWARM NODE",
            fontSize = 24.sp,
            fontWeight = FontWeight.Black,
            color = Color.White,
            fontFamily = FontFamily.Monospace
        )

        Text("Mobile Node Authorization", fontSize = 12.sp, color = Color.Gray)

        Spacer(modifier = Modifier.height(40.dp))

        if (!isMfaRequired) {
            OutlinedTextField(
                value = inputEmail,
                onValueChange = { inputEmail = it },
                label = { Text("Provider Email") },
                modifier = Modifier.fillMaxWidth(),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedTextColor = Color.White,
                    unfocusedTextColor = Color.White,
                    cursorColor = Color.White,
                    focusedBorderColor = Color(0xFF03DAC5),
                    unfocusedLabelColor = Color.Gray,
                    focusedLabelColor = Color.White
                )
            )

            Spacer(modifier = Modifier.height(16.dp))

            OutlinedTextField(
                value = inputPassword,
                onValueChange = { inputPassword = it },
                label = { Text("Password") },
                modifier = Modifier.fillMaxWidth(),
                visualTransformation = if (isPasswordVisible) VisualTransformation.None else PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                trailingIcon = {
                    TextButton(
                        onClick = { isPasswordVisible = !isPasswordVisible },
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)
                    ) {
                        Text(
                            if (isPasswordVisible) "HIDE" else "SHOW",
                            color = Color(0xFF03DAC5),
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                },
                colors = OutlinedTextFieldDefaults.colors(
                    focusedTextColor = Color.White,
                    unfocusedTextColor = Color.White,
                    cursorColor = Color.White,
                    focusedBorderColor = Color(0xFF03DAC5),
                    unfocusedLabelColor = Color.Gray,
                    focusedLabelColor = Color.White
                )
            )
        } else {
            OutlinedTextField(
                value = totpCode,
                onValueChange = { totpCode = it },
                label = { Text("2FA Authenticator Code") },
                modifier = Modifier.fillMaxWidth(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedTextColor = Color.White,
                    unfocusedTextColor = Color.White,
                    cursorColor = Color.White,
                    focusedBorderColor = Color(0xFF10B981),
                    unfocusedLabelColor = Color.Gray,
                    focusedLabelColor = Color.White
                )
            )
        }

        Spacer(modifier = Modifier.height(32.dp))

        Button(
            onClick = {
                if (loading) return@Button

                loading = true
                statusMsg = "Processing security handshake..."

                scope.launch {
                    try {
                        if (!isMfaRequired) {
                            withContext(Dispatchers.IO) {
                                supabase.auth.signInWith(Email) {
                                    email = inputEmail.trim()
                                    password = inputPassword
                                }
                            }

                            val (mfaEnabled, mfaActive) = supabase.auth.mfa.status

                            if (mfaEnabled && !mfaActive) {
                                val user = supabase.auth.currentUserOrNull()
                                val verifiedFactor = user?.factors?.firstOrNull()

                                if (verifiedFactor != null) {
                                    currentFactorId = verifiedFactor.id
                                    isMfaRequired = true
                                    statusMsg = "MFA challenge initiated."
                                } else {
                                    withContext(Dispatchers.IO) { supabase.auth.signOut() }
                                    statusMsg = "Warning: set up 2FA on the web console first."
                                }
                            } else {
                                onAuthSuccess(inputEmail.trim())
                            }
                        } else {
                            withContext(Dispatchers.IO) {
                                val challenge = supabase.auth.mfa.createChallenge(currentFactorId)
                                supabase.auth.mfa.verifyChallenge(
                                    currentFactorId,
                                    challenge.id,
                                    totpCode.trim()
                                )
                            }

                            onAuthSuccess(inputEmail.trim())
                        }
                    } catch (e: Exception) {
                        Log.e("EdgeSwarm", "Login failed", e)
                        val loginError = e.message ?: e::class.java.simpleName
                        statusMsg = "Access denied: $loginError"
                    } finally {
                        loading = false
                    }
                }
            },
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp),
            shape = RoundedCornerShape(8.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = if (isMfaRequired) Color(0xFF10B981) else Color(0xFF4F46E5)
            )
        ) {
            Text(
                if (loading) {
                    "AUTHORIZING..."
                } else if (isMfaRequired) {
                    "VERIFY SECURE TOKEN"
                } else {
                    "SIGN IN TO EDGESWARM"
                },
                fontWeight = FontWeight.Bold
            )
        }

        if (statusMsg.isNotEmpty()) {
            Spacer(modifier = Modifier.height(24.dp))

            Text(
                statusMsg,
                color = if (
                    statusMsg.contains("Warning", ignoreCase = true) ||
                    statusMsg.contains("denied", ignoreCase = true)
                ) {
                    Color.Red
                } else {
                    Color(0xFF00FFCC)
                },
                fontSize = 13.sp,
                textAlign = TextAlign.Center
            )
        }
    }
}

@Composable
fun LedgeScreen(userEmail: String) {
    var isRefreshing by remember { mutableStateOf(false) }
    var ledgeItems by remember { mutableStateOf(listOf<LedgeItem>()) }
    var statusText by remember { mutableStateOf("") }

    val scope = rememberCoroutineScope()
    val uriHandler = LocalUriHandler.current

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp)
    ) {
        Text(
            "BLOCKCHAIN LEDGE",
            fontSize = 20.sp,
            fontWeight = FontWeight.Bold,
            color = Color(0xFF03DAC5)
        )

        Text(
            "Verified EdgeSwarm rewards and proof records.",
            color = Color.Gray,
            fontSize = 12.sp
        )

        Spacer(modifier = Modifier.height(32.dp))

        Button(
            onClick = {
                isRefreshing = true
                statusText = "Refreshing proof ledger..."

                scope.launch {
                    ledgeItems = fetchLedgeEvents(userEmail)
                    statusText = if (ledgeItems.isEmpty()) {
                        "No ledger rows returned yet for this provider."
                    } else {
                        "${ledgeItems.size} ledger row(s) loaded."
                    }
                    isRefreshing = false
                }
            },
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp),
            shape = RoundedCornerShape(8.dp),
            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1E2329))
        ) {
            Text(
                if (isRefreshing) "FETCHING..." else "REFRESH LEDGE",
                fontWeight = FontWeight.Bold
            )
        }

        if (statusText.isNotEmpty()) {
            Spacer(modifier = Modifier.height(12.dp))
            Text(statusText, color = Color.Gray, fontSize = 12.sp)
        }

        Spacer(modifier = Modifier.height(24.dp))

        Text(
            "RECENT REWARD ACTIVITY",
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
            color = Color.Gray
        )

        Spacer(modifier = Modifier.height(16.dp))

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(ledgeItems.size) { index ->
                val item = ledgeItems[index]
                val proofUrl = buildProofUrl(item.txHash)

                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .then(
                            if (proofUrl != null) {
                                Modifier.clickable { uriHandler.openUri(proofUrl) }
                            } else {
                                Modifier
                            }
                        ),
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF161A1E))
                ) {
                    Row(
                        modifier = Modifier
                            .padding(16.dp)
                            .fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                "Task ${item.taskId}",
                                color = Color.White,
                                fontWeight = FontWeight.Bold
                            )

                            Text(
                                "Node: ${shortAddress(item.worker)}",
                                color = Color.Gray,
                                fontSize = 11.sp
                            )

                            if (proofUrl != null) {
                                Text(
                                    "Base proof: ${shortAddress(item.txHash)}",
                                    color = Color.Gray,
                                    fontSize = 11.sp
                                )
                            }
                        }

                        Column(horizontalAlignment = Alignment.End) {
                            Text(
                                item.score,
                                color = Color(0xFF00FFCC),
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold
                            )

                            Text(
                                item.proofStatus.ifBlank { "RECORDED" }.uppercase(Locale.US),
                                color = Color(0xFF4CAF50),
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold
                            )

                            if (proofUrl != null) {
                                TextButton(
                                    onClick = { uriHandler.openUri(proofUrl) },
                                    contentPadding = PaddingValues(0.dp),
                                    modifier = Modifier.height(30.dp)
                                ) {
                                    Text(
                                        "VIEW PROOF",
                                        color = Color(0xFF03DAC5),
                                        fontSize = 10.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                            } else {
                                Text(
                                    "PROOF PENDING",
                                    color = Color.Gray,
                                    fontSize = 9.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

private suspend fun fetchLedgeEvents(providerEmail: String): List<LedgeItem> =
    withContext(Dispatchers.IO) {
        val client = OkHttpClient()
        val encodedEmail = encodeUrl(providerEmail)

        val candidateUrls = listOf(
            "$API_BASE_URL/v1/provider/ledge?providerEmail=$encodedEmail&limit=50",
            "$API_BASE_URL/v1/provider/ledge?email=$encodedEmail&limit=50"
        )

        for (url in candidateUrls) {
            try {
                val requestBuilder = Request.Builder().url(url)
                supabase.auth.currentSessionOrNull()?.accessToken
                    ?.takeIf { it.isNotBlank() }
                    ?.let { requestBuilder.header("Authorization", "Bearer $it") }
                val request = requestBuilder.build()

                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) return@use

                    val body = response.body?.string() ?: "{}"
                    val rows = extractJsonArray(body)

                    if (rows.length() == 0) return@use

                    val parsed = mutableListOf<LedgeItem>()

                    for (i in 0 until rows.length()) {
                        val row = rows.optJSONObject(i) ?: continue

                        val taskId = row.optFirstString(
                            "taskId",
                            "task_id",
                            "id"
                        ).ifBlank { "Unknown" }

                        val worker = row.optFirstString(
                            "worker",
                            "wallet",
                            "node_wallet",
                            "nodeWallet",
                            "hardwareId",
                            "hardware_id"
                        )

                        val txHash = row.optFirstString(
                            "baseScanUrl",
                            "base_scan_url",
                            "basescanUrl",
                            "basescan_url",
                            "proofUrl",
                            "proof_url",
                            "explorerUrl",
                            "explorer_url",
                            "txHash",
                            "tx_hash",
                            "batchTxHash",
                            "batch_tx_hash",
                            "transactionHash",
                            "transaction_hash"
                        )

                        val proofStatus = row.optFirstString(
                            "proofStatus",
                            "proof_status",
                            "status",
                            "outcome"
                        )

                        val reward = row.firstDoubleOrNull(
                            "reward",
                            "amount",
                            "providerUsdReward",
                            "provider_usd_reward",
                            "rewardUsd",
                            "reward_usd",
                            "score"
                        )

                        val scoreText = if (reward != null) {
                            "$${String.format(Locale.US, "%.4f", reward)} USD"
                        } else {
                            row.optFirstString("score", "rewardText", "reward_text").ifBlank { "PENDING" }
                        }

                        val createdAt = row.optFirstString(
                            "createdAt",
                            "created_at",
                            "consensusTimestamp",
                            "consensus_timestamp"
                        )

                        parsed.add(
                            LedgeItem(
                                taskId = taskId,
                                worker = worker,
                                score = scoreText,
                                txHash = txHash,
                                proofStatus = proofStatus,
                                createdAt = createdAt
                            )
                        )
                    }

                    return@withContext parsed
                }
            } catch (e: Exception) {
                Log.w("EdgeSwarm", "Ledge endpoint failed: $url", e)
            }
        }

        return@withContext emptyList<LedgeItem>()
    }

@Composable
fun TokenDashboard(
    earningsUsd: String,
    isSyncing: Boolean,
    onSync: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            "PROVIDER EARNINGS",
            color = Color.Gray,
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold
        )

        Text(
            text = if (isSyncing) "SYNCING..." else "\$$earningsUsd USD",
            fontSize = 48.sp,
            fontWeight = FontWeight.Black,
            color = Color.White,
            textAlign = TextAlign.Center
        )

        Text(
            text = "Verified provider earnings",
            color = Color(0xFF00FFCC),
            fontSize = 18.sp
        )

        Spacer(modifier = Modifier.height(8.dp))

        Text(
            text = "Pre-market reference conversion. Not a live market price.",
            color = Color.Gray,
            fontSize = 11.sp,
            textAlign = TextAlign.Center
        )

        Spacer(modifier = Modifier.height(40.dp))

        Button(
            onClick = onSync,
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp),
            shape = RoundedCornerShape(8.dp),
            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1E2329))
        ) {
            Text(
                if (isSyncing) "SYNCING..." else "SYNC LEDGER DATA",
                fontWeight = FontWeight.Bold
            )
        }
    }
}


// ANDROID_UPDATE_CHECK_V1
private val ANDROID_RELEASE_ENDPOINT = "$API_BASE_URL/android/latest-version"

private data class AndroidReleaseInfo(
    val version: String,
    val minimumVersion: String,
    val downloadUrl: String,
    val sha256: String,
    val packageName: String
)

private suspend fun fetchAndroidReleaseInfo(): AndroidReleaseInfo =
    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        val connection = (java.net.URL(ANDROID_RELEASE_ENDPOINT).openConnection() as java.net.HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 8000
            readTimeout = 8000
            setRequestProperty("Accept", "application/json")
        }

        try {
            val code = connection.responseCode
            val body = if (code in 200..299) {
                connection.inputStream.bufferedReader().use { it.readText() }
            } else {
                connection.errorStream?.bufferedReader()?.use { it.readText() } ?: ""
            }

            if (code !in 200..299) {
                throw IllegalStateException("Android release check failed with HTTP $code")
            }

            val json = org.json.JSONObject(body)

            AndroidReleaseInfo(
                version = json.optString("version", ""),
                minimumVersion = json.optString(
                    "minimumVersion",
                    json.optString("minVersion", "")
                ),
                downloadUrl = json.optString("downloadUrl", ""),
                sha256 = json.optString("sha256", ""),
                packageName = json.optString("packageName", "com.edgeswarm.node")
            )
        } finally {
            connection.disconnect()
        }
    }

private fun cleanVersionForCompare(value: String?): List<Int> {
    if (value.isNullOrBlank()) return listOf(0)
    return value
        .trim()
        .removePrefix("v")
        .removePrefix("V")
        .split(".", "-", "_")
        .mapNotNull { part ->
            val digits = part.takeWhile { it.isDigit() }
            digits.toIntOrNull()
        }
        .ifEmpty { listOf(0) }
}

private fun compareAppVersions(current: String?, target: String?): Int {
    val a = cleanVersionForCompare(current)
    val b = cleanVersionForCompare(target)
    val max = maxOf(a.size, b.size)

    for (i in 0 until max) {
        val av = a.getOrElse(i) { 0 }
        val bv = b.getOrElse(i) { 0 }
        if (av != bv) return av.compareTo(bv)
    }

    return 0
}


@Composable
fun SentinelScreen(
    userEmail: String,
    earningsUsd: String,
    onSignOut: () -> Unit
) {
    val context = LocalContext.current
    val isRunning by SentinelService.runningState.collectAsState()
    val serviceLevel2StatusText by
        SentinelService.level2StatusState.collectAsState()

    val nodeSettings = remember(context) {
        context.getSharedPreferences(
            "EdgeSwarmNodeSettings",
            android.content.Context.MODE_PRIVATE
        )
    }

    // ANDROID_FCM_FIRST_LOGICAL_ACTIVATION_UI_V1
    // Logical activation is independent from whether the short-lived
    // Sentinel foreground service is currently awake.
    var nodeEnabled by remember {
        mutableStateOf(
            nodeSettings.getBoolean(
                "node_enabled",
                false
            )
        )
    }

    androidx.compose.runtime.DisposableEffect(
        nodeSettings
    ) {
        val listener =
            android.content.SharedPreferences
                .OnSharedPreferenceChangeListener {
                    preferences,
                    key ->

                    if (key == "node_enabled") {
                        nodeEnabled =
                            preferences.getBoolean(
                                "node_enabled",
                                false
                            )
                    }
                }

        nodeSettings
            .registerOnSharedPreferenceChangeListener(
                listener
            )

        onDispose {
            nodeSettings
                .unregisterOnSharedPreferenceChangeListener(
                    listener
                )
        }
    }

    var allowCompute by rememberSaveable {
        mutableStateOf(
            nodeSettings.getBoolean("allow_compute", true)
        )
    }

    var allowScraping by rememberSaveable {
        mutableStateOf(
            nodeSettings.getBoolean("allow_scraping", true)
        )
    }

    var allowBatteryTasks by rememberSaveable {
        mutableStateOf(
            nodeSettings.getBoolean(
                "allow_battery_tasks",
                true
            )
        )
    }

    var allowNeuralTasks by rememberSaveable {
        mutableStateOf(
            nodeSettings.getBoolean("allow_neural", false)
        )
    }

    val level2Installer = remember(context) {
        AndroidLevel2ModelInstaller(context)
    }

    val level2UiScope = rememberCoroutineScope()

    var showLevel2DownloadDialog by rememberSaveable {
        mutableStateOf(false)
    }

    var level2InstallInProgress by remember {
        mutableStateOf(false)
    }

    var level2StatusText by rememberSaveable {
        mutableStateOf(
            if (allowNeuralTasks) {
                "Verified model installed. Runtime self-test is still required."
            } else {
                "Optional 2.6 GB Gemma model for supported Android devices."
            }
        )
    }

    if (showLevel2DownloadDialog) {
        AlertDialog(
            onDismissRequest = {
                if (!level2InstallInProgress) {
                    showLevel2DownloadDialog = false
                }
            },
            title = {
                Text("Enable Level 2?")
            },
            text = {
                Text(
                    "Swarm will verify this phone's hardware, then " +
                        "download approximately 2.6 GB over HTTPS. " +
                        "The model remains in private app storage."
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showLevel2DownloadDialog = false
                        level2InstallInProgress = true
                        level2StatusText =
                            "Checking Level 2 hardware eligibility..."

                        level2UiScope.launch {
                            try {
                                val recommendation =
                                    level2Installer.requestRecommendation(
                                        confirmDownload = true
                                    )

                                check(recommendation.qualified) {
                                    recommendation.downloadBlockedReason
                                        ?: "This phone does not meet Level 2 requirements."
                                }

                                val descriptor =
                                    recommendation.model
                                        ?: error(
                                            "The backend did not return a Level 2 model."
                                        )

                                check(
                                    recommendation.shouldDownload &&
                                        descriptor.downloadReady
                                ) {
                                    recommendation.downloadBlockedReason
                                        ?: "The Level 2 download is not available."
                                }

                                var lastProgressPercent = -1

                                level2Installer.downloadAndInstall(
                                    descriptor
                                ) { progress ->
                                    val message = when (progress.stage) {
                                        AndroidLevel2InstallStage.CHECKING ->
                                            "Checking the existing model..."

                                        AndroidLevel2InstallStage.DOWNLOADING ->
                                            "Downloading Gemma: ${progress.percent}%"

                                        AndroidLevel2InstallStage.VERIFYING ->
                                            "Verifying model integrity..."

                                        AndroidLevel2InstallStage.INSTALLED ->
                                            "Model downloaded and verified."
                                    }

                                    if (
                                        progress.percent !=
                                            lastProgressPercent ||
                                        progress.stage !=
                                            AndroidLevel2InstallStage.DOWNLOADING
                                    ) {
                                        lastProgressPercent =
                                            progress.percent

                                        level2UiScope.launch {
                                            level2StatusText = message
                                        }
                                    }
                                }

                                allowNeuralTasks = true

                                nodeSettings.edit()
                                    .putBoolean(
                                        "allow_neural",
                                        true
                                    )
                                    .apply()

                                level2StatusText =
                                    "Model installed and verified. " +
                                        "Runtime self-test is next."

                                Toast.makeText(
                                    context,
                                    "Level 2 model installed successfully.",
                                    Toast.LENGTH_LONG
                                ).show()
                            } catch (error: Throwable) {
                                allowNeuralTasks = false

                                nodeSettings.edit()
                                    .putBoolean(
                                        "allow_neural",
                                        false
                                    )
                                    .apply()

                                level2StatusText =
                                    error.message
                                        ?: "Level 2 setup failed."

                                Toast.makeText(
                                    context,
                                    level2StatusText,
                                    Toast.LENGTH_LONG
                                ).show()
                            } finally {
                                level2InstallInProgress = false
                            }
                        }
                    }
                ) {
                    Text("Check and Download")
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        showLevel2DownloadDialog = false
                    }
                ) {
                    Text("Cancel")
                }
            }
        )
    }

    val currentAppVersion = BuildConfig.VERSION_NAME
    var androidReleaseInfo by remember { mutableStateOf<AndroidReleaseInfo?>(null) }
    var androidReleaseLoading by remember { mutableStateOf(true) }
    var androidReleaseError by remember {
        mutableStateOf<String?>(null)
    }

    // SWARM_ANDROID_AUTO_UPDATE_V1
    var androidUpdatePreparing by remember {
        mutableStateOf(false)
    }

    var preparedAndroidUpdate by remember {
        mutableStateOf<AndroidAutoUpdater.PreparedUpdate?>(null)
    }

    var androidAutoUpdateStatus by remember {
        mutableStateOf<String?>(null)
    }

    val unknownSourcesLauncher =
        androidx.activity.compose.rememberLauncherForActivityResult(
            contract =
                androidx.activity.result.contract
                    .ActivityResultContracts
                    .StartActivityForResult()
        ) {
            val prepared =
                preparedAndroidUpdate

            when {
                prepared == null -> {
                    androidAutoUpdateStatus =
                        "Verified update is no longer available."
                }

                !AndroidAutoUpdater
                    .canRequestPackageInstalls(context) -> {
                    androidAutoUpdateStatus =
                        "Install permission was not granted."
                }

                else -> {
                    runCatching {
                        AndroidAutoUpdater
                            .launchInstaller(
                                context,
                                prepared.apkFile
                            )
                    }.onFailure { error ->
                        androidAutoUpdateStatus =
                            error.message
                                ?: "Unable to open Android installer."
                    }
                }
            }
        }

    val requestVerifiedAndroidInstall:
        (AndroidAutoUpdater.PreparedUpdate) -> Unit = { prepared ->

        if (
            AndroidAutoUpdater
                .canRequestPackageInstalls(context)
        ) {
            runCatching {
                AndroidAutoUpdater
                    .launchInstaller(
                        context,
                        prepared.apkFile
                    )
            }.onFailure { error ->
                androidAutoUpdateStatus =
                    error.message
                        ?: "Unable to open Android installer."
            }
        } else {
            androidAutoUpdateStatus =
                "Allow Swarm to install app updates."

            runCatching {
                unknownSourcesLauncher.launch(
                    AndroidAutoUpdater
                        .unknownSourcesSettingsIntent(
                            context
                        )
                )
            }.onFailure { error ->
                androidAutoUpdateStatus =
                    error.message
                        ?: "Unable to open install-permission settings."
            }
        }
    }

    val updateRequired =
        androidReleaseInfo != null &&
        androidReleaseInfo
            ?.minimumVersion
            .orEmpty()
            .isNotBlank() &&
        compareAppVersions(
            currentAppVersion,
            androidReleaseInfo?.minimumVersion
        ) < 0

    val updateAvailable =
        androidReleaseInfo != null &&
        androidReleaseInfo
            ?.version
            .orEmpty()
            .isNotBlank() &&
        compareAppVersions(
            currentAppVersion,
            androidReleaseInfo?.version
        ) < 0

    LaunchedEffect(Unit) {
        androidReleaseLoading = true
        androidReleaseError = null
        androidAutoUpdateStatus = null

        try {
            val release =
                fetchAndroidReleaseInfo()

            androidReleaseInfo =
                release

            val newerRelease =
                release.version.isNotBlank() &&
                compareAppVersions(
                    currentAppVersion,
                    release.version
                ) < 0

            if (newerRelease) {
                androidUpdatePreparing = true

                androidAutoUpdateStatus =
                    "Downloading and verifying v${release.version}..."

                Log.i(
                    "EdgeSwarmUpdate",
                    "SWARM_ANDROID_AUTO_UPDATE_V1 " +
                        "current=$currentAppVersion " +
                        "target=${release.version} " +
                        "action=download"
                )

                try {
                    val prepared =
                        AndroidAutoUpdater
                            .downloadAndVerify(
                                context =
                                    context.applicationContext,
                                expectedVersion =
                                    release.version,
                                downloadUrl =
                                    release.downloadUrl,
                                expectedSha256 =
                                    release.sha256,
                                expectedPackageName =
                                    release.packageName
                            )

                    preparedAndroidUpdate =
                        prepared

                    androidAutoUpdateStatus =
                        "Verified v${prepared.versionName}. Ready to install."

                    Log.i(
                        "EdgeSwarmUpdate",
                        "SWARM_ANDROID_AUTO_UPDATE_V1 " +
                            "target=${prepared.versionName} " +
                            "versionCode=${prepared.versionCode} " +
                            "sha256=${prepared.sha256} " +
                            "action=verified"
                    )

                    requestVerifiedAndroidInstall(
                        prepared
                    )
                } catch (error: Throwable) {
                    androidAutoUpdateStatus =
                        error.message
                            ?: "Automatic update preparation failed."

                    Log.e(
                        "EdgeSwarmUpdate",
                        "SWARM_ANDROID_AUTO_UPDATE_V1 " +
                            "action=failed " +
                            "reason=${error.message}",
                        error
                    )
                } finally {
                    androidUpdatePreparing = false
                }
            }
        } catch (error: Exception) {
            androidReleaseError =
                error.message
                    ?: "Unable to check latest Android version."
        } finally {
            androidReleaseLoading = false
        }
    }

    // SWARM_PROVIDER_UI_V1
    val nodeScreenScrollState =
        rememberScrollState()

    var deviceSnapshot by remember {
        mutableStateOf(
            readSwarmDeviceSnapshot(
                context
            )
        )
    }

    var fcmReady by remember {
        mutableStateOf(
            !SwarmFcmState
                .cachedToken(context)
                .isNullOrBlank()
        )
    }

    val fcmPrefs =
        remember(context) {
            context.getSharedPreferences(
                SwarmFcmState.PREFS,
                android.content.Context.MODE_PRIVATE
            )
        }

    var lastTaskId by remember {
        mutableStateOf(
            fcmPrefs.getString(
                SwarmFcmState.KEY_LAST_TASK_ID,
                null
            )
        )
    }

    var lastWakeAtMs by remember {
        mutableLongStateOf(
            fcmPrefs.getLong(
                SwarmFcmState.KEY_LAST_MESSAGE_AT,
                0L
            )
        )
    }

    LaunchedEffect(
        nodeEnabled,
        isRunning
    ) {
        while (true) {
            deviceSnapshot =
                readSwarmDeviceSnapshot(
                    context
                )

            fcmReady =
                !SwarmFcmState
                    .cachedToken(context)
                    .isNullOrBlank()

            lastTaskId =
                fcmPrefs.getString(
                    SwarmFcmState.KEY_LAST_TASK_ID,
                    null
                )

            lastWakeAtMs =
                fcmPrefs.getLong(
                    SwarmFcmState.KEY_LAST_MESSAGE_AT,
                    0L
                )

            kotlinx.coroutines.delay(
                15_000L
            )
        }
    }

    val swarmPurple =
        Color(0xFF6754E8)

    val swarmBackground =
        Color(0xFFF7F7FA)

    val swarmText =
        Color(0xFF202027)

    val swarmMuted =
        Color(0xFF6F6F7A)

    val nodeStatus =
        when {
            !nodeEnabled ->
                "Inactive"

            isRunning ->
                "Activated - Awake"

            else ->
                "Activated - Sleeping"
        }

    val nodeDescription =
        when {
            !nodeEnabled ->
                "Activate this phone to make its verified capacity available to Swarm."

            isRunning ->
                "The provider is awake and checking or handling eligible work."

            else ->
                "Ready for work. Swarm wakes this phone automatically when an eligible task arrives."
        }

    val neuralSummary =
        when {
            serviceLevel2StatusText != null ->
                serviceLevel2StatusText.orEmpty()

            allowNeuralTasks ->
                "Neural enabled - certification restores on wake and the model loads only for neural work."

            else ->
                "Optional neural capacity is disabled."
        }

    val certifiedCapacity =
        if (allowNeuralTasks) {
            "Level 1 + Neural"
        } else {
            "Level 1"
        }

    val activitySummary =
        when {
            isRunning ->
                "Provider awake"

            nodeEnabled && fcmReady ->
                "Sleeping - FCM ready"

            nodeEnabled ->
                "Activated - FCM token pending"

            else ->
                "Provider inactive"
        }

    val toggleProvider: () -> Unit = {
        val serviceIntent =
            Intent(
                context,
                SentinelService::class.java
            )

        if (!nodeEnabled) {
            val accessToken =
                supabase.auth
                    .currentSessionOrNull()
                    ?.accessToken

            val walletReady =
                WalletVault.hasPrivateKey(
                    context,
                    userEmail
                )

            when {
                accessToken.isNullOrBlank() ->
                    Toast.makeText(
                        context,
                        "Your session has expired. Sign in again before activating the provider.",
                        Toast.LENGTH_LONG
                    ).show()

                !walletReady ->
                    Toast.makeText(
                        context,
                        "The provider wallet is not ready yet. Reopen Swarm and let wallet sync finish.",
                        Toast.LENGTH_LONG
                    ).show()

                updateRequired ->
                    Toast.makeText(
                        context,
                        "Install the required Android update before activating the provider.",
                        Toast.LENGTH_LONG
                    ).show()

                else -> {
                    serviceIntent.putExtra(
                        "USER_EMAIL",
                        userEmail
                    )

                    serviceIntent.putExtra(
                        "ACCESS_TOKEN",
                        accessToken
                    )

                    serviceIntent.putExtra(
                        "ALLOW_COMPUTE",
                        allowCompute
                    )

                    serviceIntent.putExtra(
                        "ALLOW_SCRAPING",
                        allowScraping
                    )

                    serviceIntent.putExtra(
                        "ALLOW_BATTERY_TASKS",
                        allowBatteryTasks
                    )

                    serviceIntent.putExtra(
                        "ALLOW_NEURAL",
                        allowNeuralTasks
                    )

                    nodeSettings.edit()
                        .putBoolean(
                            "node_enabled",
                            true
                        )
                        .apply()

                    nodeEnabled = true

                    runCatching {
                        ContextCompat
                            .startForegroundService(
                                context,
                                serviceIntent
                            )
                    }.onFailure { error ->
                        nodeSettings.edit()
                            .putBoolean(
                                "node_enabled",
                                false
                            )
                            .apply()

                        nodeEnabled = false

                        Toast.makeText(
                            context,
                            "Provider activation failed: ${error.message}",
                            Toast.LENGTH_LONG
                        ).show()
                    }
                }
            }
        } else {
            nodeSettings.edit()
                .putBoolean(
                    "node_enabled",
                    false
                )
                .apply()

            nodeEnabled = false

            runCatching {
                serviceIntent.action =
                    SentinelService.ACTION_STOP_NODE

                context.startService(
                    serviceIntent
                )
            }.onFailure { error ->
                Log.w(
                    "EdgeSwarm",
                    "Provider deactivation dispatch failed: ${error.message}"
                )

                context.stopService(
                    serviceIntent
                )
            }
        }
    }

    Surface(
        modifier =
            Modifier.fillMaxSize(),
        color =
            swarmBackground
    ) {
        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .verticalScroll(
                        nodeScreenScrollState
                    )
                    .padding(
                        horizontal = 20.dp,
                        vertical = 12.dp
                    ),
            verticalArrangement =
                Arrangement.spacedBy(
                    16.dp
                )
        ) {
            Row(
                modifier =
                    Modifier.fillMaxWidth(),
                horizontalArrangement =
                    Arrangement.SpaceBetween,
                verticalAlignment =
                    Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        text = "Swarm",
                        color = swarmText,
                        fontSize = 26.sp,
                        fontWeight =
                            FontWeight.ExtraBold
                    )

                    Text(
                        text =
                            "Distributed AI Provider",
                        color =
                            swarmMuted,
                        fontSize = 13.sp
                    )
                }

                Text(
                    text =
                        "v$currentAppVersion",
                    color =
                        swarmMuted,
                    fontSize = 12.sp,
                    fontWeight =
                        FontWeight.SemiBold
                )
            }

            Card(
                modifier =
                    Modifier.fillMaxWidth(),
                shape =
                    RoundedCornerShape(
                        22.dp
                    ),
                colors =
                    CardDefaults.cardColors(
                        containerColor =
                            Color.White
                    )
            ) {
                Column(
                    modifier =
                        Modifier.padding(
                            20.dp
                        ),
                    verticalArrangement =
                        Arrangement.spacedBy(
                            14.dp
                        )
                ) {
                    Row(
                        modifier =
                            Modifier
                                .fillMaxWidth(),
                        horizontalArrangement =
                            Arrangement.SpaceBetween,
                        verticalAlignment =
                            Alignment.CenterVertically
                    ) {
                        Column(
                            modifier =
                                Modifier.weight(
                                    1f
                                )
                        ) {
                            Text(
                                text =
                                    "Provider Node",
                                color =
                                    swarmText,
                                fontSize =
                                    20.sp,
                                fontWeight =
                                    FontWeight.Bold
                            )

                            Spacer(
                                modifier =
                                    Modifier.height(
                                        3.dp
                                    )
                            )

                            Text(
                                text =
                                    nodeDescription,
                                color =
                                    swarmMuted,
                                fontSize =
                                    13.sp
                            )
                        }

                        Spacer(
                            modifier =
                                Modifier.width(
                                    12.dp
                                )
                        )

                        SwarmStatusPill(
                            text =
                                nodeStatus,
                            enabled =
                                nodeEnabled,
                            awake =
                                isRunning
                        )
                    }

                    Button(
                        onClick =
                            toggleProvider,
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .height(
                                    54.dp
                                ),
                        shape =
                            RoundedCornerShape(
                                16.dp
                            ),
                        colors =
                            ButtonDefaults
                                .buttonColors(
                                    containerColor =
                                        swarmPurple
                                )
                    ) {
                        Text(
                            text =
                                if (nodeEnabled) {
                                    "Deactivate Provider"
                                } else {
                                    "Activate Provider"
                                },
                            fontWeight =
                                FontWeight.Bold,
                            color =
                                Color.White
                        )
                    }

                    if (
                        nodeEnabled &&
                        !isRunning
                    ) {
                        Text(
                            text =
                                "No continuous polling or Swarm wake lock while sleeping.",
                            color =
                                swarmMuted,
                            fontSize =
                                11.sp,
                            textAlign =
                                TextAlign.Center,
                            modifier =
                                Modifier.fillMaxWidth()
                        )
                    }
                }
            }

            Row(
                modifier =
                    Modifier.fillMaxWidth(),
                horizontalArrangement =
                    Arrangement.spacedBy(
                        12.dp
                    )
            ) {
                SwarmMetricCard(
                    modifier =
                        Modifier.weight(
                            1f
                        ),
                    title =
                        "Earnings",
                    value =
                        "USD $earningsUsd",
                    detail =
                        "Provider rewards"
                )

                SwarmMetricCard(
                    modifier =
                        Modifier.weight(
                            1f
                        ),
                    title =
                        "Certified Capacity",
                    value =
                        certifiedCapacity,
                    detail =
                        if (allowNeuralTasks) {
                            "Deterministic + neural"
                        } else {
                            "Deterministic"
                        }
                )
            }

            SwarmSectionCard(
                title =
                    "Device Performance",
                subtitle =
                    "Current phone conditions"
            ) {
                SwarmInfoRow(
                    label =
                        "Runtime",
                    value =
                        if (isRunning) {
                            "Awake"
                        } else if (nodeEnabled) {
                            "Sleeping"
                        } else {
                            "Inactive"
                        }
                )

                SwarmInfoRow(
                    label =
                        "Battery",
                    value =
                        deviceSnapshot
                            .batteryPercent
                            ?.let {
                                "$it%"
                            }
                            ?: "Unknown"
                )

                SwarmInfoRow(
                    label =
                        "Power",
                    value =
                        when (
                            deviceSnapshot
                                .charging
                        ) {
                            true ->
                                "Charging"

                            false ->
                                "Battery"

                            null ->
                                "Unknown"
                        }
                )

                SwarmInfoRow(
                    label =
                        "Temperature",
                    value =
                        deviceSnapshot
                            .temperatureC
                            ?.let {
                                String.format(
                                    Locale.US,
                                    "%.1f C",
                                    it
                                )
                            }
                            ?: "Unknown"
                )

                SwarmInfoRow(
                    label =
                        "Thermal state",
                    value =
                        deviceSnapshot
                            .thermalLabel
                )

                SwarmInfoRow(
                    label =
                        "Battery tasks",
                    value =
                        if (
                            allowBatteryTasks
                        ) {
                            "Allowed"
                        } else {
                            "Charging only"
                        }
                )
            }

            SwarmSectionCard(
                title =
                    "Activity",
                subtitle =
                    activitySummary
            ) {
                SwarmInfoRow(
                    label =
                        "FCM wake channel",
                    value =
                        if (fcmReady) {
                            "Ready"
                        } else {
                            "Pending"
                        }
                )

                SwarmInfoRow(
                    label =
                        "Last wake",
                    value =
                        swarmRelativeTime(
                            lastWakeAtMs
                        )
                )

                SwarmInfoRow(
                    label =
                        "Last task signal",
                    value =
                        lastTaskId
                            ?.takeIf {
                                it.isNotBlank()
                            }
                            ?.let {
                                "Task $it"
                            }
                            ?: "None"
                )
            }

            SwarmSectionCard(
                title =
                    "Workload Routing",
                subtitle =
                    if (nodeEnabled) {
                        "Deactivate the provider to change routing preferences."
                    } else {
                        "Choose which work this phone may accept."
                    }
            ) {
                SwarmRoutingSwitchRow(
                    title =
                        "Exact Extraction",
                    subtitle =
                        "Deterministic extraction and validation.",
                    checked =
                        true,
                    enabled =
                        false,
                    onCheckedChange = {}
                )

                SwarmRoutingSwitchRow(
                    title =
                        "Distributed Compute",
                    subtitle =
                        "Deterministic matrix and compute tasks.",
                    checked =
                        allowCompute,
                    enabled =
                        !nodeEnabled,
                    onCheckedChange = {
                        allowCompute = it

                        nodeSettings.edit()
                            .putBoolean(
                                "allow_compute",
                                it
                            )
                            .apply()
                    }
                )

                SwarmRoutingSwitchRow(
                    title =
                        "Data Scraper",
                    subtitle =
                        "Structured web and data extraction tasks.",
                    checked =
                        allowScraping,
                    enabled =
                        !nodeEnabled,
                    onCheckedChange = {
                        allowScraping = it

                        nodeSettings.edit()
                            .putBoolean(
                                "allow_scraping",
                                it
                            )
                            .apply()
                    }
                )

                SwarmRoutingSwitchRow(
                    title =
                        "Accept Tasks While Not Charging",
                    subtitle =
                        "Allow deterministic work while this phone is on battery.",
                    checked =
                        allowBatteryTasks,
                    enabled =
                        !nodeEnabled,
                    onCheckedChange = {
                        allowBatteryTasks = it

                        nodeSettings.edit()
                            .putBoolean(
                                "allow_battery_tasks",
                                it
                            )
                            .apply()
                    }
                )
            }

            SwarmSectionCard(
                title =
                    "Neural Capacity",
                subtitle =
                    neuralSummary
            ) {
                SwarmRoutingSwitchRow(
                    title =
                        "Level 2 Neural Inference",
                    subtitle =
                        "Gemma 4 E2B with verified Android acceleration.",
                    checked =
                        allowNeuralTasks,
                    enabled =
                        !nodeEnabled &&
                            !level2InstallInProgress,
                    onCheckedChange = {
                        enabled ->

                        if (enabled) {
                            showLevel2DownloadDialog =
                                true
                        } else {
                            allowNeuralTasks =
                                false

                            nodeSettings.edit()
                                .putBoolean(
                                    "allow_neural",
                                    false
                                )
                                .apply()

                            level2StatusText =
                                "Level 2 disabled. The installed model remains available for later use."
                        }
                    }
                )

                Text(
                    text =
                        "Neural models stay unloaded while idle and are opened only for eligible neural work.",
                    color =
                        swarmMuted,
                    fontSize =
                        11.sp
                )
            }

            SwarmSectionCard(
                title =
                    "Diagnostics & Updates",
                subtitle =
                    "Provider identity and release status"
            ) {
                SwarmInfoRow(
                    label =
                        "Account",
                    value =
                        userEmail
                )

                SwarmInfoRow(
                    label =
                        "App version",
                    value =
                        "v$currentAppVersion"
                )

                SwarmInfoRow(
                    label =
                        "Update",
                    value =
                        when {
                            androidUpdatePreparing ->
                                "Downloading"

                            preparedAndroidUpdate != null ->
                                "Verified"

                            androidReleaseLoading ->
                                "Checking"

                            androidReleaseError != null ->
                                "Check unavailable"

                            updateRequired ->
                                "Required"

                            updateAvailable ->
                                "Available"

                            else ->
                                "Up to date"
                        }
                )

                val release =
                    androidReleaseInfo

                if (
                    !release
                        ?.downloadUrl
                        .isNullOrBlank()
                ) {
                    TextButton(
                        onClick = {
                            val url =
                                release
                                    ?.downloadUrl

                            val uri =
                                url?.let(
                                    android.net.Uri::parse
                                )

                            val packageMatches =
                                release
                                    ?.packageName
                                    .isNullOrBlank() ||
                                    release
                                        ?.packageName ==
                                    BuildConfig
                                        .APPLICATION_ID

                            when {
                                !packageMatches ->
                                    Toast.makeText(
                                        context,
                                        "Update metadata does not match this Android package.",
                                        Toast.LENGTH_LONG
                                    ).show()

                                uri?.scheme !=
                                    "https" ->
                                    Toast.makeText(
                                        context,
                                        "The update URL is not a secure HTTPS address.",
                                        Toast.LENGTH_LONG
                                    ).show()

                                else ->
                                    context.startActivity(
                                        Intent(
                                            Intent.ACTION_VIEW,
                                            uri
                                        )
                                    )
                            }
                        }
                    ) {
                        Text(
                            text =
                                "Open update",
                            color =
                                swarmPurple,
                            fontWeight =
                                FontWeight.SemiBold
                        )
                    }
                }
            }

            TextButton(
                onClick =
                    onSignOut,
                enabled =
                    !nodeEnabled,
                modifier =
                    Modifier
                        .align(
                            Alignment.CenterHorizontally
                        )
            ) {
                Text(
                    text =
                        if (nodeEnabled) {
                            "Deactivate provider before signing out"
                        } else {
                            "Sign out"
                        },
                    color =
                        swarmMuted,
                    fontSize =
                        12.sp
                )
            }

            Spacer(
                modifier =
                    Modifier.height(
                        28.dp
                    )
            )
        }
    }
}

private data class SwarmDeviceSnapshot(
    val batteryPercent: Int?,
    val charging: Boolean?,
    val temperatureC: Float?,
    val thermalLabel: String
)

private fun readSwarmDeviceSnapshot(
    context: android.content.Context
): SwarmDeviceSnapshot {
    val batteryIntent =
        context.registerReceiver(
            null,
            android.content.IntentFilter(
                android.content.Intent
                    .ACTION_BATTERY_CHANGED
            )
        )

    val level =
        batteryIntent?.getIntExtra(
            android.os.BatteryManager
                .EXTRA_LEVEL,
            -1
        ) ?: -1

    val scale =
        batteryIntent?.getIntExtra(
            android.os.BatteryManager
                .EXTRA_SCALE,
            -1
        ) ?: -1

    val percent =
        if (
            level >= 0 &&
            scale > 0
        ) {
            (
                level.toFloat() /
                    scale.toFloat() *
                    100f
                ).toInt()
        } else {
            null
        }

    val batteryStatus =
        batteryIntent?.getIntExtra(
            android.os.BatteryManager
                .EXTRA_STATUS,
            -1
        ) ?: -1

    val charging =
        if (batteryStatus < 0) {
            null
        } else {
            batteryStatus ==
                android.os.BatteryManager
                    .BATTERY_STATUS_CHARGING ||
                batteryStatus ==
                android.os.BatteryManager
                    .BATTERY_STATUS_FULL
        }

    val tempTenths =
        batteryIntent?.getIntExtra(
            android.os.BatteryManager
                .EXTRA_TEMPERATURE,
            Int.MIN_VALUE
        ) ?: Int.MIN_VALUE

    val temperatureC =
        if (
            tempTenths ==
                Int.MIN_VALUE ||
            tempTenths <= 0
        ) {
            null
        } else {
            tempTenths / 10f
        }

    val thermalLabel =
        if (
            Build.VERSION.SDK_INT <
            Build.VERSION_CODES.Q
        ) {
            "Unavailable"
        } else {
            val powerManager =
                context.getSystemService(
                    android.content.Context
                        .POWER_SERVICE
                ) as android.os.PowerManager

            when (
                powerManager
                    .currentThermalStatus
            ) {
                android.os.PowerManager
                    .THERMAL_STATUS_NONE ->
                    "Normal"

                android.os.PowerManager
                    .THERMAL_STATUS_LIGHT ->
                    "Light"

                android.os.PowerManager
                    .THERMAL_STATUS_MODERATE ->
                    "Moderate"

                android.os.PowerManager
                    .THERMAL_STATUS_SEVERE ->
                    "Severe"

                android.os.PowerManager
                    .THERMAL_STATUS_CRITICAL ->
                    "Critical"

                android.os.PowerManager
                    .THERMAL_STATUS_EMERGENCY ->
                    "Emergency"

                android.os.PowerManager
                    .THERMAL_STATUS_SHUTDOWN ->
                    "Shutdown"

                else ->
                    "Unknown"
            }
        }

    return SwarmDeviceSnapshot(
        batteryPercent =
            percent,
        charging =
            charging,
        temperatureC =
            temperatureC,
        thermalLabel =
            thermalLabel
    )
}

private fun swarmRelativeTime(
    timestampMs: Long
): String {
    if (timestampMs <= 0L) {
        return "None"
    }

    val ageMs =
        (
            System.currentTimeMillis() -
                timestampMs
            ).coerceAtLeast(
                0L
            )

    return when {
        ageMs < 60_000L ->
            "Just now"

        ageMs < 3_600_000L ->
            "${ageMs / 60_000L} min ago"

        ageMs < 86_400_000L ->
            "${ageMs / 3_600_000L} hr ago"

        else ->
            "${ageMs / 86_400_000L} d ago"
    }
}

@Composable
private fun SwarmStatusPill(
    text: String,
    enabled: Boolean,
    awake: Boolean
) {
    val background =
        when {
            !enabled ->
                Color(0xFFEDEDF1)

            awake ->
                Color(0xFFEDE9FF)

            else ->
                Color(0xFFE6F6EE)
        }

    val foreground =
        when {
            !enabled ->
                Color(0xFF6F6F7A)

            awake ->
                Color(0xFF6754E8)

            else ->
                Color(0xFF197A55)
        }

    Surface(
        color =
            background,
        shape =
            RoundedCornerShape(
                999.dp
            )
    ) {
        Text(
            text =
                text,
            modifier =
                Modifier.padding(
                    horizontal =
                        12.dp,
                    vertical =
                        7.dp
                ),
            color =
                foreground,
            fontSize =
                11.sp,
            fontWeight =
                FontWeight.Bold
        )
    }
}

@Composable
private fun SwarmMetricCard(
    modifier: Modifier,
    title: String,
    value: String,
    detail: String
) {
    Card(
        modifier =
            modifier,
        shape =
            RoundedCornerShape(
                18.dp
            ),
        colors =
            CardDefaults.cardColors(
                containerColor =
                    Color.White
            )
    ) {
        Column(
            modifier =
                Modifier.padding(
                    16.dp
                )
        ) {
            Text(
                text =
                    title,
                color =
                    Color(0xFF6F6F7A),
                fontSize =
                    11.sp,
                fontWeight =
                    FontWeight.SemiBold
            )

            Spacer(
                modifier =
                    Modifier.height(
                        7.dp
                    )
            )

            Text(
                text =
                    value,
                color =
                    Color(0xFF202027),
                fontSize =
                    18.sp,
                fontWeight =
                    FontWeight.Bold
            )

            Spacer(
                modifier =
                    Modifier.height(
                        3.dp
                    )
            )

            Text(
                text =
                    detail,
                color =
                    Color(0xFF8A8A94),
                fontSize =
                    10.sp
            )
        }
    }
}

@Composable
private fun SwarmSectionCard(
    title: String,
    subtitle: String,
    content:
        @Composable
        ColumnScope.() -> Unit
) {
    Card(
        modifier =
            Modifier.fillMaxWidth(),
        shape =
            RoundedCornerShape(
                18.dp
            ),
        colors =
            CardDefaults.cardColors(
                containerColor =
                    Color.White
            )
    ) {
        Column(
            modifier =
                Modifier.padding(
                    18.dp
                ),
            verticalArrangement =
                Arrangement.spacedBy(
                    12.dp
                )
        ) {
            Text(
                text =
                    title,
                color =
                    Color(0xFF202027),
                fontSize =
                    16.sp,
                fontWeight =
                    FontWeight.Bold
            )

            Text(
                text =
                    subtitle,
                color =
                    Color(0xFF777781),
                fontSize =
                    11.sp
            )

            HorizontalDivider(
                color =
                    Color(0xFFEDEDF1)
            )

            content()
        }
    }
}

@Composable
private fun SwarmInfoRow(
    label: String,
    value: String
) {
    Row(
        modifier =
            Modifier.fillMaxWidth(),
        horizontalArrangement =
            Arrangement.SpaceBetween,
        verticalAlignment =
            Alignment.CenterVertically
    ) {
        Text(
            text =
                label,
            color =
                Color(0xFF777781),
            fontSize =
                12.sp
        )

        Spacer(
            modifier =
                Modifier.width(
                    16.dp
                )
        )

        Text(
            text =
                value,
            color =
                Color(0xFF202027),
            fontSize =
                12.sp,
            fontWeight =
                FontWeight.SemiBold,
            textAlign =
                TextAlign.End
        )
    }
}

@Composable
private fun SwarmRoutingSwitchRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    enabled: Boolean,
    onCheckedChange:
        (Boolean) -> Unit
) {
    Row(
        modifier =
            Modifier.fillMaxWidth(),
        horizontalArrangement =
            Arrangement.SpaceBetween,
        verticalAlignment =
            Alignment.CenterVertically
    ) {
        Column(
            modifier =
                Modifier
                    .weight(
                        1f
                    )
                    .padding(
                        end =
                            12.dp
                    )
        ) {
            Text(
                text =
                    title,
                color =
                    Color(0xFF202027),
                fontSize =
                    13.sp,
                fontWeight =
                    FontWeight.SemiBold
            )

            Text(
                text =
                    subtitle,
                color =
                    Color(0xFF777781),
                fontSize =
                    10.sp
            )
        }

        Switch(
            checked =
                checked,
            onCheckedChange =
                onCheckedChange,
            enabled =
                enabled
        )
    }
}
@Composable
private fun RoutingSwitchRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    enabled: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                title,
                color = Color.White,
                fontWeight = FontWeight.Bold,
                fontSize = 14.sp
            )

            Text(
                subtitle,
                color = Color.Gray,
                fontSize = 11.sp
            )
        }

        Switch(
            checked = checked,
            enabled = enabled,
            onCheckedChange = onCheckedChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = Color(0xFF0B0E11),
                checkedTrackColor = Color(0xFF03DAC5),
                disabledCheckedThumbColor = Color(0xFF0B0E11),
                disabledCheckedTrackColor = Color(0xFF03DAC5)
            )
        )
    }
}

private fun encodeUrl(value: String): String {
    return URLEncoder.encode(value, "UTF-8")
}

private fun shortAddress(value: String): String {
    if (value.isBlank()) return "N/A"
    if (value.length <= 16) return value
    return value.take(8) + "..." + value.takeLast(6)
}

private fun buildProofUrl(value: String): String? {
    val clean = value.trim()

    if (clean.isBlank()) return null

    if (
        clean.startsWith("https://sepolia.basescan.org/", ignoreCase = true) ||
        clean.startsWith("https://basescan.org/", ignoreCase = true)
    ) {
        return clean
    }

    if (clean.startsWith("0x") && clean.length >= 10) {
        return "https://sepolia.basescan.org/tx/$clean"
    }

    return null
}
private fun JSONObject.optFirstString(vararg keys: String): String {
    for (key in keys) {
        if (has(key) && !isNull(key)) {
            val value = optString(key, "").trim()
            if (value.isNotBlank() && value.lowercase(Locale.US) != "null") {
                return value
            }
        }
    }
    return ""
}

private fun JSONObject.firstDoubleOrNull(vararg keys: String): Double? {
    for (key in keys) {
        if (has(key) && !isNull(key)) {
            val raw = opt(key)

            when (raw) {
                is Number -> return raw.toDouble()
                is String -> {
                    val cleaned = raw
                        .replace("SWM", "", ignoreCase = true)
                        .replace("SWARM", "", ignoreCase = true)
                        .replace(",", "")
                        .trim()

                    cleaned.toDoubleOrNull()?.let { return it }
                }
            }
        }
    }

    return null
}

private fun extractJsonArray(body: String): JSONArray {
    val trimmed = body.trim()

    if (trimmed.startsWith("[")) {
        return JSONArray(trimmed)
    }

    val json = JSONObject(trimmed)

    val keys = listOf(
        "rows",
        "ledger",
        "items",
        "proofs",
        "data",
        "results"
    )

    for (key in keys) {
        val arr = json.optJSONArray(key)
        if (arr != null) return arr
    }

    return JSONArray()
}










@Composable
private fun AndroidUpdateStatusCard(
    currentVersion: String,
    releaseInfo: AndroidReleaseInfo?,
    loading: Boolean,
    error: String?,
    onDownload: () -> Unit
) {
    val latestVersion = releaseInfo?.version.orEmpty()
    val minimumVersion = releaseInfo?.minimumVersion.orEmpty()

    val updateRequired = releaseInfo != null &&
        minimumVersion.isNotBlank() &&
        compareAppVersions(currentVersion, minimumVersion) < 0

    val updateAvailable = releaseInfo != null &&
        latestVersion.isNotBlank() &&
        compareAppVersions(currentVersion, latestVersion) < 0

    val statusText = when {
        loading -> "Checking latest version..."
        error != null -> "Update check unavailable"
        updateRequired -> "Update required"
        updateAvailable -> "Update available"
        else -> "Up to date"
    }

    val statusColor = when {
        updateRequired -> Color(0xFFFF6B6B)
        updateAvailable -> Color(0xFFFFB020)
        error != null -> Color(0xFFFFB020)
        loading -> Color.Gray
        else -> Color(0xFF00FFCC)
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF11161A)),
        shape = RoundedCornerShape(10.dp)
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Text(
                "ANDROID RELEASE CHECK",
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                color = Color.Gray
            )

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = "Current version: v$currentVersion",
                color = Color.White,
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium
            )

            Text(
                text = "Latest version: ${if (latestVersion.isBlank()) "Unknown" else "v$latestVersion"}",
                color = Color.Gray,
                fontSize = 12.sp,
                modifier = Modifier.padding(top = 3.dp)
            )

            Text(
                text = "Minimum required: ${if (minimumVersion.isBlank()) "Unknown" else "v$minimumVersion"}",
                color = Color.Gray,
                fontSize = 12.sp,
                modifier = Modifier.padding(top = 3.dp)
            )

            Text(
                text = "Status: $statusText",
                color = statusColor,
                fontSize = 12.sp,
                modifier = Modifier.padding(top = 6.dp),
                fontWeight = FontWeight.Bold
            )

            if (!error.isNullOrBlank()) {
                Text(
                    text = error,
                    color = Color(0xFFFFB020),
                    fontSize = 11.sp,
                    modifier = Modifier.padding(top = 6.dp)
                )
            }

            if (updateRequired || updateAvailable) {
                Spacer(modifier = Modifier.height(10.dp))

                Button(
                    onClick = onDownload,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(8.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF03DAC5))
                ) {
                    Text(
                        if (updateRequired) "DOWNLOAD REQUIRED UPDATE" else "DOWNLOAD LATEST APK",
                        color = Color.Black,
                        fontWeight = FontWeight.Bold
                    )
                }

                if (!releaseInfo?.sha256.isNullOrBlank()) {
                    Text(
                        text = "SHA256: ${releaseInfo?.sha256}",
                        color = Color.Gray,
                        fontSize = 9.sp,
                        modifier = Modifier.padding(top = 6.dp)
                    )
                }
            }
        }
    }
}

