package com.esp32pumpwifi.app

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity

class ManualDoseActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_manual_dose_container)

        if (savedInstanceState == null) {
            val pumpNumber = intent.getIntExtra("pump_number", 1)
            supportFragmentManager.beginTransaction()
                .replace(R.id.manual_dose_container, ManualDoseFragment.newInstance(pumpNumber))
                .commit()
        }
    }
}
