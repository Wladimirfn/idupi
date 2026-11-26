package com.idupi.app.ui.screens

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.idupi.app.BuildConfig
import com.idupi.app.IduPiApp
import com.idupi.app.data.IduPiClientProvider
import com.idupi.app.data.connection.ConnectionStorage
import com.idupi.app.data.inbox.isInboxListenerEnabled
import com.idupi.app.domain.model.MIN_READABLE_CONTRAST_RATIO
import com.idupi.app.domain.model.ThemeColors
import com.idupi.app.domain.model.ThemeMode
import com.idupi.app.domain.model.ThemePreset
import com.idupi.app.domain.model.ThemeSettings
import com.idupi.app.domain.model.contrastRatioArgb
import com.idupi.app.domain.model.isLowContrast
import com.idupi.app.domain.model.relativeLuminance
import com.idupi.app.ui.screens.settings.ACTIONS_CATEGORY_SUMMARY
import com.idupi.app.ui.screens.settings.HELP_CATEGORY_SUMMARY
import com.idupi.app.ui.screens.settings.SettingsCategoryRow
import com.idupi.app.ui.screens.settings.SettingsRoute
import com.idupi.app.ui.screens.settings.SettingsSubScreen
import com.idupi.app.ui.screens.settings.alertsSummary
import com.idupi.app.ui.screens.settings.appearanceSummary
import com.idupi.app.ui.screens.settings.back
import com.idupi.app.ui.screens.settings.calendarSummary
import com.idupi.app.ui.screens.settings.connectionSummary
import com.idupi.app.ui.screens.settings.healthSummary
import com.idupi.app.ui.screens.settings.phoneAssistantSummary
import com.idupi.app.ui.theme.AppShapes
import com.idupi.app.ui.theme.AppSpacing
import com.idupi.app.ui.theme.AppTypography
import com.idupi.app.ui.theme.PrimaryIndigo
import com.idupi.app.ui.theme.SlateBg
import com.idupi.app.ui.theme.SlateBorder
import com.idupi.app.ui.theme.SlateCard
import com.idupi.app.ui.theme.StatusError
import com.idupi.app.ui.theme.TextPrimary
import com.idupi.app.ui.theme.TextSecondary
import com.idupi.app.ui.theme.ThemePalettes
import com.idupi.app.viewmodel.InboxSettingsViewModel
import com.idupi.app.viewmodel.MainViewModel
import com.idupi.app.viewmodel.CalendarSettingsViewModel
import com.idupi.app.viewmodel.HealthSettingsViewModel
import com.idupi.app.viewmodel.PhoneAgentSettingsViewModel
import java.util.Locale

/**
 * Ajustes (settings redesign T1): a short hub of categories that open their own
 * sub-screens inside the same SETTINGS destination. The app-level drawer and
 * BackHandler keep working; only an open sub-route consumes system back.
 */
