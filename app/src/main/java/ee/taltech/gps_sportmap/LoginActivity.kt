package ee.taltech.gps_sportmap

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.app.ActivityCompat
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class LoginActivity : AppCompatActivity() {

    private lateinit var editTextEmail: EditText
    private lateinit var editTextPassword: EditText

    companion object {
        private const val TAG = "LoginActivity"
    }

    private val requiredPermissions = arrayOf(
        Manifest.permission.ACCESS_FINE_LOCATION,
        Manifest.permission.ACCESS_COARSE_LOCATION,
        Manifest.permission.POST_NOTIFICATIONS
    )

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val deniedPermissions = permissions.filterValues { !it }.keys
        if (deniedPermissions.isEmpty()) {
            // Log.d(TAG, "All permissions granted")
        } else {
            // Log.e(TAG, "Permissions denied: $deniedPermissions")
            showPermissionRationale(deniedPermissions)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val sharedPref = getSharedPreferences("AppPreferences", MODE_PRIVATE)
        val authToken = sharedPref.getString("authToken", null)

        if (authToken != null) {
            val intent = Intent(this, MainActivity::class.java)
            startActivity(intent)
            finish()
        }

        setContentView(R.layout.login)

        // Check and request permissions
        if (!hasAllPermissions()) {
            showPermissionsExplanationDialog()
        }

        editTextEmail = findViewById(R.id.editTextEmail)
        editTextPassword = findViewById(R.id.editTextPassword)
    }

    fun onClickLoginButton(view: View) {
        CoroutineScope(Dispatchers.Main).launch {
            try {
                val result = withContext(Dispatchers.IO) {
                    WebClient.login(
                        context = this@LoginActivity,
                        user = editTextEmail.text.toString(),
                        password = editTextPassword.text.toString()
                    )
                }

                Log.d("login", "Login result status: ${result.status}")

                if (result.status.contains("logged in", ignoreCase = true)) {
                    val intent = Intent(this@LoginActivity, MainActivity::class.java)
                    startActivity(intent)
                    finish()
                } else {
                    Snackbar.make(
                        findViewById(android.R.id.content),
                        "Login failed: ${result.status}",
                        Snackbar.LENGTH_LONG
                    ).show()
                }
            } catch (e: Exception) {
                Snackbar.make(
                    findViewById(android.R.id.content),
                    "Login failed: ${e.message}",
                    Snackbar.LENGTH_LONG
                ).show()
                // Log.e("login", "Login error: ${e.message}")
            }
        }
    }

    fun onClickAccountRegisterButton(view: View) {
        val intent = Intent(this, RegisterActivity::class.java)
        startActivity(intent)
    }

    private fun hasAllPermissions(): Boolean {
        return requiredPermissions.all { permission ->
            ActivityCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED
        }
    }

    private fun requestAllPermissions() {
        requestPermissionLauncher.launch(requiredPermissions)
    }

    private fun showPermissionsExplanationDialog() {
        MaterialAlertDialogBuilder(this)
            .setTitle("Permissions Required")
            .setMessage(
                "This app requires location and notification permissions to function properly. " +
                        "Please grant these permissions to proceed."
            )
            .setPositiveButton("Grant") { _, _ ->
                requestAllPermissions()
            }
            .setNegativeButton("Cancel") { dialog, _ ->
                dialog.dismiss()
                Snackbar.make(
                    findViewById(android.R.id.content),
                    "Permissions are required to use this app.",
                    Snackbar.LENGTH_LONG
                ).show()
            }
            .show()
    }

    private fun showPermissionRationale(deniedPermissions: Set<String>) {
        MaterialAlertDialogBuilder(this)
            .setTitle("Permissions Needed")
            .setMessage(
                "The app requires the following permissions to function properly:\n\n" +
                        deniedPermissions.joinToString("\n") { it }
            )
            .setPositiveButton("Retry") { _, _ ->
                requestAllPermissions()
            }
            .setNegativeButton("Cancel") { dialog, _ ->
                dialog.dismiss()
                Toast.makeText(
                    this,
                    "Permissions are necessary for app functionality.",
                    Toast.LENGTH_SHORT
                ).show()
            }
            .show()
    }
}
