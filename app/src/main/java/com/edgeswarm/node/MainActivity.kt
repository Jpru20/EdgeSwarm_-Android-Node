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
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
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
import org.json.JSONObject
import org.web3j.protocol.Web3j
import org.web3j.protocol.http.HttpService
import org.web3j.protocol.core.DefaultBlockParameterName
import org.web3j.protocol.core.DefaultBlockParameterNumber
import org.web3j.protocol.core.methods.request.EthFilter
import org.web3j.protocol.core.methods.response.Log as Web3jLog
import org.web3j.crypto.Credentials
import org.web3j.crypto.Keys
import java.math.BigInteger
import java.util.Locale

@Serializable
data class WorkerWallet(
    val email: String,
    val private_key: String
)

data class LedgeItem(
    val taskId: String,
    val worker: String,
    val score: String,
    val txHash: String
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
        Log.d("EdgeSwarm", "System Security Clearances Updated: $permissions")
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
                }
            }

            if (!isLoggedIn) {
                LoginPortalScreen(
                    onAuthSuccess = { verifiedEmail ->
                        scope.launch {
                            Toast.makeText(context, "Syncing Secure Wallet...", Toast.LENGTH_SHORT).show()
                            syncWalletKey(verifiedEmail, sharedPrefs)
                            authenticatedUserEmail = verifiedEmail
                            isLoggedIn = true
                            sharedPrefs.edit().putString("auth_email", verifiedEmail).apply()
                            Toast.makeText(context, "Identity & Wallet Synced!", Toast.LENGTH_SHORT).show()
                        }
                    }
                )
            } else {
                Scaffold(
                    bottomBar = {
                        NavigationBar(containerColor = Color(0xFF161A1E)) {
                            NavigationBarItem(selected = selectedTab == 0, onClick = { selectedTab = 0 }, icon = { Icon(Icons.AutoMirrored.Filled.List, "Ledge", tint = Color.White) }, label = { Text("Ledge") })
                            NavigationBarItem(selected = selectedTab == 1, onClick = { selectedTab = 1 }, icon = { Icon(Icons.Default.AccountBox, "Token", tint = Color.White) }, label = { Text("Token") })
                            NavigationBarItem(selected = selectedTab == 2, onClick = { selectedTab = 2 }, icon = { Icon(Icons.Default.Settings, "Sentinel", tint = Color.White) }, label = { Text("Sentinel") })
                        }
                    },
                    containerColor = Color(0xFF0B0E11)
                ) { innerPadding ->
                    Box(modifier = Modifier.padding(innerPadding).fillMaxSize()) {
                        when (selectedTab) {
                            0 -> LedgeScreen()
                            1 -> TokenDashboard(balance, usdValue, isSyncing) {
                                isSyncing = true
                                scope.launch {
                                    val nodePrefs = context.getSharedPreferences("EdgeSwarmNode", MODE_PRIVATE)
                                    val privateKeyHex = nodePrefs.getString("private_key_$authenticatedUserEmail", null)

                                    val workerIdForBalance = if (privateKeyHex != null) {
                                        try {
                                            val credentials = Credentials.create(privateKeyHex)
                                            credentials.address
                                        } catch (e: Exception) {
                                            authenticatedUserEmail
                                        }
                                    } else {
                                        authenticatedUserEmail
                                    }

                                    val cloudBalanceResult = fetchCloudServerBalance(workerIdForBalance)

                                    try {
                                        balance = String.format(Locale.US, "%.2f", cloudBalanceResult)
                                        usdValue = String.format(Locale.US, "%.2f", cloudBalanceResult * 0.15)
                                        sharedPrefs.edit().putString("balance", balance).putString("usd", usdValue).apply()
                                    } catch (e: Exception) { Log.e("EdgeSwarm", "Format Error") }
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

    private suspend fun syncWalletKey(email: String, sharedPrefs: android.content.SharedPreferences) {
        val nodePrefs = getSharedPreferences("EdgeSwarmNode", MODE_PRIVATE)
        val keyName = "private_key_$email"
        val existingLocalKey = nodePrefs.getString(keyName, null)

        if (existingLocalKey != null) {
            Log.d("EdgeSwarm", "Wallet already synced locally for $email")
            return
        }

        try {
            Log.d("EdgeSwarm", "â˜ï¸ Searching Cloud for existing wallet...")
            val result = supabase.postgrest["worker_wallets"]
                .select { filter { eq("email", email) } }
                .decodeSingleOrNull<WorkerWallet>()

            if (result != null) {
                nodePrefs.edit().putString(keyName, result.private_key).apply()
                Log.d("EdgeSwarm", "â˜ï¸ Success: Wallet downloaded from Cloud for $email")
            } else {
                val ecKeyPair = try {
                    Keys.createEcKeyPair()
                } catch (e: Exception) {
                    Log.e("EdgeSwarm", "Fallback Key Gen", e)
                    Keys.createEcKeyPair()
                }

                val newPrivateKey = ecKeyPair.privateKey.toString(16)
                nodePrefs.edit().putString(keyName, newPrivateKey).apply()

                val newWallet = WorkerWallet(email, newPrivateKey)
                supabase.postgrest["worker_wallets"].insert(newWallet)
                Log.d("EdgeSwarm", "â˜ï¸ Success: New Wallet generated and backed up to Cloud for $email")
            }
        } catch (e: Exception) {
            Log.e("EdgeSwarm", "Cloud Sync Failed: ${e.message}")
        }
    }

    private suspend fun fetchCloudServerBalance(walletAddress: String): Double = withContext(Dispatchers.IO) {
        try {
            val client = OkHttpClient()
            val url = "http://136.115.0.26:3000/swarm/balance?worker=${walletAddress}"
            val request = Request.Builder().url(url).build()
            val response = client.newCall(request).execute()

            if (response.isSuccessful) {
                val json = JSONObject(response.body?.string() ?: "{}")
                return@withContext json.optDouble("balance", 0.0)
            }
            return@withContext 0.0
        } catch (e: Exception) {
            Log.e("EdgeSwarm", "Balance Fetch Failed", e)
            0.0
        }
    }

    private fun checkRequiredPermissions() {
        val permissions = mutableListOf(Manifest.permission.CAMERA)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) permissions.add(Manifest.permission.POST_NOTIFICATIONS)
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

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF0B0E11))
            .padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(Icons.Default.Lock, contentDescription = null, tint = Color(0xFF03DAC5), modifier = Modifier.size(64.dp))
        Spacer(modifier = Modifier.height(16.dp))
        Text("EDGESWARM SENTINEL", fontSize = 24.sp, fontWeight = FontWeight.Black, color = Color.White, fontFamily = FontFamily.Monospace)
        Text("Mobile Node Authorization Protocol", fontSize = 12.sp, color = Color.Gray)

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
                    IconButton(onClick = { isPasswordVisible = !isPasswordVisible }) {
                        Text(if (isPasswordVisible) "ðŸ™ˆ" else "ðŸ‘", fontSize = 18.sp)
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
                                    password = inputPassword.trim()
                                }
                            }

                            val (mfaEnabled, mfaActive) = supabase.auth.mfa.status

                            if (mfaEnabled && !mfaActive) {
                                val user = supabase.auth.currentUserOrNull()
                                val verifiedFactor = user?.factors?.firstOrNull()

                                if (verifiedFactor != null) {
                                    currentFactorId = verifiedFactor.id
                                    isMfaRequired = true
                                    statusMsg = "MFA Challenge Initiated."
                                } else {
                                    withContext(Dispatchers.IO) { supabase.auth.signOut() }
                                    statusMsg = "âš ï¸ Setup 2FA on the Web Console first."
                                }
                            } else {
                                onAuthSuccess(inputEmail.trim())
                            }
                        } else {
                            withContext(Dispatchers.IO) {
                                val challenge = supabase.auth.mfa.createChallenge(currentFactorId)
                                supabase.auth.mfa.verifyChallenge(currentFactorId, challenge.id, totpCode.trim())
                            }
                            onAuthSuccess(inputEmail.trim())
                        }
                    } catch (e: Exception) {
                        statusMsg = "Access Denied: Invalid Authentication."
                    } finally {
                        loading = false
                    }
                }
            },
            modifier = Modifier.fillMaxWidth().height(56.dp),
            shape = RoundedCornerShape(8.dp),
            colors = ButtonDefaults.buttonColors(containerColor = if(isMfaRequired) Color(0xFF10B981) else Color(0xFF4F46E5))
        ) {
            Text(if (loading) "AUTHORIZING..." else if(isMfaRequired) "VERIFY SECURE TOKEN" else "SIGN IN TO SWARM", fontWeight = FontWeight.Bold)
        }

        if (statusMsg.isNotEmpty()) {
            Spacer(modifier = Modifier.height(24.dp))
            Text(statusMsg, color = if (statusMsg.contains("âš ï¸") || statusMsg.contains("Denied")) Color.Red else Color(0xFF00FFCC), fontSize = 13.sp, textAlign = TextAlign.Center)
        }
    }
}