@Composable
fun SettingsScreen(
    mainViewModel: MainViewModel,
    inboxViewModel: InboxSettingsViewModel,
    phoneAgentViewModel: PhoneAgentSettingsViewModel,
    calendarViewModel: CalendarSettingsViewModel,
    healthViewModel: HealthSettingsViewModel,
    onMenuClick: () -> Unit,
    onNavigateToConnection: () -> Unit,
    onNavigateToWelcome: () -> Unit,
    onNavigateToCommands: () -> Unit
) {
    val themeSettings by mainViewModel.themeSettings.collectAsState()

    val context = LocalContext.current
    val connectionStorage = remember { ConnectionStorage(context) }
    var savedProfile by remember { mutableStateOf(connectionStorage.getProfile()) }

    LaunchedEffect(Unit) {
        savedProfile = connectionStorage.getProfile()
    }

    // The route name is what rememberSaveable persists; fromName() tolerates a
    // stale or unknown value after a future route is renamed.
    var routeName by rememberSaveable { mutableStateOf(SettingsRoute.Hub.name) }
    val route = SettingsRoute.fromName(routeName)
    val goBack: () -> Unit = {
        back(route)?.let { routeName = it.name }
    }
    val openRoute: (SettingsRoute) -> Unit = { routeName = it.name }

    // Only sub-routes intercept system back. On the hub this handler is
    // disabled, so the app-level BackHandler keeps its current behaviour.
    BackHandler(enabled = route != SettingsRoute.Hub) { goBack() }

    when (route) {
        SettingsRoute.Hub -> SettingsHub(
            themeSettings = themeSettings,
            savedProfileName = savedProfile?.name,
            savedProfileHost = savedProfile?.let { "${it.host}:${it.port}" },
            inboxViewModel = inboxViewModel,
            phoneAgentViewModel = phoneAgentViewModel,
            calendarViewModel = calendarViewModel,
            healthViewModel = healthViewModel,
            onMenuClick = onMenuClick,
            onNavigateToConnection = onNavigateToConnection,
            onNavigateToCommands = onNavigateToCommands,
            onOpenRoute = openRoute,
        )
        SettingsRoute.Appearance -> SettingsSubScreen("Apariencia", onBack = goBack) {
            AppearanceSection(
                settings = themeSettings,
                onModeSelect = { mainViewModel.setThemeMode(it) },
                onPresetSelect = { mainViewModel.setThemePreset(it) },
                onCustomColorsChange = { mainViewModel.setCustomThemeColors(it) }
            )
        }
        SettingsRoute.Alerts,
        SettingsRoute.FollowedApps,
        SettingsRoute.SilencedChats,
        SettingsRoute.AlertAnalyst,
        SettingsRoute.CaptureStatus -> SettingsSubScreen(
            // T4 gives "Apps a seguir" and the other long blocks their own
            // screens; until then every alerts route shows the Alertas section.
            title = "Alertas",
            onBack = goBack,
        ) {
            InboxAlertsSection(inboxViewModel)
        }
        SettingsRoute.PhoneAssistant -> SettingsSubScreen(
            title = "Asistente del teléfono",
            onBack = goBack,
        ) {
            PhoneAgentSection(phoneAgentViewModel)
        }
        SettingsRoute.Calendar -> SettingsSubScreen("Calendario", onBack = goBack) {
            CalendarSection(calendarViewModel)
        }
        SettingsRoute.Health -> SettingsSubScreen("Salud", onBack = goBack) {
            HealthSection(healthViewModel)
        }
        SettingsRoute.Actions -> SettingsSubScreen("Acciones y seguridad", onBack = goBack) {
            ActionsSection(
                onDiagnostics = {
                    Toast.makeText(context, "Conectado a: ${savedProfile?.host ?: "Ninguno"}", Toast.LENGTH_SHORT).show()
                },
                onClearCredentials = {
                    connectionStorage.clear()
                    // Persisted credentials are gone, but the in-memory client
                    // and the delivery poller would keep using the old host and
                    // token until the process restarted.
                    IduPiClientProvider.clear()
                    (context.applicationContext as? IduPiApp)?.stopDeliveryWatcher()
                    onNavigateToWelcome()
                }
            )
        }
    }
}

