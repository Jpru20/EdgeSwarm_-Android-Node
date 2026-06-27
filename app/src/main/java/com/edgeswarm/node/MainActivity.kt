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

private const val SWARM_REFERENCE_USD = 0.10
private const val API_BASE_URL = "https://api.edgeswarm.io"

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
    supabaseUrl = "https://xrmwmoqgukjztboemvgi.supabase.co",
    supabaseKey = "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJpc3MiOiJzdXBhYmFzZSIsInJlZiI6InhybXdtb3FndWtqenRib2VtdmdpIiwicm9sZSI6ImFub24iLCJpYXQiOjE3Nzk3MzgzNDcsImV4cCI6MjA5NTMxNDM0N30.3kP1uRFgRAgr2L2eh3Su36icRUHMEsfYIJc1RBV1jjM"
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

            var balance by remember { mutableStateOf(sharedPrefs.getString("balance", "0.00") ?: "0.00") }
            var usdValue by remember { mutableStateOf(sharedPrefs.getString("usd", "0.00") ?: "0.00") }
            var isSyncing by remember { mutableStateOf(false) }
            var selectedTab by remember { mutableIntStateOf(1) }

            LaunchedEffect(isLoggedIn, authenticatedUserEmail) {
                if (isLoggedIn && authenticatedUserEmail.isNotEmpty()) {
                    syncWalletKey(authenticatedUserEmail, sharedPrefs)

                    val cachedBalance = sharedPrefs.getString("balance", "0.00") ?: "0.00"
                    if (cachedBalance == "0.00") {
                        val synced = refreshBalanceForUser(authenticatedUserEmail, sharedPrefs)
                        balance = synced.first
                        usdValue = synced.second
                    }
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

                                syncWalletKey(verifiedEmail, sharedPrefs)

                                authenticatedUserEmail = verifiedEmail
                                isLoggedIn = true
                                sharedPrefs.edit().putString("auth_email", verifiedEmail).apply()

                                val synced = refreshBalanceForUser(verifiedEmail, sharedPrefs)
                                balance = synced.first
                                usdValue = synced.second

                                Toast.makeText(context, "Identity and wallet synced.", Toast.LENGTH_SHORT).show()
                            }
                        }
                    )
                } else {
                    Scaffold(
                        modifier = Modifier.fillMaxSize(),
                        bottomBar = {
                            NavigationBar(containerColor = Color(0xFF161A1E)) {
                                NavigationBarItem(
                                    selected = selectedTab == 0,
                                    onClick = { selectedTab = 0 },
                                    icon = { Icon(Icons.AutoMirrored.Filled.List, "Ledge", tint = Color.White) },
                                    label = { Text("Ledge") }
                                )
                                NavigationBarItem(
                                    selected = selectedTab == 1,
                                    onClick = { selectedTab = 1 },
                                    icon = { Icon(Icons.Default.AccountBox, "Token", tint = Color.White) },
                                    label = { Text("Token") }
                                )
                                NavigationBarItem(
                                    selected = selectedTab == 2,
                                    onClick = { selectedTab = 2 },
                                    icon = { Icon(Icons.Default.Settings, "Node", tint = Color.White) },
                                    label = { Text("Node") }
                                )
                            }
                        },
                        containerColor = Color(0xFF0B0E11)
                    ) { innerPadding ->
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(innerPadding)
                                .background(Color(0xFF0B0E11))
                        ) {
                            when (selectedTab) {
                                0 -> LedgeScreen(authenticatedUserEmail)
                                1 -> TokenDashboard(balance, usdValue, isSyncing) {
                                    isSyncing = true
                                    scope.launch {
                                        val synced = refreshBalanceForUser(authenticatedUserEmail, sharedPrefs)
                                        balance = synced.first
                                        usdValue = synced.second
                                        isSyncing = false
                                    }
                                }
                                2 -> SentinelScreen(authenticatedUserEmail)
                            }
                        }
                    }
                }
            }
        }
    }

    private suspend fun syncWalletKey(email: String, sharedPrefs: android.content.SharedPreferences) {
        val nodePrefs = getSharedPreferences("EdgeSwarmNode", MODE_PRIVATE)
        val keyName = "private_key_$email"
        val existingLocalKey = nodePrefs.getString(keyName, null)

        if (existingLocalKey != null) {
            Log.d("EdgeSwarm", "Wallet already synced locally for $email")
            return
        }

        try {
            Log.d("EdgeSwarm", "Searching cloud for existing wallet...")

            val result = supabase.postgrest["worker_wallets"]
                .select { filter { eq("email", email) } }
                .decodeSingleOrNull<WorkerWallet>()

            if (result != null) {
                nodePrefs.edit().putString(keyName, result.private_key).apply()
                Log.d("EdgeSwarm", "Wallet downloaded from cloud for $email")
            } else {
                val ecKeyPair = Keys.createEcKeyPair()
                val newPrivateKey = ecKeyPair.privateKey.toString(16)

                nodePrefs.edit().putString(keyName, newPrivateKey).apply()

                val newWallet = WorkerWallet(email, newPrivateKey)
                supabase.postgrest["worker_wallets"].insert(newWallet)

                Log.d("EdgeSwarm", "New wallet generated and backed up to cloud for $email")
            }
        } catch (e: Exception) {
            Log.e("EdgeSwarm", "Cloud wallet sync failed: ${e.message}", e)
        }
    }

    private suspend fun refreshBalanceForUser(
        email: String,
        sharedPrefs: android.content.SharedPreferences
    ): Pair<String, String> {
        val walletAddress = getWalletAddressForEmail(email)
        val balanceResult = fetchCloudServerBalance(walletAddress, email)

        val balanceText = String.format(Locale.US, "%.2f", balanceResult)
        val usdText = String.format(Locale.US, "%.2f", balanceResult * SWARM_REFERENCE_USD)

        sharedPrefs.edit()
            .putString("balance", balanceText)
            .putString("usd", usdText)
            .apply()

        return balanceText to usdText
    }

    private fun getWalletAddressForEmail(email: String): String {
        val nodePrefs = getSharedPreferences("EdgeSwarmNode", MODE_PRIVATE)
        val privateKeyHex = nodePrefs.getString("private_key_$email", null)

        if (privateKeyHex.isNullOrBlank()) {
            return email
        }

        return try {
            Credentials.create(privateKeyHex).address
        } catch (e: Exception) {
            Log.e("EdgeSwarm", "Could not derive wallet address for $email", e)
            email
        }
    }

    private suspend fun fetchCloudServerBalance(walletAddress: String, email: String): Double =
        withContext(Dispatchers.IO) {
            val client = OkHttpClient()
            val encodedEmail = encodeUrl(email)

            // Source of truth: proof ledger endpoint.
            // This returns balance, tokenSummary, and recent proofs from proof_ledger.
            val url = "$API_BASE_URL/v1/provider/ledge?providerEmail=$encodedEmail&limit=20&t=${System.currentTimeMillis()}"

            try {
                Log.d("EdgeSwarm", "Refreshing proof ledger balance from: $url")

                val request = Request.Builder()
                    .url(url)
                    .header("Cache-Control", "no-cache")
                    .build()

                client.newCall(request).execute().use { response ->
                    val body = response.body?.string() ?: "{}"

                    if (!response.isSuccessful) {
                        Log.w("EdgeSwarm", "Proof ledger balance failed: HTTP ${response.code} - $body")
                        return@withContext 0.0
                    }

                    val json = JSONObject(body)

                    val balance = json.optDouble("balance", Double.NaN)
                    if (!balance.isNaN()) {
                        Log.d("EdgeSwarm", "Proof ledger balance synced: $balance SWM")
                        return@withContext balance
                    }

                    val tokenSummary = json.optJSONObject("tokenSummary")
                    val totalEarned = tokenSummary?.optDouble("totalEarnedSwarm", Double.NaN) ?: Double.NaN
                    if (!totalEarned.isNaN()) {
                        Log.d("EdgeSwarm", "Proof ledger tokenSummary synced: $totalEarned SWM")
                        return@withContext totalEarned
                    }

                    Log.w("EdgeSwarm", "Proof ledger balance response missing balance fields: $body")
                    return@withContext 0.0
                }
            } catch (e: Exception) {
                Log.w("EdgeSwarm", "Proof ledger balance sync failed", e)
                return@withContext 0.0
            }
        }

    private fun checkRequiredPermissions() {
        val permissions = mutableListOf(Manifest.permission.CAMERA)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.POST_NOTIFICATIONS)
        }

        requestPermissionLauncher.launch(permissions.toTypedArray())
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
                    "SIGN IN TO SWARM"
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
            "RECENT SWARM ACTIVITY",
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
                val request = Request.Builder().url(url).build()

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
                            "score",
                            "tokenAmount",
                            "token_amount",
                            "swm",
                            "swarm"
                        )

                        val scoreText = if (reward != null) {
                            "${String.format(Locale.US, "%.2f", reward)} SWM"
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
    balance: String,
    usd: String,
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
            "SWARM SYSTEM BALANCE",
            color = Color.Gray,
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold
        )

        Text(
            text = if (isSyncing) "SYNCING..." else "$balance SWM",
            fontSize = 48.sp,
            fontWeight = FontWeight.Black,
            color = Color.White,
            textAlign = TextAlign.Center
        )

        Text(
            text = "Approx. $usd USD",
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

@Composable
fun SentinelScreen(userEmail: String) {
    val context = LocalContext.current
    var isRunning by remember { mutableStateOf(SentinelService.isServiceRunning) }

    var allowCompute by remember { mutableStateOf(true) }
    var allowScraping by remember { mutableStateOf(true) }
    var allowBatteryTasks by remember { mutableStateOf(true) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            "EDGE SWARM NODE",
            color = Color(0xFF03DAC5),
            fontSize = 24.sp,
            fontWeight = FontWeight.Bold
        )

        Text(
            text = "APP VERSION: v1.5.8",
            color = Color(0xFF00FFCC),
            fontSize = 11.sp,
            modifier = Modifier.padding(top = 4.dp),
            fontWeight = FontWeight.Bold
        )

        Text(
            text = "CONNECTED AS: $userEmail",
            color = Color.Gray,
            fontSize = 11.sp,
            modifier = Modifier.padding(top = 4.dp),
            textAlign = TextAlign.Center
        )

        Text(
            text = "STATUS: ${if (isRunning) "ACTIVE IN BACKGROUND" else "STANDBY"}",
            color = Color.Gray,
            fontSize = 12.sp,
            modifier = Modifier.padding(top = 4.dp)
        )

        Spacer(modifier = Modifier.height(32.dp))

        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = Color(0xFF161A1E)),
            shape = RoundedCornerShape(12.dp)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    "WORKLOAD ROUTING",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.Gray
                )

                Spacer(modifier = Modifier.height(16.dp))

                RoutingSwitchRow(
                    title = "Distributed Compute",
                    subtitle = "Process deterministic matrix and compute tasks.",
                    checked = allowCompute,
                    enabled = !isRunning,
                    onCheckedChange = { allowCompute = it }
                )

                Spacer(modifier = Modifier.height(12.dp))

                RoutingSwitchRow(
                    title = "Data Scraper",
                    subtitle = "Handle structured web and data extraction tasks.",
                    checked = allowScraping,
                    enabled = !isRunning,
                    onCheckedChange = { allowScraping = it }
                )

                Spacer(modifier = Modifier.height(12.dp))

                RoutingSwitchRow(
                    title = "Exact Extraction",
                    subtitle = "Run deterministic extraction and validation jobs.",
                    checked = true,
                    enabled = false,
                    onCheckedChange = {}
                )

                Spacer(modifier = Modifier.height(12.dp))

                RoutingSwitchRow(
                    title = "Accept Tasks While Not Charging",
                    subtitle = "Allow Level 1 deterministic tasks while the phone is on battery.",
                    checked = allowBatteryTasks,
                    enabled = !isRunning,
                    onCheckedChange = { allowBatteryTasks = it }
                )
            }
        }

        Spacer(modifier = Modifier.height(32.dp))

        Button(
            onClick = {
                val serviceIntent = Intent(context, SentinelService::class.java)

                if (!isRunning) {
                    serviceIntent.putExtra("USER_EMAIL", userEmail)
                    serviceIntent.putExtra("ALLOW_COMPUTE", allowCompute)
                    serviceIntent.putExtra("ALLOW_SCRAPING", allowScraping)
                    serviceIntent.putExtra("ALLOW_SLM", false)
                    serviceIntent.putExtra("ALLOW_BATTERY_TASKS", allowBatteryTasks)

                    ContextCompat.startForegroundService(context, serviceIntent)
                    isRunning = true
                } else {
                    context.stopService(serviceIntent)
                    isRunning = false
                }
            },
            modifier = Modifier
                .fillMaxWidth()
                .height(80.dp),
            shape = RoundedCornerShape(16.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = if (isRunning) Color(0xFFE53935) else Color(0xFF1E2329)
            )
        ) {
            Text(
                text = if (isRunning) "STOP HEADLESS NODE" else "ACTIVATE NODE",
                fontWeight = FontWeight.ExtraBold
            )
        }
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








