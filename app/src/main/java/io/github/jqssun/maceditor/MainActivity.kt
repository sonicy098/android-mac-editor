package io.github.jqssun.maceditor

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.widget.ArrayAdapter
import org.json.JSONObject
import android.os.Bundle
import android.provider.Settings
import android.text.InputFilter
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import io.github.jqssun.maceditor.databinding.ActivityMainBinding
import io.github.jqssun.maceditor.hookers.WifiServiceHooker
import io.github.jqssun.maceditor.utils.MacTextWatcher
import io.github.jqssun.maceditor.utils.MacUtils
import io.github.jqssun.maceditor.utils.PrefManager
import io.github.jqssun.maceditor.utils.XposedChecker

class MainActivity : AppCompatActivity() {
    private lateinit var dropdownAdapter: ArrayAdapter<String>
    private var savedMacMap = mutableMapOf<String, String>()
    private var displayList = mutableListOf<String>()
    private lateinit var binding: ActivityMainBinding
    private var updatingUI = false

    private val macReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            _refreshDeviceMac()
            _updateStatusCard()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)

        _setupToggles()
        _setupMacCard()
        _setupMacDropdown()
        binding.footerNote.text = getString(R.string.footer_note, getString(R.string.force_mac_randomization_label))

        PrefManager.loadPrefs { runOnUiThread { _refreshAll() } }
        _refreshAll()
    }

    override fun onResume() {
        super.onResume()
        ContextCompat.registerReceiver(
            this,
            macReceiver,
            IntentFilter(MacBroadcastReceiver.ACTION_MAC_DETECTED),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
        _refreshAll()
    }

    override fun onPause() {
        super.onPause()
        unregisterReceiver(macReceiver)
    }

    // Panggil fungsi ini di dalam onCreate(), tepat di bawah _setupMacCard()
    private fun _setupMacDropdown() {
        dropdownAdapter = ArrayAdapter(this, android.R.layout.simple_dropdown_item_1line, displayList)
        (binding.dropdownSavedMacs as? android.widget.AutoCompleteTextView)?.setAdapter(dropdownAdapter)

        _refreshDropdownData()

        // Saat item di dropdown dipilih, pindahkan MAC-nya ke input utama
        binding.dropdownSavedMacs.setOnItemClickListener { _, _, position, _ ->
            val selectedDisplay = displayList[position]
            // Format yang ditampilkan adalah "Nama - 00:11:22:..."
            val mac = selectedDisplay.substringAfterLast(" - ")
            binding.edittextNewMac.setText(mac)
        }

        binding.btnSaveMacToList.setOnClickListener {
            val mac = binding.edittextNewMac.text.toString().uppercase()
            var name = binding.edittextMacName.text.toString().trim()

            if (MacUtils.validate(mac) != MacUtils.ValidationResult.VALID) {
                _showError(getString(R.string.error_bad_length))
                return@setOnClickListener
            }
            if (name.isEmpty()) {
                name = "Unnamed MAC" // Default nama jika dikosongkan
            }

            _saveMacToStorage(name, mac)
        }

        binding.btnDeleteMacFromList.setOnClickListener {
            val selectedItem = binding.dropdownSavedMacs.text.toString()
            if (selectedItem.isNotEmpty() && selectedItem.contains(" - ")) {
                val name = selectedItem.substringBeforeLast(" - ")
                _deleteMacFromStorage(name)
            } else {
                Snackbar.make(binding.root, "Pilih MAC dari dropdown terlebih dahulu!", Snackbar.LENGTH_SHORT).show()
            }
        }
    }

    private fun _refreshDropdownData() {
        val prefs = getSharedPreferences("MacEditorPrefs", MODE_PRIVATE)
        val jsonString = prefs.getString("saved_macs_json", "{}") ?: "{}"
        val jsonObject = JSONObject(jsonString)

        savedMacMap.clear()
        displayList.clear()

        val keys = jsonObject.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            val value = jsonObject.getString(key)
            savedMacMap[key] = value
            displayList.add("$key - $value") 
        }
        
        displayList.sort()
        
        // CARA PALING AMPUH: Alih-alih notifyDataSetChanged(), kita inisialisasi ulang adapternya
        // agar AutoCompleteTextView mereset cache filternya secara paksa.
        dropdownAdapter = ArrayAdapter(this, android.R.layout.simple_dropdown_item_1line, displayList)
        (binding.dropdownSavedMacs as? android.widget.AutoCompleteTextView)?.setAdapter(dropdownAdapter)
    }

    private fun _saveMacToStorage(name: String, mac: String) {
        val prefs = getSharedPreferences("MacEditorPrefs", MODE_PRIVATE)
        val jsonString = prefs.getString("saved_macs_json", "{}") ?: "{}"
        val jsonObject = JSONObject(jsonString)

        jsonObject.put(name, mac) 
        prefs.edit().putString("saved_macs_json", jsonObject.toString()).apply()

        _refreshDropdownData() // Refresh list terbaru
        
        // Kosongkan nama dengan aman
        binding.edittextMacName.setText("") 
        
        // Opsional: Kosongkan dropdown agar tidak menampilkan sisa teks yang salah
        binding.dropdownSavedMacs.setText("", false)
        binding.dropdownSavedMacs.clearFocus()

        Snackbar.make(binding.root, "MAC '$name' berhasil disimpan", Snackbar.LENGTH_SHORT).show()
    }

    private fun _deleteMacFromStorage(name: String) {
        val prefs = getSharedPreferences("MacEditorPrefs", MODE_PRIVATE)
        val jsonString = prefs.getString("saved_macs_json", "{}") ?: "{}"
        val jsonObject = JSONObject(jsonString)

        // Cek dulu apakah datanya benar-benar ada untuk mencegah crash
        if (jsonObject.has(name)) {
            jsonObject.remove(name)
            prefs.edit().putString("saved_macs_json", jsonObject.toString()).apply()
            
            _refreshDropdownData()
            
            // PENTING: Gunakan setText("", false) agar tidak memicu filter pencarian error
            binding.dropdownSavedMacs.setText("", false) 
            binding.dropdownSavedMacs.clearFocus()
            
            Snackbar.make(binding.root, "MAC '$name' berhasil dihapus", Snackbar.LENGTH_SHORT).show()
        } else {
            // Jika user menekan Delete berkali-kali secara cepat
            _refreshDropdownData()
            binding.dropdownSavedMacs.setText("", false)
            Snackbar.make(binding.root, "Item tersebut sudah tidak ada", Snackbar.LENGTH_SHORT).show()
        }
    }

    private fun _refreshAll() {
        updatingUI = true
        _updateStatusCard()
        _refreshDeviceMac()
        _refreshActiveMac()
        binding.hookSwitch.isChecked = PrefManager.isHookOn()
        binding.forceRandomizationSwitch.isChecked = PrefManager.isForceShowMacRandomization()
        val saved = PrefManager.getCustomMac()
        if (saved.isNotEmpty() && binding.edittextNewMac.text.isNullOrEmpty()) {
            binding.edittextNewMac.setText(saved)
        }
        updatingUI = false
    }

    private fun _isSystemServerHooked(): Boolean {
        return getSharedPreferences(MacBroadcastReceiver.PREFS_NAME, MODE_PRIVATE)
            .getString("deviceMac", null) != null
    }

    private fun _updateStatusCard() {
        val enabled = XposedChecker.isEnabled()
        val hooked = enabled && _isSystemServerHooked()
        val hookOn = hooked && PrefManager.isHookOn()

        when {
            !enabled -> {
                binding.moduleStatusIcon.setImageResource(R.drawable.ic_disabled_24)
                binding.moduleStatus.text = getString(R.string.status_not_activated)
                binding.serviceStatus.text = getString(R.string.status_detail_not_activated)
            }
            !hooked -> {
                binding.moduleStatusIcon.setImageResource(R.drawable.ic_error_24)
                binding.moduleStatus.text = getString(R.string.status_inactive)
                binding.serviceStatus.text = getString(R.string.status_detail_inactive)
            }
            !hookOn -> {
                binding.moduleStatusIcon.setImageResource(R.drawable.ic_warning_24)
                binding.moduleStatus.text = getString(R.string.status_activated)
                binding.serviceStatus.text = getString(R.string.status_detail_hook_off)
            }
            else -> {
                binding.moduleStatusIcon.setImageResource(R.drawable.ic_baseline_router_24)
                binding.moduleStatus.text = getString(R.string.status_activated)
                binding.serviceStatus.text = getString(R.string.status_detail_hook_on)
            }
        }
    }

    private fun _setupToggles() {
        binding.hookSwitch.setOnCheckedChangeListener { _, checked ->
            if (updatingUI) return@setOnCheckedChangeListener
            PrefManager.setHookState(checked)
            _updateStatusCard()
        }
        binding.forceRandomizationSwitch.setOnCheckedChangeListener { _, checked ->
            if (updatingUI) return@setOnCheckedChangeListener
            PrefManager.setForceShowMacRandomization(checked)
        }
    }

    private fun _refreshDeviceMac() {
        val localPrefs = getSharedPreferences(MacBroadcastReceiver.PREFS_NAME, MODE_PRIVATE)
        val mac = localPrefs.getString("deviceMac", null)
        binding.textviewDeviceMac.text = mac ?: getString(R.string.mac_not_set)
    }

    private fun _refreshActiveMac() {
        val saved = PrefManager.getCustomMac()
        binding.textviewCurrentMac.text = saved.ifEmpty { getString(R.string.mac_not_set) }
    }

    private fun _setupMacCard() {
        val editText = binding.edittextNewMac
        editText.filters = arrayOf(InputFilter.AllCaps(), InputFilter.LengthFilter(17))
        editText.addTextChangedListener(MacTextWatcher())

        binding.btnGenerateMac.setOnClickListener {
            editText.setText(MacUtils.generateRandom())
        }

        binding.btnSetMac.setOnClickListener {
            val mac = editText.text.toString().uppercase()
            when (MacUtils.validate(mac)) {
                MacUtils.ValidationResult.BAD_LENGTH ->
                    _showError(getString(R.string.error_bad_length))
                MacUtils.ValidationResult.ALL_ZEROS ->
                    _showError(getString(R.string.error_all_zeros))
                MacUtils.ValidationResult.ODD_FIRST_OCTET ->
                    _showError(getString(R.string.error_odd_first_octet))
                MacUtils.ValidationResult.VALID -> {
                    PrefManager.setCustomMac(mac)
                    binding.textviewCurrentMac.text = mac
                    _applyMac()
                }
            }
        }
    }

    private fun _applyMac() {
        sendBroadcast(Intent(WifiServiceHooker.ACTION_APPLY_MAC))
        Snackbar.make(binding.root, R.string.mac_set_success, Snackbar.LENGTH_LONG)
            .setAction(R.string.open_wifi_settings) {
                startActivity(Intent(Settings.ACTION_WIFI_SETTINGS))
            }
            .show()
    }

    private fun _showError(msg: String) {
        MaterialAlertDialogBuilder(this)
            .setMessage(msg)
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }
}