/**
 * Hub list: every category shows the state the owner would find inside it, so
 * most adjustments need no extra tap to diagnose.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SettingsHub(
    themeSettings: ThemeSettings,
    savedProfileName: String?,
    savedProfileHost: String?,
    inboxViewModel: InboxSettingsViewModel,
    phoneAgentViewModel: PhoneAgentSettingsViewModel,
    calendarViewModel: CalendarSettingsViewModel,
    healthViewModel: HealthSettingsViewModel,
    onMenuClick: () -> Unit,
    onNavigateToConnection: () -> Unit,
    onNavigateToCommands: () -> Unit,
    onOpenRoute: (SettingsRoute) -> Unit,
) {
    val inboxSettings by inboxViewModel.inboxSettings.collectAsState()
    val phoneControlEnabled by phoneAgentViewModel.phoneControlEnabled.collectAsState()
    val pendingApprovals by phoneAgentViewModel.approvals.collectAsState()
    val calendarShareEnabled by calendarViewModel.shareEnabled.collectAsState()
    val calendarPermission by calendarViewModel.permission.collectAsState()
    val healthState by healthViewModel.state.collectAsState()
    val notificationAccess = rememberNotificationAccess()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Ajustes", style = AppTypography.appBarTitle, color = TextPrimary) },
                navigationIcon = {
                    IconButton(onClick = onMenuClick) {
                        Icon(Icons.Default.Menu, "Menu", tint = TextPrimary)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = SlateBg)
            )
        },
        containerColor = SlateBg
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .verticalScroll(rememberScrollState())
                .padding(AppSpacing.screenPadding),
            verticalArrangement = Arrangement.spacedBy(AppSpacing.sm)
        ) {
            SettingsCategoryRow(
                icon = Icons.Default.Dns,
                title = "Conexión",
                summary = connectionSummary(savedProfileName, savedProfileHost),
                onClick = onNavigateToConnection,
            )

            SettingsCategoryRow(
                icon = Icons.Default.Palette,
                title = "Apariencia",
                summary = appearanceSummary(themeSettings),
                onClick = { onOpenRoute(SettingsRoute.Appearance) },
            )

            SettingsCategoryRow(
                icon = Icons.Default.Notifications,
                title = "Alertas",
                summary = alertsSummary(inboxSettings, notificationAccess),
                onClick = { onOpenRoute(SettingsRoute.Alerts) },
            )

            SettingsCategoryRow(
                icon = Icons.Default.PhoneAndroid,
                title = "Asistente del teléfono",
                summary = phoneAssistantSummary(phoneControlEnabled, pendingApprovals.pending.size),
                onClick = { onOpenRoute(SettingsRoute.PhoneAssistant) },
            )

            SettingsCategoryRow(
                icon = Icons.Default.CalendarMonth,
                title = "Calendario",
                summary = calendarSummary(calendarShareEnabled, calendarPermission),
                onClick = { onOpenRoute(SettingsRoute.Calendar) },
            )

            SettingsCategoryRow(
                icon = Icons.Default.Favorite,
                title = "Salud",
                summary = healthSummary(healthState),
                onClick = { onOpenRoute(SettingsRoute.Health) },
            )

            SettingsCategoryRow(
                icon = Icons.Default.Terminal,
                title = "Ayuda y comandos",
                summary = HELP_CATEGORY_SUMMARY,
                onClick = onNavigateToCommands,
            )

            AboutSection()

            SettingsCategoryRow(
                icon = Icons.Default.Security,
                title = "Acciones y seguridad",
                summary = ACTIONS_CATEGORY_SUMMARY,
                onClick = { onOpenRoute(SettingsRoute.Actions) },
            )

            Spacer(modifier = Modifier.height(AppSpacing.xxxl))
        }
    }
}

/**
 * Notification-listener access, re-read when the owner returns from the system
 * settings, exactly like the Alertas section does.
 */
@Composable
private fun rememberNotificationAccess(): Boolean {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var enabled by remember { mutableStateOf(isInboxListenerEnabled(context)) }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                enabled = isInboxListenerEnabled(context)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    return enabled
}

/**
 * Section body. The title is optional: on a sub-screen the top bar already
 * names the category, so the card block does not repeat it.
 */
@Composable
internal fun SettingsSection(
    title: String? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(AppSpacing.sm)) {
        if (title != null) {
            Text(title, style = AppTypography.titleSmall, color = TextSecondary)
        }
        content()
    }
}

