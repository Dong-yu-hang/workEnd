package com.example.workend

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.example.workend.data.SettingsRepository
import com.example.workend.ui.HomeScreen
import com.example.workend.ui.theme.WorkEndTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val repository = SettingsRepository(applicationContext)
        setContent {
            WorkEndTheme {
                HomeScreen(repository = repository)
            }
        }
    }
}
