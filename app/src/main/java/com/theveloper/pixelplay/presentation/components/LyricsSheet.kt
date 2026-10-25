package com.theveloper.pixelplay.presentation.components

import android.os.Build
import android.widget.Toast
import com.theveloper.pixelplay.data.model.Song
import com.theveloper.pixelplay.data.model.Lyrics
import com.theveloper.pixelplay.R
import androidx.activity.compose.BackHandler
import com.theveloper.pixelplay.presentation.components.scoped.LyricsPredictiveBackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.ui.zIndex
import androidx.compose.foundation.layout.heightIn
import androidx.compose.ui.layout.ContentScale
import com.theveloper.pixelplay.presentation.components.SmartImage
import com.theveloper.pixelplay.presentation.components.AutoScrollingText
import racra.compose.smooth_corner_rect_library.AbsoluteSmoothCornerShape
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.util.lerp
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.lifecycle.compose.collectAsStateWithLifecycle

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.fadeOut
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.AnimationState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateTo
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.Spring
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ClearAll
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.KeyboardArrowUp
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material3.*
import androidx.compose.material3.TabRowDefaults.tabIndicatorOffset
import androidx.compose.runtime.*
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.animation.core.Animatable
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.input.pointer.consumePositionChange
import kotlinx.coroutines.launch
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.scaleIn
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import kotlinx.coroutines.delay
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

import com.theveloper.pixelplay.data.model.SyncedLine
import com.theveloper.pixelplay.data.model.SyncedWord
import com.theveloper.pixelplay.data.repository.LyricsSearchResult
import com.theveloper.pixelplay.presentation.screens.TabAnimation
import com.theveloper.pixelplay.presentation.components.subcomps.FetchLyricsDialog
import com.theveloper.pixelplay.presentation.components.subcomps.PlayerSeekBar
import com.theveloper.pixelplay.presentation.viewmodel.LyricsSearchUiState
import com.theveloper.pixelplay.presentation.viewmodel.StablePlayerState
import com.theveloper.pixelplay.ui.theme.GoogleSansRounded
import com.theveloper.pixelplay.utils.BubblesLine
import com.theveloper.pixelplay.utils.ProviderText
import com.theveloper.pixelplay.presentation.components.snapping.ExperimentalSnapperApi
import com.theveloper.pixelplay.presentation.components.snapping.SnapperLayoutInfo
import com.theveloper.pixelplay.presentation.components.snapping.rememberLazyListSnapperLayoutInfo
import com.theveloper.pixelplay.presentation.components.snapping.rememberSnapperFlingBehavior
import com.theveloper.pixelplay.utils.LyricsUtils
import com.theveloper.pixelplay.presentation.components.subcomps.LyricsMoreBottomSheet
import android.content.BroadcastReceiver
import android.content.Intent
import android.content.IntentFilter
import androidx.compose.ui.platform.LocalView
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.theveloper.pixelplay.data.preferences.dataStore

import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.roundToInt
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.ui.text.style.TextGeometricTransform
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.style.ResolvedTextDirection
import com.theveloper.pixelplay.presentation.components.subcomps.PlayingEqIcon
import com.theveloper.pixelplay.utils.MultiLangRomanizer

internal data class LyricsSheetColors(
    val container: Color,
    val content: Color,
    val controlContainer: Color,
    val controlContent: Color,
    val accent: Color,
    val accentContent: Color,
    val lyricHighlight: Color,
    val playPauseContainer: Color,
    val playPauseContent: Color,
    val syncButtonContainer: Color,
    val syncButtonContent: Color
)

internal fun lyricsSheetColors(colorScheme: ColorScheme): LyricsSheetColors {
    val container = colorScheme.primaryContainer
    val content = colorScheme.onPrimaryContainer
    val accent = colorScheme.primary
    val accentContent = colorScheme.onPrimary

    return LyricsSheetColors(
        container = container,
        content = content,
        controlContainer = colorScheme.surfaceContainerLowest,
        controlContent = colorScheme.onSurface,
        accent = accent,
        accentContent = accentContent,
        lyricHighlight = preferredContrastColor(
            background = container,
            preferred = accent,
            fallback = content
        ),
        playPauseContainer = colorScheme.tertiaryFixedDim,
        playPauseContent = colorScheme.onTertiaryFixed,
        syncButtonContainer = colorScheme.secondaryFixedDim,
        syncButtonContent = colorScheme.onSecondaryFixed
    )
}

private fun preferredContrastColor(
    background: Color,
    preferred: Color,
    fallback: Color,
    minContrastRatio: Double = 4.5
): Color {
    if (contrastRatio(preferred, background) >= minContrastRatio) return preferred
    if (contrastRatio(fallback, background) >= minContrastRatio) return fallback

    val blackContrast = contrastRatio(Color.Black, background)
    val whiteContrast = contrastRatio(Color.White, background)
    return if (blackContrast >= whiteContrast) Color.Black else Color.White
}

private fun contrastRatio(foreground: Color, background: Color): Double {
    val foregroundLuminance = foreground.relativeLuminance()
    val backgroundLuminance = background.relativeLuminance()
    val lighter = maxOf(foregroundLuminance, backgroundLuminance)
    val darker = minOf(foregroundLuminance, backgroundLuminance)
    return (lighter + 0.05) / (darker + 0.05)
}

private fun Color.relativeLuminance(): Double {
    val argb = encodedSrgbArgb()
    val red = linearizedChannel((argb shr 16) and 0xFF)
    val green = linearizedChannel((argb shr 8) and 0xFF)
    val blue = linearizedChannel(argb and 0xFF)
    return (0.2126 * red) + (0.7152 * green) + (0.0722 * blue)
}

private fun Color.encodedSrgbArgb(): Int = (value shr 32).toInt()