@Composable
fun LedgeScreen() {
    var isRefreshing by remember { mutableStateOf(false) }
    var ledgeItems by remember { mutableStateOf(listOf<LedgeItem>()) }
    val scope = rememberCoroutineScope()

    Column(modifier = Modifier.fillMaxSize().padding(24.dp)) {
        Text("BLOCKCHAIN LEDGE", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = Color(0xFF03DAC5))
        Text("Verified NPU proofs from Base Sepolia.", color = Color.Gray, fontSize = 12.sp)
        Spacer(modifier = Modifier.height(32.dp))
        Button(
            onClick = {
                isRefreshing = true
                scope.launch {
                    ledgeItems = fetchLedgeEvents()
                    isRefreshing = false
                }
            },
            modifier = Modifier.fillMaxWidth().height(56.dp),
            shape = RoundedCornerShape(8.dp),
            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1E2329))
        ) {
            Text(if (isRefreshing) "FETCHING..." else "REFRESH LEDGE", fontWeight = FontWeight.Bold)
        }
        Spacer(modifier = Modifier.height(24.dp))
        Text("RECENT SWARM ACTIVITY", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Color.Gray)
        Spacer(modifier = Modifier.height(16.dp))
        LazyColumn(modifier = Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(ledgeItems.size) { index ->
                val item = ledgeItems[index]
                Card(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = Color(0xFF161A1E))) {
                    Row(modifier = Modifier.padding(16.dp).fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Proof #${item.taskId}", color = Color.White, fontWeight = FontWeight.Bold)
                            Text("Node: ${item.worker.take(12)}...", color = Color.Gray, fontSize = 11.sp)
                        }
                        Text("VERIFIED", color = Color(0xFF4CAF50), fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}

private suspend fun fetchLedgeEvents(): List<LedgeItem> = withContext(Dispatchers.IO) {
    try {
        val web3 = Web3j.build(HttpService("https://sepolia.base.org"))
        val contract = "0xf6F82e2591f9B4A7ed53FF161080e1d1be891440"
        val eventSignature = "0x0fa6157f4e9e99fb9310ffbda586ef6dacedf5e0d84f679f44e65ee1eb61687c"

        val latestBlock = web3.ethBlockNumber().send().blockNumber
        val startBlock = latestBlock.subtract(BigInteger.valueOf(2000))

        val ethFilter = EthFilter(
            DefaultBlockParameterNumber(startBlock),
            DefaultBlockParameterName.LATEST,
            contract
        ).addSingleTopic(eventSignature)

        val ethLog = web3.ethGetLogs(ethFilter).send()

        if (ethLog.hasError()) return@withContext emptyList<LedgeItem>()

        return@withContext ethLog.logs.map { log ->
            val logObj = log as Web3jLog
            val topics = logObj.topics
            val rawWorker = if (topics.size > 1) topics[1] else "0x..."
            val cleanWorker = "0x" + rawWorker.substring(rawWorker.length - 40)

            LedgeItem(taskId = "Verified Task", worker = cleanWorker, score = "1.00 SWM", txHash = logObj.transactionHash ?: "")
        }.reversed()
    } catch (e: Exception) {
        Log.e("EdgeSwarm", "Ledge Fetch Error", e)
        emptyList<LedgeItem>()
    }
}

@Composable
fun TokenDashboard(balance: String, usd: String, isSyncing: Boolean, onSync: () -> Unit) {
    Column(modifier = Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
        Text("SWARM SYSTEM BALANCE", color = Color.Gray, fontSize = 12.sp, fontWeight = FontWeight.Bold)
        Text(text = if(isSyncing) "SYNCING..." else "$balance SWM", fontSize = 48.sp, fontWeight = FontWeight.Black, color = Color.White)
        Text(text = "â‰ˆ $$usd USD", color = Color(0xFF00FFCC), fontSize = 18.sp)
        Spacer(modifier = Modifier.height(40.dp))
        Button(onClick = onSync, modifier = Modifier.fillMaxWidth().height(56.dp), shape = RoundedCornerShape(8.dp), colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1E2329))) {
            Text("SYNC LEDGER DATA", fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
fun SentinelScreen(userEmail: String) {
    val context = LocalContext.current
    var isRunning by remember { mutableStateOf(SentinelService.isServiceRunning) }

    var allowCompute by remember { mutableStateOf(true) }
    var allowScraping by remember { mutableStateOf(true) }
    var allowSlm by remember { mutableStateOf(true) }

    Column(modifier = Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
        Text("HEADLESS NPU NODE", color = Color(0xFF03DAC5), fontSize = 24.sp, fontWeight = FontWeight.Bold)
        Text(text = "CONNECTED AS: $userEmail", color = Color.Gray, fontSize = 11.sp, modifier = Modifier.padding(top = 4.dp))
        Text(text = "STATUS: ${if (isRunning) "ACTIVE IN BACKGROUND" else "STANDBY"}", color = Color.Gray, fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp))

        Spacer(modifier = Modifier.height(32.dp))

        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = Color(0xFF161A1E)),
            shape = RoundedCornerShape(12.dp)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text("WORKLOAD ROUTING", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Color.Gray)
                Spacer(modifier = Modifier.height(16.dp))

                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("NPU Tensor Compute", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                        Text("Process heavy matrix calculations.", color = Color.Gray, fontSize = 11.sp)
                    }
                    Switch(checked = allowCompute, onCheckedChange = { if (!isRunning) allowCompute = it }, colors = SwitchDefaults.colors(checkedThumbColor = Color(0xFF0B0E11), checkedTrackColor = Color(0xFF03DAC5)))
                }

                Spacer(modifier = Modifier.height(12.dp))

                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Residential Proxy Scraper", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                        Text("Route enterprise network requests.", color = Color.Gray, fontSize = 11.sp)
                    }
                    Switch(checked = allowScraping, onCheckedChange = { if (!isRunning) allowScraping = it }, colors = SwitchDefaults.colors(checkedThumbColor = Color(0xFF0B0E11), checkedTrackColor = Color(0xFF03DAC5)))
                }

                Spacer(modifier = Modifier.height(12.dp))

                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Local SLM Inference", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                        Text("Run quantized text-gen models.", color = Color.Gray, fontSize = 11.sp)
                    }
                    Switch(checked = allowSlm, onCheckedChange = { if (!isRunning) allowSlm = it }, colors = SwitchDefaults.colors(checkedThumbColor = Color(0xFF0B0E11), checkedTrackColor = Color(0xFF03DAC5)))
                }
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
                    serviceIntent.putExtra("ALLOW_SLM", allowSlm)

                    ContextCompat.startForegroundService(context, serviceIntent)
                    isRunning = true
                } else {
                    context.stopService(serviceIntent)
                    isRunning = false
                }
            },
            modifier = Modifier.fillMaxWidth().height(80.dp),
            shape = RoundedCornerShape(16.dp),
            colors = ButtonDefaults.buttonColors(containerColor = if (isRunning) Color(0xFFE53935) else Color(0xFF1E2329))
        ) {
            Text(text = if (isRunning) "â— STOP HEADLESS NODE" else "ACTIVATE HEADLESS MODE", fontWeight = FontWeight.ExtraBold)
        }
    }
}

