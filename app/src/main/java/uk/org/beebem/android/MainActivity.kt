package uk.org.beebem.android

import android.content.res.Configuration
import android.os.Bundle
import android.util.Log
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import uk.org.beebem.android.ui.theme.BeebEmForAndroidTheme
import java.io.File

enum class InputMode { NONE, KEYBOARD, JOYPAD }

class MainActivity : ComponentActivity() {

    private var glView: BeebGLSurfaceView? = null

    // Set to true by any screen that contains a text input (e.g. BbcSearchScreen).
    // dispatchKeyEvent defers to Compose while this is true so typing works normally.
    internal var textInputActive = false

    // Physical controller → BBC keys, using the current per-disc joypad mapping.
    internal val gamepad = GamepadInput()

    // True while a full-screen overlay (menu, game picker, search, snapshots)
    // is showing; controller input is then left to Android as normal.
    internal var overlayActive = false
        set(value) {
            if (value && !field) gamepad.releaseAll()
            field = value
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        hideSystemBars()

        val ok = BeebEmNative.nativeInit(assets, filesDir.absolutePath)
        Log.i("BeebEm-For-Android", "nativeInit returned $ok")

        setContent {
            BeebEmForAndroidTheme {
                if (ok) {
                    EmulatorLayout(onViewCreated = { glView = it })
                }
            }
        }
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (textInputActive) return super.dispatchKeyEvent(event)

        if (!overlayActive && gamepad.isGamepadEvent(event) && gamepad.onKeyEvent(event)) return true

        val bbc = HARDWARE_KEY_MAP[event.keyCode] ?: return super.dispatchKeyEvent(event)

        if (bbc == BBC_BREAK) {
            if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
                val withShift = event.keyCode == KeyEvent.KEYCODE_F12 ||
                        event.isShiftPressed
                BeebEmNative.nativeBreakKey(withShift)
            }
            return true
        }

        when (event.action) {
            KeyEvent.ACTION_DOWN -> if (event.repeatCount == 0)
                BeebEmNative.nativeKeyDown(bbc.row, bbc.col)
            KeyEvent.ACTION_UP   -> BeebEmNative.nativeKeyUp(bbc.row, bbc.col)
        }
        return true
    }

    override fun dispatchGenericMotionEvent(event: MotionEvent): Boolean {
        if (!textInputActive && !overlayActive && gamepad.onMotionEvent(event)) return true
        return super.dispatchGenericMotionEvent(event)
    }

    // Full-screen: hide the status and navigation bars; a swipe from the
    // edge shows them temporarily.
    private fun hideSystemBars() {
        WindowCompat.getInsetsController(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }

    override fun onResume() {
        super.onResume()
        hideSystemBars()
        glView?.resumeEmulation()
    }

    override fun onPause() {
        super.onPause()
        gamepad.releaseAll()
        glView?.pauseEmulation()
    }

    override fun onDestroy() {
        super.onDestroy()
        BeebEmNative.nativeShutdown()
    }
}

// Toolbar auto-hide delay, and the height of the touch strip at the top of the
// screen that shows it again.
private const val TOOLBAR_HIDE_MS = 3000L
private val TOOLBAR_TOUCH_STRIP = 56.dp

