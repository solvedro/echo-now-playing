package io.music_assistant.nowplaying

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.View
import android.view.WindowManager
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.LinearSmoothScroller
import androidx.recyclerview.widget.RecyclerView
import coil.imageLoader
import coil.request.ImageRequest
import io.music_assistant.nowplaying.api.LyricsRepository
import io.music_assistant.nowplaying.api.MusicAssistantClient
import io.music_assistant.nowplaying.databinding.ActivityMainBinding
import io.music_assistant.nowplaying.databinding.DialogSettingsBinding
import io.music_assistant.nowplaying.model.PlayerInfo
import io.music_assistant.nowplaying.model.TrackLyrics
import io.music_assistant.nowplaying.service.NavAccessibilityService
import io.music_assistant.nowplaying.service.OverlayService
import io.music_assistant.nowplaying.ui.AppDrawerActivity
import io.music_assistant.nowplaying.ui.LyricsAdapter

class MainActivity : AppCompatActivity(), MusicAssistantClient.Listener {

    private lateinit var binding: ActivityMainBinding
    private lateinit var prefs: SharedPreferences
    private val mainHandler = Handler(Looper.getMainLooper())

    private var isUserTrackingProgress = false
    private var isUserTrackingVolume = false
    private var currentPlayer: PlayerInfo? = null

    // Lyrics support
    private lateinit var lyricsAdapter: LyricsAdapter
    private var currentLyrics: TrackLyrics? = null
    private var isShowingLyrics: Boolean = false
    private var lastLyricsTrackKey: String? = null
    private var isUserScrollingLyrics: Boolean = false
    private val resumeLyricsAutoScrollRunnable = Runnable { isUserScrollingLyrics = false }

