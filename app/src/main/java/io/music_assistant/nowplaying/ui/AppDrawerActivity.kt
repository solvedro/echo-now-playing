package io.music_assistant.nowplaying.ui

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.widget.EditText
import android.widget.ImageButton
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import io.music_assistant.nowplaying.R
import io.music_assistant.nowplaying.model.AppItem
import java.util.concurrent.Executors

class AppDrawerActivity : Activity() {

    private lateinit var recyclerApps: RecyclerView
    private lateinit var editSearch: EditText
    private lateinit var btnClose: ImageButton
    private lateinit var adapter: AppDrawerAdapter
    private val executor = Executors.newSingleThreadExecutor()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setupFullscreen()
        setContentView(R.layout.activity_app_drawer)

        recyclerApps = findViewById(R.id.recyclerApps)
        editSearch = findViewById(R.id.editSearchApps)
        btnClose = findViewById(R.id.btnCloseDrawer)

        adapter = AppDrawerAdapter { appItem ->
            try {
                startActivity(appItem.launchIntent)
            } catch (e: Exception) {
                e.printStackTrace()
            }
            finish()
        }

        recyclerApps.layoutManager = GridLayoutManager(this, 5)
        recyclerApps.adapter = adapter

        editSearch.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                adapter.filter(s?.toString() ?: "")
            }
            override fun afterTextChanged(s: Editable?) {}
        })

        btnClose.setOnClickListener {
            finish()
        }

        loadApplications()
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

    private fun loadApplications() {
        executor.execute {
            val pm = packageManager
            val mainIntent = Intent(Intent.ACTION_MAIN, null).apply {
                addCategory(Intent.CATEGORY_LAUNCHER)
            }
            val launchables = pm.queryIntentActivities(mainIntent, 0)
            val items = mutableListOf<AppItem>()
            val seenPackages = mutableSetOf<String>()

            for (info in launchables) {
                val pkg = info.activityInfo.packageName
                if (seenPackages.contains(pkg)) continue
                seenPackages.add(pkg)

                val label = try {
                    info.loadLabel(pm).toString()
                } catch (e: Exception) {
                    pkg
                }
                val icon = try {
                    info.loadIcon(pm)
                } catch (e: Exception) {
                    getDrawable(android.R.drawable.sym_def_app_icon)!!
                }

                val intent = pm.getLaunchIntentForPackage(pkg) ?: continue
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                items.add(AppItem(label, pkg, icon, intent))
            }

            // Also ensure Android Settings is accessible
            if (!seenPackages.contains("com.android.settings")) {
                try {
                    val settingsIntent = Intent(android.provider.Settings.ACTION_SETTINGS).apply {
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK
                    }
                    val appInfo = pm.getApplicationInfo("com.android.settings", 0)
                    val icon = pm.getApplicationIcon(appInfo)
                    val label = pm.getApplicationLabel(appInfo).toString()
                    items.add(AppItem(label.ifEmpty { "Settings" }, "com.android.settings", icon, settingsIntent))
                } catch (e: Exception) {
                    // Settings not queryable via application info
                }
            }

            items.sortBy { it.label.lowercase() }

            runOnUiThread {
                adapter.setApps(items)
            }
        }
    }

    override fun onDestroy() {
        executor.shutdown()
        super.onDestroy()
    }

    companion object {
        fun launch(context: Context) {
            val intent = Intent(context, AppDrawerActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
            context.startActivity(intent)
        }
    }
}
