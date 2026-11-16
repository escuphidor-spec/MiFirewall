package com.example.mifirewall

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.net.VpnService
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.ImageView
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView

data class AppItem(
    val name: String,
    val packageName: String,
    val icon: Drawable,
    var blockWifi: Boolean,
    var blockMobile: Boolean
)

class MainActivity : AppCompatActivity() {

    private lateinit var switchFirewall: SwitchCompat
    private lateinit var recyclerApps: RecyclerView
    private val appList = mutableListOf<AppItem>()

    private val vpnPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            startVpnService(FirewallVpnService.ACTION_START)
        } else {
            switchFirewall.isChecked = false
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        switchFirewall = findViewById(R.id.switchFirewall)
        recyclerApps = findViewById(R.id.recyclerApps)
        recyclerApps.layoutManager = LinearLayoutManager(this)

        switchFirewall.isChecked = FirewallVpnService.isRunning

        switchFirewall.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked) {
                val intent = VpnService.prepare(this)
                if (intent != null) {
                    vpnPermissionLauncher.launch(intent)
                } else {
                    startVpnService(FirewallVpnService.ACTION_START)
                }
            } else {
                startVpnService(FirewallVpnService.ACTION_STOP)
            }
        }

        loadInstalledApps()
    }

    private fun startVpnService(action: String) {
        val intent = Intent(this, FirewallVpnService::class.java).apply {
            this.action = action
        }
        startService(intent)
    }

    private fun loadInstalledApps() {
        val prefs = getSharedPreferences("firewall_prefs", Context.MODE_PRIVATE)
        val blockedWifi = prefs.getStringSet("blocked_wifi", emptySet())?.toMutableSet() ?: mutableSetOf()
        val blockedMobile = prefs.getStringSet("blocked_mobile", emptySet())?.toMutableSet() ?: mutableSetOf()

        val pm = packageManager
        val packages = pm.getInstalledPackages(PackageManager.GET_PERMISSIONS)

        appList.clear()
        for (pkgInfo in packages) {
            // Solo mostramos apps que realmente tienen permiso de usar Internet
            val permissions = pkgInfo.requestedPermissions
            if (permissions == null || !permissions.contains(Manifest.permission.INTERNET)) {
                continue
            }
            if (pkgInfo.packageName == packageName) continue

            val appName = pkgInfo.applicationInfo.loadLabel(pm).toString()
            val appIcon = pkgInfo.applicationInfo.loadIcon(pm)

            appList.add(
                AppItem(
                    name = appName,
                    packageName = pkgInfo.packageName,
                    icon = appIcon,
                    blockWifi = blockedWifi.contains(pkgInfo.packageName),
                    blockMobile = blockedMobile.contains(pkgInfo.packageName)
                )
            )
        }

        // Ordenar primero las que tienen algún bloqueo activo y luego alfabéticamente
        appList.sortWith(compareByDescending<AppItem> { it.blockWifi || it.blockMobile }.thenBy { it.name.lowercase() })

        recyclerApps.adapter = AppAdapter(appList) { item, isWifiChanged ->
            val currentWifi = prefs.getStringSet("blocked_wifi", emptySet())?.toMutableSet() ?: mutableSetOf()
            val currentMobile = prefs.getStringSet("blocked_mobile", emptySet())?.toMutableSet() ?: mutableSetOf()

            if (isWifiChanged) {
                if (item.blockWifi) currentWifi.add(item.packageName) else currentWifi.remove(item.packageName)
                prefs.edit().putStringSet("blocked_wifi", currentWifi).apply()
            } else {
                if (item.blockMobile) currentMobile.add(item.packageName) else currentMobile.remove(item.packageName)
                prefs.edit().putStringSet("blocked_mobile", currentMobile).apply()
            }

            if (FirewallVpnService.isRunning) {
                startVpnService(FirewallVpnService.ACTION_RELOAD)
            }
        }
    }

    class AppAdapter(
        private val items: List<AppItem>,
        private val onCheckChanged: (AppItem, Boolean) -> Unit
    ) : RecyclerView.Adapter<AppAdapter.ViewHolder>() {

        class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            val imgIcon: ImageView = view.findViewById(R.id.imgIcon)
            val txtAppName: TextView = view.findViewById(R.id.txtAppName)
            val chkWifi: CheckBox = view.findViewById(R.id.chkWifi)
            val chkMobile: CheckBox = view.findViewById(R.id.chkMobile)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_app, parent, false)
            return ViewHolder(view)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val item = items[position]
            holder.imgIcon.setImageDrawable(item.icon)
            holder.txtAppName.text = item.name

            // Quitamos los listeners temporalmente para evitar disparos falsos al hacer scroll
            holder.chkWifi.setOnCheckedChangeListener(null)
            holder.chkMobile.setOnCheckedChangeListener(null)

            holder.chkWifi.isChecked = item.blockWifi
            holder.chkMobile.isChecked = item.blockMobile

            holder.chkWifi.setOnCheckedChangeListener { _, isChecked ->
                item.blockWifi = isChecked
                onCheckChanged(item, true)
            }

            holder.chkMobile.setOnCheckedChangeListener { _, isChecked ->
                item.blockMobile = isChecked
                onCheckChanged(item, false)
            }
        }

        override fun getItemCount() = items.size
    }
}
