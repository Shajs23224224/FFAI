package com.ffai

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.accessibility.AccessibilityManager
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.ffai.databinding.ActivityMainBinding
import com.ffai.ui.ControlPanelFragment
import com.ffai.ui.PerformanceMonitorFragment
import com.ffai.ui.PersonalityConfigFragment
import com.ffai.service.FFAIAccessibilityService
import com.google.android.material.tabs.TabLayoutMediator
import kotlinx.coroutines.launch
import timber.log.Timber

/**
 * Main Activity - Control Panel for FFAI
 * Manages permissions and provides UI for configuration
 */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    
    private val requestOverlayPermission = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { _ ->
        checkPermissions()
    }
    
    private val requestAccessibilityPermission = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { _ ->
        checkPermissions()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        
        setupViewPager()
        setupButtons()
        checkPermissions()
    }
    
    override fun onResume() {
        super.onResume()
        checkPermissions()
    }

    private fun setupViewPager() {
        val fragments = listOf(
            ControlPanelFragment(),
            PerformanceMonitorFragment(),
            PersonalityConfigFragment()
        )
        
        val titles = listOf("Control", "Performance", "Personality")
        
        binding.viewPager.adapter = object : androidx.viewpager2.adapter.FragmentStateAdapter(this) {
            override fun getItemCount(): Int = fragments.size
            override fun createFragment(position: Int) = fragments[position]
        }
        
        TabLayoutMediator(binding.tabLayout, binding.viewPager) { tab, position ->
            tab.text = titles[position]
        }.attach()
    }

    private fun setupButtons() {
        binding.btnStartService.setOnClickListener {
            if (isAccessibilityServiceEnabled()) {
                startService()
            } else {
                showAccessibilityDialog()
            }
        }
        
        binding.btnStopService.setOnClickListener {
            stopService()
        }
        
        binding.btnSettings.setOnClickListener {
            openSettings()
        }
    }

    private fun checkPermissions() {
        val hasAccessibility = isAccessibilityServiceEnabled()
        val hasOverlay = Settings.canDrawOverlays(this)
        
        binding.statusAccessibility.text = 
            if (hasAccessibility) "✓ Accesibilidad" else "✗ Accesibilidad"
        binding.statusOverlay.text = 
            if (hasOverlay) "✓ Overlay" else "✗ Overlay"
        
        binding.btnStartService.isEnabled = hasAccessibility
        
        if (!hasOverlay) {
            requestOverlayPermission()
        }
    }

    private fun isAccessibilityServiceEnabled(): Boolean {
        val am = getSystemService(ACCESSIBILITY_SERVICE) as AccessibilityManager
        val enabledServices = am.getEnabledAccessibilityServiceList(
            android.accessibilityservice.AccessibilityServiceInfo.FEEDBACK_ALL_MASK
        )
        return enabledServices.any { 
            it.resolveInfo.serviceInfo.packageName == packageName 
        }
    }

    private fun showAccessibilityDialog() {
        AlertDialog.Builder(this)
            .setTitle("Permiso de Accesibilidad Requerido")
            .setMessage("FFAI necesita el permiso de accesibilidad para:\n\n" +
                    "• Detectar elementos en pantalla\n" +
                    "• Ejecutar gestos táctiles\n" +
                    "• Controlar el juego\n\n" +
                    "Por favor, activa el servicio FFAI Core Service en la siguiente pantalla.")
            .setPositiveButton("Continuar") { _, _ ->
                val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                requestAccessibilityPermission.launch(intent)
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun requestOverlayPermission() {
        if (!Settings.canDrawOverlays(this)) {
            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")
            )
            requestOverlayPermission.launch(intent)
        }
    }

    private fun startService() {
        val intent = Intent(this, FFAIAccessibilityService::class.java)
        intent.action = FFAIAccessibilityService.ACTION_START
        startService(intent)
        binding.statusService.text = "Estado: Activo"
        binding.statusService.setTextColor(getColor(android.R.color.holo_green_dark))
    }

    private fun stopService() {
        val intent = Intent(this, FFAIAccessibilityService::class.java)
        intent.action = FFAIAccessibilityService.ACTION_STOP
        startService(intent)
        binding.statusService.text = "Estado: Inactivo"
        binding.statusService.setTextColor(getColor(android.R.color.holo_red_dark))
    }

    private fun openSettings() {
        // Open detailed settings
        val intent = Intent(this, SettingsActivity::class.java)
        startActivity(intent)
    }
}
