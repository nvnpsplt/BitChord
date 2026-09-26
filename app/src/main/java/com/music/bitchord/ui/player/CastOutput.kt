package com.music.bitchord.ui.player

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.VolumeOff
import androidx.compose.material.icons.automirrored.rounded.VolumeUp
import androidx.compose.material.icons.rounded.CastConnected
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Speaker
import androidx.compose.material.icons.rounded.Tv
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.util.UnstableApi
import androidx.mediarouter.media.MediaRouter
import com.music.bitchord.R
import com.music.bitchord.playback.cast.CastStatus
import com.music.bitchord.playback.cast.CastSupport
import com.music.bitchord.ui.haptics.Haptic
import com.music.bitchord.ui.haptics.rememberHaptics
import kotlinx.coroutines.delay

/** One cast device the output sheet can offer, snapshotted off its MediaRouter route. */
internal data class CastDevice(
    val routeId: String,
    val name: String,
    val isTv: Boolean,
    val connectionState: Int,
    val isSelected: Boolean,
)

/**
 * Cast devices on this network, discovered for as long as the caller is on
 * screen — MediaRouter's active scan costs battery and Wi-Fi traffic, so it
 * runs while the output sheet is open and not a moment longer.
 */
@androidx.annotation.OptIn(UnstableApi::class)
@Composable
internal fun rememberCastDevices(enabled: Boolean): List<CastDevice> {
    val context = LocalContext.current
    var devices by remember { mutableStateOf(emptyList<CastDevice>()) }
    DisposableEffect(context, enabled) {
        if (!enabled) {
            devices = emptyList()
            return@DisposableEffect onDispose { }
        }
        CastSupport.initialize(context)
        val router = MediaRouter.getInstance(context)
        val selector = CastSupport.routeSelector
        var lastLogged: String? = null
        fun refresh() {
            val seen = router.routes.joinToString { route ->
                val categories = route.controlFilters.flatMap { f -> (0 until f.countCategories()).map(f::getCategory) }
                "${route.name}[default=${route.isDefaultOrBluetooth} enabled=${route.isEnabled} $categories]"
            }
            if (seen != lastLogged) {
                lastLogged = seen
                com.music.bitchord.data.TrackLog.d("BitChordCast", "routes: $seen")
            }
            devices = router.routes
                .filter(CastSupport::isCastRoute)
                .map {
                    CastDevice(
                        routeId = it.id,
                        name = it.name,
                        isTv = it.deviceType == MediaRouter.RouteInfo.DEVICE_TYPE_TV,
                        connectionState = it.connectionState,
                        isSelected = it.isSelected,
                    )
                }
                .sortedBy { it.name.lowercase() }
        }
        val callback = object : MediaRouter.Callback() {
            override fun onRouteAdded(router: MediaRouter, route: MediaRouter.RouteInfo) = refresh()
            override fun onRouteRemoved(router: MediaRouter, route: MediaRouter.RouteInfo) = refresh()
            override fun onRouteChanged(router: MediaRouter, route: MediaRouter.RouteInfo) = refresh()
            override fun onRouteSelected(router: MediaRouter, route: MediaRouter.RouteInfo, reason: Int) = refresh()
            override fun onRouteUnselected(router: MediaRouter, route: MediaRouter.RouteInfo, reason: Int) = refresh()
        }
        router.addCallback(
            selector,
            callback,
            MediaRouter.CALLBACK_FLAG_REQUEST_DISCOVERY or MediaRouter.CALLBACK_FLAG_PERFORM_ACTIVE_SCAN,
        )
        refresh()
        // The Cast framework adds its route provider to this process's
        // MediaRouter only once it has finished starting, which can land after
        // the sheet opens; a provider change fires none of the callbacks above.
        val handler = android.os.Handler(android.os.Looper.getMainLooper())
        val poll = object : Runnable {
            override fun run() {
                refresh()
                handler.postDelayed(this, ROUTE_POLL_MS)
            }
        }
        handler.postDelayed(poll, ROUTE_POLL_MS)
        onDispose {
            handler.removeCallbacks(poll)
            router.removeCallback(callback)
        }
    }
    return devices
}

/**
 * The Cast half of the output sheet: the devices on this Wi-Fi, which one is
 * playing, and the way back to the phone.
 */
