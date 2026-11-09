package io.music_assistant.nowplaying.service

import android.annotation.SuppressLint
import android.app.*
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import android.util.Log
import android.view.*
import android.widget.*
import androidx.cardview.widget.CardView
import androidx.core.app.NotificationCompat
import coil.imageLoader
import coil.request.ImageRequest
import io.music_assistant.nowplaying.MainActivity
import io.music_assistant.nowplaying.R
import io.music_assistant.nowplaying.api.MusicAssistantClient
import io.music_assistant.nowplaying.model.PlayerInfo
import io.music_assistant.nowplaying.ui.AppDrawerActivity
import kotlin.math.abs

class OverlayService : Service(), MusicAssistantClient.Listener {

    private val TAG = "OverlayService"
    private lateinit var windowManager: WindowManager
    private var overlayView: View? = null
    private var windowLayoutParams: WindowManager.LayoutParams? = null

    // UI elements
    private var overlayRoot: View? = null
    private var bubbleView: View? = null
    private var imgBubbleIcon: ImageView? = null
    private var overlayCard: CardView? = null
    private var navOptionsLayout: View? = null
    private var btnNavBack: View? = null
    private var btnNavHome: View? = null
    private var btnNavApps: View? = null
    private var btnNavPlayer: View? = null
    private var btnOverlayToggleNav: ImageButton? = null
    private var btnOverlayClose: ImageButton? = null

    private var imgOverlayArtwork: ImageView? = null
    private var txtOverlayTitle: TextView? = null
    private var txtOverlayArtist: TextView? = null
    private var overlayProgress: ProgressBar? = null
    private var btnOverlayPlayPause: ImageButton? = null
    private var btnOverlayNext: ImageButton? = null

    private lateinit var mediaSession: MediaSessionCompat
    private val mainHandler = Handler(Looper.getMainLooper())
    private var currentPlayer: PlayerInfo? = null

    private var isOverlayAttached = false
    private var isBubbleMode = false
    private var isNavExpanded = false
    private var userDismissedOverlay = false