@Composable
private fun EmulatorLayout(onViewCreated: (BeebGLSurfaceView) -> Unit) {
    val context = LocalContext.current
    val activity = context as? MainActivity
    val view = remember { BeebGLSurfaceView(context).also { onViewCreated(it) } }
    val scope = rememberCoroutineScope()

    val isLandscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE

    // With a game controller connected, start with the picture full height
    // rather than sharing the screen with the on-screen keyboard.
    var inputMode by remember {
        mutableStateOf(if (isLandscape && !hasGameController()) InputMode.KEYBOARD else InputMode.NONE)
    }

    val prefs = remember { context.getSharedPreferences("display", android.content.Context.MODE_PRIVATE) }
    var aspectMode by remember {
        mutableStateOf(
            runCatching { AspectMode.valueOf(prefs.getString("aspect", null) ?: "") }
                .getOrDefault(AspectMode.BBC)
        )
    }
    LaunchedEffect(aspectMode) { view.setAspectMode(aspectMode) }

    var disc0LocalFile by remember { mutableStateOf<File?>(null) }
    val disc0Name = disc0LocalFile?.name
    // The file the disc was loaded from (Load Disc), so writes such as saved
    // games are copied back to it. Null for catalogue/search downloads, which
    // live in app storage already.
    var disc0SourceUri by remember { mutableStateOf<android.net.Uri?>(null) }

    var showGamePicker  by remember { mutableStateOf(false) }
    var showSearch      by remember { mutableStateOf(false) }
    var showMenu        by remember { mutableStateOf(false) }
    var showStatePicker by remember { mutableStateOf(false) }
    var savedStates     by remember { mutableStateOf<List<java.io.File>>(emptyList()) }
    var showControls    by remember { mutableStateOf(false) }
    var showManual      by remember { mutableStateOf(false) }

    val joypadConfigPair = remember { JoypadConfigManager.load(context) }
    var joypadDefault by remember { mutableStateOf(joypadConfigPair.first) }
    val joypadDiscMappings = remember { joypadConfigPair.second }
    var currentJoypadMapping by remember { mutableStateOf(joypadConfigPair.first) }

    // Where the current controls came from (shown in the ⋮ menu), and the
    // automatic mapping for the current disc (known game or its instructions).
    var controlsSource by remember { mutableStateOf("Default") }
    var detectedMapping by remember { mutableStateOf<Pair<JoypadMapping, String>?>(null) }

    // Elite cockpit overlay: on/off is remembered; offered when an Elite disc loads.
    var eliteOverlayOn by remember { mutableStateOf(prefs.getBoolean("eliteOverlay", false)) }
    var eliteOffer by remember { mutableStateOf(false) }
    val isElite = isEliteDisc(disc0LocalFile)
    var pictureFraction by remember { mutableFloatStateOf(1f) }
    DisposableEffect(view) {
        view.onPictureWidthFraction = { pictureFraction = it }
        onDispose { view.onPictureWidthFraction = null }
    }

    // A newly mounted disc: its saved custom mapping if there is one, else the
    // mapping for the known game or read from the disc, else the default.
    fun useDisc(file: File, source: android.net.Uri? = null) {
        disc0LocalFile = file
        disc0SourceUri = source
        detectedMapping = null
        val saved = joypadDiscMappings[file.name]
        currentJoypadMapping = saved ?: joypadDefault
        controlsSource = if (saved != null) "Custom" else "Default"
        eliteOffer = isEliteDisc(file) && !eliteOverlayOn
        scope.launch {
            val detected = withContext(Dispatchers.IO) { detectGameMapping(context, file) }
            if (disc0LocalFile != file) return@launch
            detectedMapping = detected
            if (saved == null && detected != null) {
                currentJoypadMapping = detected.first
                controlsSource = detected.second
            }
        }
    }

    fun onMappingChanged(newMapping: JoypadMapping) {
        currentJoypadMapping = newMapping
        controlsSource = "Custom"
        val discName = disc0LocalFile?.name
        if (discName != null) joypadDiscMappings[discName] = newMapping
        else                  joypadDefault = newMapping
        // Snapshot the maps on the main thread; the write happens on IO.
        val defaultSnapshot = joypadDefault
        val discSnapshot    = joypadDiscMappings.toMap()
        scope.launch(Dispatchers.IO) {
            JoypadConfigManager.save(context, defaultSnapshot, discSnapshot)
        }
    }

    val overlayVisible = showMenu || showGamePicker || showSearch || showStatePicker || showControls || showManual
    SideEffect {
        activity?.gamepad?.mapping = currentJoypadMapping
        activity?.overlayActive = overlayVisible
    }

    val pickDisc = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val local = withContext(Dispatchers.IO) {
                DiscManager.copyUriToPrivate(context, uri)
            } ?: return@launch
            val ok = BeebEmNative.nativeMountDisc(0, local.absolutePath, false)
            if (ok) useDisc(local, uri)
        }
    }

    // Copy disc writes (e.g. a saved Elite commander) back to the original
    // file. The emulator writes to the private copy; check it every 2 s, and
    // once more when the disc is ejected or replaced.
    LaunchedEffect(disc0LocalFile, disc0SourceUri) {
        val file = disc0LocalFile ?: return@LaunchedEffect
        val uri  = disc0SourceUri ?: return@LaunchedEffect
        var synced = withContext(Dispatchers.IO) { DiscManager.checksum(file) }
        var warned = false
        suspend fun syncIfChanged() {
            val now = withContext(Dispatchers.IO) { DiscManager.checksum(file) } ?: return
            if (now == synced) return
            val ok = withContext(Dispatchers.IO) { DiscManager.copyPrivateToUri(context, file, uri) }
            if (ok) {
                synced = now
                Log.i("BeebEm", "Disc changes written back to ${file.name}")
            } else if (!warned) {
                warned = true
                android.widget.Toast.makeText(
                    context,
                    "Couldn't write changes back to ${file.name}. Use Save Disc to keep them.",
                    android.widget.Toast.LENGTH_LONG,
                ).show()
            }
        }
        try {
            while (true) {
                delay(2000)
                syncIfChanged()
            }
        } finally {
            withContext(kotlinx.coroutines.NonCancellable) { syncIfChanged() }
        }
    }

    val saveDisc = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("*/*")
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        val local = disc0LocalFile ?: return@rememberLauncherForActivityResult
        scope.launch {
            BeebEmNative.nativeFlushDisc(0)
            withContext(Dispatchers.IO) { DiscManager.copyPrivateToUri(context, local, uri) }
        }
    }

    // The toolbar floats over the top of the picture and hides itself after
    // TOOLBAR_HIDE_MS without a touch; touching the top of the screen brings it
    // back. It stays up while its menu is open.
    var toolbarVisible by remember { mutableStateOf(true) }
    var toolbarTouches by remember { mutableIntStateOf(0) }
    fun pokeToolbar() { toolbarVisible = true; toolbarTouches++ }
    LaunchedEffect(toolbarVisible, toolbarTouches, showMenu) {
        if (toolbarVisible && !showMenu) {
            delay(TOOLBAR_HIDE_MS)
            toolbarVisible = false
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .systemBarsPadding()
        ) {
          // Video — takes all remaining vertical space, with the toolbar on top.
          Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
            AndroidView(
                factory  = { view },
                modifier = Modifier.fillMaxSize(),
            )

            if (isElite && eliteOverlayOn) {
                EliteOverlay(
                    pictureWidthFraction = pictureFraction,
                    onManual = { showManual = true },
                    modifier = Modifier.fillMaxSize(),
                )
            }

            if (eliteOffer) {
                LaunchedEffect(Unit) { delay(20_000); eliteOffer = false }
                EliteOffer(
                    onShow = {
                        eliteOverlayOn = true
                        eliteOffer = false
                        prefs.edit().putBoolean("eliteOverlay", true).apply()
                    },
                    onDismiss = { eliteOffer = false },
                    modifier = Modifier.align(Alignment.BottomCenter),
                )
            }

            // Touch strip along the top edge that brings the hidden toolbar back.
            if (!toolbarVisible) {
                Box(
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .fillMaxWidth()
                        .height(TOOLBAR_TOUCH_STRIP)
                        .pointerInput(Unit) { detectTapGestures { pokeToolbar() } },
                )
            }

            // Fully qualified: inside this Box the ColumnScope overload would be picked.
            androidx.compose.animation.AnimatedVisibility(
                visible  = toolbarVisible,
                enter    = slideInVertically { -it } + fadeIn(),
                exit     = slideOutVertically { -it } + fadeOut(),
                modifier = Modifier.align(Alignment.TopCenter),
            ) {
            // Compact toolbar — always fits in portrait.
            // Disc operations are in the ⋮ overflow menu.
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(30.dp)
                    .background(Color(0xE6111122))
                    // Any touch on the toolbar restarts the hide timer
                    // (observed without consuming, so buttons still work).
                    .pointerInput(Unit) {
                        awaitPointerEventScope {
                            while (true) {
                                awaitPointerEvent(PointerEventPass.Initial)
                                pokeToolbar()
                            }
                        }
                    },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                ToolbarButton(
                    label       = "⋮",  // ⋮
                    highlighted = showMenu,
                ) { showMenu = !showMenu }

                ToolbarButton("GAMES") { showGamePicker = true }
                ToolbarButton("SEARCH") { showSearch = true }

                Spacer(modifier = Modifier.weight(1f))

                ToolbarButton(
                    label       = "KB",
                    highlighted = inputMode == InputMode.KEYBOARD,
                ) {
                    inputMode = if (inputMode == InputMode.KEYBOARD) InputMode.NONE else InputMode.KEYBOARD
                }
                ToolbarButton(
                    label       = "PAD",
                    highlighted = inputMode == InputMode.JOYPAD,
                ) {
                    inputMode = if (inputMode == InputMode.JOYPAD) InputMode.NONE else InputMode.JOYPAD
                }
            }
            }
          }

            AnimatedVisibility(visible = inputMode == InputMode.KEYBOARD) {
                BbcKeyboard(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 2.dp, vertical = 2.dp),
                )
            }
            AnimatedVisibility(visible = inputMode == InputMode.JOYPAD) {
                JoypadScreen(
                    mapping              = currentJoypadMapping,
                    onMappingChange      = ::onMappingChanged,
                    onConfiguringChanged = { paused ->
                        if (paused) view.softPause() else view.softResume()
                    },
                    modifier             = Modifier.fillMaxWidth(),
                )
            }
        }

        // Overflow menu — floating overlay so the GLSurfaceView never resizes.
        // Pauses emulation while open via softPause (non-blocking queueEvent).
        if (showMenu) {
            DisposableEffect(Unit) {
                view.softPause()
                onDispose { view.softResume() }
            }
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .systemBarsPadding(),
            ) {
                Column(
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(top = 30.dp)
                        .fillMaxWidth()
                        .background(Color(0xFF0E0E1E)),
                ) {
                    HorizontalDivider(color = Color(0xFF1E1E30))
                    MenuButton("SHF+BRK") {
                        showMenu = false
                        BeebEmNative.nativeBreakKey(true)
                        scope.launch {
                            delay(1500)
                            BeebEmNative.nativeKeyUp(0, 0)
                        }
                    }
                    MenuButton("Load Disc") {
                        showMenu = false
                        pickDisc.launch(arrayOf("*/*"))
                    }
                    MenuButton(
                        label   = "Save Disc",
                        enabled = disc0LocalFile != null,
                    ) {
                        showMenu = false
                        saveDisc.launch(disc0Name ?: "disc.ssd")
                    }
                    if (disc0LocalFile != null) {
                        MenuButton("Eject D0") {
                            showMenu = false
                            BeebEmNative.nativeEjectDisc(0)
                            disc0LocalFile = null
                            detectedMapping = null
                            currentJoypadMapping = joypadDefault
                            controlsSource = "Default"
                        }
                    }
                    HorizontalDivider(color = Color(0xFF1E1E30))
                    // Cycles BBC → 4:3 → Stretch; the menu stays open so the
                    // change can be seen behind it.
                    MenuButton("Controls: $controlsSource") {
                        showMenu = false
                        showControls = true
                    }
                    if (isElite) {
                        MenuButton("Elite cockpit keys: ${if (eliteOverlayOn) "ON" else "OFF"}") {
                            eliteOverlayOn = !eliteOverlayOn
                            eliteOffer = false
                            prefs.edit().putBoolean("eliteOverlay", eliteOverlayOn).apply()
                        }
                    }
                    MenuButton("Aspect: ${aspectMode.label}") {
                        aspectMode = aspectMode.next()
                        prefs.edit().putString("aspect", aspectMode.name).apply()
                    }
                    HorizontalDivider(color = Color(0xFF1E1E30))
                    MenuButton("Save Snapshot") {
                        val path = StateManager.generatePath(context)
                        BeebEmNative.nativeSaveState(path)
                        showMenu = false
                    }
                    MenuButton("Load Snapshot") {
                        savedStates = StateManager.listStates(context)
                        showMenu = false
                        showStatePicker = true
                    }
                    HorizontalDivider(color = Color(0xFF1E1E30))
                }
            }
        }

        // Game picker — rendered as an in-window overlay so it shares the same
        // Android Window as the GLSurface. Using Dialog {} creates a separate
        // Window that disrupts vsync on the GL render thread.
        if (showGamePicker) {
            DisposableEffect(Unit) {
                view.softPause()
                onDispose { view.softResume() }
            }
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .systemBarsPadding(),
            ) {
                GamePickerScreen(
                    onGameMounted = { file ->
                        useDisc(file)
                        scope.launch {
                            delay(300)
                            BeebEmNative.nativeBreakKey(true)
                            delay(1500)
                            BeebEmNative.nativeKeyUp(0, 0)
                        }
                    },
                    onDismiss = { showGamePicker = false },
                )
            }
        }

        // BBC Micro archive search — in-window overlay, same pattern as game picker.
        if (showSearch) {
            DisposableEffect(Unit) {
                view.softPause()
                onDispose { view.softResume() }
            }
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .systemBarsPadding(),
            ) {
                BbcSearchScreen(
                    onGameMounted = { file ->
                        useDisc(file)
                        scope.launch {
                            delay(300)
                            BeebEmNative.nativeBreakKey(true)
                            delay(1500)
                            BeebEmNative.nativeKeyUp(0, 0)
                        }
                    },
                    onDismiss = { showSearch = false },
                )
            }
        }

        // Elite's original flight training manual, opened from the cockpit overlay.
        if (showManual) {
            DisposableEffect(Unit) {
                view.softPause()
                onDispose { view.softResume() }
            }
            Box(modifier = Modifier.fillMaxSize().systemBarsPadding()) {
                EliteManualScreen(onDismiss = { showManual = false })
            }
        }

        // Controls — quick presets and the automatic mapping for this disc.
        if (showControls) {
            DisposableEffect(Unit) {
                view.softPause()
                onDispose { view.softResume() }
            }
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .systemBarsPadding(),
            ) {
                ControlsScreen(
                    currentSource = controlsSource,
                    detected      = detectedMapping,
                    hasCustom     = disc0LocalFile?.name?.let { joypadDiscMappings.containsKey(it) } == true,
                    discName      = disc0LocalFile?.name,
                    onPick        = { mapping, source ->
                        onMappingChanged(mapping.copy(layout = currentJoypadMapping.layout))
                        controlsSource = source
                        showControls = false
                    },
                    onClearCustom = {
                        val name = disc0LocalFile?.name
                        if (name != null) {
                            joypadDiscMappings.remove(name)
                            val defaultSnapshot = joypadDefault
                            val discSnapshot    = joypadDiscMappings.toMap()
                            scope.launch(Dispatchers.IO) {
                                JoypadConfigManager.save(context, defaultSnapshot, discSnapshot)
                            }
                            currentJoypadMapping = detectedMapping?.first ?: joypadDefault
                            controlsSource = detectedMapping?.second ?: "Default"
                        }
                        showControls = false
                    },
                    onDismiss = { showControls = false },
                )
            }
        }

        // State picker — in-window overlay, same pattern as game picker.
        if (showStatePicker) {
            DisposableEffect(Unit) {
                view.softPause()
                onDispose { view.softResume() }
            }
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .systemBarsPadding(),
            ) {
                StatePickerScreen(
                    states   = savedStates,
                    onLoad   = { file ->
                        BeebEmNative.nativeLoadState(file.absolutePath)
                        showStatePicker = false
                    },
                    onDelete = { file ->
                        StateManager.delete(file)
                        savedStates = savedStates - file
                    },
                    onDismiss = { showStatePicker = false },
                )
            }
        }
    }
}

@Composable
private fun ToolbarButton(
    label:       String,
    enabled:     Boolean = true,
    highlighted: Boolean = false,
    onClick:     () -> Unit,
) {
    TextButton(
        onClick        = onClick,
        enabled        = enabled,
        contentPadding = PaddingValues(horizontal = 6.dp, vertical = 0.dp),
    ) {
        Text(
            label,
            color = when {
                highlighted -> Color(0xFFE0A020)
                enabled     -> Color(0xFFAAAAAA)
                else        -> Color(0xFF555566)
            },
            fontSize = 10.sp,
            maxLines = 1,
        )
    }
}

@Composable
private fun MenuButton(
    label:   String,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    TextButton(
        onClick        = onClick,
        enabled        = enabled,
        modifier       = Modifier.fillMaxWidth(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 2.dp),
    ) {
        Text(
            label,
            color    = if (enabled) Color(0xFFCCCCCC) else Color(0xFF555566),
            fontSize = 11.sp,
            maxLines = 1,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}
