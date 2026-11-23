package com.idupi.app.ui.navigation

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ScreenShare
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.animation.togetherWith
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally

import androidx.lifecycle.viewmodel.compose.viewModel
import com.idupi.app.ui.screens.*
import com.idupi.app.ui.theme.*
import com.idupi.app.viewmodel.*
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

enum class Screen(val title: String) {
    WELCOME("Bienvenido"),
    CONNECTION("Conexión"),
    DASHBOARD("Dashboard"),
    ALERTS("Alertas Supervisor"),
    PROJECTS("Proyectos"),
    TERMINALS("Terminales"),
    CONSOLE("Procesos"),
    PTY_TERMINAL("Terminal interactiva"),
    DELIVERIES("Entregas"),
    REMOTE("Escritorio Remoto"),
    SETTINGS("Ajustes")
}

@OptIn(ExperimentalMaterial3Api::class, androidx.compose.animation.ExperimentalAnimationApi::class)
@Composable
fun AppNavigation() {
    val mainViewModel: MainViewModel = viewModel()
    val darkThemeEnabled by mainViewModel.darkThemeEnabled.collectAsState()

    var currentScreen by remember { mutableStateOf(Screen.TERMINALS) }
    var previousScreen by remember { mutableStateOf<Screen?>(null) }
    val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
    val scope = rememberCoroutineScope()

    val connectionViewModel: ConnectionViewModel = viewModel()
    val alertsViewModel: AlertsViewModel = viewModel()
    val projectsViewModel: ProjectsViewModel = viewModel()
    val terminalsViewModel: TerminalsViewModel = viewModel()
    val consoleViewModel: ConsoleViewModel = viewModel()
    val ptyTerminalViewModel: PtyTerminalViewModel = viewModel()
    val remoteViewModel: RemoteScreenViewModel = viewModel()
    val deliveriesViewModel: DeliveriesViewModel = viewModel()

    // Interactive terminal target chosen from the Terminals screen. The
    // Terminals screen creates the PTY first and passes its id; null would
    // mean "create a new one" for callers that navigate directly.
    var ptyTerminalTargetId by remember { mutableStateOf<String?>(null) }

    // Notification taps arrive before/after Compose starts; MainActivity parks
    // the requested screen here and this effect routes to it once.
    val requestedScreen by AppNavigationIntents.pending.collectAsState()
    LaunchedEffect(requestedScreen) {
        val target = requestedScreen ?: return@LaunchedEffect
        AppNavigationIntents.clear()
        previousScreen = currentScreen
        currentScreen = target
    }

    // Central error surface: every ViewModel's errorMessage is observed here and
    // shown via a single shared snackbar, instead of wiring it in each screen.
    val snackbarHostState = remember { SnackbarHostState() }
    ObserveViewModelError(mainViewModel.errorMessage, snackbarHostState, mainViewModel::clearError)
    ObserveViewModelError(connectionViewModel.errorMessage, snackbarHostState, connectionViewModel::clearError)
    ObserveViewModelError(alertsViewModel.errorMessage, snackbarHostState, alertsViewModel::clearError)
    ObserveViewModelError(projectsViewModel.errorMessage, snackbarHostState, projectsViewModel::clearError)
    ObserveViewModelError(terminalsViewModel.errorMessage, snackbarHostState, terminalsViewModel::clearError)
    ObserveViewModelError(consoleViewModel.errorMessage, snackbarHostState, consoleViewModel::clearError)
    ObserveViewModelError(deliveriesViewModel.errorMessage, snackbarHostState, deliveriesViewModel::clearError)

    // Manejo de BackButton del sistema
    androidx.activity.compose.BackHandler(enabled = drawerState.isOpen || currentScreen != Screen.TERMINALS) {
        if (drawerState.isOpen) {
            scope.launch { drawerState.close() }
        } else if (currentScreen == Screen.CONNECTION) {
            currentScreen = previousScreen ?: Screen.WELCOME
        } else if (currentScreen == Screen.PTY_TERMINAL) {
            // Back leaves the PTY running on the PC; return to its launcher.
            currentScreen = Screen.TERMINALS
        } else if (currentScreen != Screen.TERMINALS && currentScreen != Screen.WELCOME) {
            currentScreen = Screen.TERMINALS
        }
    }

    val navigateTo = { newScreen: Screen ->
        previousScreen = currentScreen
        currentScreen = newScreen
        scope.launch { drawerState.close() }
    }

    IDUPITheme(darkTheme = darkThemeEnabled) {
        Box(modifier = Modifier.fillMaxSize()) {
        androidx.compose.animation.AnimatedContent(
            targetState = currentScreen,
            transitionSpec = {
                (fadeIn() + slideInHorizontally { width -> width / 4 }) togetherWith
                        (fadeOut() + slideOutHorizontally { width -> -width / 4 })
            },
            label = "screen_transition"
        ) { targetScreen ->
            when (targetScreen) {
                Screen.WELCOME -> WelcomeScreen(
                    onEnterDemo = {
                        mainViewModel.refreshStatus()
                        navigateTo(Screen.TERMINALS)
                    },
                    onConfigureConnection = {
                        navigateTo(Screen.CONNECTION)
                    }
                )
                Screen.CONNECTION -> ConnectionScreen(
                    viewModel = connectionViewModel,
                    onBack = { currentScreen = previousScreen ?: Screen.WELCOME },
                    onContinue = {
                        mainViewModel.refreshStatus()
                        navigateTo(Screen.TERMINALS)
                    }
                )
                else -> {
                    ModalNavigationDrawer(
                        drawerState = drawerState,
                        // Swipe-to-open hijacked ordinary horizontal drags
                        // (terminal pan, chip rows, lists). Every screen has a
                        // menu button, so the drawer opens only from it; once
                        // open, swiping still closes it.
                        gesturesEnabled = drawerState.isOpen,
                        drawerContent = {
                            ModalDrawerSheet(
                                drawerContainerColor = SlateCard,
                                drawerContentColor = TextPrimary,
                                modifier = Modifier.width(300.dp)
                            ) {
                                Spacer(modifier = Modifier.height(20.dp))
                                
                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(20.dp),
                                    verticalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    Text(
                                        text = "IDUPI",
                                        color = PrimaryIndigo,
                                        style = AppTypography.displayLarge
                                    )
                                    Text(
                                        text = "Cliente Conectado",
                                        color = TextPrimary,
                                        style = AppTypography.bodyLarge.copy(fontWeight = FontWeight.Bold)
                                    )
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                                    ) {
                                        Box(
                                            modifier = Modifier
                                                .size(8.dp)
                                                .clip(RoundedCornerShape(4.dp))
                                                .background(StatusConnected)
                                        )
                                        Text(
                                            text = "Servidor PC conectado",
                                            color = StatusConnected,
                                            style = AppTypography.labelSmall
                                        )
                                    }
                                }

                                HorizontalDivider(color = SlateBorder, modifier = Modifier.padding(horizontal = 20.dp))
                                Spacer(modifier = Modifier.height(10.dp))

                                // Drawer destination order for the reshaped app:
                                // live terminals first, then the surfaces that
                                // replaced chat.
                                val navItems = listOf(
                                    Screen.TERMINALS to Icons.Default.Terminal,
                                    Screen.DELIVERIES to Icons.Default.FileDownload,
                                    Screen.CONSOLE to Icons.Default.List,
                                    Screen.DASHBOARD to Icons.Default.Dashboard,
                                    Screen.PROJECTS to Icons.Default.Folder,
                                    Screen.ALERTS to Icons.Default.Notifications,
                                    Screen.REMOTE to Icons.AutoMirrored.Filled.ScreenShare,
                                    Screen.SETTINGS to Icons.Default.Settings
                                )

                                navItems.forEach { (screen, icon) ->
                                    val isSelected = targetScreen == screen
                                    val itemTextClr = if (isSelected) PrimaryIndigo else TextPrimary
                                    
                                    NavigationDrawerItem(
                                        icon = {
                                            Box {
                                                Icon(imageVector = icon, contentDescription = screen.title, tint = itemTextClr)
                                                if (screen == Screen.ALERTS && alertsViewModel.unreadCount > 0) {
                                                    Box(
                                                        modifier = Modifier
                                                            .size(8.dp)
                                                            .clip(RoundedCornerShape(4.dp))
                                                            .background(StatusError)
                                                            .align(Alignment.TopEnd)
                                                    )
                                                }
                                            }
                                        },
                                        label = { Text(screen.title, fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium) },
                                        selected = isSelected,
                                        onClick = { navigateTo(screen) },
                                        colors = NavigationDrawerItemDefaults.colors(
                                            unselectedContainerColor = Color.Transparent,
                                            selectedContainerColor = PrimaryIndigo.copy(alpha = 0.15f),
                                            selectedIconColor = PrimaryIndigo,
                                            selectedTextColor = PrimaryIndigo,
                                            unselectedIconColor = TextSecondary,
                                            unselectedTextColor = TextPrimary
                                        ),
                                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 2.dp)
                                    )
                                }

                                Spacer(modifier = Modifier.weight(1f))

                                Text(
                                    text = "IDUPI v${com.idupi.app.BuildConfig.VERSION_NAME}",
                                    color = TextSecondary.copy(alpha = 0.5f),
                                    style = AppTypography.labelSmall,
                                    modifier = Modifier.padding(20.dp)
                                )
                            }
                        }
                    ) {
                        when (targetScreen) {
                            Screen.DASHBOARD -> DashboardScreen(
                                viewModel = mainViewModel,
                                onMenuClick = { scope.launch { drawerState.open() } }
                            )
                            Screen.TERMINALS -> TerminalsScreen(
                                viewModel = terminalsViewModel,
                                onMenuClick = { scope.launch { drawerState.open() } },
                                onOpenTerminal = { id ->
                                    ptyTerminalTargetId = id
                                    navigateTo(Screen.PTY_TERMINAL)
                                }
                            )
                            Screen.ALERTS -> AlertsScreen(
                                viewModel = alertsViewModel,
                                onMenuClick = { scope.launch { drawerState.open() } }
                            )
                            Screen.PROJECTS -> ProjectsScreen(
                                viewModel = projectsViewModel,
                                onMenuClick = { scope.launch { drawerState.open() } }
                            )
                            Screen.CONSOLE -> ConsoleScreen(
                                viewModel = consoleViewModel,
                                onMenuClick = { scope.launch { drawerState.open() } }
                            )
                            Screen.PTY_TERMINAL -> PtyTerminalScreen(
                                viewModel = ptyTerminalViewModel,
                                ptyId = ptyTerminalTargetId,
                                onBack = { currentScreen = Screen.TERMINALS }
                            )
                            Screen.DELIVERIES -> DeliveriesScreen(
                                viewModel = deliveriesViewModel,
                                onMenuClick = { scope.launch { drawerState.open() } }
                            )
                            Screen.REMOTE -> RemoteScreen(
                                viewModel = remoteViewModel,
                                onMenuClick = { scope.launch { drawerState.open() } }
                            )
                            Screen.SETTINGS -> SettingsScreen(
                                mainViewModel = mainViewModel,
                                onMenuClick = { scope.launch { drawerState.open() } },
                                onNavigateToConnection = { navigateTo(Screen.CONNECTION) },
                                onNavigateToWelcome = { navigateTo(Screen.WELCOME) }
                            )
                            else -> {}
                        }
                    }
                }
            }
        }

        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(AppSpacing.lg)
        ) { data ->
            Snackbar(
                modifier = Modifier.clip(AppShapes.card),
                containerColor = SlateCard,
                contentColor = TextPrimary,
                shape = AppShapes.card
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(AppSpacing.sm)
                ) {
                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .clip(RoundedCornerShape(4.dp))
                            .background(StatusError)
                    )
                    Text(text = data.visuals.message, color = TextPrimary)
                }
            }
        }
        }
    }
}

/**
 * Observes a ViewModel's error stream and shows it as a snackbar once,
 * clearing it afterward so it does not re-show on recomposition.
 * Centralizes error display so screens do not each wire their own snackbar.
 */
@Composable
private fun ObserveViewModelError(
    errorMessage: StateFlow<String?>,
    snackbarHostState: SnackbarHostState,
    onShown: () -> Unit
) {
    val error by errorMessage.collectAsState()
    LaunchedEffect(error) {
        val message = error
        if (message != null) {
            snackbarHostState.showSnackbar(message = message)
            onShown()
        }
    }
}