@androidx.annotation.OptIn(UnstableApi::class)
@Composable
internal fun CastDevicesSection(devices: List<CastDevice>) {
    val context = LocalContext.current
    val cast by CastStatus.current.collectAsStateWithLifecycle()

    Text(
        text = stringResource(R.string.cast_section_title),
        style = MaterialTheme.typography.labelMedium,
        color = Color.White.copy(alpha = 0.55f),
        modifier = Modifier.padding(start = 6.dp, top = 4.dp, bottom = 2.dp),
    )
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (devices.isEmpty()) {
            // Searching for a while, then an honest answer and a way out: the
            // standard Cast picker, which also tells a filtering problem here
            // apart from there being no device to find.
            var searchedLong by remember { mutableStateOf(false) }
            LaunchedEffect(Unit) {
                delay(SEARCH_PATIENCE_MS)
                searchedLong = true
            }
            val failure = remember(searchedLong) { CastSupport.initFailure(context) }
            CastRow(
                icon = Icons.Rounded.CastConnected,
                title = stringResource(if (searchedLong) R.string.cast_none_found else R.string.cast_searching),
                subtitle = when {
                    failure != null -> failure
                    searchedLong -> stringResource(R.string.cast_open_picker)
                    else -> null
                },
                active = false,
                searching = !searchedLong,
                onClick = if (searchedLong) {
                    {
                        runCatching {
                            androidx.mediarouter.app.MediaRouteChooserDialog(context).apply {
                                routeSelector = CastSupport.routeSelector
                            }.show()
                        }
                    }
                } else {
                    null
                },
            )
        }
        devices.forEach { device ->
            val connecting = device.connectionState == MediaRouter.RouteInfo.CONNECTION_STATE_CONNECTING ||
                (device.isSelected && cast.phase == CastStatus.Phase.CONNECTING)
            val playing = device.isSelected && cast.isCasting
            CastRow(
                icon = if (device.isTv) Icons.Rounded.Tv else Icons.Rounded.Speaker,
                title = device.name,
                subtitle = when {
                    connecting -> stringResource(R.string.cast_connecting)
                    playing -> stringResource(R.string.cast_casting)
                    else -> null
                },
                active = playing,
                searching = connecting,
                onClick = if (playing || connecting) {
                    null
                } else {
                    {
                        MediaRouter.getInstance(context).routes
                            .firstOrNull { it.id == device.routeId }
                            ?.select()
                    }
                },
            )
        }
        if (cast.isActive) {
            CastRow(
                icon = Icons.Rounded.Close,
                title = stringResource(R.string.cast_stop),
                subtitle = null,
                active = false,
                searching = false,
                onClick = { CastSupport.endSession(context) },
            )
        }
    }
}

/**
 * What stops working on a receiver, said where the listener is looking when
 * they wonder: everything that shapes the sound runs on this phone's own
 * audio pipeline, and none of it travels with the stream.
 */
@Composable
internal fun CastEffectsNote() {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(ROW_SHAPE)
            .background(Color.White.copy(alpha = 0.05f))
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = Icons.Rounded.Info,
            contentDescription = null,
            tint = Color.White.copy(alpha = 0.55f),
            modifier = Modifier.size(18.dp),
        )
        Spacer(Modifier.width(12.dp))
        Text(
            text = stringResource(R.string.cast_local_effects_note),
            style = MaterialTheme.typography.labelMedium,
            color = Color.White.copy(alpha = 0.6f),
        )
    }
}

/**
 * The receiver's own volume, on the same slider as the phone's. Read back
 * twice a second while shown, because the TV remote and Google Home move it
 * too and neither tells this phone.
 */
@androidx.annotation.OptIn(UnstableApi::class)
@Composable
internal fun CastVolumeRow() {
    val context = LocalContext.current
    var level by remember { mutableFloatStateOf(CastSupport.deviceVolume(context) ?: 0f) }
    var dragging by remember { mutableStateOf(false) }
    LaunchedEffect(context) {
        while (true) {
            if (!dragging) CastSupport.deviceVolume(context)?.let { level = it }
            delay(CAST_VOLUME_POLL_MS)
        }
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(ROW_SHAPE)
            .background(Color.White.copy(alpha = 0.05f))
            .padding(horizontal = 16.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = if (level > 0f) Icons.AutoMirrored.Rounded.VolumeUp else Icons.AutoMirrored.Rounded.VolumeOff,
            contentDescription = null,
            tint = Color.White.copy(alpha = 0.6f),
            modifier = Modifier.size(20.dp),
        )
        Spacer(Modifier.width(12.dp))
        ThinSlider(
            value = level,
            onValueChange = {
                dragging = true
                level = it
                CastSupport.setDeviceVolume(context, it)
            },
            onValueChangeFinished = { dragging = false },
            idleHeight = 6.dp,
            activeHeight = 10.dp,
            modifier = Modifier.weight(1f),
        )
    }
}

/** A row in the output sheet's Cast section, drawn the way its phone outputs are. */
@Composable
private fun CastRow(
    icon: ImageVector,
    title: String,
    subtitle: String?,
    active: Boolean,
    searching: Boolean,
    onClick: (() -> Unit)?,
) {
    val haptics = rememberHaptics()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(ROW_SHAPE)
            .background(Color.White.copy(alpha = if (active) 0.10f else 0.05f))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                enabled = onClick != null,
            ) {
                haptics.play(Haptic.Select)
                onClick?.invoke()
            }
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(CircleShape)
                .background(Color.White.copy(alpha = if (active) 0.16f else 0.08f)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = Color.White.copy(alpha = if (active) 1f else 0.7f),
                modifier = Modifier.size(21.dp),
            )
        }
        Spacer(Modifier.width(13.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge.copy(
                    fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
                ),
                color = Color.White.copy(alpha = if (active) 1f else 0.85f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (subtitle != null) {
                Spacer(Modifier.height(2.dp))
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.labelMedium,
                    color = Color.White.copy(alpha = 0.55f),
                )
            }
        }
        when {
            searching -> CircularProgressIndicator(
                color = Color.White.copy(alpha = 0.6f),
                strokeWidth = 2.dp,
                modifier = Modifier.size(18.dp),
            )
            active -> Icon(
                imageVector = Icons.Rounded.Check,
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier.size(20.dp),
            )
        }
    }
}

private const val CAST_VOLUME_POLL_MS = 500L
private const val ROUTE_POLL_MS = 1_500L
private const val SEARCH_PATIENCE_MS = 8_000L