private fun linearizedChannel(channel: Int): Double {
    val value = channel / 255.0
    return if (value <= 0.03928) {
        value / 12.92
    } else {
        ((value + 0.055) / 1.055).pow(2.4)
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun LyricsSheet(
    stablePlayerStateFlow: StateFlow<StablePlayerState>,
    playbackPositionFlow: StateFlow<Long>,
    lyricsSearchUiState: LyricsSearchUiState,
    resetLyricsForCurrentSong: () -> Unit,
    onSearchLyrics: (Boolean) -> Unit,
    onPickResult: (LyricsSearchResult) -> Unit,
    onManualSearch: (String, String?) -> Unit,
    onImportLyrics: () -> Unit,
    onDismissLyricsSearch: () -> Unit,
    lyricsSyncOffset: Int,
    onLyricsSyncOffsetChange: (Int) -> Unit,
    lyricsTextStyle: TextStyle,
    colorScheme: ColorScheme,
    /** False while the player sheet is collapsed/hidden. Karaoke animations
     *  are paused then, so the lyrics tree cannot keep the app rendering
     *  offscreen at display refresh rate. */
    isPlayerVisible: Boolean = true,
    onBackClick: () -> Unit,
    onSeekTo: (Long) -> Unit,
    onPlayPause: () -> Unit,
    onNext: () -> Unit,
    onPrev: () -> Unit,
    immersiveLyricsEnabled: Boolean,
    immersiveLyricsTimeout: Long,
    isImmersiveTemporarilyDisabled: Boolean,
    onSetImmersiveTemporarilyDisabled: (Boolean) -> Unit,
    onSaveLyricsToFile: (Song, Lyrics, Boolean) -> Unit,
    onTranslateViaAi: () -> Unit,
    // BottomToggleRow Params
    isShuffleEnabled: Boolean,
    repeatMode: Int,
    isFavoriteProvider: () -> Boolean,
    onShuffleToggle: () -> Unit,
    onRepeatToggle: () -> Unit,
    onFavoriteToggle: () -> Unit,
    modifier: Modifier = Modifier,
    swipeThreshold: Dp = 100.dp,
    highlightZoneFraction: Float = 0.08f, // Reduced from 0.22 for less padding
    highlightOffsetDp: Dp = 32.dp,
    autoscrollAnimationSpec: AnimationSpec<Float>? = null // null = auto-detect from preference
) {
    // ─── Enter / Exit animation state ────────────────────────────────────────
    // Mirrors the player-sheet pattern: a plain Float in state drives graphicsLayer
    // at draw-phase (no recomposition per frame). 0f = fully visible, 1f = dismissed.
    var backProgress by remember { mutableFloatStateOf(1f) }

    // Draw-phase lambda provider — read only inside graphicsLayer so layout is never
    // re-triggered during the gesture (same technique as SheetVisualState).
    val backProgressProvider = rememberUpdatedState(backProgress)

    // Enter animation: slide up from +6 % height + fade in.
    LaunchedEffect(Unit) {
        val anim = Animatable(1f)
        anim.animateTo(
            targetValue = 0f,
            animationSpec = spring(
                stiffness = Spring.StiffnessMediumLow,
                dampingRatio = Spring.DampingRatioLowBouncy
            )
        ) { backProgress = value }
    }

    // Predictive-back (Android 13+) or plain back on older devices.
    LyricsPredictiveBackHandler(
        enabled = true,
        onProgressChanged = { backProgress = it },
        onBack = onBackClick
    )

    val stablePlayerState by stablePlayerStateFlow.collectAsStateWithLifecycle()
    val sheetColors = remember(colorScheme) { lyricsSheetColors(colorScheme) }
    val backgroundColor = sheetColors.controlContainer
    val onBackgroundColor = sheetColors.controlContent
    val containerColor = sheetColors.container
    val contentColor = sheetColors.content
    val accentColor = sheetColors.accent
    val onAccentColor = sheetColors.accentContent
    val lyricHighlightColor = sheetColors.lyricHighlight
    val playPauseColor = sheetColors.playPauseContainer
    val onPlayPauseColor = sheetColors.playPauseContent

    val isLoadingLyrics by remember(stablePlayerState) { derivedStateOf { stablePlayerState.isLoadingLyrics } }
    val lyrics by remember(stablePlayerState) { derivedStateOf { stablePlayerState.lyrics } }
    val isPlaying by remember(stablePlayerState) { derivedStateOf { stablePlayerState.isPlaying } }
    val currentSong by remember(stablePlayerState) { derivedStateOf { stablePlayerState.currentSong } }

    val hasTranslatedLyrics = remember(lyrics) {
        // Translated lyrics read same timestamp on the lrc, not possible in plain type lyrics
        lyrics?.synced?.any { !it.translation.isNullOrBlank() } == true
    }

    val hasRomanizedLyrics = remember(lyrics) {
        val hasSynced = lyrics?.synced?.any { !it.romanization.isNullOrBlank() } == true
        val hasPlain = lyrics?.plain?.any { line ->
            MultiLangRomanizer.isScriptThatNeedsRomanization(line)
        } == true
        hasSynced || hasPlain
    }

    val context = LocalContext.current

    // Read lyrics alignment preference internally from DataStore
    val lyricsAlignmentFlow = remember(context) {
        context.dataStore.data.map { it[stringPreferencesKey("lyrics_alignment")] ?: "left" }
    }
    val lyricsAlignment by lyricsAlignmentFlow.collectAsStateWithLifecycle(initialValue = "left")

    // Read lyrics translation preference internally from DataStore
    val showLyricsTranslationFlow = remember(context) {
        context.dataStore.data.map { it[booleanPreferencesKey("show_lyrics_translation")] ?: true }
    }
    val showLyricsTranslation by showLyricsTranslationFlow.collectAsStateWithLifecycle(initialValue = true)

    // Read lyrics romanization preference internally from DataStore
    val showLyricsRomanizationFlow = remember(context) {
        context.dataStore.data.map { it[booleanPreferencesKey("show_lyrics_romanization")] ?: true }
    }
    val showLyricsRomanization by showLyricsRomanizationFlow.collectAsStateWithLifecycle(initialValue = true)

    // Read animated lyrics preference internally from DataStore
    val useAnimatedLyricsFlow = remember(context) {
        context.dataStore.data.map { it[booleanPreferencesKey("use_animated_lyrics")] ?: false }
    }
    val useAnimatedLyrics by useAnimatedLyricsFlow.collectAsStateWithLifecycle(initialValue = false)

    val animatedLyricsBlurEnabledFlow = remember(context) {
        context.dataStore.data.map { it[booleanPreferencesKey("animated_lyrics_blur_enabled")] ?: true }
    }
    val animatedLyricsBlurEnabled by animatedLyricsBlurEnabledFlow.collectAsStateWithLifecycle(initialValue = true)

    val disableBlurAllOverFlow = remember(context) {
        context.dataStore.data.map { it[booleanPreferencesKey("disable_blur_all_over")] ?: false }
    }
    val disableBlurAllOver by disableBlurAllOverFlow.collectAsStateWithLifecycle(initialValue = false)

    val animatedLyricsBlurStrengthFlow = remember(context) {
        context.dataStore.data.map { it[androidx.datastore.preferences.core.floatPreferencesKey("animated_lyrics_blur_strength")] ?: 2.5f }
    }
    val animatedLyricsBlurStrength by animatedLyricsBlurStrengthFlow.collectAsStateWithLifecycle(initialValue = 2.5f)

    // Read keep-screen-on preference from DataStore
    val keepScreenOnFlow = remember(context) {
        context.dataStore.data.map { it[booleanPreferencesKey("keep_screen_on_lyrics")] ?: false }
    }
    var keepScreenOn by remember { mutableStateOf(false) }
    // Sync DataStore → local state
    LaunchedEffect(Unit) {
        keepScreenOnFlow.collect { keepScreenOn = it }
    }
    val coroutineScope = rememberCoroutineScope()

    // Apply FLAG_KEEP_SCREEN_ON via the window when enabled
    val view = LocalView.current
    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current

    DisposableEffect(keepScreenOn, lifecycleOwner) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_STOP && keepScreenOn) {
                keepScreenOn = false
                coroutineScope.launch {
                    context.dataStore.edit { prefs ->
                        prefs[booleanPreferencesKey("keep_screen_on_lyrics")] = false
                    }
                }
            }
        }

        if (keepScreenOn) {
            view.keepScreenOn = true
        }

        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            view.keepScreenOn = false
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    val resolvedAutoscrollSpec = autoscrollAnimationSpec ?: if (useAnimatedLyrics) {
        spring(
            stiffness = Spring.StiffnessMediumLow,
            dampingRatio = Spring.DampingRatioLowBouncy
        )
    } else {
        tween(durationMillis = 450, easing = FastOutSlowInEasing)
    }

    var showFetchLyricsDialog by remember { mutableStateOf(false) }
    // Flag to prevent dialog from showing briefly after reset
    var wasResetTriggered by remember { mutableStateOf(false) }
    // Save lyrics dialog state
    var showSaveLyricsDialog by remember { mutableStateOf(false) }
    var showSyncControls by remember { mutableStateOf(false) }
    var previewSeekPositionMs by remember(currentSong?.id) { mutableStateOf<Long?>(null) }

    var showSyncedLyrics by remember(lyrics) {
        mutableStateOf(
            when {
                !lyrics?.synced.isNullOrEmpty() -> true
                !lyrics?.plain.isNullOrEmpty() -> false
                else -> null
            }
        )
    }

    val hasSyncedLyrics = remember(lyrics) {
        !lyrics?.synced.isNullOrEmpty()
    }

    // Immersive Mode State
    var immersiveMode by remember { mutableStateOf(false) }
    var lastInteractionTime by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var showMoreSheet by remember { mutableStateOf(false) }
    val moreSheetState = rememberModalBottomSheetState(
        skipPartiallyExpanded = true
    )

    // Swipe Gesture State
    val hapticFeedback = LocalHapticFeedback.current
    var dragOffset by remember { mutableFloatStateOf(0f) }
    var isSwipeActive by remember { mutableStateOf(false) }
    var hasTriggeredAction by remember { mutableStateOf(false) }
    val swipeThresholdPx = with(LocalDensity.current) { swipeThreshold.toPx() }
    val overlayTranslation = remember { Animatable(0f) }
    val swipeProgress = remember { Animatable(0f) }

    // Reset keep-screen-on when the physical screen goes off (power button / OEM sleep gesture).
    // ACTION_SCREEN_OFF is a guaranteed platform broadcast; no OEM can suppress it.
    DisposableEffect(Unit) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: android.content.Context, intent: Intent) {
                if (intent.action == Intent.ACTION_SCREEN_OFF) {
                    keepScreenOn = false
                    coroutineScope.launch {
                        context.dataStore.edit { prefs ->
                            prefs[booleanPreferencesKey("keep_screen_on_lyrics")] = false
                        }
                    }
                }
            }
        }
        context.registerReceiver(receiver, IntentFilter(Intent.ACTION_SCREEN_OFF))
        onDispose { context.unregisterReceiver(receiver) }
    }

    // Auto-hide controls logic
    LaunchedEffect(immersiveLyricsEnabled, lastInteractionTime, showSyncedLyrics, isImmersiveTemporarilyDisabled) {
        if (immersiveLyricsEnabled && showSyncedLyrics == true && !isImmersiveTemporarilyDisabled) {
            delay(immersiveLyricsTimeout)
            immersiveMode = true
        } else {
            immersiveMode = false
        }
    }

    // Font Scaling
    val fontScale by animateFloatAsState(
        targetValue = if (immersiveMode) 1.4f else 1f,
        animationSpec = spring(stiffness = Spring.StiffnessLow),
        label = "fontScale"
    )
    
    val scaledTextStyle = lyricsTextStyle.copy(
        fontSize = lyricsTextStyle.fontSize * fontScale,
        lineHeight = lyricsTextStyle.lineHeight * fontScale
    )

    fun resetImmersiveTimer() {
        lastInteractionTime = System.currentTimeMillis()
        immersiveMode = false
    }

    LaunchedEffect(currentSong, lyrics, isLoadingLyrics) {
        if (currentSong != null && lyrics == null && !isLoadingLyrics) {
            // Only show dialog if reset was not just triggered
            if (!wasResetTriggered) {
                showFetchLyricsDialog = true
            }
        } else if (lyrics != null || isLoadingLyrics) {
            showFetchLyricsDialog = false
            wasResetTriggered = false // Reset the flag when lyrics are loaded
        }
    }

    if (showFetchLyricsDialog) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = MaterialTheme.typography,
            shapes = MaterialTheme.shapes
        ) {
            FetchLyricsDialog(
                uiState = lyricsSearchUiState,
                currentSong = currentSong,
                onConfirm = onSearchLyrics,
                onPickResult = onPickResult,
                onManualSearch = onManualSearch,
                onDismiss = {
                    showFetchLyricsDialog = false
                    onDismissLyricsSearch()
                    if (lyrics == null && !isLoadingLyrics) {
                        onBackClick()
                    }
                },
                onImport = onImportLyrics
            )
        }
    }

    // Save Lyrics Dialog
    if (showSaveLyricsDialog && lyrics != null && currentSong != null) {
        val hasSynced = !lyrics?.synced.isNullOrEmpty()
        val hasPlain = !lyrics?.plain.isNullOrEmpty()
        
        AlertDialog(
            onDismissRequest = { showSaveLyricsDialog = false },
            title = { Text(stringResource(R.string.lyrics_save_dialog_title)) },
            text = {
                Column {
                    Text(stringResource(R.string.lyrics_save_dialog_message))
                    Spacer(modifier = Modifier.height(16.dp))
                    if (hasSynced) {
                        FilledTonalButton(
                            onClick = {
                                showSaveLyricsDialog = false
                                onSaveLyricsToFile(
                                    currentSong!!,
                                    lyrics!!,
                                    true
                                )
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(stringResource(R.string.lyrics_save_synced))
                        }
                        Spacer(modifier = Modifier.height(8.dp))
                    }
                    if (hasPlain) {
                        OutlinedButton(
                            onClick = {
                                showSaveLyricsDialog = false
                                onSaveLyricsToFile(
                                    currentSong!!,
                                    lyrics!!,
                                    false
                                )
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(stringResource(R.string.lyrics_save_plain))
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { showSaveLyricsDialog = false }) {
                    Text(stringResource(R.string.common_cancel), maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        )
    }

    

    Scaffold(
        modifier = modifier
            .fillMaxSize()
            // ─── Enter / Predictive-back exit transformation ──────────────────
            // Read backProgressProvider inside graphicsLayer (draw-phase) — no layout
            // pass is triggered per gesture frame, same pattern as SheetVisualState.
            // 0f = fully visible, 1f = fully dismissed.
            // Effect: scale down to 92 % + slide down 8 % of height + fade to 72 % alpha.
            // Matches Android predictive back spec for full-screen destinations and
            // mirrors the scale+alpha treatment used across the rest of the app.
            .graphicsLayer {
                val p = backProgressProvider.value
                val scale = lerp(1f, 0.92f, p)
                scaleX = scale
                scaleY = scale
                translationY = lerp(0f, size.height * 0.08f, p)
            }
            .clip(RoundedCornerShape(32.dp))
            .pointerInput(Unit) {
                detectDragGestures(
                    onDragStart = {
                        isSwipeActive = true
                        hasTriggeredAction = false
                        dragOffset = 0f
                        resetImmersiveTimer()
                        coroutineScope.launch {
                            swipeProgress.snapTo(0f)
                        }
                    },
                    onDragEnd = {
                        isSwipeActive = false
                        val committed = abs(dragOffset) > swipeThresholdPx && !hasTriggeredAction 
                        
                        if (committed) {
                            if (dragOffset > 0) onPrev() else onNext()
                            hapticFeedback.performHapticFeedback(HapticFeedbackType.LongPress)
                        }

                        coroutineScope.launch {
                             swipeProgress.animateTo(0f, tween(200))
                             dragOffset = 0f
                        }
                    },
                    onDragCancel = {
                        isSwipeActive = false
                        dragOffset = 0f
                        coroutineScope.launch {
                            swipeProgress.animateTo(0f, tween(200))
                        }
                    },
                    onDrag = { change, dragAmount ->
                        change.consume()
                        resetImmersiveTimer()
                        
                        if (!hasTriggeredAction) {
                            dragOffset += dragAmount.x
                            val progress = (abs(dragOffset) / swipeThresholdPx).coerceIn(0f, 1f)
                            
                            coroutineScope.launch {
                                swipeProgress.snapTo(progress)
                            }
                        }
                    }
                )
            },
        containerColor = containerColor,
        contentColor = contentColor,
        // Removed TopBar and FAB
    ) { paddingValues ->
        Box(modifier = Modifier.fillMaxSize()) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(top = paddingValues.calculateTopPadding())
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null
                ) {
                    resetImmersiveTimer()
                }
        ) {
            val initialSyncedLineIndex = remember(lyrics, playbackPositionFlow, lyricsSyncOffset) {
                resolveCurrentLineIndex(
                    lines = lyrics?.synced.orEmpty(),
                    position = (
                        playbackPositionFlow.value + lyricsSyncOffset +
                            LYRICS_VISUAL_LEAD_MS
                        ).coerceAtLeast(0L)
                ).coerceAtLeast(0)
            }
            val syncedListState = rememberLazyListState(
                initialFirstVisibleItemIndex = initialSyncedLineIndex
            )
            val staticListState = rememberLazyListState()

            // Lyrics Content (Weight 1)
            Box(
                modifier = Modifier
                    .align(Alignment.Start)
                    .weight(1f)
                    .fillMaxWidth()
            ) {
                // Track Info Header (Fixed at top)
                AnimatedContent(
                    targetState = currentSong,
                    transitionSpec = {
                        (fadeIn(animationSpec = tween(300)) + 
                         scaleIn(initialScale = 0.9f, animationSpec = tween(300)))
                        .togetherWith(fadeOut(animationSpec = tween(300)))
                    },
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .zIndex(2f)
                        .wrapContentWidth(),
                    label = "headerAnimation"
                ) { song ->
                    LyricsTrackInfo(
                        song = song,
                        modifier = Modifier
                            .align(Alignment.TopStart)
                            .padding(
                                top = 4.dp, bottom = 24.dp, start = 18.dp, end = 18.dp
                            )
                            .background(
                                color = backgroundColor,
                                shape = CircleShape
                            )
                            .wrapContentWidth()
                            .animateContentSize(), // Animate width changes
                        backgroundColor = backgroundColor, // Distinct solid background
                        contentColor = onBackgroundColor,
                        isPlaying = isPlaying
                    )
                }

                when (showSyncedLyrics) {
                    null -> {
                        LazyColumn(
                            modifier = Modifier.fillMaxSize(),
                            contentPadding = PaddingValues(top = 110.dp, bottom = 24.dp, start = 24.dp, end = 24.dp)
                        ) {
                            item(key = "loader_or_empty") {
                                Box(
                                    modifier = Modifier
                                        .fillParentMaxSize(),
                                    contentAlignment = Alignment.Center
                                ) {
                                    if (isLoadingLyrics) {
                                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                            Text(
                                                text = stringResource(R.string.lyrics_loading),
                                                style = MaterialTheme.typography.titleMedium
                                            )
                                            Spacer(modifier = Modifier.height(8.dp))
                                            LinearWavyProgressIndicator(
                                                trackColor = accentColor.copy(alpha = 0.4f),
                                                color = accentColor,
                                                modifier = Modifier.width(100.dp)
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }

                    true -> {
                        lyrics?.synced?.let { synced ->
                            SyncedLyricsList(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .padding(horizontal = 24.dp),
                                contentPadding = PaddingValues(top = 130.dp, bottom = 100.dp),
                                lines = synced,
                                listState = syncedListState,
                                playbackPositionFlow = playbackPositionFlow,
                                lyricsSyncOffset = lyricsSyncOffset,
                                positionOverrideMs = previewSeekPositionMs,
                                isPlaying = isPlaying,
                                isVisible = isPlayerVisible,
                                accentColor = lyricHighlightColor,
                                textStyle = scaledTextStyle,
                                onLineClick = { syncedLine -> 
                                    onSeekTo(
                                        resolveSeekPositionMs(
                                            lineTimeMs = syncedLine.time.toLong(),
                                            lyricsSyncOffsetMs = lyricsSyncOffset
                                        )
                                    )
                                    resetImmersiveTimer()
                                },
                                highlightZoneFraction = highlightZoneFraction,
                                highlightOffsetDp = highlightOffsetDp,
                                autoscrollAnimationSpec = resolvedAutoscrollSpec,
                                useAnimatedLyrics = useAnimatedLyrics,
                                animatedLyricsBlurEnabled = animatedLyricsBlurEnabled &&
                                    !disableBlurAllOver &&
                                    Build.VERSION.SDK_INT >= Build.VERSION_CODES.S,
                                animatedLyricsBlurStrength = animatedLyricsBlurStrength,
                                immersiveMode = immersiveMode,
                                lyricsAlignment = lyricsAlignment,
                                showTranslation = showLyricsTranslation,
                                showRomanization = showLyricsRomanization,
                                footer = {
                                    if (lyrics?.areFromRemote == true) {
                                        item(key = "provider_text") {
                                            ProviderText(
                                                providerText = stringResource(R.string.lyrics_provided_by),
                                                uri = stringResource(R.string.lyrics_lrclib_uri),
                                                textAlign = TextAlign.Center,
                                                accentColor = lyricHighlightColor,
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .padding(vertical = 16.dp)
                                            )
                                        }
                                    }
                                }
                            )
                        }
                    }

                    false -> {
                        lyrics?.plain?.let { plain ->
                            LazyColumn(
                                modifier = Modifier.fillMaxSize(),
                                state = staticListState,
                                contentPadding = PaddingValues(
                                    start = 24.dp,
                                    end = 24.dp,
                                    top = 130.dp,
                                    bottom = 24.dp
                                )
                            ) {
                                itemsIndexed(
                                    items = plain,
                                    key = { index, line -> "$index-$line" }
                                ) { _, line ->
                                    PlainLyricsLine(
                                        line = line,
                                        style = lyricsTextStyle,
                                        lyricsAlignment = lyricsAlignment,
                                        showTranslation = if (hasTranslatedLyrics) showLyricsTranslation else true,
                                        showRomanization = if (hasRomanizedLyrics) showLyricsRomanization else true,
                                        modifier = Modifier.fillMaxWidth()
                                    )
                                    Spacer(modifier = Modifier.height(16.dp))
                                }
                            }
                        }
                    }
                }
                
                // Top Gradient for fade
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(130.dp)
                        .align(Alignment.TopCenter)
                        .background(
                            brush = Brush.verticalGradient(
                                colors = listOf(containerColor, Color.Transparent)
                            )
                        )
                )

                // Bottom Gradient for fade
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(80.dp)
                        .align(Alignment.BottomCenter)
                        .background(
                            brush = Brush.verticalGradient(
                                colors = listOf(Color.Transparent, containerColor)
                            )
                        )
                )
            }

            // Controls Section (Auto-hide in immersive mode)
            AnimatedVisibility(
                visible = !immersiveMode,
                enter = expandVertically(expandFrom = Alignment.Top) + fadeIn(),
                exit = shrinkVertically(shrinkTowards = Alignment.Top) + fadeOut()
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(containerColor)
                        .padding(bottom = paddingValues.calculateBottomPadding() + 10.dp, end = 16.dp, start = 16.dp)
                        .pointerInput(Unit) {
                            awaitPointerEventScope {
                                while (true) {
                                    val event = awaitPointerEvent()
                                    // Reset timer on any touch down or move in this area
                                    if (event.changes.any { it.pressed }) {
                                         resetImmersiveTimer()
                                    }
                                }
                            }
                        }
                ) {
                                AnimatedVisibility(
                    visible = showSyncedLyrics == true && lyrics?.synced != null && showSyncControls,
                    enter = expandVertically() + fadeIn(),
                    exit = shrinkVertically() + fadeOut()
                ) {
                    LyricsSyncControls(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 8.dp),
                        offsetMillis = lyricsSyncOffset,
                        onOffsetChange = onLyricsSyncOffsetChange,
                        backgroundColor = backgroundColor,
                        accentColor = sheetColors.syncButtonContainer,
                        onAccentColor = sheetColors.syncButtonContent,
                        onBackgroundColor = onBackgroundColor
                    )
                }

                // Playback Controls Row
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 0.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    // Play/Pause Button (Smaller)
                    val playPauseCornerRadius by animateDpAsState(
                        targetValue = if (isPlaying) 18.dp else 50.dp,
                        animationSpec = spring(stiffness = Spring.StiffnessLow),
                        label = "playPauseShape"
                    )

                    Box(
                        modifier = Modifier
                            .size(78.dp)
                            .clip(RoundedCornerShape(playPauseCornerRadius))
                            .background(playPauseColor)
                            .clickable {
                                hapticFeedback.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                onPlayPause()
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        AnimatedContent(
                            targetState = isPlaying,
                            label = "playPauseIconAnimation"
                        ) { playing ->
                            if (playing) {
                                Icon(
                                    modifier = Modifier.size(32.dp),
                                    imageVector = Icons.Rounded.Pause,
                                    contentDescription = "Pause",
                                    tint = onPlayPauseColor
                                )
                            } else {
                                Icon(
                                    modifier = Modifier.size(32.dp),
                                    imageVector = Icons.Rounded.PlayArrow,
                                    contentDescription = stringResource(R.string.common_play),
                                    tint = onPlayPauseColor
                                )
                            }
                        }
                    }

                    // Progress Bar
                    LyricsPlaybackSeekBar(
                        modifier = Modifier
                            .weight(1f)
                            .height(50.dp),
                        playbackPositionFlow = playbackPositionFlow,
                        backgroundColor = backgroundColor,
                        onBackgroundColor = onBackgroundColor,
                        accentColor = accentColor,
                        totalDuration = stablePlayerState.totalDuration,
                        onSeekTo = onSeekTo,
                        onSeekPreviewChange = { previewSeekPositionMs = it },
                        isPlaying = isPlaying
                    )
                }
                
                Spacer(modifier = Modifier.height(16.dp))

                // Floating Toolbar
                LyricsFloatingToolbar(
                    modifier = Modifier.padding(horizontal = 0.dp),
                    showSyncedLyrics = showSyncedLyrics,
                    hasSyncedLyrics = hasSyncedLyrics,
                    onShowSyncedLyricsChange = { showSyncedLyrics = it },
                    onNavigateBack = {
                        onBackClick()
                    },
                    onMoreClick = { showMoreSheet = true },
                    backgroundColor = backgroundColor,
                    onBackgroundColor = onBackgroundColor,
                    accentColor = accentColor,
                    onAccentColor = onAccentColor,
                    // Pass progress so the back button animates with the gesture (draw-phase).
                    backProgressProvider = { backProgressProvider.value },
                )
             }
            }
        }

        if (showMoreSheet) {
            MaterialTheme(
                colorScheme = colorScheme,
                typography = MaterialTheme.typography,
                shapes = MaterialTheme.shapes
            ) {
                LyricsMoreBottomSheet(
                    onDismissRequest = { showMoreSheet = false },
                    sheetState = moreSheetState,
                    lyrics = lyrics,
                    showSyncedLyrics = showSyncedLyrics == true,
                    isSyncControlsVisible = showSyncControls,
                    onSaveLyricsAsLrc = { showSaveLyricsDialog = true },
                    onResetImportedLyrics = {
                        wasResetTriggered = true
                        resetLyricsForCurrentSong()
                    },
                    onTranslateViaAi = onTranslateViaAi,
                    onToggleSyncControls = {
                        resetImmersiveTimer()
                        showSyncControls = !showSyncControls
                    },
                    isImmersiveTemporarilyDisabled = isImmersiveTemporarilyDisabled,
                    onSetImmersiveTemporarilyDisabled = {
                        resetImmersiveTimer()
                        onSetImmersiveTemporarilyDisabled(it)
                    },
                    keepScreenOn = keepScreenOn,
                    onKeepScreenOnChange = { enabled ->
                        keepScreenOn = enabled
                        coroutineScope.launch {
                            context.dataStore.edit { prefs ->
                                prefs[booleanPreferencesKey("keep_screen_on_lyrics")] = enabled
                            }
                        }
                    },
                    lyricsAlignment = lyricsAlignment,
                    onLyricsAlignmentChange = { newAlignment ->
                        coroutineScope.launch {
                            context.dataStore.edit { preferences ->
                                preferences[stringPreferencesKey("lyrics_alignment")] = newAlignment
                            }
                        }
                    },
                    hasTranslatedLyrics = hasTranslatedLyrics,
                    hasRomanizedLyrics = hasRomanizedLyrics,
                    showTranslation = showLyricsTranslation,
                    showRomanization = showLyricsRomanization,
                    onShowTranslationChange = { enabled ->
                        resetImmersiveTimer()
                        coroutineScope.launch {
                            context.dataStore.edit { preferences ->
                                preferences[booleanPreferencesKey("show_lyrics_translation")] = enabled
                            }
                        }
                    },
                    onShowRomanizationChange = { enabled ->
                        resetImmersiveTimer()
                        coroutineScope.launch {
                            context.dataStore.edit { preferences ->
                                preferences[booleanPreferencesKey("show_lyrics_romanization")] = enabled
                            }
                        }
                    },
                    immersiveLyricsEnabled = immersiveLyricsEnabled,
                    isShuffleEnabled = isShuffleEnabled,
                    repeatMode = repeatMode,
                    isFavoriteProvider = isFavoriteProvider,
                    onShuffleToggle = {
                        resetImmersiveTimer()
                        onShuffleToggle()
                    },
                    onRepeatToggle = {
                        resetImmersiveTimer()
                        onRepeatToggle()
                    },
                    onFavoriteToggle = {
                        resetImmersiveTimer()
                        onFavoriteToggle()
                    },
                )
            }
        }

       // Show Controls Button (Overlay)
       AnimatedVisibility(
            visible = immersiveMode,
            enter = fadeIn() + slideInVertically { it / 2 },
            exit = fadeOut() + slideOutVertically { it / 2 },
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 32.dp)
        ) {
            FilledIconButton(
                onClick = { resetImmersiveTimer() },
                modifier = Modifier.size(48.dp),
                colors = IconButtonDefaults.filledIconButtonColors(
                    containerColor = accentColor,
                    contentColor = onAccentColor
                )
            ) {
                Icon(
                    imageVector = Icons.Rounded.KeyboardArrowUp,
                    contentDescription = "Show Controls"
                )
            }
        }
       
       // Swipe Feedback Overlay
       if (isSwipeActive || swipeProgress.value > 0f) {
           val isNext = dragOffset < 0
           val overlayAlignment = if (isNext) Alignment.CenterEnd else Alignment.CenterStart
           val icon = if (isNext) Icons.Rounded.SkipNext else Icons.Rounded.SkipPrevious
           
           Box(
               modifier = Modifier
                   .align(overlayAlignment)
                   .size(100.dp) // Base size
                   .padding(
                       start = if(isNext) 0.dp else 6.dp,
                       end = if(isNext) 6.dp else 0.dp
                   )
                   .graphicsLayer {
                        val widthPx = size.width
                        val initialOffset = if(isNext) widthPx else -widthPx
                        translationX = initialOffset * (1f - swipeProgress.value)

                        scaleX = 0.8f + (swipeProgress.value * 0.2f)
                        scaleY = 0.8f + (swipeProgress.value * 0.2f)
                   }
                   .background(
                        color = accentColor, // No alpha modulation
                        shape = RoundedCornerShape(
                            topStart = if(isNext) 360.dp else 8.dp,
                            bottomStart = if(isNext) 360.dp else 8.dp,
                            topEnd = if(isNext) 8.dp else 360.dp,
                            bottomEnd = if(isNext) 8.dp else 360.dp
                        )
                   ),
               contentAlignment = Alignment.Center
           ) {
               Icon(
                   imageVector = icon,
                   contentDescription = null,
                   modifier = Modifier.size(48.dp),
                   tint = onAccentColor
               )
           }
       }

      }
    }
}

@Composable
private fun LyricsPlaybackSeekBar(
    playbackPositionFlow: StateFlow<Long>,
    backgroundColor: Color,
    onBackgroundColor: Color,
    accentColor: Color,
    totalDuration: Long,
    onSeekTo: (Long) -> Unit,
    onSeekPreviewChange: (Long?) -> Unit,
    isPlaying: Boolean,
    modifier: Modifier = Modifier
) {
    val playbackPosition by playbackPositionFlow.collectAsStateWithLifecycle()

    PlayerSeekBar(
        backgroundColor = backgroundColor,
        onBackgroundColor = onBackgroundColor,
        primaryColor = accentColor,
        currentPosition = playbackPosition,
        totalDuration = totalDuration,
        onSeek = onSeekTo,
        onSeekPreview = onSeekPreviewChange,
        isPlaying = isPlaying,
        modifier = modifier
    )
}

@OptIn(ExperimentalSnapperApi::class)
@Composable
fun SyncedLyricsList(
    lines: List<SyncedLine>,
    listState: LazyListState,
    playbackPositionFlow: StateFlow<Long>,
    lyricsSyncOffset: Int,
    positionOverrideMs: Long? = null,
    isPlaying: Boolean = false,
    isVisible: Boolean = true,
    accentColor: Color,
    textStyle: TextStyle,
    onLineClick: (SyncedLine) -> Unit,
    highlightZoneFraction: Float,
    highlightOffsetDp: Dp,
    autoscrollAnimationSpec: AnimationSpec<Float>,
    useAnimatedLyrics: Boolean = false,
    animatedLyricsBlurEnabled: Boolean = true,
    animatedLyricsBlurStrength: Float = 2.5f,
    immersiveMode: Boolean = false,
    lyricsAlignment: String = "left",
    showTranslation: Boolean = true,
    showRomanization: Boolean = true,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(0.dp),
    footer: LazyListScope.() -> Unit = {}
) {
    val density = LocalDensity.current
    val isPreviewSeeking = positionOverrideMs != null

    // Playback is collected into a plain box instead of a compose State read in
    // the list body. Only the line index, word index and draw phase consult it,
    // so the list no longer recomposes every 250 ms playback tick.
    val samplePositionMs = remember { mutableLongStateOf(playbackPositionFlow.value) }
    LaunchedEffect(playbackPositionFlow) {
        playbackPositionFlow.collect { samplePositionMs.longValue = it }
    }
    fun visualPosition(): Long = positionOverrideMs
        ?: (samplePositionMs.longValue + lyricsSyncOffset + LYRICS_VISUAL_LEAD_MS)
            .coerceAtLeast(0L)
    val initialPosition = remember(positionOverrideMs, lyricsSyncOffset) {
        positionOverrideMs
            ?: (playbackPositionFlow.value + lyricsSyncOffset + LYRICS_VISUAL_LEAD_MS)
                .coerceAtLeast(0L)
    }

    // Karaoke fill used to move in 250 ms steps, because the player's position
    // flow only ticks four times a second. Run a vsync loop between ticks and
    // steer it toward the last authoritative sample: word fills and line-sync
    // sweeps then glide at display refresh rate while staying locked to
    // playback. Seeks snap.
    //
    // The loop is only worth its cost when the rows carry something to sweep —
    // word-timed fills and line-sync karaoke alike — and the lyrics are on
    // screen. A hidden player sheet must not keep the app rendering at display
    // refresh rate while the user navigates elsewhere.
    val hasProgressiveRows = remember(lines) {
        lines.any { !it.words.isNullOrEmpty() || it.line.isNotBlank() }
    }
    val isKaraokeActive = isPlaying && isVisible && hasProgressiveRows
    val framePositionMs = remember { mutableLongStateOf(initialPosition) }
    LaunchedEffect(
        isPlaying,
        isPreviewSeeking,
        isKaraokeActive,
        lyricsSyncOffset,
        positionOverrideMs,
    ) {
        if (!isKaraokeActive || isPreviewSeeking) {
            snapshotFlow { visualPosition() }.collect { framePositionMs.longValue = it }
            return@LaunchedEffect
        }
        var lastSample = visualPosition()
        var sampleFrameNanos = -1L
        var lastFrameNanos = -1L
        var display = lastSample.toDouble()
        while (true) {
            withFrameNanos { now ->
                val sample = visualPosition()
                if (sample != lastSample || sampleFrameNanos < 0L) {
                    lastSample = sample
                    sampleFrameNanos = now
                }
                val target = sample + ((now - sampleFrameNanos) / 1_000_000.0).coerceAtMost(250.0)
                val frameDelta = if (lastFrameNanos < 0L) 0.0 else (now - lastFrameNanos) / 1_000_000.0
                lastFrameNanos = now
                val predicted = display + frameDelta
                val error = target - predicted
                // Snap on seeks, otherwise glide toward the sample. Never run
                // more than a frame or two ahead of the last reported position,
                // so buffering cannot fill words before they are sung.
                display = if (abs(error) > 300.0) {
                    target
                } else {
                    (predicted + error * 0.2).coerceAtMost(target + 32.0)
                }
                framePositionMs.longValue = display.toLong()
            }
        }
    }
    val positionProvider: () -> Long = remember { { framePositionMs.longValue } }
    // Reading the derived int only invalidates the list when the active line
    // actually changes, not on every playback tick.
    val currentLineIndex by remember(lines) {
        derivedStateOf {
            resolveCurrentLineIndex(lines = lines, position = framePositionMs.longValue)
        }
    }
    var hasAlignedInitialLine by remember(lines) { mutableStateOf(false) }
    var lastAutoScrolledLineIndex by remember(lines) { mutableIntStateOf(-1) }

    BoxWithConstraints(modifier = modifier) {
        val metrics = remember(maxHeight, highlightZoneFraction, highlightOffsetDp) {
            calculateHighlightMetrics(maxHeight, highlightZoneFraction, highlightOffsetDp)
        }
        val highlightOffsetPx = remember(highlightOffsetDp, density) { with(density) { highlightOffsetDp.toPx() } }

        val snapperLayoutInfo = rememberLazyListSnapperLayoutInfo(
            lazyListState = listState,
            snapOffsetForItem = { layoutInfo, item ->
                val viewportHeight = layoutInfo.endScrollOffset - layoutInfo.startScrollOffset
                highlightSnapOffsetPx(viewportHeight, item.size, highlightOffsetPx)
            }
        )
        val flingBehavior = rememberSnapperFlingBehavior(layoutInfo = snapperLayoutInfo)

        LaunchedEffect(currentLineIndex, lines.size, metrics, isPreviewSeeking) {
            if (lines.isEmpty()) return@LaunchedEffect
            if (currentLineIndex !in lines.indices) return@LaunchedEffect
            if (listState.layoutInfo.totalItemsCount == 0) return@LaunchedEffect

            if (!hasAlignedInitialLine) {
                listState.scrollToItem(currentLineIndex)
                snapToSnapIndex(
                    listState = listState,
                    layoutInfo = snapperLayoutInfo,
                    targetIndex = currentLineIndex
                )
                hasAlignedInitialLine = true
                lastAutoScrolledLineIndex = currentLineIndex
                return@LaunchedEffect
            }

            if (listState.isScrollInProgress && !isPreviewSeeking) return@LaunchedEffect

            val lineJumpDistance = if (lastAutoScrolledLineIndex >= 0) {
                abs(currentLineIndex - lastAutoScrolledLineIndex)
            } else {
                0
            }

            if (isPreviewSeeking) {
                if (lineJumpDistance > 2) {
                    listState.scrollToItem(currentLineIndex)
                    snapToSnapIndex(
                        listState = listState,
                        layoutInfo = snapperLayoutInfo,
                        targetIndex = currentLineIndex
                    )
                } else {
                    animateToSnapIndex(
                        listState = listState,
                        layoutInfo = snapperLayoutInfo,
                        targetIndex = currentLineIndex,
                        animationSpec = tween(durationMillis = 110, easing = FastOutSlowInEasing)
                    )
                }
                lastAutoScrolledLineIndex = currentLineIndex
                return@LaunchedEffect
            }

            // Music Style Dynamic Velocity
            val dynamicAnimationSpec = if (useAnimatedLyrics) {
                val currentLineTime = lines.getOrNull(currentLineIndex)?.time ?: 0
                val nextLineTime = lines.getOrNull(currentLineIndex + 1)?.time ?: (currentLineTime + 1000)
                val timeDiff = (nextLineTime - currentLineTime).coerceIn(250, 2000) // Bound the duration
                
                tween<Float>(
                    durationMillis = timeDiff,
                    easing = FastOutSlowInEasing
                )
            } else {
                autoscrollAnimationSpec
            }

            animateToSnapIndex(
                listState = listState,
                layoutInfo = snapperLayoutInfo,
                targetIndex = currentLineIndex,
                animationSpec = dynamicAnimationSpec
            )
            lastAutoScrolledLineIndex = currentLineIndex
        }

        Box(modifier = Modifier.fillMaxSize()) {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                state = listState,
                flingBehavior = flingBehavior,
                contentPadding = contentPadding
            ) {
                itemsIndexed(
                    items = lines,
                    key = { index, item -> "${item.time}_$index" }
                ) { index, line ->
                    val nextTime = lines.getOrNull(index + 1)?.time ?: Int.MAX_VALUE
                    val distanceFromCurrent = if (currentLineIndex != -1) abs(currentLineIndex - index) else 100
                    
                    val parallaxModifier = if (useAnimatedLyrics) {
                        Modifier.graphicsLayer {
                            // Calculate translation dynamically inside graphicsLayer to avoid recomposing the row during scroll
                            val currentLayoutInfo = listState.layoutInfo
                            val lineItemInfo = currentLayoutInfo.visibleItemsInfo.find { it.index == index }
                            val itemCenter = lineItemInfo?.let { it.offset + (it.size / 2f) }
                            val viewportCenter = currentLayoutInfo.viewportEndOffset / 2f
                            
                            val distanceFromCenter = itemCenter?.let { it - viewportCenter } ?: 0f
                            
                            val maxTranslation = 40f 
                            val distanceRatio = (distanceFromCenter / viewportCenter).coerceIn(-1f, 1f)
                            translationY = distanceRatio * distanceRatio * distanceRatio * maxTranslation 
                        }
                    } else Modifier

                    if (line.line.isNotBlank()) {
                        val instrumentalGapStart = remember(line, nextTime) {
                            resolveInstrumentalGapStartMs(line, nextTime)
                        }
                        val isInstrumentalGap by remember(line.time, instrumentalGapStart, nextTime) {
                            derivedStateOf {
                                val gapStart = instrumentalGapStart ?: return@derivedStateOf false
                                positionProvider() in gapStart until nextTime.toLong()
                            }
                        }
                        if (isInstrumentalGap && instrumentalGapStart != null) {
                            BubblesLine(
                                positionFlow = playbackPositionFlow,
                                positionProvider = positionProvider,
                                time = instrumentalGapStart.toInt(),
                                color = LocalContentColor.current.copy(alpha = 0.6f),
                                nextTime = nextTime,
                                animate = isVisible,
                                modifier = parallaxModifier
                                    .fillMaxWidth()
                                    .padding(vertical = 8.dp)
                            )
                        } else {
                            LyricLineRow(
                                line = line,
                                nextTime = nextTime,
                                positionProvider = positionProvider,
                                distanceFromCurrent = distanceFromCurrent,
                                useAnimatedLyrics = useAnimatedLyrics,
                                animatedLyricsBlurEnabled = animatedLyricsBlurEnabled,
                                animatedLyricsBlurStrength = animatedLyricsBlurStrength,
                                immersiveMode = immersiveMode,
                                lyricsAlignment = lyricsAlignment,
                                showTranslation = showTranslation,
                                showRomanization = showRomanization,
                                accentColor = accentColor,
                                style = textStyle,
                                modifier = parallaxModifier
                                    .fillMaxWidth()
                                    .testTag("synced_line_${line.time}"),
                                onClick = { onLineClick(line) }
                            )
                        }
                    } else {
                        BubblesLine(
                            positionFlow = playbackPositionFlow,
                            time = line.time,
                            color = LocalContentColor.current.copy(alpha = 0.6f),
                            nextTime = nextTime,
                            animate = isVisible,
                            modifier = parallaxModifier
                                .fillMaxWidth()
                                .padding(vertical = 8.dp)
                        )
                    }
// 16 dp Spacer removed to allow dynamic padding in LyricLineRow
                }
                footer()
            }

//            if (metrics.zoneHeight > 0.dp) {
//                Box(
//                    modifier = Modifier
//                        .fillMaxWidth()
//                        .offset(y = metrics.topPadding)
//                        .height(metrics.zoneHeight)
//                        .align(Alignment.TopCenter)
//                        .clip(RoundedCornerShape(18.dp))
//                        .background(accentColor.copy(alpha = 0.12f))
//                        .testTag("synced_highlight_zone")
//                )
//            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun LyricLineRow(
    line: SyncedLine,
    nextTime: Int,
    positionProvider: () -> Long,
    distanceFromCurrent: Int = 100,
    useAnimatedLyrics: Boolean = false,
    animatedLyricsBlurEnabled: Boolean = true,
    animatedLyricsBlurStrength: Float = 2.5f,
    immersiveMode: Boolean = false,
    lyricsAlignment: String = "left",
    showTranslation: Boolean = true,
    showRomanization: Boolean = true,
    accentColor: Color,
    style: TextStyle,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    val sanitizedLine = remember(line.line) { sanitizeLyricLineText(line.line) }
    val lineEndTime = remember(line, nextTime) {
        resolveLineVocalEndTimeMs(line, nextTime)
    }
    // Providers sometimes time a whole phrase as one span ("And it comes so
    // far"). Split it into words with distributed timings so the accent moves
    // with the vocals instead of sweeping characters across the phrase.
    val sanitizedWords = remember(line.words, lineEndTime) {
        line.words
            ?.let(::sanitizeSyncedWords)
            ?.let { splitMultiTokenWords(it, lineEndTime) }
    }
    val sanitizedWordClusters = remember(sanitizedWords) {
        sanitizedWords?.takeIf { it.isNotEmpty() }?.let(::clusterSyncedWords)
    }
    val isCurrentLine by remember(line.time, lineEndTime) {
        derivedStateOf { positionProvider() in line.time.toLong()..<lineEndTime }
    }
    val unhighlightedColor = LocalContentColor.current.copy(alpha = 0.45f)
    val lineColor by animateColorAsState(
        targetValue = if (isCurrentLine) accentColor else unhighlightedColor,
        animationSpec = if (useAnimatedLyrics) spring(
            stiffness = Spring.StiffnessVeryLow,
            dampingRatio = Spring.DampingRatioMediumBouncy
        ) else tween(durationMillis = 250),
        label = "lineColor"
    )

    // Animated mode: fisheye scaling + alpha based on distance from current line
    val targetScale = if (useAnimatedLyrics) when (distanceFromCurrent) {
        0 -> if (immersiveMode) 1.02f else 1.1f; 1 -> 0.95f; else -> 0.85f
    } else 1f
    val targetPadding = if (useAnimatedLyrics) when (distanceFromCurrent) {
        0 -> 32.dp; 1 -> 16.dp; else -> 8.dp
    } else 12.dp
    val targetAlpha = if (useAnimatedLyrics) when (distanceFromCurrent) {
        0 -> 1.0f; 1 -> 0.6f; else -> 0.3f
    } else 1f

    val scale by animateFloatAsState(
        targetValue = targetScale,
        animationSpec = if (useAnimatedLyrics) spring(
            stiffness = Spring.StiffnessVeryLow,
            dampingRatio = Spring.DampingRatioMediumBouncy
        ) else tween(durationMillis = 200),
        label = "lineScale"
    )
    val verticalPadding by animateDpAsState(
        targetValue = targetPadding,
        animationSpec = if (useAnimatedLyrics) spring(
            stiffness = Spring.StiffnessVeryLow,
            dampingRatio = Spring.DampingRatioMediumBouncy
        ) else tween(durationMillis = 200),
        label = "linePadding"
    )
    val alpha by animateFloatAsState(
        targetValue = targetAlpha,
        animationSpec = if (useAnimatedLyrics) spring(
            stiffness = Spring.StiffnessLow,
            dampingRatio = Spring.DampingRatioNoBouncy
        ) else tween(durationMillis = 200),
        label = "lineAlpha"
    )

    // Blur Effect
    val targetBlur = if (useAnimatedLyrics && animatedLyricsBlurEnabled && distanceFromCurrent > 0) {
        (distanceFromCurrent * animatedLyricsBlurStrength).coerceAtMost(10f).dp
    } else 0.dp

    val blurRadius by animateDpAsState(
        targetValue = targetBlur,
        animationSpec = if (useAnimatedLyrics) tween(durationMillis = 400) else tween(durationMillis = 200),
        label = "lineBlur"
    )

    // Animated mode: apply graphicsLayer for scale/alpha transforms
    val baseModifier = if (useAnimatedLyrics && !immersiveMode) {
        when (lyricsAlignment) {
            "center" -> modifier.padding(horizontal = 36.dp)
            "right" -> modifier.padding(start = 36.dp)
            else -> modifier.padding(end = 36.dp)
        }
    } else {
        modifier
    }
    val animatedModifier = if (useAnimatedLyrics) {
        baseModifier
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
                this.alpha = alpha
                transformOrigin = TransformOrigin(
                    pivotFractionX = when (lyricsAlignment) {
                        "center" -> 0.5f
                        "right" -> 1f
                        else -> 0f
                    },
                    pivotFractionY = 0.5f
                )
            }
            .then(if (blurRadius > 0.dp) Modifier.blur(blurRadius) else Modifier)
    } else baseModifier

    // Roman or Translate Logic
    val translationText = line.translation
    val romanizationText = line.romanization

    val secondaryStyle = remember(style) {
        style.copy(
            fontSize = (style.fontSize.value * 0.75f).sp,
            fontWeight = FontWeight.Normal
        )
    }

    val romanizationColor = lineColor.copy(alpha = lineColor.alpha * 0.85f)
    val translationColor = lineColor.copy(alpha = lineColor.alpha * 0.55f)

    val horizontalAlignment = when (lyricsAlignment) {
        "center" -> Alignment.CenterHorizontally
        "right" -> Alignment.End
        else -> Alignment.Start
    }

    val textAlign = when (lyricsAlignment) {
        "center" -> TextAlign.Center
        "right" -> TextAlign.Right
        else -> TextAlign.Left
    }

    val boxAlignment = when (lyricsAlignment) {
        "center" -> Alignment.TopCenter
        "right" -> Alignment.TopEnd
        else -> Alignment.TopStart
    }

    if (sanitizedWordClusters.isNullOrEmpty()) {
        // Line-sync karaoke (LastWave parity): lines without word timing still
        // progress as the song goes. The dim base text stays still while an
        // accent copy sweeps across the row — a row's end is never stored, it
        // is implied by the next row's start, and the final row holds for a
        // fixed default instead of an unbounded span.
        val lineWeight = if (isCurrentLine) FontWeight.Bold else FontWeight.Normal
        val lineSweepDurationMs = remember(line.time, nextTime) {
            resolveLineSweepDurationMs(
                lineStartMs = line.time.toLong(),
                nextLineStartMs = nextTime.toLong(),
                isLastLine = nextTime >= Int.MAX_VALUE
            )
        }
        var lineTextLayout by remember(line.line, style, lineWeight) {
            mutableStateOf<TextLayoutResult?>(null)
        }
        Column(
            modifier = animatedModifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .clickable { onClick() }
                .padding(vertical = verticalPadding, horizontal = 2.dp),
            horizontalAlignment = horizontalAlignment
        ) {
            Box(modifier = Modifier.fillMaxWidth(), contentAlignment = boxAlignment) {
                // Invisible bold text to reserve layout space and prevent reflow
                Text(
                    text = sanitizedLine,
                    style = style,
                    color = Color.Transparent,
                    fontWeight = FontWeight.Bold,
                    textAlign = textAlign
                )
                if (isCurrentLine && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    // LastWave's lyric glow: a soft blurred copy of the active
                    // row behind the ink.
                    Text(
                        text = sanitizedLine,
                        style = style,
                        color = Color.White.copy(alpha = LYRIC_GLOW_ALPHA),
                        fontWeight = lineWeight,
                        textAlign = textAlign,
                        modifier = Modifier.blur(LYRIC_GLOW_RADIUS)
                    )
                }
                Text(
                    text = sanitizedLine,
                    style = style,
                    color = unhighlightedColor,
                    fontWeight = lineWeight,
                    textAlign = textAlign,
                    onTextLayout = { lineTextLayout = it }
                )
                Text(
                    text = sanitizedLine,
                    style = style,
                    color = accentColor,
                    fontWeight = lineWeight,
                    textAlign = textAlign,
                    modifier = Modifier.drawWithContent {
                        val progress = if (isCurrentLine) {
                            resolveWordFillProgress(
                                positionMs = positionProvider(),
                                wordStartMs = line.time.toLong(),
                                wordDurationMs = lineSweepDurationMs,
                                fallbackEndMs = lineEndTime,
                            )
                        } else {
                            0f
                        }
                        drawWithKaraokeSweep(progress, lineTextLayout) { drawContent() }
                    }
                )
            }

            if (showRomanization && !romanizationText.isNullOrBlank()) {
                Text(
                    text = romanizationText,
                    style = secondaryStyle,
                    color = romanizationColor,
                    textAlign = textAlign,
                    modifier = Modifier.padding(top = 4.dp)
                )
            }

            if (showTranslation && !translationText.isNullOrBlank()) {
                Text(
                    text = translationText,
                    style = secondaryStyle,
                    color = translationColor,
                    textAlign = textAlign,
                    modifier = Modifier.padding(top = 2.dp)
                )
            }
        }
    } else {
        val highlightedWordIndex by remember(sanitizedWords, line.time, lineEndTime) {
            derivedStateOf {
                resolveHighlightedWordIndex(
                    words = requireNotNull(sanitizedWords),
                    positionMs = positionProvider(),
                    lineStartTimeMs = line.time.toLong(),
                    lineEndTimeMs = lineEndTime
                )
            }
        }

        Column(
            modifier = animatedModifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .clickable { onClick() }
                .padding(vertical = verticalPadding, horizontal = 2.dp),
            horizontalAlignment = horizontalAlignment
        ) {
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = when (lyricsAlignment) {
                    "center" -> Arrangement.spacedBy(3.dp, Alignment.CenterHorizontally)
                    "right" -> Arrangement.spacedBy(3.dp, Alignment.End)
                    else -> Arrangement.spacedBy(3.dp, Alignment.Start)
                },
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                sanitizedWordClusters.forEach { cluster ->
                    cluster.words.forEachIndexed { clusterOffset, word ->
                        val wordIndex = cluster.startIndex + clusterOffset
                        val wordEndTimeMs = requireNotNull(sanitizedWords)
                            .getOrNull(wordIndex + 1)?.time?.toLong()
                            ?: lineEndTime
                        key("${line.time}_${word.time}_${word.word}_$wordIndex") {
                            LyricWordSpan(
                                word = word,
                                isHighlighted = isCurrentLine && wordIndex == highlightedWordIndex,
                                isActiveLine = isCurrentLine,
                                wordEndTimeMs = wordEndTimeMs,
                                positionProvider = positionProvider,
                                useAnimatedLyrics = useAnimatedLyrics,
                                style = style,
                                highlightedColor = accentColor,
                                unhighlightedColor = unhighlightedColor
                            )
                        }
                    }
                }
            }

            if (showRomanization && !romanizationText.isNullOrBlank()) {
                Text(
                    text = romanizationText,
                    style = secondaryStyle,
                    color = romanizationColor,
                    textAlign = textAlign,
                    modifier = Modifier.padding(top = 4.dp)
                )
            }

            if (showTranslation && !translationText.isNullOrBlank()) {
                Text(
                    text = translationText,
                    style = secondaryStyle,
                    color = translationColor,
                    textAlign = textAlign,
                    modifier = Modifier.padding(top = 2.dp)
                )
            }
        }
    }
}

@Composable
fun LyricWordSpan(
    word: SyncedWord,
    isHighlighted: Boolean,
    isActiveLine: Boolean = false,
    wordEndTimeMs: Long = Long.MAX_VALUE,
    positionProvider: () -> Long = { word.time.toLong() },
    useAnimatedLyrics: Boolean = false,
    style: TextStyle,
    highlightedColor: Color,
    unhighlightedColor: Color,
    modifier: Modifier = Modifier
) {
    val wordAnimSpec = if (useAnimatedLyrics) spring<Float>(
        stiffness = Spring.StiffnessVeryLow,
        dampingRatio = Spring.DampingRatioMediumBouncy
    ) else tween(durationMillis = 200)

    // Scale: pop up to 1.10 on highlight, settle back to 1f. Only active when
    // animated lyrics is on — layout is untouched because it's applied in graphicsLayer.
    val scale by animateFloatAsState(
        targetValue = if (useAnimatedLyrics && isHighlighted) 1.10f else 1f,
        animationSpec = wordAnimSpec,
        label = "wordScale"
    )

    // The line is bold while active (matching the non-word-synced row), so the
    // dim base and the accent fill share metrics and align glyph for glyph.
    val wordWeight = if (isActiveLine) FontWeight.Bold else FontWeight.Normal
    var textLayoutResult by remember(word.word, style, wordWeight) {
        mutableStateOf<TextLayoutResult?>(null)
    }

    Box(
        modifier = modifier,
        contentAlignment = Alignment.Center
    ) {
        // Invisible bold text reserves layout space and prevents reflow
        Text(
            text = word.word,
            style = style,
            color = Color.Transparent,
            fontWeight = FontWeight.Bold,
        )
        Box(
            modifier = Modifier.graphicsLayer {
                scaleX = scale
                scaleY = scale
            },
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = word.word,
                style = style,
                color = unhighlightedColor,
                fontWeight = wordWeight,
                onTextLayout = { textLayoutResult = it },
            )
            // Accent copy clipped to the sung width. The playhead is read in
            // the draw phase, so the wipe advances every frame without
            // recomposing the lyrics list.
            Text(
                text = word.word,
                style = style,
                color = highlightedColor,
                fontWeight = wordWeight,
                modifier = Modifier.drawWithContent {
                    val progress = if (!isActiveLine) {
                        0f
                    } else {
                        resolveWordFillProgress(
                            positionMs = positionProvider(),
                            wordStartMs = word.time.toLong(),
                            wordDurationMs = word.durationMs.toLong(),
                            fallbackEndMs = wordEndTimeMs,
                        )
                    }
                    drawWithKaraokeSweep(progress, textLayoutResult) { drawContent() }
                }
            )
        }
    }
}

/*
 * Line-sync karaoke sweep (LastWave parity).
 *
 * Word-synced rows carry per-syllable windows and fill through
 * [LyricWordSpan]; rows that only carry a line timestamp used to light up
 * statically. LastWave's contract makes a row's end implicit — implied by
 * the next row's start, never stored — so a line-sync row can still sweep
 * progressively across its span as the song goes. The constants below keep
 * that sweep from misbehaving at the edges.
 */

/** Default hold for the final row, which has no follower to imply its end. */
private const val LAST_LINE_SWEEP_DURATION_MS = 4_500L

/** Upper bound on a row's sweep so instrumental gaps end in a hold, not a crawl. */
private const val MAX_LINE_SWEEP_DURATION_MS = 8_000L

/**
 * Sweep window for one line-sync row: from its start to the next row's
 * start (0 when timestamps are duplicated), capped for the final row and
 * for long instrumental gaps.
 */
internal fun resolveLineSweepDurationMs(
    lineStartMs: Long,
    nextLineStartMs: Long,
    isLastLine: Boolean
): Long {
    val raw = nextLineStartMs - lineStartMs
    return raw.coerceAtLeast(0L).coerceAtMost(
        if (isLastLine) LAST_LINE_SWEEP_DURATION_MS else MAX_LINE_SWEEP_DURATION_MS
    )
}

internal fun resolveLineVocalEndTimeMs(line: SyncedLine, nextLineStartMs: Int): Long {
    val lineStartMs = line.time.toLong()
    val nextStartMs = nextLineStartMs.toLong()
    val fallbackEndMs = lineStartMs + resolveLineSweepDurationMs(
        lineStartMs = lineStartMs,
        nextLineStartMs = nextStartMs,
        isLastLine = nextLineStartMs >= Int.MAX_VALUE
    )
    val timedWordEndMs = line.words.orEmpty()
        .filter { it.durationMs > 0 }
        .maxOfOrNull { it.time.toLong() + it.durationMs }
    val minimumEndMs = lineStartMs + 1L
    return (timedWordEndMs ?: fallbackEndMs)
        .coerceAtLeast(minimumEndMs)
        .coerceAtMost(nextStartMs.coerceAtLeast(minimumEndMs))
}

/**
 * Start of the music-only stretch that follows a row's vocals, or `null`
 * when the next row starts before the vocals end (or there is no next row).
 */
internal fun resolveInstrumentalGapStartMs(line: SyncedLine, nextLineStartMs: Int): Long? {
    if (nextLineStartMs >= Int.MAX_VALUE) return null
    val vocalEndMs = resolveLineVocalEndTimeMs(line, nextLineStartMs)
    return vocalEndMs.takeIf { it < nextLineStartMs.toLong() }
}

/** LastWave's lyric glow: a soft blurred copy of the active row behind the ink. */
private const val LYRIC_GLOW_ALPHA = 0.5f
private val LYRIC_GLOW_RADIUS = 8.dp

/**
 * LastWave-style karaoke sweep shared by the per-word wipe and the line-sync
 * sweep: draws the accent copy clipped to the sung extent at [progress].
 *
 * A wrapped text fills in reading order — the first visual line completes
 * before the wipe starts on the next one — instead of revealing a vertical
 * slice of every line at once.
 */
internal fun ContentDrawScope.drawWithKaraokeSweep(
    progress: Float,
    layout: TextLayoutResult?,
    content: () -> Unit
) {
    if (progress <= 0f) return
    if (progress >= 1f) {
        content()
        return
    }
    if (layout == null || layout.lineCount <= 1) {
        clipRect(right = size.width * progress) { content() }
        return
    }

    val lineCounts = ArrayList<Int>(layout.lineCount)
    for (line in 0 until layout.lineCount) {
        lineCounts += layout.getLineEnd(line, visibleEnd = true) -
            layout.getLineStart(line)
    }
    val lineFractions = resolveLineFillFractions(lineCounts, progress)
    if (lineFractions.isEmpty()) {
        content()
        return
    }

    val path = Path()
    for (line in 0 until layout.lineCount) {
        val fraction = lineFractions.getOrElse(line) { 0f }
        if (fraction <= 0f) break

        val lineLeft = layout.getLineLeft(line)
        val lineRight = layout.getLineRight(line)
        val lineWidth = (lineRight - lineLeft).coerceAtLeast(0f)
        val top = layout.getLineTop(line)
        val bottom = layout.getLineBottom(line)
        val isRtl = layout.getParagraphDirection(line) ==
            ResolvedTextDirection.Rtl
        val fillWidth = lineWidth * fraction
        path.addRect(
            if (isRtl) {
                Rect(lineRight - fillWidth, top, lineRight, bottom)
            } else {
                Rect(lineLeft, top, lineLeft + fillWidth, bottom)
            }
        )

        if (fraction < 1f) break
    }

    if (!path.isEmpty) {
        clipPath(path) { content() }
    }
}

@Composable
fun PlainLyricsLine(
    line: String,
    style: TextStyle,
    lyricsAlignment: String = "left",
    showTranslation: Boolean = true,
    showRomanization: Boolean = true,
    modifier: Modifier = Modifier
) {
    val sanitizedLines = remember(line) { line.split("\n") }
    val primaryText = remember(sanitizedLines) { if (sanitizedLines.isNotEmpty()) sanitizeLyricLineText(sanitizedLines[0]) else "" }

    val isRomanizedScript = remember(primaryText) {
        MultiLangRomanizer.isScriptThatNeedsRomanization(primaryText)
    }

    val translationText = remember(sanitizedLines, primaryText, isRomanizedScript) {
        if (sanitizedLines.size > 1) {
            val firstExtra = sanitizedLines[1]
            val rest = if (sanitizedLines.size > 2) sanitizedLines.drop(2).joinToString("\n") { sanitizeLyricLineText(it) } else ""
            
            val isLatin = firstExtra.any { it.code in 32..126 } 
            val isFirstRomanization = isRomanizedScript && isLatin

            if (isFirstRomanization) rest else sanitizedLines.drop(1).joinToString("\n") { sanitizeLyricLineText(it) }
        } else ""
    }

    val romanizationText = remember(sanitizedLines, primaryText, isRomanizedScript) {
         if (sanitizedLines.size > 1) {
            val firstExtra = sanitizedLines[1]
            val isLatin = firstExtra.any { it.code in 32..126 } 
            val isFirstRomanization = isRomanizedScript && isLatin
            
            if (isFirstRomanization) sanitizeLyricLineText(firstExtra) else ""
        } else ""
    }
    val textAlign = when (lyricsAlignment) {
        "center" -> TextAlign.Center
        "right" -> TextAlign.Right
        else -> TextAlign.Left
    }

    val horizontalAlignment = when (lyricsAlignment) {
        "center" -> Alignment.CenterHorizontally
        "right" -> Alignment.End
        else -> Alignment.Start
    }

    val translationStyle = remember(style) {
        style.copy(
            fontSize = (style.fontSize.value * 0.75f).sp,
            fontWeight = FontWeight.Normal
        )
    }
    val translationColor = LocalContentColor.current.copy(alpha = 0.45f)

    Column(modifier = modifier, horizontalAlignment = horizontalAlignment) {
        if (primaryText.isNotBlank()) {
            Text(text = primaryText, style = style, color = LocalContentColor.current.copy(alpha = 0.7f), textAlign = textAlign)

            if (showRomanization && romanizationText.isNotBlank()) {
                Text(
                    text = romanizationText,
                    style = translationStyle,
                    color = translationColor,
                    textAlign = textAlign,
                    modifier = Modifier.padding(top = 4.dp)
                )
            }

            if (showTranslation && translationText.isNotBlank()) {
                Text(
                    text = translationText,
                    style = translationStyle,
                    color = translationColor,
                    textAlign = textAlign,
                    modifier = Modifier.padding(top = if (showRomanization && romanizationText.isNotBlank()) 2.dp else 4.dp)
                )
            }
        }
    }
}

private val LeadingTagRegex = Regex("^v\\d+:\\s*", RegexOption.IGNORE_CASE)

internal fun sanitizeLyricLineText(raw: String): String =
    LyricsUtils.stripLrcTimestamps(raw).replace(LeadingTagRegex, "").trimStart()

internal fun sanitizeSyncedWords(words: List<SyncedWord>): List<SyncedWord> =
    buildList {
        words.forEachIndexed { index, word ->
            val sanitized = if (index == 0) LeadingTagRegex.replace(word.word, "") else word.word
            val normalized = sanitized.trim()
            if (normalized.isEmpty()) return@forEachIndexed

            add(
                word.copy(
                    word = normalized,
                    startsNewWord = if (isEmpty()) true else word.startsNewWord
                )
            )
        }
    }

private val WordTokenSeparatorRegex = Regex("\\s+")
private const val MAX_SYNTHETIC_SPAN_DURATION_MS = 8_000L

/**
 * Some providers return a complete sung phrase as a single timed word (for
 * example a TTML `<span>` containing "And it comes so far"). A single clip
 * cannot follow the vocals phrase-internally, so split those spans into
 * display words and distribute the span's time across them by length. The
 * karaoke fill then steps through "And", "it", "comes"… as they are sung.
 */
internal fun splitMultiTokenWords(
    words: List<SyncedWord>,
    lineEndTimeMs: Long
): List<SyncedWord> {
    if (words.isEmpty()) return words

    val result = ArrayList<SyncedWord>(words.size)
    words.forEachIndexed { index, word ->
        val tokens = word.word.trim()
            .split(WordTokenSeparatorRegex)
            .filter { it.isNotEmpty() }
        if (tokens.size <= 1) {
            result += word
            return@forEachIndexed
        }

        val wordStart = word.time.toLong()
        val nextWordStart = words.getOrNull(index + 1)?.time?.toLong()
        val declaredEnd = word.durationMs
            .takeIf { it > 0 }
            ?.let { wordStart + it }
        val lineBoundEnd = lineEndTimeMs.takeIf {
            it > wordStart && it < Int.MAX_VALUE.toLong()
        }
        val fallbackEnd = nextWordStart
            ?: lineBoundEnd
            ?: (wordStart + MAX_SYNTHETIC_SPAN_DURATION_MS)
        val spanEnd = (declaredEnd ?: fallbackEnd)
            .let { end -> nextWordStart?.let { minOf(end, it) } ?: end }
            .coerceAtLeast(wordStart + tokens.size)

        val weights = tokens.map { it.length.coerceAtLeast(1) }
        val totalWeight = weights.sum().coerceAtLeast(1)
        val spanDuration = spanEnd - wordStart

        var consumedWeight = 0
        tokens.forEachIndexed { tokenIndex, token ->
            val tokenStart = wordStart + spanDuration * consumedWeight / totalWeight
            consumedWeight += weights[tokenIndex]
            val tokenEnd = if (tokenIndex == tokens.lastIndex) {
                spanEnd
            } else {
                wordStart + spanDuration * consumedWeight / totalWeight
            }
            result += SyncedWord(
                time = tokenStart.coerceAtMost(Int.MAX_VALUE.toLong()).toInt(),
                word = token,
                startsNewWord = true,
                durationMs = (tokenEnd - tokenStart)
                    .coerceAtLeast(0L)
                    .coerceAtMost(Int.MAX_VALUE.toLong())
                    .toInt()
            )
        }
    }
    return result
}

internal data class SyncedWordCluster(
    val startIndex: Int,
    val words: List<SyncedWord>
)

internal fun clusterSyncedWords(words: List<SyncedWord>): List<SyncedWordCluster> {
    if (words.isEmpty()) return emptyList()

    val clusters = mutableListOf<SyncedWordCluster>()
    var currentWords = mutableListOf<SyncedWord>()
    var currentStartIndex = 0

    words.forEachIndexed { index, word ->
        if (word.startsNewWord && currentWords.isNotEmpty()) {
            clusters += SyncedWordCluster(startIndex = currentStartIndex, words = currentWords.toList())
            currentWords = mutableListOf()
            currentStartIndex = index
        } else if (currentWords.isEmpty()) {
            currentStartIndex = index
        }

        currentWords += word
    }

    if (currentWords.isNotEmpty()) {
        clusters += SyncedWordCluster(startIndex = currentStartIndex, words = currentWords.toList())
    }

    return clusters
}

internal fun normalizeWordEndTime(
    currentWordTimeMs: Long,
    nextWordTimeMs: Long,
    lineEndTimeMs: Long
): Long {
    val minEnd = currentWordTimeMs + 1L
    val boundedLineEnd = lineEndTimeMs.coerceAtLeast(minEnd)
    return nextWordTimeMs.coerceIn(minEnd, boundedLineEnd)
}

internal fun resolveLineEndTimeMs(line: SyncedLine, nextLineStartMs: Int): Long {
    val baseEnd = nextLineStartMs.toLong()
    val lastWordStart = line.words?.maxOfOrNull { it.time.toLong() } ?: line.time.toLong()
    return maxOf(baseEnd, lastWordStart + 1L)
}

internal fun resolveHighlightedWordIndex(
    words: List<SyncedWord>,
    positionMs: Long,
    lineStartTimeMs: Long,
    lineEndTimeMs: Long
): Int {
    if (positionMs < lineStartTimeMs || positionMs >= lineEndTimeMs) return -1
    return words.indexOfLast { it.time.toLong() <= positionMs }
}

/**
 * Width of the accent fill for one word, `0f..1f`.
 *
 * Providers that report a syllable duration animate within that duration;
 * legacy LRC words (duration = 0) hold the fill until the next word starts.
 * A mild ease-out mirrors the attack/sustain shape of sung syllables and
 * keeps the wipe from visibly lagging behind the audio.
 */
internal fun resolveWordFillProgress(
    positionMs: Long,
    wordStartMs: Long,
    wordDurationMs: Long,
    fallbackEndMs: Long
): Float {
    if (positionMs <= wordStartMs) return 0f
    val duration = wordDurationMs.takeIf { it > 0L }
        // Legacy word-stamped LRC has no durations. Fill toward the next word,
        // but cap at 1.5 s so the last word of a line cannot crawl across a
        // long instrumental gap before the next line.
        ?: (fallbackEndMs - wordStartMs).coerceIn(0L, MAX_LEGACY_WORD_DURATION_MS)
            .takeIf { it > 0L }
        ?: return 1f
    val linear = ((positionMs - wordStartMs).toFloat() / duration).coerceIn(0f, 1f)
    return 1f - (1f - linear).pow(1.4f)
}

private const val MAX_LEGACY_WORD_DURATION_MS = 1_500L

/**
 * Distributes karaoke progress across the visual lines of a wrapped word.
 * Characters are consumed in reading order, so a two-line word fills the
 * first line completely before the second line starts. Returns one fraction
 * per line in `0f..1f`.
 */
internal fun resolveLineFillFractions(
    lineCharCounts: List<Int>,
    progress: Float
): List<Float> {
    val totalChars = lineCharCounts.sum()
    if (totalChars <= 0) return lineCharCounts.map { 0f }

    var remaining = progress.coerceIn(0f, 1f) * totalChars
    return lineCharCounts.map { count ->
        if (count <= 0) {
            0f
        } else {
            val filled = remaining.coerceIn(0f, count.toFloat())
            remaining -= filled
            filled / count
        }
    }
}

/**
 * Small visual anticipation applied to karaoke rendering. Playback position
 * updates arrive in coarse 250 ms ticks; leading the render by ~120 ms makes
 * syllables light up on the beat instead of after it. This is intentionally
 * separate from the user's manual lyrics sync offset so both still apply.
 */
private const val LYRICS_VISUAL_LEAD_MS = 120L

internal fun resolveSeekPositionMs(
    lineTimeMs: Long,
    lyricsSyncOffsetMs: Int
): Long = (lineTimeMs - lyricsSyncOffsetMs.toLong()).coerceAtLeast(0L)

internal data class HighlightZoneMetrics(
    val topPadding: Dp,
    val bottomPadding: Dp,
    val zoneHeight: Dp,
    val centerFromTop: Dp
)

internal fun calculateHighlightMetrics(
    containerHeight: Dp,
    highlightZoneFraction: Float,
    highlightOffset: Dp
): HighlightZoneMetrics {
    val container = containerHeight.value
    val zoneHeight = (containerHeight * highlightZoneFraction).value.coerceAtLeast(0f)
    val offset = highlightOffset.value
    val minCenter = zoneHeight / 2f
    val maxCenter = (container - zoneHeight / 2f).coerceAtLeast(minCenter)
    val unclampedCenter = container / 2f - offset
    val center = unclampedCenter.coerceIn(minCenter, maxCenter)
    val topPadding = (center - zoneHeight / 2f).coerceAtLeast(0f)
    val bottomPadding = (container - center - zoneHeight / 2f).coerceAtLeast(0f)

    return HighlightZoneMetrics(
        topPadding = topPadding.dp,
        bottomPadding = bottomPadding.dp,
        zoneHeight = zoneHeight.dp,
        centerFromTop = center.dp
    )
}

internal fun highlightSnapOffsetPx(
    viewportHeight: Int,
    itemSize: Int,
    highlightOffsetPx: Float
): Int {
    if (viewportHeight <= 0 || itemSize <= 0) return 0
    if (itemSize >= viewportHeight) return 0
    val viewport = viewportHeight.toFloat()
    val halfItem = itemSize / 2f
    val targetCenter = (viewport / 2f) - highlightOffsetPx
    val clampedCenter = targetCenter.coerceIn(halfItem, viewport - halfItem)
    return (clampedCenter - halfItem).roundToInt()
}

internal suspend fun animateToSnapIndex(
    listState: LazyListState,
    layoutInfo: SnapperLayoutInfo,
    targetIndex: Int,
    animationSpec: AnimationSpec<Float>
) {
    val distance = layoutInfo.distanceToIndexSnap(targetIndex)
    if (distance == 0) return

    listState.scroll {
        var previous = 0f
        AnimationState(initialValue = 0f).animateTo(
            targetValue = distance.toFloat(),
            animationSpec = animationSpec
        ) {
            val delta = value - previous
            val consumed = scrollBy(delta)
            previous = value
            if (abs(delta - consumed) > 0.5f) cancelAnimation()
        }
    }
}

internal suspend fun snapToSnapIndex(
    listState: LazyListState,
    layoutInfo: SnapperLayoutInfo,
    targetIndex: Int
) {
    val distance = layoutInfo.distanceToIndexSnap(targetIndex)
    if (distance == 0) return

    listState.scroll {
        scrollBy(distance.toFloat())
    }
}

internal fun resolveCurrentLineIndex(
    lines: List<SyncedLine>,
    position: Long
): Int {
    if (lines.isEmpty()) return -1

    return lines.withIndex().lastOrNull { (index, line) ->
        val nextTime = lines.getOrNull(index + 1)?.time ?: Int.MAX_VALUE
        val lineEndTime = resolveLineEndTimeMs(line, nextTime)
        position in line.time.toLong()..<lineEndTime
    }?.index ?: -1
}

@Composable
private fun LyricsTrackInfo(
    song: Song?,
    modifier: Modifier = Modifier,
    backgroundColor: Color,
    contentColor: Color,
    isPlaying: Boolean
) {
    if (song == null) return

    val albumShape = CircleShape

    // Helper state to stop rotation when paused, but we want it to pause in place?
    // Using infiniteTransition.animateFloat will reset on recomposition if spec changes or stops.
    // For a realistic vinyl pause, we need a manual Animatable that loops.
    // But for simplicity requested: "Animate the cover art to rotate... when music is playing".
    // If we just use conditional Modifier.graphicsLayer rotation, it might jump.
    // Let's use a simpler approach: if isPlaying, rotate.
    
    // Better approach for pausing rotation in place is non-trivial without a dedicated running time state.
    // Given the constraints, I will use a simple AnimatedVisibility or just let it reset, OR
    // use a monotonic clock if possible.
    // Let's stick to infinite transition for running, and maybe 0f for static?
    // Actually, user said "simulate a vinyl record". This implies continuous storage of rotation?
    // I'll try to implement continuous rotation.
    
    val currentRotation = remember { Animatable(0f) }
    
    LaunchedEffect(isPlaying) {
        if (isPlaying) {
            // Spin forever. 8s per revolution halves the effective per-second animation work
            // vs the original 4s cadence — visually still clearly a rotating "vinyl", but
            // drives fewer Compose invalidations during long listening sessions.
            while (true) {
                currentRotation.animateTo(
                    targetValue = currentRotation.value + 360f,
                    animationSpec = tween(8000, easing = LinearEasing)
                )
            }
        } else {
             currentRotation.stop()
        }
    }

    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        SmartImage(
            model = song.albumArtUriString ?: R.drawable.rounded_album_24,
            shape = albumShape,
            contentDescription = "Cover Art",
            modifier = Modifier
                .size(66.dp)
                .padding(6.dp)
                .graphicsLayer {
                    rotationZ = currentRotation.value % 360f
                }
                .clip(albumShape),
            contentScale = ContentScale.Crop
        )

        Column(
            modifier = Modifier
                .weight(1f, fill = false) // Allow shrinking if content is small
                .padding(vertical = 6.dp)
                .padding(end = 6.dp),
            verticalArrangement = Arrangement.Center
        ) {
            Text(
                text = song.title,
                style = MaterialTheme.typography.titleMedium.copy(
                    fontWeight = FontWeight.SemiBold,
                    color = contentColor,
                    //textGeometricTransform = TextGeometricTransform(scaleX = (0.9f)),
                ),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = song.displayArtist,
                style = MaterialTheme.typography.bodyMedium.copy(
                    color = contentColor.copy(alpha = 0.7f),
                    //textGeometricTransform = TextGeometricTransform(scaleX = (0.9f)),
                ),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }

        PlayingEqIcon(
            modifier = Modifier
                .padding(start = 8.dp, end = 18.dp)
                .size(width = 18.dp, height = 16.dp),
            color = contentColor,
            isPlaying = isPlaying
        )
    }
}
