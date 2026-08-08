package com.hillelsht.smart

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.CompositionLocalProvider
import com.hillelsht.smart.ui.ImageResolver
import com.hillelsht.smart.ui.LocalImageResolver
import com.hillelsht.smart.ui.navigation.SmartApp
import com.hillelsht.smart.ui.theme.SmartTheme

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        val container = (application as SmartApplication).container
        val repository = container.repository

        setContent {
            SmartTheme {
                CompositionLocalProvider(
                    LocalImageResolver provides ImageResolver { repository.imageUrl(it) },
                ) {
                    SmartApp(repository = repository)
                }
            }
        }
    }
}