    private val progressTicker = object : Runnable {
        override fun run() {
            updatePlaybackProgress()
            mainHandler.postDelayed(this, 350)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        prefs = getSharedPreferences("ma_nowplaying_prefs", Context.MODE_PRIVATE)

        setupFullscreen()
        setupLyrics()
        setupListeners()

        // Start overlay background service
        OverlayService.start(this)
    }

    override fun onResume() {
        super.onResume()
        applyKeepScreenOn()
        MusicAssistantClient.instance.addListener(this)
        mainHandler.post(progressTicker)
        OverlayService.hideOverlay(this)
    }

    override fun onPause() {
        super.onPause()
        MusicAssistantClient.instance.removeListener(this)
        mainHandler.removeCallbacks(progressTicker)
        val shouldShowOverlay = prefs.getBoolean("auto_overlay", true) ||
            prefs.getBoolean("floating_nav_overlay", true)
        if (shouldShowOverlay) {
            OverlayService.showOverlay(this)
        }
    }

    private fun setupFullscreen() {
        window.decorView.systemUiVisibility = (
            View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_FULLSCREEN
                or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
        )
    }

    private fun applyKeepScreenOn() {
        val keepOn = prefs.getBoolean("keep_screen_on", true)
        if (keepOn) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    private fun setupListeners() {
        binding.btnPlayPause.setOnClickListener {
            MusicAssistantClient.instance.playPause()
        }

        binding.btnNext.setOnClickListener {
            MusicAssistantClient.instance.next()
        }

        binding.btnPrevious.setOnClickListener {
            MusicAssistantClient.instance.previous()
        }

        binding.btnMute.setOnClickListener {
            val muted = currentPlayer?.isMuted ?: false
            MusicAssistantClient.instance.setMute(!muted)
        }

        binding.btnHome.setOnClickListener {
            NavAccessibilityService.triggerHome(this)
        }

        binding.btnApps.setOnClickListener {
            AppDrawerActivity.launch(this)
        }

        binding.btnAmbientHome.setOnClickListener {
            NavAccessibilityService.triggerHome(this)
        }

        binding.btnAmbientApps.setOnClickListener {
            AppDrawerActivity.launch(this)
        }

        binding.btnSettings.setOnClickListener {
            showSettingsDialog()
        }

        binding.btnAmbientSettings.setOnClickListener {
            showSettingsDialog()
        }

        binding.btnOverlay.setOnClickListener {
            switchToOverlayMode()
        }

        binding.btnLyrics.setOnClickListener {
            toggleLyrics()
        }

        binding.artworkCard.setOnClickListener {
            toggleLyrics()
        }

        binding.lyricsArtworkCard.setOnClickListener {
            toggleLyrics()
        }

        binding.btnLyricsPlayPause.setOnClickListener {
            MusicAssistantClient.instance.playPause()
        }

        binding.btnLyricsPrevious.setOnClickListener {
            MusicAssistantClient.instance.previous()
        }

        binding.btnLyricsNext.setOnClickListener {
            MusicAssistantClient.instance.next()
        }

        binding.seekBarProgress.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser && currentPlayer != null) {
                    val duration = currentPlayer!!.duration
                    val seekSec = (progress / 1000.0) * duration
                    binding.txtElapsed.text = formatTime(seekSec)
                    binding.txtLyricsElapsed.text = formatTime(seekSec)
                }
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) {
                isUserTrackingProgress = true
            }

            override fun onStopTrackingTouch(seekBar: SeekBar?) {
                isUserTrackingProgress = false
                val dur = currentPlayer?.duration ?: 0.0
                if (dur > 0 && seekBar != null) {
                    val targetSec = ((seekBar.progress / 1000.0) * dur).toLong()
                    MusicAssistantClient.instance.seek(targetSec)
                }
            }
        })

        binding.seekBarLyricsProgress.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser && currentPlayer != null) {
                    val duration = currentPlayer!!.duration
                    val seekSec = (progress / 1000.0) * duration
                    binding.txtElapsed.text = formatTime(seekSec)
                    binding.txtLyricsElapsed.text = formatTime(seekSec)
                }
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) {
                isUserTrackingProgress = true
            }

            override fun onStopTrackingTouch(seekBar: SeekBar?) {
                isUserTrackingProgress = false
                val dur = currentPlayer?.duration ?: 0.0
                if (dur > 0 && seekBar != null) {
                    val targetSec = ((seekBar.progress / 1000.0) * dur).toLong()
                    MusicAssistantClient.instance.seek(targetSec)
                }
            }
        })

        binding.seekBarVolume.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    binding.txtVolumePercent.text = "$progress%"
                    binding.txtLyricsVolumePercent.text = "$progress%"
                }
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) {
                isUserTrackingVolume = true
            }

            override fun onStopTrackingTouch(seekBar: SeekBar?) {
                isUserTrackingVolume = false
                seekBar?.let {
                    MusicAssistantClient.instance.setVolume(it.progress)
                }
            }
        })

        binding.seekBarLyricsVolume.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    binding.txtVolumePercent.text = "$progress%"
                    binding.txtLyricsVolumePercent.text = "$progress%"
                }
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) {
                isUserTrackingVolume = true
            }

            override fun onStopTrackingTouch(seekBar: SeekBar?) {
                isUserTrackingVolume = false
                seekBar?.let {
                    MusicAssistantClient.instance.setVolume(it.progress)
                }
            }
        })
    }

    private fun switchToOverlayMode() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
            Toast.makeText(this, "Please grant Overlay permission to enable floating player", Toast.LENGTH_LONG).show()
            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")
            )
            startActivity(intent)
            return
        }

        OverlayService.showOverlay(this)
        moveTaskToBack(true)
    }

    private fun setupLyrics() {
        lyricsAdapter = LyricsAdapter { line ->
            if (line.timeSeconds >= 0) {
                MusicAssistantClient.instance.seek(line.timeSeconds.toLong())
            }
        }
        binding.recyclerLyrics.apply {
            layoutManager = LinearLayoutManager(this@MainActivity)
            adapter = lyricsAdapter
            addOnScrollListener(object : RecyclerView.OnScrollListener() {
                override fun onScrollStateChanged(recyclerView: RecyclerView, newState: Int) {
                    if (newState == RecyclerView.SCROLL_STATE_DRAGGING) {
                        isUserScrollingLyrics = true
                        mainHandler.removeCallbacks(resumeLyricsAutoScrollRunnable)
                    } else if (newState == RecyclerView.SCROLL_STATE_IDLE) {
                        mainHandler.removeCallbacks(resumeLyricsAutoScrollRunnable)
                        mainHandler.postDelayed(resumeLyricsAutoScrollRunnable, 3500)
                    }
                }
            })
        }
    }

    private fun toggleLyrics() {
        showLyrics(!isShowingLyrics)
    }

    private fun showLyrics(show: Boolean) {
        isShowingLyrics = show
        if (show) {
            binding.contentLayout.visibility = View.GONE
            binding.lyricsPlayerLayout.visibility = View.VISIBLE
            binding.btnLyrics.setBackgroundResource(R.drawable.bg_lyrics_pill_active)
            binding.imgLyricsIcon.imageTintList = android.content.res.ColorStateList.valueOf(Color.WHITE)
            binding.txtLyricsBtnLabel.setTextColor(Color.WHITE)
            val active = lyricsAdapter.activeIndex
            if (active >= 0) {
                centerLyricPosition(active)
            }
        } else {
            binding.lyricsPlayerLayout.visibility = View.GONE
            binding.contentLayout.visibility = View.VISIBLE
            binding.btnLyrics.setBackgroundResource(R.drawable.bg_rounded_card)
            binding.imgLyricsIcon.imageTintList = android.content.res.ColorStateList.valueOf(Color.parseColor("#90FFFFFF"))
            binding.txtLyricsBtnLabel.setTextColor(Color.parseColor("#90FFFFFF"))
        }
    }

    private fun centerLyricPosition(position: Int) {
        val lm = binding.recyclerLyrics.layoutManager as? LinearLayoutManager ?: return
        val scroller = object : LinearSmoothScroller(this) {
            override fun calculateDtToFit(
                viewStart: Int,
                viewEnd: Int,
                boxStart: Int,
                boxEnd: Int,
                snapPreference: Int
            ): Int {
                return (boxStart + (boxEnd - boxStart) / 2) - (viewStart + (viewEnd - viewStart) / 2)
            }
        }
        scroller.targetPosition = position
        lm.startSmoothScroll(scroller)
    }

    private fun updatePlaybackProgress() {
        if (isUserTrackingProgress) return
        val player = currentPlayer ?: return

        val estPos: Double
        if (player.isPlaying && player.duration > 0.0) {
            estPos = player.getEstimatedPositionSeconds()
            val fraction = (estPos / player.duration).coerceIn(0.0, 1.0)
            val prog = (fraction * 1000).toInt()
            val elStr = formatTime(estPos)
            val durStr = formatTime(player.duration)
            binding.seekBarProgress.progress = prog
            binding.txtElapsed.text = elStr
            binding.txtDuration.text = durStr

            binding.seekBarLyricsProgress.progress = prog
            binding.txtLyricsElapsed.text = elStr
            binding.txtLyricsDuration.text = durStr
        } else if (player.duration > 0.0) {
            estPos = player.elapsedTime
            val fraction = (player.elapsedTime / player.duration).coerceIn(0.0, 1.0)
            val prog = (fraction * 1000).toInt()
            val elStr = formatTime(player.elapsedTime)
            val durStr = formatTime(player.duration)
            binding.seekBarProgress.progress = prog
            binding.txtElapsed.text = elStr
            binding.txtDuration.text = durStr

            binding.seekBarLyricsProgress.progress = prog
            binding.txtLyricsElapsed.text = elStr
            binding.txtLyricsDuration.text = durStr
        } else {
            estPos = 0.0
            binding.seekBarProgress.progress = 0
            binding.txtElapsed.text = "00:00"
            binding.txtDuration.text = "00:00"

            binding.seekBarLyricsProgress.progress = 0
            binding.txtLyricsElapsed.text = "00:00"
            binding.txtLyricsDuration.text = "00:00"
        }

        // Synchronized lyrics update
        val lyrics = currentLyrics
        if (lyrics != null && lyrics.isSynced) {
            val activeIdx = lyrics.findActiveIndex(estPos)
            val changed = lyricsAdapter.setActiveIndex(activeIdx)
            if (changed && activeIdx >= 0 && !isUserScrollingLyrics && isShowingLyrics) {
                centerLyricPosition(activeIdx)
            }
        }
    }

    private fun bindPlayerUI(player: PlayerInfo) {
        currentPlayer = player
        binding.txtPlayerName.text = player.name

        val hasMedia = !player.currentTitle.isNullOrEmpty() || player.isPlaying || player.isPaused
        if (!hasMedia && player.state == "idle") {
            binding.ambientLayout.visibility = View.VISIBLE
            binding.txtAmbientMessage.text = "Ready to stream • ${player.name}"
            showLyrics(false)
            return
        } else {
            binding.ambientLayout.visibility = View.GONE
        }

        val titleText = player.currentTitle ?: getString(R.string.no_media)
        binding.txtTitle.text = titleText
        binding.txtLyricsTrackTitle.text = titleText

        binding.txtArtist.text = player.currentArtist ?: ""
        binding.txtLyricsArtistName.text = player.currentArtist ?: ""
        binding.txtLyricsArtistName.visibility = if (player.currentArtist.isNullOrEmpty()) View.GONE else View.VISIBLE

        binding.txtAlbum.text = player.currentAlbum ?: ""
        binding.txtAlbum.visibility = if (player.currentAlbum.isNullOrEmpty()) View.GONE else View.VISIBLE
        binding.txtLyricsAlbumName.text = player.currentAlbum ?: ""
        binding.txtLyricsAlbumName.visibility = if (player.currentAlbum.isNullOrEmpty()) View.GONE else View.VISIBLE

        val playRes = if (player.isPlaying) {
            R.drawable.baseline_pause_24
        } else {
            R.drawable.baseline_play_arrow_24
        }
        binding.btnPlayPause.setImageResource(playRes)
        binding.btnLyricsPlayPause.setImageResource(playRes)

        val muteRes = if (player.isMuted) {
            R.drawable.ic_volume_off
        } else {
            R.drawable.ic_volume_up
        }
        binding.btnMute.setImageResource(muteRes)

        if (!isUserTrackingVolume) {
            binding.seekBarVolume.progress = player.volume
            binding.txtVolumePercent.text = "${player.volume}%"
            binding.seekBarLyricsVolume.progress = player.volume
            binding.txtLyricsVolumePercent.text = "${player.volume}%"
        }

        binding.txtLyricsDevicePill.text = player.name

        val imgUrl = player.imageUrl
        android.util.Log.i("MainActivity", "bindPlayerUI: title=${player.currentTitle}, img=$imgUrl")
        if (!imgUrl.isNullOrEmpty()) {
            val req = ImageRequest.Builder(this)
                .data(imgUrl)
                .target(
                    onStart = {
                        binding.imgAlbumArt.imageTintList = android.content.res.ColorStateList.valueOf(0x60FFFFFF.toInt())
                        binding.imgAlbumArt.setImageResource(R.drawable.baseline_library_music_24)
                        binding.imgLyricsAlbumArt.imageTintList = android.content.res.ColorStateList.valueOf(0x60FFFFFF.toInt())
                        binding.imgLyricsAlbumArt.setImageResource(R.drawable.baseline_library_music_24)
                    },
                    onSuccess = { drawable ->
                        binding.imgAlbumArt.imageTintList = null
                        binding.imgAlbumArt.setImageDrawable(drawable)
                        binding.imgLyricsAlbumArt.imageTintList = null
                        binding.imgLyricsAlbumArt.setImageDrawable(drawable)
                        android.util.Log.i("MainActivity", "Coil loaded image successfully: $imgUrl")
                    },
                    onError = {
                        binding.imgAlbumArt.imageTintList = android.content.res.ColorStateList.valueOf(0x60FFFFFF.toInt())
                        binding.imgAlbumArt.setImageResource(R.drawable.baseline_library_music_24)
                        binding.imgLyricsAlbumArt.imageTintList = android.content.res.ColorStateList.valueOf(0x60FFFFFF.toInt())
                        binding.imgLyricsAlbumArt.setImageResource(R.drawable.baseline_library_music_24)
                        android.util.Log.e("MainActivity", "Coil failed to load image: $imgUrl")
                    }
                )
                .crossfade(true)
                .build()
            imageLoader.enqueue(req)
        } else {
            binding.imgAlbumArt.imageTintList = android.content.res.ColorStateList.valueOf(0x60FFFFFF.toInt())
            binding.imgAlbumArt.setImageResource(R.drawable.baseline_library_music_24)
            binding.imgLyricsAlbumArt.imageTintList = android.content.res.ColorStateList.valueOf(0x60FFFFFF.toInt())
            binding.imgLyricsAlbumArt.setImageResource(R.drawable.baseline_library_music_24)
        }

        // Lyrics check & update
        val trackKey = "${player.currentArtist ?: ""} - ${player.currentTitle ?: ""}"
        if (trackKey != lastLyricsTrackKey) {
            lastLyricsTrackKey = trackKey
            fetchLyricsForCurrentTrack(player.currentArtist, player.currentTitle, player.duration, player.lyrics)
        }

        updatePlaybackProgress()
    }

    private fun fetchLyricsForCurrentTrack(artist: String?, title: String?, duration: Double, serverLyrics: String?) {
        if (title.isNullOrBlank()) {
            currentLyrics = null
            lyricsAdapter.setLyrics(null)
            binding.badgeLyricsHint.visibility = View.GONE
            binding.progressLyricsLoading.visibility = View.GONE
            binding.txtLyricsEmpty.visibility = View.VISIBLE
            binding.txtLyricsEmpty.text = "No track playing"
            return
        }

        // Cache server-provided lyrics if available
        if (!serverLyrics.isNullOrBlank() && !serverLyrics.equals("null", ignoreCase = true)) {
            LyricsRepository.cacheLyrics(artist, title, serverLyrics)
        }

        val cached = LyricsRepository.getCachedLyrics(artist, title)
        if (cached != null && cached.lines.isNotEmpty()) {
            displayLyrics(cached)
            return
        }

        // Show loading indicator
        binding.progressLyricsLoading.visibility = View.VISIBLE
        binding.txtLyricsEmpty.visibility = View.GONE

        val requestedKey = "${artist ?: ""} - ${title ?: ""}"
        LyricsRepository.fetchLyrics(artist, title, duration) { lyrics ->
            val currentKey = "${currentPlayer?.currentArtist ?: ""} - ${currentPlayer?.currentTitle ?: ""}"
            if (currentKey == requestedKey) {
                binding.progressLyricsLoading.visibility = View.GONE
                if (lyrics != null && lyrics.lines.isNotEmpty()) {
                    displayLyrics(lyrics)
                } else {
                    currentLyrics = null
                    lyricsAdapter.setLyrics(null)
                    binding.txtLyricsEmpty.visibility = View.VISIBLE
                    binding.txtLyricsEmpty.text = "No lyrics found for this track"
                    binding.badgeLyricsHint.visibility = View.GONE
                }
            }
        }
    }

    private fun displayLyrics(lyrics: TrackLyrics) {
        currentLyrics = lyrics
        lyricsAdapter.setLyrics(lyrics)
        binding.progressLyricsLoading.visibility = View.GONE
        binding.txtLyricsEmpty.visibility = View.GONE
        binding.badgeLyricsHint.visibility = View.VISIBLE

        val estPos = currentPlayer?.getEstimatedPositionSeconds() ?: 0.0
        val activeIdx = lyrics.findActiveIndex(estPos)
        lyricsAdapter.setActiveIndex(activeIdx)
        if (activeIdx >= 0 && isShowingLyrics) {
            centerLyricPosition(activeIdx)
        }
    }

    private fun formatTime(seconds: Double): String {
        val totalSec = seconds.toInt().coerceAtLeast(0)
        val m = totalSec / 60
        val s = totalSec % 60
        return String.format("%02d:%02d", m, s)
    }

    private fun showSettingsDialog() {
        val dialogBinding = DialogSettingsBinding.inflate(layoutInflater)
        val currentHost = prefs.getString("server_host", "192.168.100.116")
        val currentPort = prefs.getInt("server_port", 8095)
        val currentToken = prefs.getString("auth_token", NowPlayingApp.DEFAULT_TOKEN)
        val currentPid = prefs.getString("selected_player_id", "") ?: ""
        val autoOverlay = prefs.getBoolean("auto_overlay", true)
        val keepScreen = prefs.getBoolean("keep_screen_on", true)
        val floatingNav = prefs.getBoolean("floating_nav_overlay", true)

        dialogBinding.editHost.setText(currentHost)
        dialogBinding.editPort.setText(currentPort.toString())
        dialogBinding.editToken.setText(currentToken)
        dialogBinding.checkAutoOverlay.isChecked = autoOverlay
        dialogBinding.checkKeepScreenOn.isChecked = keepScreen
        dialogBinding.checkFloatingNav.isChecked = floatingNav

        // Setup Player Spinner
        val players = MusicAssistantClient.instance.allPlayers.values.toList()
        val playerLabels = mutableListOf<String>()
        val playerIds = mutableListOf<String>()

        // Option 0: Auto (This Device - Squeezelite & Music Assistant)
        playerLabels.add("Auto (This Device - Squeezelite & Music Assistant)")
        playerIds.add("auto")

        var selectedIndex = 0
        players.forEachIndexed { index, p ->
            playerLabels.add("${p.name} (${p.id})")
            playerIds.add(p.id)
            if (p.id == currentPid) {
                selectedIndex = index + 1
            }
        }

        if (currentPid.isEmpty() || currentPid == "auto" || currentPid == "cd:ef:db:59:36:12" || currentPid == "cd:ef:db:59:36:13") {
            selectedIndex = 0
        }

        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, playerLabels)
        dialogBinding.spinnerPlayer.adapter = adapter
        dialogBinding.spinnerPlayer.setSelection(selectedIndex)

        AlertDialog.Builder(this)
            .setTitle(R.string.settings_title)
            .setView(dialogBinding.root)
            .setPositiveButton(R.string.save) { _, _ ->
                val newHost = dialogBinding.editHost.text.toString().trim()
                val newPort = dialogBinding.editPort.text.toString().trim().toIntOrNull() ?: 8095
                val newToken = dialogBinding.editToken.text.toString().trim()
                val spinIdx = dialogBinding.spinnerPlayer.selectedItemPosition
                val newPid = if (spinIdx in playerIds.indices) playerIds[spinIdx] else currentPid
                val newAutoOverlay = dialogBinding.checkAutoOverlay.isChecked
                val newKeepScreen = dialogBinding.checkKeepScreenOn.isChecked
                val newFloatingNav = dialogBinding.checkFloatingNav.isChecked

                prefs.edit().apply {
                    putString("server_host", newHost)
                    putInt("server_port", newPort)
                    putString("auth_token", newToken)
                    putString("selected_player_id", newPid)
                    putBoolean("auto_overlay", newAutoOverlay)
                    putBoolean("keep_screen_on", newKeepScreen)
                    putBoolean("floating_nav_overlay", newFloatingNav)
                    apply()
                }

                applyKeepScreenOn()
                MusicAssistantClient.instance.updateConfig(newHost, newPort, newToken, newPid)
                Toast.makeText(this, "Settings updated", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    // MusicAssistantClient.Listener callbacks
    override fun onConnectionStateChanged(connected: Boolean, errorMsg: String?) {
        if (connected) {
            binding.connectionDot.backgroundTintList = android.content.res.ColorStateList.valueOf(0xFF4CAF50.toInt())
            binding.txtConnectionStatus.text = getString(R.string.connected)
        } else {
            binding.connectionDot.backgroundTintList = android.content.res.ColorStateList.valueOf(0xFFF44336.toInt())
            binding.txtConnectionStatus.text = errorMsg ?: getString(R.string.disconnected)
        }
    }

    override fun onPlayerUpdated(player: PlayerInfo) {
        bindPlayerUI(player)
    }

    override fun onPlayersListUpdated(players: List<PlayerInfo>) {
        // Player list updated
    }
}
