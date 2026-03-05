package com.example.riceshopbilling

import android.content.Intent
import android.os.Bundle
import android.widget.Button
import androidx.appcompat.app.AppCompatActivity

class HomeActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_home)

        findViewById<Button>(R.id.btnNewBill).setOnClickListener {
            startActivity(Intent(this, BillActivity::class.java))
        }

        findViewById<Button>(R.id.btnManageProducts).setOnClickListener {
            startActivity(Intent(this, ProductListActivity::class.java))
        }

        findViewById<Button>(R.id.btnPrinterSettings).setOnClickListener {
            startActivity(Intent(this, PrinterSettingsActivity::class.java))
        }
    }
}