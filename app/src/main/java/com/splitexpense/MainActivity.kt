package com.splitexpense

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.splitexpense.ui.navigation.AppNavigation
import com.splitexpense.ui.theme.FairShareTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        setTheme(R.style.Theme_FairShare)
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            FairShareTheme {
                AppNavigation()
            }
        }
    }
}
