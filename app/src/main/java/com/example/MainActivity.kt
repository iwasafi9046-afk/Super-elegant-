package com.example

import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Star
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.input.pointer.PointerId
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.ui.theme.MyApplicationTheme
import com.example.viewmodel.AppItem
import com.example.viewmodel.LauncherViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.*

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MyApplicationTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = Color.Black
                ) {
                    DracoLauncherScreen()
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun DracoLauncherScreen() {
    val viewModel: LauncherViewModel = viewModel()
    val installedApps by viewModel.installedApps.collectAsState()
    val favorites by viewModel.favoriteAppItems.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    // Screen dimension context for layout math
    val configuration = LocalConfiguration.current
    val screenHeight = configuration.screenHeightDp.dp
    val screenWidth = configuration.screenWidthDp.dp
    val density = LocalDensity.current
    val screenHeightPx = with(density) { screenHeight.toPx() }
    val drawerHeight = screenHeight * 0.42f
    val drawerHeightPx = screenHeightPx * 0.42f

    // Sliding drawer animation state (1f = fully closed/bottom, 0f = open)
    val drawerOffset = remember { Animatable(1f) }

    // Radial Menu state
    var isRadialMenuVisible by remember { mutableStateOf(false) }
    var radialCenter by remember { mutableStateOf(Offset.Zero) }
    var currentDragOffset by remember { mutableStateOf(Offset.Zero) }
    var highlightedFavIndex by remember { mutableStateOf<Int?>(null) }
    var isDraggingJoystick by remember { mutableStateOf(false) }

    // App Actions popups
    var selectedAppForMenu by remember { mutableStateOf<AppItem?>(null) }
    var showAppActionsMenu by remember { mutableStateOf(false) }
    var actionMenuPosition by remember { mutableStateOf(Offset.Zero) }

    // Uninstallation Confirmation UI
    var appToUninstall by remember { mutableStateOf<AppItem?>(null) }
    var showUninstallDialog by remember { mutableStateOf(false) }

    // Realtime DateTime state
    var currentTime by remember { mutableStateOf("") }
    var currentDate by remember { mutableStateOf("") }
    LaunchedEffect(Unit) {
        while (true) {
            val sdfTime = java.text.SimpleDateFormat("hh:mm a", java.util.Locale.getDefault())
            val sdfDate = java.text.SimpleDateFormat("EEEE, MMMM dd", java.util.Locale.getDefault())
            val dateNow = java.util.Date()
            currentTime = sdfTime.format(dateNow)
            currentDate = sdfDate.format(dateNow)
            delay(1000)
        }
    }

    // App drawer search filter
    var drawerSearchQuery by remember { mutableStateOf("") }
    val filteredApps = remember(installedApps, drawerSearchQuery) {
        if (drawerSearchQuery.isEmpty()) {
            installedApps
        } else {
            installedApps.filter { it.label.contains(drawerSearchQuery, ignoreCase = true) }
        }
    }

    // Utility launcher triggers
    val launchApp = { pkg: String ->
        try {
            val intent = context.packageManager.getLaunchIntentForPackage(pkg)
            if (intent != null) {
                context.startActivity(intent)
            } else {
                Toast.makeText(context, "Cannot open this application", Toast.LENGTH_SHORT).show()
            }
        } catch (e: Exception) {
            Toast.makeText(context, "Error launching app: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    // Custom background canvas - cosmic radial atmosphere
    Box(
        modifier = Modifier
            .fillMaxSize()
            .pointerInput(favorites) {
                // Raw multi-touch custom parser for double tap, joystick slide, AND global swipe up/down
                awaitPointerEventScope {
                    var lastUpTime = 0L
                    var lastUpOffset = Offset.Zero
                    val ringRadius = 110.dp

                    while (true) {
                        val down = awaitPointerEvent().changes.first()
                        val downTime = System.currentTimeMillis()
                        val downOffset = down.position

                        val isDoubleTap = (downTime - lastUpTime < 350L) &&
                                (downOffset - lastUpOffset).getDistance() < 120f

                        if (isDoubleTap) {
                            // Intercept immediately on double-tap to enable live joystick
                            radialCenter = downOffset
                            isRadialMenuVisible = true
                            isDraggingJoystick = true
                            currentDragOffset = Offset.Zero
                            highlightedFavIndex = null

                            val dragPointerId = down.id

                            while (true) {
                                val event = awaitPointerEvent()
                                val activeChanges = event.changes.filter { it.pressed }
                                val trackingChange = activeChanges.firstOrNull { it.id == dragPointerId }

                                if (trackingChange == null || activeChanges.isEmpty()) {
                                    // Touch released! Launch targeted app if inside a valid hotspot
                                    val finalIndex = highlightedFavIndex
                                    if (finalIndex != null && finalIndex < favorites.size) {
                                        launchApp(favorites[finalIndex].packageName)
                                    }
                                    isDraggingJoystick = false
                                    isRadialMenuVisible = false
                                    highlightedFavIndex = null
                                    break
                                }

                                // Update joystick math
                                currentDragOffset = trackingChange.position - radialCenter
                                val numFavorites = favorites.size

                                if (numFavorites > 0) {
                                    val ringRadiusPx = with(density) { ringRadius.toPx() }
                                    val iconRadiusPx = with(density) { 32.dp.toPx() } // Card size is 64.dp; radius is 32.dp
                                    
                                    var foundHighlightIndex: Int? = null
                                    for (i in 0 until numFavorites) {
                                        val angleRad = (2.0 * PI * i / numFavorites) - (PI / 2.0)
                                        val targetOffset = Offset(
                                            (ringRadiusPx * cos(angleRad)).toFloat(),
                                            (ringRadiusPx * sin(angleRad)).toFloat()
                                        )
                                        val distance = (currentDragOffset - targetOffset).getDistance()
                                        if (distance <= iconRadiusPx) {
                                            foundHighlightIndex = i
                                            break
                                        }
                                    }
                                    highlightedFavIndex = foundHighlightIndex
                                } else {
                                    highlightedFavIndex = null
                                }
                                trackingChange.consume()
                            }
                        } else {
                            // Single touch down: can trigger vertical scroll/drag anywhere to pull up/down drawer
                            val currentPointerId = down.id
                            var startY = down.position.y
                            var isDraggingDrawer = false

                            while (true) {
                                val event = awaitPointerEvent()
                                val activeChanges = event.changes.filter { it.pressed }
                                val trackingChange = activeChanges.firstOrNull { it.id == currentPointerId }

                                if (trackingChange == null || activeChanges.isEmpty()) {
                                    // Touch released.
                                    if (isDraggingDrawer) {
                                        scope.launch {
                                            if (drawerOffset.value > 0.5f) { // If closed more than 50%
                                                drawerOffset.animateTo(1f, spring(stiffness = Spring.StiffnessMedium))
                                            } else {
                                                drawerOffset.animateTo(0f, spring(stiffness = Spring.StiffnessMedium))
                                            }
                                        }
                                    } else {
                                        // Standard tap release, record for potential double tap
                                        lastUpTime = System.currentTimeMillis()
                                        lastUpOffset = downOffset
                                    }
                                    break
                                }

                                val currentY = trackingChange.position.y
                                val deltaY = currentY - startY
                                startY = currentY

                                // Check swipe activation threshold
                                if (!isDraggingDrawer && abs(deltaY) > 8f) {
                                    isDraggingDrawer = true
                                }

                                if (isDraggingDrawer) {
                                    scope.launch {
                                        val currentVal = drawerOffset.value
                                        val extraFraction = deltaY / drawerHeightPx
                                        drawerOffset.snapTo((currentVal + extraFraction).coerceIn(0f, 1f))
                                    }
                                    trackingChange.consume()
                                }
                            }
                        }
                    }
                }
            }
    ) {
        // High fidelity decorative backdrop colors
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.radialGradient(
                        colors = listOf(
                            Color(0xFF140F26), // Cosmic deep violet
                            Color(0xFF03010C)  // Pure deep black
                        ),
                        center = Offset(screenWidth.value / 2f, screenHeight.value / 3f)
                    )
                )
        )

        // Cosmic particle stars canvas
        Canvas(modifier = Modifier.fillMaxSize()) {
            val random = java.util.Random(1337)
            for (i in 0..60) {
                val x = random.nextFloat() * size.width
                val y = random.nextFloat() * size.height
                val radius = random.nextFloat() * 2.5f + 1f
                val alphaVal = random.nextFloat() * 0.6f + 0.2f
                drawCircle(
                    color = Color(0xFFA5C5E8),
                    radius = radius,
                    center = Offset(x, y),
                    alpha = alphaVal
                )
            }
        }

        // Clock and Greeting Area
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(top = 80.dp, start = 24.dp, end = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = currentTime,
                fontSize = 58.sp,
                fontWeight = FontWeight.Light,
                fontFamily = FontFamily.SansSerif,
                color = Color.White,
                textAlign = TextAlign.Center,
                modifier = Modifier.testTag("launcher_clock")
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = currentDate,
                fontSize = 16.sp,
                fontWeight = FontWeight.Normal,
                fontFamily = FontFamily.SansSerif,
                color = Color(0xFF908D9C),
                textAlign = TextAlign.Center
            )

            Spacer(modifier = Modifier.height(36.dp))

            // Premium Translucent Search Bar
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(54.dp)
                    .clip(RoundedCornerShape(27.dp))
                    .background(Color(0x331E1B2C))
                    .border(1.dp, Color(0x33A5C5E8), RoundedCornerShape(27.dp))
                    .clickable {
                        scope.launch {
                            drawerOffset.animateTo(
                                0.3f,
                                spring(dampingRatio = Spring.DampingRatioLowBouncy)
                            )
                        }
                    }
                    .padding(horizontal = 20.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Default.Search,
                    contentDescription = "Search",
                    tint = Color(0xFFB1ADCA),
                    modifier = Modifier.size(24.dp)
                )
                Spacer(modifier = Modifier.width(12.dp))
                Text(
                    text = "Search drawer or sliding swipe up...",
                    fontSize = 15.sp,
                    color = Color(0xFF88849E),
                    modifier = Modifier.weight(1f)
                )
            }
        }

        // Swipe up indicator/handle at screen bottom
        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .navigationBarsPadding()
                .padding(bottom = 20.dp)
                .clickable {
                    scope.launch {
                        drawerOffset.animateTo(0f, spring(dampingRatio = 0.75f))
                    }
                },
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Glowing neon bar
            Box(
                modifier = Modifier
                    .size(60.dp, 5.dp)
                    .clip(RoundedCornerShape(3.dp))
                    .background(
                        Brush.horizontalGradient(
                            listOf(Color(0xFF00E5FF), Color(0xFF140F26), Color(0xFF00E5FF))
                        )
                    )
            )
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = "SWIPE UP FOR DRAWER",
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
                color = Color(0x9900E5FF),
                style = MaterialTheme.typography.labelSmall
            )
        }

        // Draw HUD Radial joystick tracer lines when drawer is closed
        if (isRadialMenuVisible) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .testTag("radial_menu_container")
            ) {
                // Background overlay to dim home screen
                Box(modifier = Modifier.fillMaxSize().background(Color(0x9903010C)))

                val centerDpX = with(density) { radialCenter.x.toDp() }
                val centerDpY = with(density) { radialCenter.y.toDp() }

                // Canvas for laser tracker lines
                Canvas(modifier = Modifier.fillMaxSize()) {
                    // Concentric rings matching radial slots
                    drawCircle(
                        color = Color(0x3300E5FF),
                        radius = 110.dp.toPx(),
                        center = radialCenter,
                        style = androidx.compose.ui.graphics.drawscope.Stroke(
                            width = 2f,
                            pathEffect = PathEffect.dashPathEffect(floatArrayOf(15f, 15f), 0f)
                        )
                    )

                    // Neon tracing joystick line
                    if (isDraggingJoystick) {
                        drawLine(
                            color = Color(0xFF00E5FF),
                            start = radialCenter,
                            end = radialCenter + currentDragOffset,
                            strokeWidth = 4f,
                            cap = StrokeCap.Round
                        )
                    }
                }

                // Symmetrical icons loop
                val ringRadius = 110.dp
                if (favorites.isEmpty()) {
                    // Explanatory empty HUD ring state
                    Column(
                        modifier = Modifier
                            .size(240.dp)
                            .offset(centerDpX - 120.dp, centerDpY - 120.dp)
                            .clip(CircleShape)
                            .background(Color(0xBB1E1B2C))
                            .border(1.dp, Color(0xFF3B2E64), CircleShape)
                            .padding(24.dp),
                        verticalArrangement = Arrangement.Center,
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Icon(
                            imageVector = Icons.Default.Star,
                            contentDescription = "No Favorites",
                            tint = Color(0xFFFFD54F),
                            modifier = Modifier.size(32.dp)
                        )
                        Spacer(modifier = Modifier.height(10.dp))
                        Text(
                            text = "Radial ring is empty!",
                            color = Color.White,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold,
                            textAlign = TextAlign.Center
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "Long-press any app in the app drawer of Draco to pin it here",
                            color = Color(0xFF8C86A8),
                            fontSize = 10.sp,
                            textAlign = TextAlign.Center,
                            lineHeight = 14.sp
                        )
                    }
                } else {
                    // Draw favorited app items around the coordinate circle
                    favorites.forEachIndexed { index, app ->
                        val total = favorites.size
                        val angleRad = (2.0 * PI * index / total) - (PI / 2.0)
                        val iconOffsetX = ringRadius * cos(angleRad).toFloat()
                        val iconOffsetY = ringRadius * sin(angleRad).toFloat()

                        val isHighlighted = index == highlightedFavIndex
                        val scale by animateFloatAsState(if (isHighlighted) 1.35f else 1f)

                        Card(
                            modifier = Modifier
                                .size(64.dp)
                                .offset(
                                    centerDpX + iconOffsetX - 32.dp,
                                    centerDpY + iconOffsetY - 32.dp
                                )
                                .scale(scale)
                                .combinedClickable(
                                    onClick = { launchApp(app.packageName) },
                                    onLongClick = {
                                        selectedAppForMenu = app
                                        showAppActionsMenu = true
                                        actionMenuPosition = radialCenter + Offset(
                                            with(density) { iconOffsetX.toPx() },
                                            with(density) { iconOffsetY.toPx() }
                                        )
                                    }
                                ),
                            shape = CircleShape,
                            colors = CardDefaults.cardColors(
                                containerColor = if (isHighlighted) Color(0xFF00E5FF) else Color(0xCC201D3A),
                                contentColor = if (isHighlighted) Color.Black else Color.White
                            ),
                            elevation = CardDefaults.cardElevation(if (isHighlighted) 12.dp else 4.dp),
                            border = BorderStroke(1.5.dp, if (isHighlighted) Color.White else Color(0x3300E5FF))
                        ) {
                            Box(
                                modifier = Modifier.fillMaxSize(),
                                contentAlignment = Alignment.Center
                            ) {
                                AppIcon(
                                    drawable = app.icon,
                                    modifier = Modifier
                                        .size(44.dp)
                                        .padding(4.dp)
                                )
                            }
                        }
                    }
                }
            }
        }

        // Custom animated Gestural slide-up bottom drawer
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(drawerHeight)
                .align(Alignment.BottomCenter)
                .offset { IntOffset(0, (drawerOffset.value * drawerHeightPx).toInt()) }
                .pointerInput(Unit) {
                    detectVerticalDragGestures(
                        onVerticalDrag = { change, dragAmount ->
                            scope.launch {
                                val currentOffset = drawerOffset.value
                                val extraFraction = dragAmount / drawerHeightPx
                                drawerOffset.snapTo((currentOffset + extraFraction).coerceIn(0f, 1f))
                            }
                            change.consume()
                        },
                        onDragEnd = {
                            scope.launch {
                                if (drawerOffset.value > 0.5f) {
                                    drawerOffset.animateTo(1f, spring(stiffness = Spring.StiffnessMedium))
                                } else {
                                    drawerOffset.animateTo(0f, spring(stiffness = Spring.StiffnessMedium))
                                }
                            }
                        }
                    )
                }
        ) {
            // App Drawer frosted glass container card
            Card(
                modifier = Modifier
                    .fillMaxSize()
                    .border(
                        1.5.dp, 
                        Color(0x33A5C5E8), 
                        RoundedCornerShape(topStart = 32.dp, topEnd = 32.dp)
                    ),
                shape = RoundedCornerShape(topStart = 32.dp, topEnd = 32.dp),
                colors = CardDefaults.cardColors(containerColor = Color(0xF20A0912))
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    // Tactile swipe header handle
                    Spacer(modifier = Modifier.height(12.dp))
                    Box(
                        modifier = Modifier
                            .size(50.dp, 4.dp)
                            .clip(RoundedCornerShape(2.dp))
                            .background(Color(0x44A5C5E8))
                    )
                    Spacer(modifier = Modifier.height(16.dp))

                    Text(
                        text = "Draco App Station",
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFF00E5FF)
                    )
                    Spacer(modifier = Modifier.height(12.dp))

                    // Embedded live filter search box
                    OutlinedTextField(
                        value = drawerSearchQuery,
                        onValueChange = { drawerSearchQuery = it },
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("drawer_search_input"),
                        shape = RoundedCornerShape(16.dp),
                        placeholder = { Text("Filter applications...", color = Color(0xFF6B658A)) },
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = Color.White,
                            unfocusedTextColor = Color.White,
                            focusedBorderColor = Color(0xFF00E5FF),
                            unfocusedBorderColor = Color(0x3300E5FF),
                            focusedContainerColor = Color(0xFF131124),
                            unfocusedContainerColor = Color(0xFF131124)
                        ),
                        leadingIcon = {
                            Icon(
                                imageVector = Icons.Default.Search,
                                contentDescription = "Search icon",
                                tint = Color(0xFF00E5FF)
                            )
                        },
                        singleLine = true
                    )

                    Spacer(modifier = Modifier.height(16.dp))

                    if (isLoading) {
                        CircularProgressIndicator(
                            color = Color(0xFF00E5FF),
                            modifier = Modifier.padding(top = 80.dp)
                        )
                    } else if (filteredApps.isEmpty()) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 80.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Text(
                                "No matched applications",
                                color = Color.White,
                                fontSize = 16.sp,
                                fontWeight = FontWeight.Bold
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                "Please type a different query",
                                color = Color(0xFF6B658A),
                                fontSize = 13.sp
                            )
                        }
                    } else {
                        // Dense list grid of launcher apps (cells at 85.dp size target)
                        LazyVerticalGrid(
                            columns = GridCells.Adaptive(minSize = 85.dp),
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(bottom = 16.dp),
                            contentPadding = PaddingValues(vertical = 8.dp),
                            verticalArrangement = Arrangement.spacedBy(16.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            items(filteredApps) { app ->
                                val isFav = favorites.any { it.packageName == app.packageName }

                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(8.dp))
                                        .combinedClickable(
                                            onClick = {
                                                // Auto-close drawer on app launch
                                                scope.launch { drawerOffset.animateTo(1f) }
                                                launchApp(app.packageName)
                                            },
                                            onLongClick = {
                                                selectedAppForMenu = app
                                                showAppActionsMenu = true
                                                // Trigger menu location directly over target element
                                                actionMenuPosition = Offset(
                                                    x = screenWidth.value * 1.5f,
                                                    y = screenHeight.value * 1.5f
                                                )
                                            }
                                        )
                                        .padding(8.dp),
                                    horizontalAlignment = Alignment.CenterHorizontally
                                ) {
                                    Box(
                                        modifier = Modifier.size(54.dp),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        AppIcon(
                                            drawable = app.icon,
                                            modifier = Modifier.size(48.dp)
                                        )
                                        if (isFav) {
                                            // Mini custom pin indication star
                                            Icon(
                                                imageVector = Icons.Default.Star,
                                                contentDescription = "Favorited",
                                                tint = Color(0xFFFFD54F),
                                                modifier = Modifier
                                                    .size(16.dp)
                                                    .align(Alignment.TopEnd)
                                                    .background(Color(0xFF0F0E1C), CircleShape)
                                                    .padding(1.dp)
                                            )
                                        }
                                    }
                                    Spacer(modifier = Modifier.height(6.dp))
                                    Text(
                                        text = app.label,
                                        fontSize = 11.sp,
                                        color = Color.White,
                                        textAlign = TextAlign.Center,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        fontFamily = FontFamily.SansSerif
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        // Custom High-End floating Actions Popup (App Actions Menu overlay)
        if (showAppActionsMenu && selectedAppForMenu != null) {
            val app = selectedAppForMenu!!
            Dialog(onDismissRequest = { showAppActionsMenu = false }) {
                Card(
                    modifier = Modifier
                        .fillMaxWidth(0.85f)
                        .clip(RoundedCornerShape(24.dp))
                        .border(1.dp, Color(0x5500E5FF), RoundedCornerShape(24.dp))
                        .shadow(16.dp),
                    shape = RoundedCornerShape(24.dp),
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF0F0E1E))
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(20.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        // Title App Header inside popup card
                        Box(
                            modifier = Modifier
                                .size(64.dp)
                                .clip(CircleShape)
                                .background(Color(0x1A00E5FF)),
                            contentAlignment = Alignment.Center
                        ) {
                            AppIcon(drawable = app.icon, modifier = Modifier.size(48.dp))
                        }
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = app.label,
                            color = Color.White,
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Bold,
                            textAlign = TextAlign.Center
                        )
                        Text(
                            text = app.packageName,
                            color = Color(0xFF6B658A),
                            fontSize = 11.sp,
                            textAlign = TextAlign.Center,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Spacer(modifier = Modifier.height(20.dp))

                        HorizontalDivider(color = Color(0x1A6B658A))

                        // App Info Option
                        TextButton(
                            onClick = {
                                showAppActionsMenu = false
                                try {
                                    val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                                        data = Uri.parse("package:${app.packageName}")
                                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                    }
                                    context.startActivity(intent)
                                } catch (e: Exception) {
                                    Toast.makeText(context, "Error opening app settings", Toast.LENGTH_SHORT).show()
                                }
                            },
                            modifier = Modifier.fillMaxWidth().testTag("app_action_info")
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    imageVector = Icons.Outlined.Info,
                                    contentDescription = "App Info",
                                    tint = Color(0xFF00FFC4)
                                )
                                Spacer(modifier = Modifier.width(14.dp))
                                Text("App Info", color = Color.White, fontSize = 14.sp)
                            }
                        }

                        // Play Store Option
                        TextButton(
                            onClick = {
                                showAppActionsMenu = false
                                try {
                                    val storeIntent = Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=${app.packageName}")).apply {
                                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                    }
                                    context.startActivity(storeIntent)
                                } catch (e: Exception) {
                                    // Fallback link browse
                                    try {
                                        val webIntent = Intent(Intent.ACTION_VIEW, Uri.parse("https://play.google.com/store/apps/details?id=${app.packageName}")).apply {
                                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                        }
                                        context.startActivity(webIntent)
                                    } catch (err: Exception) {
                                        Toast.makeText(context, "No store software discovered", Toast.LENGTH_SHORT).show()
                                    }
                                }
                            },
                            modifier = Modifier.fillMaxWidth().testTag("app_action_play_store")
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    imageVector = Icons.Outlined.PlayArrow,
                                    contentDescription = "Play Store",
                                    tint = Color(0xFF00E5FF)
                                )
                                Spacer(modifier = Modifier.width(14.dp))
                                Text("Play Store", color = Color.White, fontSize = 14.sp)
                            }
                        }

                        // Favorite Addition Pin Toggle Option
                        val isFavNow = favorites.any { it.packageName == app.packageName }
                        TextButton(
                            onClick = {
                                showAppActionsMenu = false
                                viewModel.toggleFavorite(app)
                                Toast.makeText(
                                    context,
                                    if (isFavNow) "Removed from favorites" else "Added to favorites radial loop",
                                    Toast.LENGTH_SHORT
                                ).show()
                            },
                            modifier = Modifier.fillMaxWidth().testTag("app_action_favorite")
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    imageVector = if (isFavNow) Icons.Default.Star else Icons.Outlined.Star,
                                    contentDescription = "Favorite Pin",
                                    tint = Color(0xFFFFD54F)
                                )
                                Spacer(modifier = Modifier.width(14.dp))
                                Text(
                                    text = if (isFavNow) "Unfavorite APP" else "Add to Radial Ring",
                                    color = Color.White,
                                    fontSize = 14.sp
                                )
                            }
                        }

                        // Absolute System uninstallation Trigger but with Beautiful warning message
                        TextButton(
                            onClick = {
                                showAppActionsMenu = false
                                appToUninstall = app
                                showUninstallDialog = true
                            },
                            modifier = Modifier.fillMaxWidth().testTag("app_action_uninstall")
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    imageVector = Icons.Outlined.Delete,
                                    contentDescription = "Uninstall",
                                    tint = Color(0xFFFF486A)
                                )
                                Spacer(modifier = Modifier.width(14.dp))
                                Text("Uninstall App", color = Color(0xFFFF486A), fontSize = 14.sp)
                            }
                        }
                    }
                }
            }
        }

        // Custom AlertDialog confirm uninstallation warning loop
        if (showUninstallDialog && appToUninstall != null) {
            val app = appToUninstall!!
            AlertDialog(
                onDismissRequest = { showUninstallDialog = false },
                title = { Text("Uninstall ${app.label}?", color = Color.White, fontWeight = FontWeight.Bold) },
                text = {
                    Text(
                        "Are you absolutely sure you want to trigger the native layout package manager to wipe ${app.label} entirely from system memories?",
                        color = Color(0xFFC9C5DB)
                    )
                },
                confirmButton = {
                    Button(
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFF486A)),
                        onClick = {
                            showUninstallDialog = false
                            try {
                                val intent = Intent(Intent.ACTION_DELETE, Uri.parse("package:${app.packageName}")).apply {
                                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                }
                                context.startActivity(intent)
                                // Clean up records inside our DB instantly
                                viewModel.removeFavorite(app.packageName)
                            } catch (e: Exception) {
                                Toast.makeText(context, "Error initiating uninstall mechanism: ${e.message}", Toast.LENGTH_SHORT).show()
                            }
                        }
                    ) {
                        Text("Confirm Uninstall", color = Color.White)
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showUninstallDialog = false }) {
                        Text("Hold Back", color = Color(0xFF908D9C))
                    }
                },
                containerColor = Color(0xFF131124)
            )
        }
    }
}

@Composable
fun AppIcon(
    drawable: android.graphics.drawable.Drawable?,
    modifier: Modifier = Modifier
) {
    AndroidView(
        factory = { ctx ->
            android.widget.ImageView(ctx).apply {
                scaleType = android.widget.ImageView.ScaleType.FIT_CENTER
            }
        },
        update = { imageView ->
            imageView.setImageDrawable(drawable)
        },
        modifier = modifier
    )
}
