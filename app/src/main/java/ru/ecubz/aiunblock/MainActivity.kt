package ru.ecubz.aiunblock

import android.Manifest
import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.os.PersistableBundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

class MainActivity : ComponentActivity() {
    companion object {
        private const val FOUR_PDA_URL =
            "https://4pda.to/forum/index.php?showuser=1266125"
        private const val TELEGRAM_URL = "https://t.me/eCubz"
    }

    private var continueAfterNotificationPermission = false

    private val vpnPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == RESULT_OK) startVpnService()
        }

    private val notificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) {
            if (continueAfterNotificationPermission) {
                continueAfterNotificationPermission = false
                requestVpnPermission()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            AiUnblockTheme {
                val state by TunnelStateStore.state.collectAsState()
                AiUnblockScreen(
                    state = state,
                    onToggle = {
                        if (state is TunnelState.On || state is TunnelState.Starting) {
                            stopVpnService()
                        } else {
                            beginEnableFlow()
                        }
                    },
                    onOpen4Pda = { openUrl(FOUR_PDA_URL) },
                    onOpenTelegram = { openUrl(TELEGRAM_URL) },
                )
            }
        }
    }

    private fun beginEnableFlow() {
        if (
            Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.POST_NOTIFICATIONS,
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            continueAfterNotificationPermission = true
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            requestVpnPermission()
        }
    }

    private fun requestVpnPermission() {
        val intent = VpnService.prepare(this)
        if (intent == null) {
            startVpnService()
        } else {
            vpnPermissionLauncher.launch(intent)
        }
    }

    private fun startVpnService() {
        TunnelStateStore.update(TunnelState.Starting)
        val intent = Intent(this, AiUnblockVpnService::class.java).apply {
            action = AiUnblockVpnService.ACTION_CONNECT
        }
        ContextCompat.startForegroundService(this, intent)
        requestBatteryOptimizationExemptionOnce()
    }

    private fun requestBatteryOptimizationExemptionOnce() {
        if (Build.VERSION.SDK_INT < 23) return
        val preferences = getSharedPreferences("state", MODE_PRIVATE)
        if (preferences.getBoolean("battery_prompt_shown", false)) return

        val powerManager = getSystemService(PowerManager::class.java)
        if (powerManager.isIgnoringBatteryOptimizations(packageName)) {
            preferences.edit().putBoolean("battery_prompt_shown", true).apply()
            return
        }

        val request = Intent(
            Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
            Uri.parse("package:$packageName"),
        ).setPackage("com.android.settings")
        if (request.resolveActivity(packageManager) == null) {
            request.setPackage(null)
        }
        if (runCatching { startActivity(request) }.isSuccess) {
            preferences.edit().putBoolean("battery_prompt_shown", true).apply()
        }
    }

    private fun stopVpnService() {
        val intent = Intent(this, AiUnblockVpnService::class.java).apply {
            action = AiUnblockVpnService.ACTION_DISCONNECT
        }
        startService(intent)
    }

    private fun openUrl(url: String) {
        runCatching {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun AiUnblockScreen(
    state: TunnelState,
    onToggle: () -> Unit,
    onOpen4Pda: () -> Unit,
    onOpenTelegram: () -> Unit,
) {
    val context = LocalContext.current
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    var showDonate by remember { mutableStateOf(false) }
    var showApps by remember { mutableStateOf(false) }
    var customPackages by remember {
        mutableStateOf(AppSelection.loadCustomPackages(context))
    }
    val enabled = state is TunnelState.On
    val busy = state is TunnelState.Starting

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        containerColor = MaterialTheme.colorScheme.surface,
    ) { contentPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(contentPadding)
                .padding(horizontal = 24.dp)
                .navigationBarsPadding(),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(Modifier.height(28.dp))
            Text(
                text = "AI Unblock",
                style = MaterialTheme.typography.headlineLarge,
                fontWeight = FontWeight.Bold,
            )
            Spacer(Modifier.height(8.dp))
            StatusText(state)
            TextButton(onClick = { showApps = true }) {
                Text(
                    if (customPackages.isEmpty()) {
                        "Добавить приложения"
                    } else {
                        "Мои приложения · ${customPackages.size}"
                    },
                )
            }

            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                contentAlignment = Alignment.Center,
            ) {
                PowerButton(
                    enabled = enabled,
                    busy = busy,
                    onClick = onToggle,
                )
            }

            DeveloperFooter(
                onOpen4Pda = onOpen4Pda,
                onOpenTelegram = onOpenTelegram,
                onDonate = { showDonate = true },
            )
            Spacer(Modifier.height(12.dp))
        }
    }

    if (showDonate) {
        ModalBottomSheet(onDismissRequest = { showDonate = false }) {
            DonationSheet(
                onCopy = { label, value ->
                    copySensitive(context, label, value)
                    scope.launch { snackbar.showSnackbar("$label скопирован") }
                },
            )
        }
    }

    if (showApps) {
        ModalBottomSheet(onDismissRequest = { showApps = false }) {
            AppSelectionSheet(
                selectedPackages = customPackages,
                tunnelIsActive = enabled || busy,
                onSelectionChanged = { updated ->
                    customPackages = updated
                    AppSelection.saveCustomPackages(context, updated)
                },
            )
        }
    }
}

