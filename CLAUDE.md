# Pupa — App Android personal (Samsung S24)

App de autodisciplina para uso personal, nunca publicada. Detecta uso excesivo de redes sociales, fuerza escala de grises y monitorea YouTube.

---

## Stack

- **Kotlin** nativo, Android Studio
- `minSdk 29` (Android 10), `targetSdk 34`, AGP 8.2.2, Kotlin 1.9.22
- Package: `com.personal.selfcontrol`
- Sin coroutines — usa `Handler(Looper.getMainLooper())`
- Sin librerías externas — solo AndroidX (core-ktx, appcompat, material, constraintlayout, recyclerview)
- `viewBinding = true`

---

## Las tres features

### Feature 1 — Timer de 15 minutos con bloqueo diario

**Apps monitoreadas:**
- Instagram: `com.instagram.android`
- Chrome: `com.android.chrome`
- TikTok: `com.zhiliaoapp.musically`, `com.ss.android.ugc.trill`, `com.zhiliaoapp.musically.go`, `com.ss.android.ugc.trill.lite`

**Flujo:**
1. Usuario abre app monitoreada → se inicia timer de 15 min continuos (`TIMER_LIMIT_MS = 15 * 60 * 1000L`)
2. Si sale antes de 15 min → timer se cancela (se reinicia al volver)
3. Si pasan 15 min → `performGlobalAction(GLOBAL_ACTION_HOME)` + `blockAppUntilMidnight(pkg)` (silencioso, sin sonido)
4. Si intenta reabrir la misma app ese mismo día → `playRandomSound()` + kick al home
5. A la medianoche el bloqueo se auto-expira (timestamp en SharedPreferences)
6. Contadores independientes por app

**Clave:** el timer solo cuenta mientras la pantalla está encendida (`PowerManager.isInteractive()`). Si el celular está en el bolsillo y el timer expira, no hace nada.

**Reset de bloqueos al reinstalar:** `App.onCreate()` compara `PackageManager.getPackageInfo().lastUpdateTime` con el timestamp guardado en prefs. Si cambió (nueva instalación desde Android Studio), llama a `prefs.clearAllBlocks()` y guarda el nuevo timestamp. Esto permite testear reinstalando sin quedar bloqueado.

### Feature 2 — Escala de grises permanente con excepciones

Siempre activo, EXCEPTO en estas apps (color normal):
- Galería Samsung: `com.sec.android.gallery3d`
- Google Photos: `com.google.android.apps.photos`
- Cámara Samsung: `com.sec.android.app.camera`
- WhatsApp: `com.whatsapp`
- Google Maps: `com.google.android.apps.maps`
- Waze: `com.waze`
- Netflix: `com.netflix.mediaclient`
- YouTube: `com.google.android.youtube` (ver Feature 3 para excepción de Shorts)
- Crunchyroll: `com.crunchyroll.crunchyroid`

Usa `Settings.Secure`: `accessibility_display_daltonizer_enabled=1` y `accessibility_display_daltonizer=0`.

Un `ContentObserver` vigila el setting en tiempo real: si el usuario lo apaga manualmente, lo vuelve a activar al instante. Excepción: si está en una app de `COLOR_ALLOWED_APPS`, no lo reactiva.

**Requiere ADB (una vez):**
```bash
adb shell pm grant com.personal.selfcontrol android.permission.WRITE_SECURE_SETTINGS
```

### Feature 3 — YouTube: forzar horizontal + penalizar Shorts

**Al abrir YouTube:**
- Se fuerza landscape (`ACCELEROMETER_ROTATION=0`, `USER_ROTATION=1`)
- Se registra un `BroadcastReceiver` para `ACTION_CONFIGURATION_CHANGED`

**Detección de Shorts (portrait):**
- Cuando Shorts fuerza vertical, `ACTION_CONFIGURATION_CHANGED` dispara
- `resources.configuration.orientation == ORIENTATION_PORTRAIT` → **grayscale ON** + timer de 15 min (`YOUTUBE_PORTRAIT_TIMER_MS = 15 * 60 * 1000L`)
- Si el timer expira: `soundManager.playRandomSound()`

