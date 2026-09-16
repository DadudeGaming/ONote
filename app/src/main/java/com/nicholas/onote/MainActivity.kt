package com.nicholas.onote

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.nicholas.onote.ui.ONoteApp
import com.nicholas.onote.ui.theme.ONoteTheme

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            ONoteTheme {
                ONoteApp()
            }
        }
    }
}