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
    CHAT("Chat Directo"),
    ALERTS("Alertas Supervisor"),
    ORCHESTRATOR("Orquestador SDD"),
    PROJECTS("Proyectos"),
    SESSIONS("Sesiones"),
    CONSOLE("Terminal"),
    REMOTE("Escritorio Remoto"),
    SETTINGS("Ajustes")
}

@OptIn(ExperimentalMaterial3Api::class, androidx.compose.animation.ExperimentalAnimationApi::class)
@Composable
fun AppNavigation() {
    val mainViewModel: MainViewModel = viewModel()
    val darkThemeEnabled by mainViewModel.darkThemeEnabled.collectAsState()

    var currentScreen by remember { mutableStateOf(Screen.CHAT) }
    var previousScreen by remember { mutableStateOf<Screen?>(null) }
    val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
    val scope = rememberCoroutineScope()

    val connectionViewModel: ConnectionViewModel = viewModel()
    val chatViewModel: ChatViewModel = viewModel()
    val alertsViewModel: AlertsViewModel = viewModel()
    val orchestratorViewModel: OrchestratorViewModel = viewModel()
    val projectsViewModel: ProjectsViewModel = viewModel()
    val sessionsViewModel: SessionsViewModel = viewModel()
    val consoleViewModel: ConsoleViewModel = viewModel()
    val remoteViewModel: RemoteScreenViewModel = viewModel()

    // Central error surface: every ViewModel's errorMessage is observed here and
    // shown via a single shared snackbar, instead of wiring it in each screen.
    val snackbarHostState = remember { SnackbarHostState() }
    ObserveViewModelError(mainViewModel.errorMessage, snackbarHostState, mainViewModel::clearError)
    ObserveViewModelError(connectionViewModel.errorMessage, snackbarHostState, connectionViewModel::clearError)
    ObserveViewModelError(chatViewModel.errorMessage, snackbarHostState, chatViewModel::clearError)
    ObserveViewModelError(alertsViewModel.errorMessage, snackbarHostState, alertsViewModel::clearError)
    ObserveViewModelError(projectsViewModel.errorMessage, snackbarHostState, projectsViewModel::clearError)
    ObserveViewModelError(sessionsViewModel.errorMessage, snackbarHostState, sessionsViewModel::clearError)
    ObserveViewModelError(consoleViewModel.errorMessage, snackbarHostState, consoleViewModel::clearError)

    // Manejo de BackButton del sistema
    androidx.activity.compose.BackHandler(enabled = drawerState.isOpen || currentScreen != Screen.CHAT) {
        if (drawerState.isOpen) {
            scope.launch { drawerState.close() }
        } else if (currentScreen == Screen.CONNECTION) {
            currentScreen = previousScreen ?: Screen.WELCOME
        } else if (currentScreen != Screen.CHAT && currentScreen != Screen.WELCOME) {
            currentScreen = Screen.CHAT
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
                        navigateTo(Screen.CHAT)
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
                        navigateTo(Screen.CHAT)
                    }
                )
                else -> {
                    ModalNavigationDrawer(
                        drawerState = drawerState,
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
                                            text = "Orquestador Activo",
                                            color = StatusConnected,
                                            style = AppTypography.labelSmall
                                        )
                                    }
                                }

                                HorizontalDivider(color = SlateBorder, modifier = Modifier.padding(horizontal = 20.dp))
                                Spacer(modifier = Modifier.height(10.dp))

                                // Drawer order documented in tasks.md §5.3: [Pantalla Remota, Chat,
                                // Proyectos, Orquestador SDD, Configuracion]. Keeping that subset in the
                                // exact relative order shown there; the additional items (Alerts,
                                // Dashboard, Sessions, Console) stay in their slots and are not part
                                // of the documented order.
                                val navItems = listOf(
                                    Screen.REMOTE to Icons.AutoMirrored.Filled.ScreenShare,
                                    Screen.CHAT to Icons.Default.Chat,
                                    Screen.PROJECTS to Icons.Default.Folder,
                                    Screen.ORCHESTRATOR to Icons.Default.AccountTree,
                                    Screen.ALERTS to Icons.Default.Notifications,
                                    Screen.DASHBOARD to Icons.Default.Dashboard,
                                    Screen.SESSIONS to Icons.Default.History,
                                    Screen.CONSOLE to Icons.Default.Terminal,
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
                                onMenuClick = { scope.launch { drawerState.open() } },
                                onNavigateToChat = { navigateTo(Screen.CHAT) },
                                onNavigateToConsole = { navigateTo(Screen.CONSOLE) }
                            )
                            Screen.CHAT -> ChatScreen(
                                viewModel = chatViewModel,
                                mainViewModel = mainViewModel,
                                onMenuClick = { scope.launch { drawerState.open() } }
                            )
                            Screen.ALERTS -> AlertsScreen(
                                viewModel = alertsViewModel,
                                onMenuClick = { scope.launch { drawerState.open() } }
                            )
                            Screen.ORCHESTRATOR -> OrchestratorScreen(
                                viewModel = orchestratorViewModel,
                                onMenuClick = { scope.launch { drawerState.open() } }
                            )
                            Screen.PROJECTS -> ProjectsScreen(
                                viewModel = projectsViewModel,
                                onMenuClick = { scope.launch { drawerState.open() } }
                            )
                            Screen.SESSIONS -> SessionsScreen(
                                viewModel = sessionsViewModel,
                                onMenuClick = { scope.launch { drawerState.open() } },
                                onSessionSelect = { sessionId ->
                                    chatViewModel.loadSessionHistory(sessionId)
                                    navigateTo(Screen.CHAT)
                                }
                            )
                            Screen.CONSOLE -> ConsoleScreen(
                                viewModel = consoleViewModel,
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
