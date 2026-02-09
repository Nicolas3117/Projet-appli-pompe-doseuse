package com.esp32pumpwifi.app

import android.os.Bundle
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

class ScheduleHelperActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_schedule_helper)

        title = getString(R.string.schedule_helper_title)

        val pumpNumber = intent.getIntExtra(EXTRA_PUMP_NUMBER, 1)

        val pumpLabel = getString(R.string.pump_label, pumpNumber)
        findViewById<TextView>(R.id.tv_schedule_helper_pump).text = pumpLabel
    }

    companion object {
        const val EXTRA_PUMP_NUMBER = "pumpNumber"
        const val EXTRA_MODULE_ID = "moduleId"
    }
}
