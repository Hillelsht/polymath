package com.hillelsht.smart

import android.content.Context
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.CompositionLocalProvider
import com.hillelsht.smart.ui.ImageResolver
import com.hillelsht.smart.ui.LocalImageResolver
import com.hillelsht.smart.ui.navigation.SmartApp
import com.hillelsht.smart.ui.theme.SmartTheme
import com.hillelsht.smart.util.LocalePrefs
import com.hillelsht.smart.util.withLanguage

class MainActivity : ComponentActivity() {

    // Applied here, ahead of onCreate, because the Compose tree's stringResource() calls
    // resolve against whatever context the Activity attaches with — setting it any later
    // would leave the very first frame in the previous language.
    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(newBase.withLanguage(LocalePrefs.get(newBase)))
    }

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
