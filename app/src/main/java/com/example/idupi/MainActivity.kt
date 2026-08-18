package com.example.idupi

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.example.idupi.ui.navigation.AppNavigation

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // Auto-cargar perfil de conexión guardado si existe
        val savedProfile = com.example.idupi.data.connection.ConnectionStorage(this).getProfile()
        if (savedProfile != null && savedProfile.host.isNotBlank()) {
            com.example.idupi.data.IduPiClientProvider.configureRealClient(savedProfile)
        }

        setContent {
            // Theme wrapping is handled inside AppNavigation
            // to respect the dynamic dark/light toggle from MainViewModel.
            AppNavigation()
        }
    }
}