/**
 * "Apariencia": dark/light/custom mode, the three accent presets and — in
 * custom mode — the colour editor with a live preview. Every choice is
 * persisted immediately (DataStore) and applied app-wide by IDUPITheme.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AppearanceSection(
    settings: ThemeSettings,
    onModeSelect: (ThemeMode) -> Unit,
    onPresetSelect: (ThemePreset) -> Unit,
    onCustomColorsChange: (ThemeColors) -> Unit
) {
    SettingsSection {
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = SlateCard),
            shape = AppShapes.card
        ) {
            Column(
                modifier = Modifier.padding(AppSpacing.cardPadding),
                verticalArrangement = Arrangement.spacedBy(AppSpacing.md)
            ) {
                Text("Modo", style = AppTypography.bodySmall, color = TextSecondary)
                ModeSelector(selected = settings.mode, onSelect = onModeSelect)

                when (settings.mode) {
                    ThemeMode.DARK, ThemeMode.LIGHT -> {
                        Text("Acento", style = AppTypography.bodySmall, color = TextSecondary)
                        PresetRow(selected = settings.preset, onSelect = onPresetSelect)
                    }
                    ThemeMode.CUSTOM -> {
                        Text("Colores", style = AppTypography.bodySmall, color = TextSecondary)
                        CustomColorsEditor(
                            colors = settings.custom,
                            onChange = onCustomColorsChange
                        )
                    }
                }

                Text(
                    text = appearanceSectionSummary(settings),
                    style = AppTypography.labelSmall,
                    color = TextSecondary
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ModeSelector(
    selected: ThemeMode,
    onSelect: (ThemeMode) -> Unit
) {
    val modes = listOf(
        ThemeMode.DARK to "Oscuro",
        ThemeMode.LIGHT to "Claro",
        ThemeMode.CUSTOM to "Personalizado"
    )
    SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
        modes.forEachIndexed { index, (mode, label) ->
            SegmentedButton(
                selected = selected == mode,
                onClick = { onSelect(mode) },
                shape = SegmentedButtonDefaults.itemShape(index = index, count = modes.size),
                colors = SegmentedButtonDefaults.colors(
                    activeContainerColor = PrimaryIndigo,
                    activeContentColor = Color.White,
                    activeBorderColor = PrimaryIndigo,
                    inactiveContainerColor = SlateBg,
                    inactiveContentColor = TextSecondary,
                    inactiveBorderColor = SlateBorder
                ),
                icon = {},
                label = {
                    Text(
                        text = label,
                        style = AppTypography.labelSmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PresetRow(
    selected: ThemePreset,
    onSelect: (ThemePreset) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(AppSpacing.sm)
    ) {
        ThemePalettes.presets.forEach { option ->
            val isSelected = option.preset == selected
            FilterChip(
                selected = isSelected,
                onClick = { onSelect(option.preset) },
                label = {
                    Text(
                        text = option.label,
                        style = AppTypography.labelSmall,
                        maxLines = 1
                    )
                },
                leadingIcon = {
                    Box(
                        modifier = Modifier
                            .size(10.dp)
                            .clip(CircleShape)
                            .background(option.accent)
                    )
                },
                colors = FilterChipDefaults.filterChipColors(
                    selectedContainerColor = PrimaryIndigo.copy(alpha = 0.15f),
                    selectedLabelColor = PrimaryIndigo,
                    selectedLeadingIconColor = PrimaryIndigo,
                    labelColor = TextSecondary
                )
            )
        }
    }
}

@Composable
private fun CustomColorsEditor(
    colors: ThemeColors,
    onChange: (ThemeColors) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(AppSpacing.md)) {
        ColorField(
            label = "Acento",
            selectedArgb = colors.accentArgb,
            onSelect = { onChange(colors.copy(accentArgb = it)) }
        )
        ColorField(
            label = "Fondo",
            selectedArgb = colors.backgroundArgb,
            onSelect = { onChange(colors.copy(backgroundArgb = it)) }
        )
        ColorField(
            label = "Tarjeta",
            selectedArgb = colors.cardArgb,
            onSelect = { onChange(colors.copy(cardArgb = it)) }
        )
        ColorField(
            label = "Texto",
            selectedArgb = colors.textArgb,
            onSelect = { onChange(colors.copy(textArgb = it)) }
        )

        if (isLowContrast(colors.textArgb, colors.backgroundArgb)) {
            val ratio = contrastRatioArgb(colors.textArgb, colors.backgroundArgb)
            Text(
                text = "Contraste bajo: ${String.format(Locale.US, "%.1f", ratio)}:1 entre texto y fondo " +
                    "(mínimo ${MIN_READABLE_CONTRAST_RATIO.toInt()}:1). El texto puede quedar difícil de leer.",
                style = AppTypography.bodySmall,
                color = StatusError
            )
        }

        CustomThemePreview(colors)
    }
}

@Composable
private fun ColorField(
    label: String,
    selectedArgb: Int,
    onSelect: (Int) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(AppSpacing.sm)) {
        Text(label, style = AppTypography.bodySmall, color = TextPrimary)
        ThemePalettes.customSwatchArgb.chunked(6).forEach { row ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(AppSpacing.sm)
            ) {
                row.forEach { argb ->
                    val isSelected = argb == selectedArgb
                    Box(
                        modifier = Modifier
                            .size(36.dp)
                            .clip(CircleShape)
                            .background(Color(argb))
                            .border(
                                width = if (isSelected) 3.dp else 1.dp,
                                color = if (isSelected) TextPrimary else SlateBorder,
                                shape = CircleShape
                            )
                            .clickable { onSelect(argb) }
                    )
                }
            }
        }
    }
}

@Composable
private fun CustomThemePreview(colors: ThemeColors) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(AppShapes.card)
            .background(Color(colors.backgroundArgb))
            .border(1.dp, Color(colors.cardArgb), AppShapes.card)
            .padding(AppSpacing.md),
        verticalArrangement = Arrangement.spacedBy(AppSpacing.sm)
    ) {
        Text("Vista previa", style = AppTypography.titleSmall, color = Color(colors.accentArgb))
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(AppShapes.small)
                .background(Color(colors.cardArgb))
                .padding(AppSpacing.md),
            verticalArrangement = Arrangement.spacedBy(AppSpacing.xs)
        ) {
            Text(
                text = "Así se verá el texto de la app",
                style = AppTypography.bodyLarge,
                color = Color(colors.textArgb)
            )
            Text(
                text = "Texto secundario de ejemplo",
                style = AppTypography.bodySmall,
                color = Color(colors.textArgb).copy(alpha = 0.7f)
            )
        }
        Box(
            modifier = Modifier
                .clip(AppShapes.pill)
                .background(Color(colors.accentArgb))
                .padding(horizontal = AppSpacing.lg, vertical = AppSpacing.xs)
        ) {
            Text(
                text = "Acento",
                style = AppTypography.labelSmall,
                color = inkOn(colors.accentArgb)
            )
        }
    }
}

@Composable
private fun AboutSection() {
    SettingsSection("ACERCA DE") {
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = SlateCard),
            shape = AppShapes.card
        ) {
            Column(modifier = Modifier.padding(AppSpacing.cardPadding)) {
                Text("IDUPI", style = AppTypography.titleMedium, color = TextPrimary)
                Text(
                    text = "Cliente Android · versión ${BuildConfig.VERSION_NAME}",
                    style = AppTypography.bodySmall,
                    color = TextSecondary
                )
            }
        }
    }
}

@Composable
private fun ActionsSection(
    onDiagnostics: () -> Unit,
    onClearCredentials: () -> Unit
) {
    SettingsSection {
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = SlateCard),
            shape = AppShapes.card
        ) {
            Column(modifier = Modifier.padding(AppSpacing.cardPadding)) {
                Text(
                    text = "Ejecutar Autodiagnóstico",
                    style = AppTypography.bodyLarge.copy(fontWeight = FontWeight.Bold),
                    color = PrimaryIndigo,
                    modifier = Modifier.fillMaxWidth().clickable { onDiagnostics() }.padding(vertical = AppSpacing.sm)
                )
                Divider(color = SlateBorder)
                Text(
                    text = "Borrar Credenciales y Salir",
                    style = AppTypography.bodyLarge.copy(fontWeight = FontWeight.Bold),
                    color = StatusError,
                    modifier = Modifier.fillMaxWidth().clickable { onClearCredentials() }.padding(vertical = AppSpacing.sm)
                )
            }
        }
    }
}

private fun appearanceSectionSummary(settings: ThemeSettings): String = when (settings.mode) {
    ThemeMode.DARK -> "Oscuro · acento ${ThemePalettes.labelFor(settings.preset)}"
    ThemeMode.LIGHT -> "Claro · acento ${ThemePalettes.labelFor(settings.preset)}"
    ThemeMode.CUSTOM -> "Colores personalizados"
}

/** Black or white ink for a filled accent chip/button, by luminance. */
private fun inkOn(argb: Int): Color =
    if (relativeLuminance(argb) > 0.5) Color(0xFF0F172A) else Color.White
