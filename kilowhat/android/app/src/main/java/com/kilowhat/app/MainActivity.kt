package com.kilowhat.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import com.kilowhat.app.ui.KilowhatApp
import com.kilowhat.app.ui.KilowhatViewModel
import com.kilowhat.app.ui.theme.KilowhatTheme

class MainActivity : ComponentActivity() {

    /**
     * Scoped to MainActivity so switching bottom navigation tabs or rotating the screen
     * never interrupts the 1 Hz simulator stream.
     */
    private val viewModel: KilowhatViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            KilowhatTheme {
                KilowhatApp(viewModel = viewModel)
            }
        }
    }
}
