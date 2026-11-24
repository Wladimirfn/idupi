package com.idupi.app.ui.screens.commands

/** One PC-side command shown in the IDUPI command reference. */
data class IdupiCommand(
    val title: String,
    val command: String,
    val description: String
)

/** A titled group of related commands. */
data class IdupiCommandSection(
    val title: String,
    val commands: List<IdupiCommand>
)

/**
 * Static reference for the commands the PC side exposes. Kept apart from the
 * Compose screen so it can be unit-tested on the JVM and updated without
 * touching UI code. [IdupiCommand.command] is copied verbatim to the
 * clipboard, so it must stay exactly as the PC expects it.
 */
object IdupiCommandCatalog {

    val sections: List<IdupiCommandSection> = listOf(
        IdupiCommandSection(
            title = "Servidor",
            commands = listOf(
                IdupiCommand(
                    title = "Levantar el servidor",
                    command = "powershell -ExecutionPolicy Bypass -File idupi-server\\scripts\\start-server.ps1",
                    description = "Inicia el servidor en el puerto 8788."
                ),
                IdupiCommand(
                    title = "Reiniciar el servidor",
                    command = "powershell -ExecutionPolicy Bypass -File idupi-server\\scripts\\start-server.ps1 -Restart",
                    description = "Detiene el servidor actual y lo vuelve a iniciar."
                ),
                IdupiCommand(
                    title = "Inicio automático con Windows",
                    command = "powershell -ExecutionPolicy Bypass -File idupi-server\\scripts\\install-autostart.ps1",
                    description = "Levanta el servidor al iniciar sesión. Con -Uninstall lo quita."
                ),
                IdupiCommand(
                    title = "Instalar sensores (admin)",
                    command = "powershell -ExecutionPolicy Bypass -File idupi-server\\scripts\\install-sensors-helper.ps1",
                    description = "Temperaturas y consumo en el Dashboard. Requiere PowerShell como administrador."
                )
            )
        ),
        IdupiCommandSection(
            title = "Terminales (idupi-term)",
            commands = listOf(
                IdupiCommand(
                    title = "Abrir una terminal",
                    command = "idupi-term",
                    description = "Lista y conecta (crea una si no hay vivas)."
                ),
                IdupiCommand(
                    title = "Listar terminales",
                    command = "idupi-term ls",
                    description = "Lista las terminales."
                ),
                IdupiCommand(
                    title = "Crear terminal en una carpeta",
                    command = "idupi-term new --cwd C:\\ruta\\proyecto",
                    description = "Crea una terminal en esa carpeta y se conecta."
                ),
                IdupiCommand(
                    title = "Conectar a una terminal",
                    command = "idupi-term attach 1",
                    description = "Conecta a una terminal (id, prefijo o número). Ctrl+] desconecta sin cerrarla."
                ),
                IdupiCommand(
                    title = "Vista web de terminales",
                    command = "idupi-term web",
                    description = "Abre la vista web de las terminales en el navegador."
                )
            )
        ),
        IdupiCommandSection(
            title = "Entregas (idupi-push)",
            commands = listOf(
                IdupiCommand(
                    title = "Publicar un APK",
                    command = "idupi-push app\\build\\outputs\\apk\\release\\app-release.apk",
                    description = "Publica un APK para instalarlo en el teléfono."
                ),
                IdupiCommand(
                    title = "Listar entregas",
                    command = "idupi-push ls",
                    description = "Lista las entregas publicadas."
                )
            )
        ),
        IdupiCommandSection(
            title = "Flota idu-pi",
            commands = listOf(
                IdupiCommand(
                    title = "Abrir el panel de perfiles",
                    command = "idupi",
                    description = "Abre el panel TUI de perfiles y modelos."
                ),
                IdupiCommand(
                    title = "Estado de la flota",
                    command = "idu-pi status",
                    description = "Muestra las CLIs detectadas y los perfiles."
                ),
                IdupiCommand(
                    title = "Cuota de las CLIs",
                    command = "idu-pi quota",
                    description = "Cuota de cada CLI."
                )
            )
        ),
        IdupiCommandSection(
            title = "Teléfono (idupi-phone)",
            commands = listOf(
                IdupiCommand(
                    title = "Estado del teléfono",
                    command = "idupi-phone status",
                    description = "Batería, señal, red y uso de hoy."
                ),
                IdupiCommand(
                    title = "Leer la pantalla",
                    command = "idupi-phone screen",
                    description = "Devuelve la pantalla visible como texto."
                ),
                IdupiCommand(
                    title = "Abrir una app",
                    command = "idupi-phone open whatsapp",
                    description = "Abre una app por nombre o paquete."
                ),
                IdupiCommand(
                    title = "Tocar un elemento",
                    command = "idupi-phone tap \"Enviar\"",
                    description = "Toca un elemento por su texto visible."
                ),
                IdupiCommand(
                    title = "Escribir y enviar",
                    command = "idupi-phone type \"hola\" --submit",
                    description = "Escribe en el campo activo y presiona enviar."
                ),
                IdupiCommand(
                    title = "Volver atrás",
                    command = "idupi-phone back",
                    description = "Ejecuta el botón Atrás del sistema."
                )
            )
        ),
        IdupiCommandSection(
            title = "Compilar la app",
            commands = listOf(
                IdupiCommand(
                    title = "Generar el APK firmado",
                    command = ".\\gradlew :app:assembleRelease",
                    description = "Genera el APK firmado."
                )
            )
        )
    )
}
