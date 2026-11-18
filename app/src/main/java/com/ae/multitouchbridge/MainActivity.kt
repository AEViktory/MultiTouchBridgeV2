package com.ae.multitouchbridge

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.hardware.display.DisplayManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.widget.*
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

class MainActivity : Activity() {
    private val bg = Color.rgb(10, 16, 25)
    private val panel = Color.rgb(20, 31, 45)
    private val cyan = Color.rgb(63, 220, 235)
    private val pink = Color.rgb(255, 72, 165)
    private lateinit var displaySpinner: Spinner
    private lateinit var logView: TextView
    private lateinit var resultsBox: LinearLayout
    private lateinit var displayManager: DisplayManager
    private lateinit var content: LinearLayout
    private val displayIds = mutableListOf<Int>()
    private var inspectedDisplayId: Int = 0
    private var receiverRegistered = false
    private val prefs by lazy { getSharedPreferences("bridge_settings", MODE_PRIVATE) }
    private lateinit var tokenView: TextView

    private val resultReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent == null) return
            when (intent.action) {
                Protocol.INSPECT_RESULT -> renderInspector(intent.getStringExtra(Protocol.PAYLOAD).orEmpty())
                Protocol.STATUS -> log(intent.getStringExtra(Protocol.PAYLOAD) ?: "Respuesta recibida")
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        displayManager = getSystemService(DISPLAY_SERVICE) as DisplayManager
        buildUi()
        refreshDisplays()
    }

    override fun onResume() {
        super.onResume()
        if (!receiverRegistered) {
            val f = IntentFilter().apply {
                addAction(Protocol.INSPECT_RESULT)
                addAction(Protocol.STATUS)
            }
            if (Build.VERSION.SDK_INT >= 33) registerReceiver(resultReceiver, f, Context.RECEIVER_NOT_EXPORTED)
            else @Suppress("DEPRECATION") registerReceiver(resultReceiver, f)
            receiverRegistered = true
        }
    }

    override fun onPause() {
        if (receiverRegistered) {
            unregisterReceiver(resultReceiver)
            receiverRegistered = false
        }
        super.onPause()
    }

    private fun buildUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(bg)
            setPadding(dp(16), dp(18), dp(16), dp(18))
        }
        val scroll = ScrollView(this)
        content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        scroll.addView(content)

        val title = TextView(this).apply {
            text = "AE MULTITOUCH BRIDGE"
            textSize = 21f
            setTextColor(cyan)
            letterSpacing = .08f
        }
        content.addView(title)
        content.addView(label("2.0.1  /  VISUAL CONTROL"))
        content.addView(sectionTitle("PANTALLA DE DESTINO"))
        displaySpinner = Spinner(this)
        content.addView(displaySpinner)
        val refresh = button("Actualizar pantallas") { refreshDisplays() }
        content.addView(refresh)

        content.addView(sectionTitle("SEGURIDAD TASKER"))
        tokenView = label("Token de comandos: ${commandToken()}")
        content.addView(tokenView)
        content.addView(button("Copiar token para Tasker") {
            val clipboard = getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager
            clipboard.setPrimaryClip(android.content.ClipData.newPlainText("AE Bridge token", commandToken()))
            log("Token copiado. Guárdalo en una variable segura de Tasker.")
        })

        content.addView(sectionTitle("INSPECTOR VISUAL"))
        content.addView(label("Consulta controles que Android exponga mediante accesibilidad."))
        content.addView(button("Inspeccionar pantalla seleccionada") {
            inspectedDisplayId = selectedDisplay()
            send(Protocol.INSPECT) { putExtra(Protocol.DISPLAY_ID, inspectedDisplayId) }
            log("Inspección solicitada para display $inspectedDisplayId...")
        })
        resultsBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(6), 0, dp(6))
        }
        content.addView(resultsBox)

        content.addView(sectionTitle("GESTOS RÁPIDOS"))
        content.addView(button("TAP · Centro") { sendTapCenter() })
        content.addView(button("PINCH IN · Alejar") { sendPinch(inward = true) })
        content.addView(button("PINCH OUT · Acercar") { sendPinch(inward = false) })
        content.addView(button("SWIPE · Subir") { sendSwipeUp() })

        content.addView(sectionTitle("ACCIÓN POR TEXTO"))
        val textInput = EditText(this).apply {
            hint = "Texto del botón, por ejemplo: Aceptar"
            setHintTextColor(Color.LTGRAY)
            setTextColor(Color.WHITE)
            setSingleLine(true)
            setPadding(dp(12), dp(10), dp(12), dp(10))
            setBackgroundColor(panel)
        }
        content.addView(textInput)
        content.addView(button("Pulsar texto") {
            val value = textInput.text.toString().trim()
            if (value.isEmpty()) log("Escribe el texto que quieres localizar.")
            else send(Protocol.TAP_TEXT) {
                putExtra(Protocol.TEXT, value)
                putExtra(Protocol.DISPLAY_ID, selectedDisplay())
            }
        })
        content.addView(button("Mantener texto") {
            val value = textInput.text.toString().trim()
            if (value.isEmpty()) log("Escribe el texto que quieres localizar.")
            else send(Protocol.LONG_TAP_TEXT) {
                putExtra(Protocol.TEXT, value)
                putExtra(Protocol.DISPLAY_ID, selectedDisplay())
                putExtra(Protocol.DURATION, 900)
            }
        })

        content.addView(sectionTitle("ESTADO"))
        logView = TextView(this).apply {
            setTextColor(Color.rgb(190, 207, 220))
            textSize = 12f
            text = "Esperando conexión del servicio de accesibilidad."
            setPadding(dp(12), dp(12), dp(12), dp(12))
            setBackgroundColor(panel)
        }
        content.addView(logView)
        content.addView(button("Abrir ajustes de accesibilidad") {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        })
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root)
    }

    private fun refreshDisplays() {
        val displays = displayManager.displays
        displayIds.clear()
        val labels = mutableListOf<String>()
        displays.forEach { d ->
            displayIds.add(d.displayId)
            val m = android.util.DisplayMetrics()
            @Suppress("DEPRECATION")
            d.getRealMetrics(m)
            labels.add("Display ${d.displayId} · ${m.widthPixels} × ${m.heightPixels}" +
                if (d.displayId == 0) " · Principal" else " · Secundaria")
        }
        if (labels.isEmpty()) {
            labels.add("No se detectaron pantallas")
            displayIds.add(0)
        }
        displaySpinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, labels)
        log("Pantallas detectadas: ${labels.joinToString(" | ")}")
    }

    private fun selectedDisplay(): Int {
        val p = displaySpinner.selectedItemPosition
        return displayIds.getOrElse(p) { 0 }
    }

    private fun sendPinch(inward: Boolean) {
        val id = selectedDisplay()
        val d = displayManager.getDisplay(id) ?: run { log("Display $id no disponible"); return }
        val m = android.util.DisplayMetrics()
        @Suppress("DEPRECATION")
        d.getRealMetrics(m)
        val action = if (inward) Protocol.PINCH_IN else Protocol.PINCH_OUT
        val start = if (inward) 220 else 120
        val end = if (inward) 120 else 220
        send(action) {
            putExtra(Protocol.DISPLAY_ID, id)
            putExtra(Protocol.CENTER_X, m.widthPixels / 2)
            putExtra(Protocol.CENTER_Y, m.heightPixels / 2)
            putExtra(Protocol.START_SPAN, start)
            putExtra(Protocol.END_SPAN, end)
            putExtra(Protocol.DURATION, 350)
        }
    }

    private fun sendTapCenter() {
        val id = selectedDisplay()
        val d = displayManager.getDisplay(id) ?: return
        val m = android.util.DisplayMetrics()
        @Suppress("DEPRECATION")
        d.getRealMetrics(m)
        send(Protocol.TAP_COORDS) {
            putExtra(Protocol.DISPLAY_ID, id)
            putExtra(Protocol.CENTER_X, m.widthPixels / 2)
            putExtra(Protocol.CENTER_Y, m.heightPixels / 2)
            putExtra(Protocol.DURATION, 100)
        }
    }

    private fun sendSwipeUp() {
        val id = selectedDisplay()
        val d = displayManager.getDisplay(id) ?: return
        val m = android.util.DisplayMetrics()
        @Suppress("DEPRECATION")
        d.getRealMetrics(m)
        send(Protocol.SWIPE) {
            putExtra(Protocol.DISPLAY_ID, id)
            putExtra("x1", m.widthPixels / 2)
            putExtra("y1", (m.heightPixels * .75f).toInt())
            putExtra("x2", m.widthPixels / 2)
            putExtra("y2", (m.heightPixels * .25f).toInt())
            putExtra(Protocol.DURATION, 350)
        }
    }

    private inline fun send(action: String, extras: Intent.() -> Unit) {
        val i = Intent(action).setPackage(packageName).apply {
            putExtra(Protocol.AUTH_TOKEN, commandToken())
            putExtra(Protocol.REQUEST_ID, UUID.randomUUID().toString())
            extras()
        }
        sendBroadcast(i)
        log("Enviado: ${action.substringAfterLast('.')}")
    }

    private fun renderInspector(payload: String) {
        resultsBox.removeAllViews()
        try {
            val root = JSONObject(payload)
            val arr = root.optJSONArray("items") ?: JSONArray()
            inspectedDisplayId = root.optInt("display_id", inspectedDisplayId)
            if (arr.length() == 0) {
                resultsBox.addView(label("No se encontraron elementos accesibles. Comprueba el display y sus ventanas interactivas."))
                return
            }
            for (i in 0 until arr.length()) {
                val item = arr.getJSONObject(i)
                val text = item.optString("text").ifBlank { item.optString("description") }
                    .ifBlank { item.optString("viewId", "Elemento sin etiqueta") }
                val meta = "${item.optString("class")} · ${item.optString("bounds")}"
                val matchType = item.optString("matchType", "TEXT")
                val b = button("$text\n$meta\n${if(item.optBoolean("clickable")) "Interactivo" else "Elemento"}") {
                    val value = when(matchType) {
                        "DESCRIPTION" -> item.optString("description")
                        "ID" -> item.optString("viewId")
                        else -> item.optString("text")
                    }
                    val action = when(matchType) {
                        "DESCRIPTION" -> Protocol.TAP_DESCRIPTION
                        "ID" -> Protocol.TAP_ID
                        else -> Protocol.TAP_TEXT
                    }
                    if (value.isNotBlank()) send(action) {
                        if(matchType == "ID") putExtra(Protocol.VIEW_ID, value) else putExtra(Protocol.TEXT, value)
                        putExtra(Protocol.DISPLAY_ID, inspectedDisplayId)
                    } else log("El elemento no tiene un identificador accionable.")
                }
                resultsBox.addView(b)
            }
            log("${arr.length()} elementos recibidos del display ${root.optInt("display_id")}")
        } catch (e: Exception) {
            resultsBox.addView(label("No se pudo interpretar el resultado: ${e.message}"))
        }
    }

    private fun commandToken(): String {
        val existing = prefs.getString("command_token", null)
        if (!existing.isNullOrBlank()) return existing
        val created = UUID.randomUUID().toString().replace("-", "")
        prefs.edit().putString("command_token", created).apply()
        return created
    }

    private fun sectionTitle(s: String): TextView = TextView(this).apply {
        text = s
        textSize = 14f
        setTextColor(pink)
        setPadding(0, dp(18), 0, dp(7))
    }

    private fun label(s: String): TextView = TextView(this).apply {
        text = s
        textSize = 12f
        setTextColor(Color.LTGRAY)
        setPadding(0, dp(5), 0, dp(5))
    }

    private fun button(textValue: String, click: () -> Unit): Button =
        Button(this).apply {
            text = textValue
            isAllCaps = false
            setTextColor(Color.WHITE)
            setBackgroundColor(panel)
            setOnClickListener { click() }
            val lp = LinearLayout.LayoutParams(-1, LinearLayout.LayoutParams.WRAP_CONTENT)
            lp.bottomMargin = dp(6)
            layoutParams = lp
        }

    private fun log(s: String) {
        if (::logView.isInitialized) logView.text = s
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
}