@Composable
private fun AppSelectionSheet(
    selectedPackages: Set<String>,
    tunnelIsActive: Boolean,
    onSelectionChanged: (Set<String>) -> Unit,
) {
    val context = LocalContext.current
    val apps by produceState<List<SelectableApp>?>(initialValue = null, context) {
        value = withContext(Dispatchers.IO) {
            AppSelection.loadLaunchableApps(context)
        }
    }
    var query by remember { mutableStateOf("") }
    val visibleApps = apps?.filter { app ->
        query.isBlank() ||
            app.label.contains(query, ignoreCase = true) ||
            app.packageName.contains(query, ignoreCase = true)
    }.orEmpty()

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp)
            .padding(bottom = 24.dp),
    ) {
        Text(
            text = "Приложения GeoHide",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
        )
        Spacer(Modifier.height(6.dp))
        Text(
            text = "Добавьте приложения, которым нужен обход геоблокировки. " +
                "Штатные AI-приложения подключены всегда.",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodyMedium,
        )
        if (tunnelIsActive) {
            Spacer(Modifier.height(6.dp))
            Text(
                text = "Изменения применятся после выключения и повторного включения.",
                color = MaterialTheme.colorScheme.primary,
                style = MaterialTheme.typography.bodySmall,
            )
        }
        Spacer(Modifier.height(16.dp))
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            label = { Text("Поиск") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(8.dp))

        if (apps == null) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(180.dp),
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator()
            }
        } else if (visibleApps.isEmpty()) {
            Text(
                text = "Приложения не найдены",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(vertical = 24.dp),
            )
        } else {
            LazyColumn(modifier = Modifier.heightIn(max = 480.dp)) {
                items(
                    items = visibleApps,
                    key = SelectableApp::packageName,
                ) { app ->
                    val checked = app.isBuiltIn || app.packageName in selectedPackages
                    AppSelectionRow(
                        app = app,
                        checked = checked,
                        onCheckedChange = {
                            onSelectionChanged(
                                if (checked) {
                                    selectedPackages - app.packageName
                                } else {
                                    selectedPackages + app.packageName
                                },
                            )
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun AppSelectionRow(
    app: SelectableApp,
    checked: Boolean,
    onCheckedChange: () -> Unit,
) {
    Surface(
        color = Color.Transparent,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = !app.isBuiltIn, onClick = onCheckedChange),
    ) {
        Row(
            modifier = Modifier.padding(vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Checkbox(
                checked = checked,
                enabled = !app.isBuiltIn,
                onCheckedChange = { onCheckedChange() },
            )
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(start = 8.dp),
            ) {
                Text(
                    text = app.label,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = if (app.isBuiltIn) {
                        "Встроенная поддержка"
                    } else {
                        app.packageName.lowercase(Locale.ROOT)
                    },
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1,
                )
            }
        }
    }
}

@Composable
private fun StatusText(state: TunnelState) {
    val text = when (state) {
        TunnelState.Off -> "Выключено"
        TunnelState.Starting -> "Подключение…"
        is TunnelState.On -> "Включено"
        is TunnelState.Error -> state.message
    }
    val color by animateColorAsState(
        targetValue = when (state) {
            is TunnelState.On -> MaterialTheme.colorScheme.primary
            is TunnelState.Error -> MaterialTheme.colorScheme.error
            else -> MaterialTheme.colorScheme.onSurfaceVariant
        },
        label = "statusColor",
    )
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        AnimatedContent(targetState = text, label = "statusText") { value ->
            Text(
                text = value,
                color = color,
                style = MaterialTheme.typography.bodyLarge,
                textAlign = TextAlign.Center,
            )
        }
        if (state is TunnelState.On) {
            Spacer(Modifier.height(6.dp))
            Text(
                text = state.gatewaySummary,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall,
                textAlign = TextAlign.Center,
            )
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun PowerButton(
    enabled: Boolean,
    busy: Boolean,
    onClick: () -> Unit,
) {
    val containerColor by animateColorAsState(
        targetValue = if (enabled) {
            MaterialTheme.colorScheme.primaryContainer
        } else {
            MaterialTheme.colorScheme.primary
        },
        label = "powerContainer",
    )
    val contentColor by animateColorAsState(
        targetValue = if (enabled) {
            MaterialTheme.colorScheme.onPrimaryContainer
        } else {
            MaterialTheme.colorScheme.onPrimary
        },
        label = "powerContent",
    )

    Button(
        onClick = onClick,
        enabled = !busy,
        shapes = ButtonDefaults.shapes(
            shape = RoundedCornerShape(64.dp),
            pressedShape = RoundedCornerShape(36.dp),
        ),
        colors = ButtonDefaults.buttonColors(
            containerColor = containerColor,
            contentColor = contentColor,
        ),
        contentPadding = PaddingValues(0.dp),
        modifier = Modifier
            .size(184.dp)
            .semantics {
                contentDescription = if (enabled) "Выключить AI Unblock" else "Включить AI Unblock"
            },
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            PowerGlyph(
                color = contentColor,
                modifier = Modifier.size(62.dp),
            )
            Spacer(Modifier.height(12.dp))
            Text(
                text = when {
                    busy -> "ПОДКЛЮЧЕНИЕ"
                    enabled -> "ВЫКЛЮЧИТЬ"
                    else -> "ВКЛЮЧИТЬ"
                },
                fontWeight = FontWeight.Bold,
                letterSpacing = 0.8.sp,
            )
        }
    }
}

@Composable
private fun PowerGlyph(color: Color, modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val stroke = size.minDimension * 0.105f
        drawArc(
            color = color,
            startAngle = -48f,
            sweepAngle = 276f,
            useCenter = false,
            topLeft = Offset(stroke, stroke),
            size = Size(size.width - stroke * 2, size.height - stroke * 2),
            style = Stroke(width = stroke, cap = StrokeCap.Round),
        )
        drawLine(
            color = color,
            start = Offset(size.width / 2, size.height * 0.08f),
            end = Offset(size.width / 2, size.height * 0.50f),
            strokeWidth = stroke,
            cap = StrokeCap.Round,
        )
    }
}

@Composable
private fun DeveloperFooter(
    onOpen4Pda: () -> Unit,
    onOpenTelegram: () -> Unit,
    onDonate: () -> Unit,
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = "Разработчик: eCubz",
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
        )
        Text(
            text = "Принимаю плюсики в репу, на добровольной основе.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Row(horizontalArrangement = Arrangement.Center) {
            TextButton(onClick = onOpen4Pda) { Text("4PDA") }
            TextButton(onClick = onOpenTelegram) { Text("Telegram") }
            TextButton(onClick = onDonate) { Text("Поддержать") }
        }
    }
}

@Composable
private fun DonationSheet(onCopy: (label: String, value: String) -> Unit) {
    val wallet = "UQCLyovMu5882XPekfUqXOLFbYFHROaB9uoWMsIaifvMqEC4"
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp)
            .padding(bottom = 36.dp),
    ) {
        Text(
            text = "Донат на развитие",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = "Нажмите на реквизиты, чтобы скопировать.",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodyMedium,
        )
        Spacer(Modifier.height(20.dp))

        CopyRow(
            title = "USDT в сети TON",
            value = wallet,
            onClick = { onCopy("USDT-кошелёк", wallet) },
        )
        HorizontalDivider()
        CopyRow(
            title = "GRAM в сети TON",
            value = wallet,
            onClick = { onCopy("GRAM-кошелёк", wallet) },
        )
        HorizontalDivider()
        CopyRow(
            title = "СБП · Т-Банк",
            value = "+7-923-618-89-93",
            onClick = { onCopy("Номер СБП", "9236188993") },
        )
    }
}

@Composable
private fun CopyRow(title: String, value: String, onClick: () -> Unit) {
    Surface(
        color = Color.Transparent,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
    ) {
        Column(modifier = Modifier.padding(vertical = 16.dp)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = value,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

private fun copySensitive(context: Context, label: String, value: String) {
    val clipboard = context.getSystemService(ClipboardManager::class.java)
    val clip = ClipData.newPlainText(label, value)
    if (Build.VERSION.SDK_INT >= 33) {
        clip.description.extras = PersistableBundle().apply {
            putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true)
        }
    }
    clipboard.setPrimaryClip(clip)
}
