package com.cheatervpnapp

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.cheatervpnapp.databinding.ActivityContactBinding

class ContactActivity : AppCompatActivity() {

    private lateinit var binding: ActivityContactBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityContactBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.btnTelegram.setOnClickListener {
            val username = getString(R.string.contact_telegram_value)
                .removePrefix("@")
            openBrowser("https://t.me/$username")
        }

        binding.btnEmail.setOnClickListener {
            val email = getString(R.string.contact_email_value)
            val intent = Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:$email"))
            runCatching { startActivity(intent) }
                .onFailure {
                    Toast.makeText(this, R.string.error_generic, Toast.LENGTH_SHORT).show()
                }
        }

        binding.btnVk.setOnClickListener {
            val id = getString(R.string.contact_vk_value)
            openBrowser("https://vk.com/$id")
        }

        binding.btnWhatsapp.setOnClickListener {
            val phone = getString(R.string.contact_whatsapp_value)
                .replace(Regex("[^0-9+]"), "")
            openBrowser("https://wa.me/$phone")
        }

        binding.btnContactBack.setOnClickListener { finish() }
    }

    private fun openBrowser(url: String) {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
        runCatching { startActivity(intent) }
            .onFailure {
                Toast.makeText(this, R.string.error_generic, Toast.LENGTH_SHORT).show()
            }
    }
}