**Al volver a landscape (salir de Shorts):**
- Grayscale OFF, timer cancelado
- Nuestro forced landscape vuelve a tomar efecto

**Al salir de YouTube:**
- Se desregistra el receiver
- Se cancela el timer de Shorts
- `rotationManager.restore()` restaura la rotación original

---

## Arquitectura

Motor central: `AccessibilityService` (`TYPE_WINDOW_STATE_CHANGED`) en `AppMonitorService.kt`.
`notificationTimeout="0"` en el XML de configuración para respuesta inmediata.

En cada cambio de app en primer plano → `handleForegroundChange(newPkg)` que despacha las 3 features.

### Filtrado de paquetes (crítico)

Samsung dispara eventos de sistema constantemente. Para no romper los timers:

- `ALWAYS_IGNORED`: systemui, biometrics, cocktailbar, etc. → ignorados siempre
- `MONITORED_APPS` / `COLOR_ALLOWED_APPS` → nunca ignorados
- `HOME_PACKAGES`: launchers conocidos → nunca ignorados (necesario para detectar cuando el usuario vuelve al home)
- `systemPackageCache`: cache de `FLAG_SYSTEM` por paquete (evita llamadas repetidas a PackageManager)

**CRÍTICO:** el `accessibility_service_config.xml` NO tiene `android:packageNames`. Si se restringiera a paquetes específicos, el servicio no recibiría el evento cuando el usuario *sale* de esas apps y el timer nunca se detendría.

### Detección del servicio de accesibilidad

Usa `AccessibilityManager.getEnabledAccessibilityServiceList()` — NO parsear el string de `ENABLED_ACCESSIBILITY_SERVICES`. Samsung puede guardar el nombre en formato corto o largo y el string parsing falla.

### Sonidos de castigo

- `AudioAttributes.USAGE_ALARM`: suena aunque el teléfono esté en silencio/vibración
- Solo sube `STREAM_ALARM` al máximo (NO tocar STREAM_MUSIC — cambia el volumen de música permanentemente)
- Los botones físicos de volumen controlan `STREAM_ALARM` durante la reproducción → el usuario puede bajar el volumen
- Archivos guardados en `filesDir/sounds/`
- Formatos: mp3, m4a, wav, ogg, opus (audios de WhatsApp), aac

### Importar sonidos

Dos formas:
1. Desde la app con el botón "+ Agregar" (file picker)
2. Compartir desde WhatsApp u otra app → la app aparece en el menú "Compartir" con `audio/*`

La activity tiene `launchMode="singleTop"` + maneja `onNewIntent` para el caso de compartir cuando la app ya estaba abierta.

---

## Estructura de archivos

```
app/src/main/
├── AndroidManifest.xml
├── java/com/personal/selfcontrol/
│   ├── App.kt                    — NotificationChannel + reset de bloqueos al reinstalar
│   ├── MainActivity.kt           — UI: permisos, status apps, sonidos, historial
│   ├── data/
│   │   ├── Models.kt             — data class ForceCloseRecord
│   │   └── PrefsManager.kt       — SharedPreferences: bloqueos, historial, sonidos, install time
│   ├── service/
│   │   └── AppMonitorService.kt  — AccessibilityService central
│   ├── managers/
│   │   ├── GrayscaleManager.kt   — Settings.Secure para blanco/negro
│   │   ├── RotationManager.kt    — Settings.System para rotación
│   │   └── SoundManager.kt       — biblioteca de sonidos + reproducción
│   └── receiver/
│       └── BootReceiver.kt       — notifica si el servicio quedó deshabilitado
└── res/
    ├── drawable/
    │   ├── ic_launcher_foreground.xml  — bitmap wrapper → @drawable/ic_launcher_logo
    │   └── ic_notification.xml         — ícono blanco para notificaciones
    ├── drawable-nodpi/
    │   └── ic_launcher_logo.png        — logo 512×512 (adaptive icon foreground)
    ├── mipmap-{mdpi,hdpi,xhdpi,xxhdpi,xxxhdpi}/
    │   ├── ic_launcher.png
    │   └── ic_launcher_round.png
    ├── mipmap-anydpi-v26/
    │   ├── ic_launcher.xml             — adaptive icon (bg #090718 + logo)
    │   └── ic_launcher_round.xml
    ├── layout/
    │   ├── activity_main.xml
    │   ├── item_sound.xml
    │   └── item_history.xml
    ├── values/
    │   ├── strings.xml   — app_name="Pupa", service_label="Pupa Monitor"
    │   ├── colors.xml    — ic_launcher_background=#FF090718
    │   └── themes.xml
    └── xml/
        └── accessibility_service_config.xml  — notificationTimeout="0", sin packageNames
```