    private val progressUpdater = object : Runnable {
        override fun run() {
            currentPlayer?.let { p ->
                if (p.isPlaying && p.duration > 0) {
                    val estPos = p.getEstimatedPositionSeconds()
                    val fraction = (estPos / p.duration).coerceIn(0.0, 1.0)
                    overlayProgress?.progress = (fraction * 1000).toInt()
                }
            }
            if (isOverlayAttached) {
                mainHandler.postDelayed(this, 1000)
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager

        startForegroundNotification()
        setupMediaSession()

        MusicAssistantClient.instance.addListener(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action
        when (action) {
            ACTION_SHOW_OVERLAY -> {
                userDismissedOverlay = false
                val asBubble = if (intent.hasExtra(EXTRA_AS_BUBBLE)) {
                    intent.getBooleanExtra(EXTRA_AS_BUBBLE, false)
                } else null

                if (!isOverlayAttached) {
                    showOverlay(startInBubble = asBubble)
                } else if (asBubble == true) {
                    switchToBubbleMode()
                } else if (asBubble == false) {
                    switchToCardMode()
                }
            }
            ACTION_HIDE_OVERLAY -> {
                userDismissedOverlay = true
                hideOverlay()
            }
            else -> {
                val prefs = getSharedPreferences("ma_nowplaying_prefs", Context.MODE_PRIVATE)
                val autoOverlay = prefs.getBoolean("auto_overlay", true)
                if (autoOverlay && !isOverlayAttached) {
                    showOverlay()
                }
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        MusicAssistantClient.instance.removeListener(this)
        mainHandler.removeCallbacks(progressUpdater)
        hideOverlay()
        mediaSession.isActive = false
        mediaSession.release()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun startForegroundNotification() {
        val channelId = "ma_nowplaying_service"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId,
                "Now Playing Overlay Service",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Shows floating navigation and media controls"
            }
            val nm = getSystemService(NotificationManager::class.java)
            nm?.createNotificationChannel(channel)
        }

        val launchIntent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, launchIntent,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0
        )

        val notification = NotificationCompat.Builder(this, channelId)
            .setContentTitle("Echo Navigation & Now Playing")
            .setContentText("Assistive navigation overlay active")
            .setSmallIcon(R.drawable.baseline_library_music_24)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()

        startForeground(1001, notification)
    }

    private fun setupMediaSession() {
        mediaSession = MediaSessionCompat(this, "MusicAssistantEcho").apply {
            setFlags(
                MediaSessionCompat.FLAG_HANDLES_MEDIA_BUTTONS or
                MediaSessionCompat.FLAG_HANDLES_TRANSPORT_CONTROLS
            )
            setCallback(object : MediaSessionCompat.Callback() {
                override fun onPlay() {
                    MusicAssistantClient.instance.playPause()
                }

                override fun onPause() {
                    MusicAssistantClient.instance.playPause()
                }

                override fun onSkipToNext() {
                    MusicAssistantClient.instance.next()
                }

                override fun onSkipToPrevious() {
                    MusicAssistantClient.instance.previous()
                }

                override fun onSeekTo(pos: Long) {
                    MusicAssistantClient.instance.seek(pos / 1000)
                }
            })
            isActive = true
        }
    }

    private fun updateMediaSession(player: PlayerInfo) {
        val stateBuilder = PlaybackStateCompat.Builder()
            .setActions(
                PlaybackStateCompat.ACTION_PLAY or
                PlaybackStateCompat.ACTION_PAUSE or
                PlaybackStateCompat.ACTION_PLAY_PAUSE or
                PlaybackStateCompat.ACTION_SKIP_TO_NEXT or
                PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS or
                PlaybackStateCompat.ACTION_SEEK_TO
            )

        val state = if (player.isPlaying) {
            PlaybackStateCompat.STATE_PLAYING
        } else if (player.isPaused) {
            PlaybackStateCompat.STATE_PAUSED
        } else {
            PlaybackStateCompat.STATE_STOPPED
        }

        val posMs = (player.getEstimatedPositionSeconds() * 1000).toLong()
        stateBuilder.setState(state, posMs, if (player.isPlaying) 1.0f else 0.0f)
        mediaSession.setPlaybackState(stateBuilder.build())

        val metaBuilder = MediaMetadataCompat.Builder()
            .putString(MediaMetadataCompat.METADATA_KEY_TITLE, player.currentTitle ?: "Unknown")
            .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, player.currentArtist ?: "")
            .putString(MediaMetadataCompat.METADATA_KEY_ALBUM, player.currentAlbum ?: "")
            .putLong(MediaMetadataCompat.METADATA_KEY_DURATION, (player.duration * 1000).toLong())

        mediaSession.setMetadata(metaBuilder.build())
    }

    private fun canDrawOverlays(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            Settings.canDrawOverlays(this)
        } else {
            true
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun showOverlay(startInBubble: Boolean? = null) {
        if (isOverlayAttached || !canDrawOverlays()) return

        val themedContext = ContextThemeWrapper(this, R.style.Theme_EchoNowPlaying)
        val inflater = LayoutInflater.from(themedContext)
        val view = inflater.inflate(R.layout.overlay_player, null)
        overlayView = view

        overlayRoot = view.findViewById(R.id.overlayRoot)
        bubbleView = view.findViewById(R.id.bubbleView)
        imgBubbleIcon = view.findViewById(R.id.imgBubbleIcon)
        overlayCard = view.findViewById(R.id.overlayCard)
        navOptionsLayout = view.findViewById(R.id.navOptionsLayout)
        btnNavBack = view.findViewById(R.id.btnNavBack)
        btnNavHome = view.findViewById(R.id.btnNavHome)
        btnNavApps = view.findViewById(R.id.btnNavApps)
        btnNavPlayer = view.findViewById(R.id.btnNavPlayer)
        btnOverlayToggleNav = view.findViewById(R.id.btnOverlayToggleNav)
        btnOverlayClose = view.findViewById(R.id.btnOverlayClose)

        imgOverlayArtwork = view.findViewById(R.id.imgOverlayArtwork)
        txtOverlayTitle = view.findViewById(R.id.txtOverlayTitle)
        txtOverlayArtist = view.findViewById(R.id.txtOverlayArtist)
        overlayProgress = view.findViewById(R.id.overlayProgress)
        btnOverlayPlayPause = view.findViewById(R.id.btnOverlayPlayPause)
        btnOverlayNext = view.findViewById(R.id.btnOverlayNext)

        val layoutType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_SYSTEM_ALERT
        }

        val dm = resources.displayMetrics
        val prefs = getSharedPreferences("ma_nowplaying_prefs", Context.MODE_PRIVATE)
        val defaultBubble = prefs.getBoolean("overlay_bubble_mode", currentPlayer?.isPlaying != true)
        isBubbleMode = startInBubble ?: defaultBubble

        val bubbleSize = (58 * dm.density).toInt()
        val cardWidth = (430 * dm.density).toInt()

        val initialWidth = if (isBubbleMode) bubbleSize else cardWidth
        val initialHeight = if (isBubbleMode) bubbleSize else WindowManager.LayoutParams.WRAP_CONTENT

        val savedX = prefs.getInt("overlay_pos_x", 40)
        val savedY = prefs.getInt("overlay_pos_y", 40)

        val params = WindowManager.LayoutParams(
            initialWidth,
            initialHeight,
            layoutType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = savedX
            y = savedY
        }
        windowLayoutParams = params

        if (isBubbleMode) {
            bubbleView?.visibility = View.VISIBLE
            overlayCard?.visibility = View.GONE
        } else {
            bubbleView?.visibility = View.GONE
            overlayCard?.visibility = View.VISIBLE
        }

        // --- Touch handling for Bubble Mode (Draggable & Tap to expand) ---
        var bubbleInitialX = 0
        var bubbleInitialY = 0
        var bubbleTouchDownX = 0f
        var bubbleTouchDownY = 0f
        var bubbleIsDrag = false

        bubbleView?.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    bubbleInitialX = params.x
                    bubbleInitialY = params.y
                    bubbleTouchDownX = event.rawX
                    bubbleTouchDownY = event.rawY
                    bubbleIsDrag = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - bubbleTouchDownX
                    val dy = event.rawY - bubbleTouchDownY
                    if (abs(dx) > 10 || abs(dy) > 10) {
                        bubbleIsDrag = true
                    }
                    if (bubbleIsDrag) {
                        val screenW = resources.displayMetrics.widthPixels
                        val screenH = resources.displayMetrics.heightPixels
                        params.x = (bubbleInitialX + dx.toInt()).coerceIn(0, (screenW - params.width).coerceAtLeast(0))
                        params.y = (bubbleInitialY + dy.toInt()).coerceIn(0, (screenH - params.height).coerceAtLeast(0))
                        if (isOverlayAttached && overlayView != null) {
                            windowManager.updateViewLayout(overlayView, params)
                        }
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (bubbleIsDrag) {
                        saveOverlayPosition(params.x, params.y)
                    } else {
                        // User tapped the bubble dot -> expand to card with nav options visible!
                        Log.i(TAG, "Bubble tapped -> expanding to card with nav options")
                        switchToCardMode(showNav = true)
                    }
                    true
                }
                else -> false
            }
        }

        // --- Drag tracking for Card Mode via main player row ---
        val mainPlayerRow = view.findViewById<View>(R.id.mainPlayerRow)
        var cardInitialX = 0
        var cardInitialY = 0
        var cardTouchDownX = 0f
        var cardTouchDownY = 0f
        var cardIsDrag = false

        mainPlayerRow?.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    cardInitialX = params.x
                    cardInitialY = params.y
                    cardTouchDownX = event.rawX
                    cardTouchDownY = event.rawY
                    cardIsDrag = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - cardTouchDownX
                    val dy = event.rawY - cardTouchDownY
                    if (abs(dx) > 10 || abs(dy) > 10) {
                        cardIsDrag = true
                    }
                    if (cardIsDrag) {
                        val screenW = resources.displayMetrics.widthPixels
                        val screenH = resources.displayMetrics.heightPixels
                        val newX = cardInitialX + dx.toInt()
                        val newY = cardInitialY + dy.toInt()

                        params.x = newX.coerceIn(0, (screenW - params.width).coerceAtLeast(0))
                        params.y = newY.coerceIn(0, (screenH - (overlayView?.height ?: 120)).coerceAtLeast(0))

                        if (isOverlayAttached && overlayView != null) {
                            windowManager.updateViewLayout(overlayView, params)
                        }
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (cardIsDrag) {
                        saveOverlayPosition(params.x, params.y)
                    }
                    true
                }
                else -> false
            }
        }

        // Click on Album Artwork thumbnail opens MainActivity
        val cardArtwork = view.findViewById<View>(R.id.cardArtwork)
        cardArtwork?.setOnClickListener {
            val intent = Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
            }
            startActivity(intent)
        }

        // Toggle Navigation Bar (Expandable Options List)
        btnOverlayToggleNav?.setOnClickListener {
            toggleNav()
        }

        // Nav Action: Back
        btnNavBack?.setOnClickListener {
            Log.i(TAG, "Nav Action: Back tapped")
            NavAccessibilityService.triggerBack(this)
            toggleNav(false)
        }

        // Nav Action: Home
        btnNavHome?.setOnClickListener {
            Log.i(TAG, "Nav Action: Home tapped")
            NavAccessibilityService.triggerHome(this)
            toggleNav(false)
        }

        // Nav Action: Apps (App Drawer)
        btnNavApps?.setOnClickListener {
            Log.i(TAG, "Nav Action: Apps tapped")
            AppDrawerActivity.launch(this)
            toggleNav(false)
        }

        // Nav Action: Player (MainActivity)
        btnNavPlayer?.setOnClickListener {
            Log.i(TAG, "Nav Action: Player tapped")
            val intent = Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
            }
            startActivity(intent)
            toggleNav(false)
        }

        // Close Overlay Button -> collapses to minimal bubble dot!
        btnOverlayClose?.setOnClickListener {
            Log.i(TAG, "Close tapped -> collapsing to minimal bubble dot")
            switchToBubbleMode()
        }

        // Media Controls
        btnOverlayPlayPause?.setOnClickListener {
            MusicAssistantClient.instance.playPause()
        }

        btnOverlayNext?.setOnClickListener {
            MusicAssistantClient.instance.next()
        }

        try {
            windowManager.addView(view, params)
            isOverlayAttached = true
            isNavExpanded = false
            mainHandler.post(progressUpdater)
            currentPlayer?.let { bindOverlayUI(it) }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to add overlay window", e)
        }
    }

    private fun switchToBubbleMode() {
        if (!isOverlayAttached || overlayView == null) return
        isBubbleMode = true
        overlayCard?.visibility = View.GONE
        bubbleView?.visibility = View.VISIBLE

        val dm = resources.displayMetrics
        val bubbleSize = (58 * dm.density).toInt()
        windowLayoutParams?.let { params ->
            params.width = bubbleSize
            params.height = bubbleSize
            try {
                windowManager.updateViewLayout(overlayView, params)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to update layout to bubble mode", e)
            }
        }
        getSharedPreferences("ma_nowplaying_prefs", Context.MODE_PRIVATE)
            .edit()
            .putBoolean("overlay_bubble_mode", true)
            .apply()
    }

    private fun switchToCardMode(showNav: Boolean = false) {
        if (!isOverlayAttached || overlayView == null) return
        isBubbleMode = false
        bubbleView?.visibility = View.GONE
        overlayCard?.visibility = View.VISIBLE

        if (showNav) {
            toggleNav(true)
        }

        val dm = resources.displayMetrics
        val cardWidth = (430 * dm.density).toInt()
        val screenW = dm.widthPixels
        val screenH = dm.heightPixels

        windowLayoutParams?.let { params ->
            params.width = cardWidth
            params.height = WindowManager.LayoutParams.WRAP_CONTENT

            // Adjust position so expanded card is not clipped outside the screen
            if (params.x + cardWidth > screenW) {
                params.x = (screenW - cardWidth - 16).coerceAtLeast(0)
            }
            if (params.y + 220 > screenH) {
                params.y = (screenH - 220).coerceAtLeast(0)
            }

            try {
                windowManager.updateViewLayout(overlayView, params)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to update layout to card mode", e)
            }
        }
        getSharedPreferences("ma_nowplaying_prefs", Context.MODE_PRIVATE)
            .edit()
            .putBoolean("overlay_bubble_mode", false)
            .apply()
    }

    private fun saveOverlayPosition(x: Int, y: Int) {
        getSharedPreferences("ma_nowplaying_prefs", Context.MODE_PRIVATE)
            .edit()
            .putInt("overlay_pos_x", x)
            .putInt("overlay_pos_y", y)
            .apply()
    }

    private fun toggleNav(expanded: Boolean? = null) {
        isNavExpanded = expanded ?: !isNavExpanded
        navOptionsLayout?.visibility = if (isNavExpanded) View.VISIBLE else View.GONE
        btnOverlayToggleNav?.imageTintList = ColorStateList.valueOf(
            if (isNavExpanded) 0xFF4FC3F7.toInt() else 0xFFFFFFFF.toInt()
        )

        if (isOverlayAttached && overlayView != null && windowLayoutParams != null) {
            try {
                windowManager.updateViewLayout(overlayView, windowLayoutParams)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to update view layout on toggleNav", e)
            }
        }
    }

    private fun hideOverlay() {
        if (!isOverlayAttached || overlayView == null) return
        mainHandler.removeCallbacks(progressUpdater)
        try {
            windowManager.removeView(overlayView)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to remove overlay window", e)
        } finally {
            isOverlayAttached = false
            overlayView = null
        }
    }

    private fun bindOverlayUI(player: PlayerInfo) {
        if (!isOverlayAttached) return

        txtOverlayTitle?.text = player.currentTitle ?: getString(R.string.no_media)
        txtOverlayArtist?.text = player.currentArtist ?: ""

        val playIcon = if (player.isPlaying) {
            R.drawable.baseline_pause_24
        } else {
            R.drawable.baseline_play_arrow_24
        }
        btnOverlayPlayPause?.setImageResource(playIcon)

        val imgUrl = player.imageUrl
        if (!imgUrl.isNullOrEmpty() && imgOverlayArtwork != null) {
            val req = ImageRequest.Builder(this)
                .data(imgUrl)
                .target(
                    onStart = {
                        imgOverlayArtwork?.imageTintList = ColorStateList.valueOf(0x60FFFFFF.toInt())
                        imgOverlayArtwork?.setImageResource(R.drawable.baseline_library_music_24)
                    },
                    onSuccess = { drawable ->
                        imgOverlayArtwork?.imageTintList = null
                        imgOverlayArtwork?.setImageDrawable(drawable)
                    },
                    onError = {
                        imgOverlayArtwork?.imageTintList = ColorStateList.valueOf(0x60FFFFFF.toInt())
                        imgOverlayArtwork?.setImageResource(R.drawable.baseline_library_music_24)
                    }
                )
                .crossfade(true)
                .build()
            imageLoader.enqueue(req)
        } else {
            imgOverlayArtwork?.imageTintList = ColorStateList.valueOf(0x60FFFFFF.toInt())
            imgOverlayArtwork?.setImageResource(R.drawable.baseline_library_music_24)
        }

        if (player.duration > 0) {
            val fraction = (player.getEstimatedPositionSeconds() / player.duration).coerceIn(0.0, 1.0)
            overlayProgress?.progress = (fraction * 1000).toInt()
        } else {
            overlayProgress?.progress = 0
        }
    }

    // MusicAssistantClient.Listener
    override fun onConnectionStateChanged(connected: Boolean, errorMsg: String?) {}

    override fun onPlayerUpdated(player: PlayerInfo) {
        currentPlayer = player
        updateMediaSession(player)

        val prefs = getSharedPreferences("ma_nowplaying_prefs", Context.MODE_PRIVATE)
        val autoOverlay = prefs.getBoolean("auto_overlay", true)

        if (!isOverlayAttached && autoOverlay && !userDismissedOverlay) {
            val startInBubble = prefs.getBoolean("overlay_bubble_mode", !player.isPlaying)
            showOverlay(startInBubble = startInBubble)
        } else if (isOverlayAttached) {
            bindOverlayUI(player)
        }
    }

    override fun onPlayersListUpdated(players: List<PlayerInfo>) {}

    companion object {
        const val ACTION_SHOW_OVERLAY = "io.music_assistant.nowplaying.ACTION_SHOW_OVERLAY"
        const val ACTION_HIDE_OVERLAY = "io.music_assistant.nowplaying.ACTION_HIDE_OVERLAY"
        const val EXTRA_AS_BUBBLE = "extra_as_bubble"

        fun start(context: Context) {
            val intent = Intent(context, OverlayService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun showOverlay(context: Context, asBubble: Boolean? = null) {
            val intent = Intent(context, OverlayService::class.java).apply {
                action = ACTION_SHOW_OVERLAY
                if (asBubble != null) {
                    putExtra(EXTRA_AS_BUBBLE, asBubble)
                }
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun hideOverlay(context: Context) {
            val intent = Intent(context, OverlayService::class.java).apply {
                action = ACTION_HIDE_OVERLAY
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }
    }
}
