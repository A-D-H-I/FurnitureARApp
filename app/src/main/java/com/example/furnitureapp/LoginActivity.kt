package com.example.furnitureapp

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

class LoginActivity : AppCompatActivity() {

    private val prefsName = "user_prefs"
    private val keyUserName = "user_name"
    private val keyPassword = "user_password"
    private val keyLoggedIn = "logged_in"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val prefs = getSharedPreferences(prefsName, Context.MODE_PRIVATE)

        // If already logged in, skip login screen
        if (prefs.getBoolean(keyLoggedIn, false)) {
            openHomeScreen()
            finish()
            return
        }

        setContentView(R.layout.activity_login)

        val etName: EditText = findViewById(R.id.etName)
        val etPassword: EditText = findViewById(R.id.etPassword)
        val btnLogin: Button = findViewById(R.id.btnLogin)
        val tvCreateAccount: TextView = findViewById(R.id.tvCreateAccount)
        val btnReset: Button = findViewById(R.id.btnResetAccount)

        btnReset.setOnClickListener {
            prefs.edit()
                .remove(keyUserName)
                .remove(keyPassword)
                .putBoolean(keyLoggedIn, false)
                .apply()

            Toast.makeText(this, "Account cleared ✅ Now create a new account.", Toast.LENGTH_LONG).show()
            etName.setText("")
            etPassword.setText("")
        }

        btnLogin.setOnClickListener {
            val name = etName.text.toString().trim()
            val password = etPassword.text.toString().trim()

            if (name.isEmpty() || password.isEmpty()) {
                Toast.makeText(this, "Enter name and password", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            val storedName = prefs.getString(keyUserName, null)
            val storedPassword = prefs.getString(keyPassword, null)

            if (storedName.isNullOrEmpty() || storedPassword.isNullOrEmpty()) {
                Toast.makeText(this, "No account found. Create an account first.", Toast.LENGTH_LONG).show()
                return@setOnClickListener
            }

            val nameMatches = storedName.equals(name, ignoreCase = true)
            val passMatches = storedPassword == password

            if (nameMatches && passMatches) {
                prefs.edit().putBoolean(keyLoggedIn, true).apply()
                Toast.makeText(this, "Login successful ✅", Toast.LENGTH_SHORT).show()
                openHomeScreen()
                finish()
            } else {
                Toast.makeText(this, "Invalid name or password", Toast.LENGTH_SHORT).show()
            }
        }

        tvCreateAccount.setOnClickListener {
            val name = etName.text.toString().trim()
            val password = etPassword.text.toString().trim()

            if (name.isEmpty() || password.isEmpty()) {
                Toast.makeText(this, "Enter name and password to create account", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            val storedName = prefs.getString(keyUserName, null)
            val storedPassword = prefs.getString(keyPassword, null)

            // ✅ If account already exists:
            if (!storedName.isNullOrEmpty() && !storedPassword.isNullOrEmpty()) {
                // If user entered SAME credentials -> treat as login
                val sameUser = storedName.equals(name, ignoreCase = true)
                val samePass = storedPassword == password

                if (sameUser && samePass) {
                    prefs.edit().putBoolean(keyLoggedIn, true).apply()
                    Toast.makeText(this, "Logged in ✅", Toast.LENGTH_SHORT).show()
                    openHomeScreen()
                    finish()
                } else {
                    Toast.makeText(
                        this,
                        "Account already exists. Tap 'Reset Account' to create a new one.",
                        Toast.LENGTH_LONG
                    ).show()
                }
                return@setOnClickListener
            }

            // ✅ Save new account
            prefs.edit()
                .putString(keyUserName, name)
                .putString(keyPassword, password)
                .putBoolean(keyLoggedIn, true)
                .apply()

            Toast.makeText(this, "Account created ✅ Logged in.", Toast.LENGTH_SHORT).show()
            openHomeScreen()
            finish()
        }
    }

    private fun openHomeScreen() {
        startActivity(Intent(this, MainActivity::class.java))
    }
}