---

## Permisos

| Permiso | Cómo se otorga |
|---|---|
| `BIND_ACCESSIBILITY_SERVICE` | Ajustes > Accesibilidad > Pupa Monitor |
| `WRITE_SETTINGS` | Desde la app (botón abre la pantalla correcta) |
| `WRITE_SECURE_SETTINGS` | **Una sola vez por ADB** (ver abajo) |
| `MODIFY_AUDIO_SETTINGS` | Automático |
| `READ_MEDIA_AUDIO` | Runtime (Android 13+) |
| `FOREGROUND_SERVICE` | Automático |
| `RECEIVE_BOOT_COMPLETED` | Automático |
| `POST_NOTIFICATIONS` | Runtime (Android 13+) |
| `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` | Desde la app |

---

## Setup inicial después de instalar

```bash
adb shell pm grant com.personal.selfcontrol android.permission.WRITE_SECURE_SETTINGS

# Verificar que se otorgó
adb shell dumpsys package com.personal.selfcontrol | grep WRITE_SECURE
```

**En el teléfono (checklist):**
1. Ajustes > Accesibilidad > Apps instaladas > **Pupa Monitor** → Activar
2. Desde la app: botón "Modificar ajustes del sistema" → Activar
3. Desde la app: botón "Optimización de batería" → **Sin restricciones** (crítico en Samsung — sin esto el servicio se muere)
4. Correr el comando ADB de arriba

---

## Instalación en Samsung S24

1. Activar modo desarrollador: Ajustes > Acerca del teléfono > Información de software > tocar "Número de compilación" 7 veces
2. Ajustes > Opciones de desarrollador > Depuración USB: ON
3. Si aparece "blocked by Auto Blocker": Ajustes > Seguridad y privacidad > Auto Blocker > desactivar
4. Conectar por USB, aceptar el popup de depuración
5. Android Studio > ▶ Run → instala directo al teléfono
6. Correr comando ADB de WRITE_SECURE_SETTINGS (terminal de Android Studio)
7. Auto Blocker se puede volver a activar después de instalar

---

## Bugs resueltos (no volver a introducir)

- **Paquetes de sistema de Samsung resetean el timer:** resuelto con `isIgnorablePackage()` usando cache de `FLAG_SYSTEM` + lista `ALWAYS_IGNORED` + `HOME_PACKAGES`
- **Timer dispara con pantalla apagada:** resuelto con `PowerManager.isInteractive()` check en el Runnable
- **MediaPlayer crash por doble release:** `setOnCompletionListener { mp -> mp.release(); mediaPlayer = null }`
- **Rotación queda atascada en landscape al reiniciar el servicio:** resuelto con `resetToAutoRotate()` en `onServiceConnected()`
- **Launcher Samsung es FLAG_SYSTEM y se ignoraba:** resuelto con `HOME_PACKAGES` hardcodeado + fallback en `launcherPackage`
- **STREAM_MUSIC no debe subirse:** solo `STREAM_ALARM` — si se sube STREAM_MUSIC cambia el volumen de música permanentemente
- **Detección de accesibilidad con string parsing:** falla en Samsung (formato largo vs corto). Usar `AccessibilityManager.getEnabledAccessibilityServiceList()`
- **Bloqueos persisten entre sesiones de testing:** resuelto comparando `lastUpdateTime` del paquete en `App.onCreate()` — cada build nuevo desde Android Studio limpia los bloqueos

---

## Batería

Diseño event-driven, sin polling:
- AccessibilityService duerme entre eventos
- Timer: un único `Handler.postDelayed`, no loop
- ContentObserver del grayscale: costo cero cuando no cambia el setting
- BroadcastReceiver de orientación: solo registrado mientras YouTube está en primer plano
- Sin WakeLock, sin permisos de red
