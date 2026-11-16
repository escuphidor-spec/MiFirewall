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
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView

data class AppItem(
    val name: String,
    val packageName: String,
    val icon: Drawable,
    var allowWifi: Boolean,
    var allowMobile: Boolean
)

class MainActivity : AppCompatActivity() {

    private lateinit var mainContainer: LinearLayout
    private lateinit var switchFirewall: SwitchCompat
    private lateinit var recyclerApps: RecyclerView
    private val appList = mutableListOf<AppItem>()
    private var isAuthenticated = false

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

        mainContainer = findViewById(R.id.mainContainer)
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

    override fun onResume() {
        super.onResume()
        if (!isAuthenticated) {
            mainContainer.visibility = View.INVISIBLE
            authenticateUser()
        }
    }

    override fun onStop() {
        super.onStop()
        // Al salir o minimizar la app, vuelve a bloquearse para pedir huella al regresar
        isAuthenticated = false
        mainContainer.visibility = View.INVISIBLE
    }

    private fun authenticateUser() {
        val executor = ContextCompat.getMainExecutor(this)
        val biometricPrompt = BiometricPrompt(this, executor,
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    super.onAuthenticationSucceeded(result)
                    isAuthenticated = true
                    mainContainer.visibility = View.VISIBLE
                }

                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                    super.onAuthenticationError(errorCode, errString)
                    Toast.makeText(applicationContext, "Autenticación cancelada", Toast.LENGTH_SHORT).show()
                    finish()
                }
            })

        val promptInfo = BiometricPrompt.PromptInfo.Builder()
            .setTitle("Acceso a Mi Firewall")
            .setSubtitle("Coloca tu huella digital para desbloquear")
            .setAllowedAuthenticators(
                BiometricManager.Authenticators.BIOMETRIC_STRONG or
                BiometricManager.Authenticators.BIOMETRIC_WEAK or
                BiometricManager.Authenticators.DEVICE_CREDENTIAL
            )
            .build()

        biometricPrompt.authenticate(promptInfo)
    }

    private fun startVpnService(action: String) {
        val intent = Intent(this, FirewallVpnService::class.java).apply {
            this.action = action
        }
        startService(intent)
    }

    private fun loadInstalledApps() {
        val prefs = getSharedPreferences("firewall_prefs", Context.MODE_PRIVATE)
        val allowedWifi = prefs.getStringSet("allowed_wifi", emptySet())?.toMutableSet() ?: mutableSetOf()
        val allowedMobile = prefs.getStringSet("allowed_mobile", emptySet())?.toMutableSet() ?: mutableSetOf()

        val pm = packageManager
        val packages = pm.getInstalledPackages(PackageManager.GET_PERMISSIONS)

        appList.clear()
        for (pkgInfo in packages) {
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
                    allowWifi = allowedWifi.contains(pkgInfo.packageName),
                    allowMobile = allowedMobile.contains(pkgInfo.packageName)
                )
            )
        }

        // Ordena primero las aplicaciones que tienen permiso activo y luego alfabéticamente
        appList.sortWith(compareByDescending<AppItem> { it.allowWifi || it.allowMobile }.thenBy { it.name.lowercase() })

        recyclerApps.adapter = AppAdapter(appList) { item, isWifiChanged ->
            val currentWifi = prefs.getStringSet("allowed_wifi", emptySet())?.toMutableSet() ?: mutableSetOf()
            val currentMobile = prefs.getStringSet("allowed_mobile", emptySet())?.toMutableSet() ?: mutableSetOf()

            if (isWifiChanged) {
                if (item.allowWifi) currentWifi.add(item.packageName) else currentWifi.remove(item.packageName)
                prefs.edit().putStringSet("allowed_wifi", currentWifi).apply()
            } else {
                if (item.allowMobile) currentMobile.add(item.packageName) else currentMobile.remove(item.packageName)
                prefs.edit().putStringSet("allowed_mobile", currentMobile).apply()
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

            holder.chkWifi.setOnCheckedChangeListener(null)
            holder.chkMobile.setOnCheckedChangeListener(null)

            holder.chkWifi.isChecked = item.allowWifi
            holder.chkMobile.isChecked = item.allowMobile

            holder.chkWifi.setOnCheckedChangeListener { _, isChecked ->
                item.allowWifi = isChecked
                onCheckChanged(item, true)
            }

            holder.chkMobile.setOnCheckedChangeListener { _, isChecked ->
                item.allowMobile = isChecked
                onCheckChanged(item, false)
            }
        }

        override fun getItemCount() = items.size
    }
}
