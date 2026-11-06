package mx.oli.glass

import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import android.view.KeyEvent
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.setContent

class MainActivity : ComponentActivity() {

    private val state = HomeState()
    private lateinit var sounds: Sounds

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.decorView.isSoundEffectsEnabled = false
        sounds = Sounds(this)

        // En el inicio, "Atrás" solo cierra capas; nunca sale del launcher.
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                when {
                    state.switching != null -> { state.switching = null; sounds.back() }
                    state.showAll -> { state.showAll = false; sounds.back() }
                }
            }
        })

        setContent { GlassApp(state, sounds, ::launch) }
    }

    private fun launch(intent: Intent) {
        try {
            startActivity(intent)
        } catch (e: Exception) {
            Toast.makeText(this, "No se pudo abrir", Toast.LENGTH_SHORT).show()
        }
    }

    /** Botón Home del control mientras ya estamos en el inicio: limpia todo y vuelve al dock. */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        state.showAll = false
        state.gallery = false
        state.switching = null
        state.homeSignal++
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        state.lastInput = SystemClock.uptimeMillis()
        if (state.gallery) {
            // Cualquier botón saca del modo galería sin hacer nada más.
            if (event.action == KeyEvent.ACTION_UP) state.gallery = false
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    override fun onResume() {
        super.onResume()
        state.resumed = true
        state.lastInput = SystemClock.uptimeMillis()
        state.switching = null
    }

    override fun onPause() {
        super.onPause()
        state.resumed = false
        state.gallery = false
    }

    override fun onDestroy() {
        super.onDestroy()
        sounds.release()
    }
}
