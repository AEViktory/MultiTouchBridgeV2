package com.ae.multitouchbridge

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.accessibilityservice.GestureDescription
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Path
import android.graphics.Rect
import android.hardware.display.DisplayManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Display
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import org.json.JSONArray
import org.json.JSONObject
import java.util.ArrayDeque
import java.util.UUID
import kotlin.math.max
import kotlin.math.min

class MultiTouchAccessibilityService : AccessibilityService() {
    private var receiver: BroadcastReceiver? = null
    private val handler = Handler(Looper.getMainLooper())
    private val gestureQueue = ArrayDeque<QueuedGesture>()
    private var gestureRunning = false
    private val prefs by lazy { getSharedPreferences(PREFS, MODE_PRIVATE) }

    private data class QueuedGesture(val gesture: GestureDescription, val displayId: Int, val name: String, val requestId: String)
    private enum class Match { TEXT, DESCRIPTION, ID }

    override fun onServiceConnected() {
        super.onServiceConnected()
        serviceInfo = serviceInfo.apply {
            flags = flags or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS or AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS
        }
        val filter = IntentFilter().apply {
            listOf(Protocol.PINCH_IN, Protocol.PINCH_OUT, Protocol.TAP_TEXT, Protocol.LONG_TAP_TEXT,
                Protocol.TAP_DESCRIPTION, Protocol.TAP_ID, Protocol.TAP_COORDS, Protocol.SCROLL_TO_TEXT, Protocol.INSPECT, Protocol.SWIPE).forEach(::addAction)
        }
        receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                if (intent == null) return
                val supplied = intent.getStringExtra(Protocol.AUTH_TOKEN).orEmpty()
                val expected = prefs.getString(TOKEN_KEY, "").orEmpty()
                if (expected.isBlank() || supplied != expected) {
                    reply(intent, "UNAUTHORIZED", "Token ausente o incorrecto")
                    return
                }
                try {
                    when (intent.action) {
                        Protocol.PINCH_IN, Protocol.PINCH_OUT -> pinch(intent)
                        Protocol.TAP_TEXT -> semanticAction(intent, Match.TEXT, false)
                        Protocol.LONG_TAP_TEXT -> semanticAction(intent, Match.TEXT, true)
                        Protocol.TAP_DESCRIPTION -> semanticAction(intent, Match.DESCRIPTION, false)
                        Protocol.TAP_ID -> semanticAction(intent, Match.ID, false)
                        Protocol.TAP_COORDS -> tapCoordinates(intent)
                        Protocol.SCROLL_TO_TEXT -> scrollToText(intent)
                        Protocol.INSPECT -> inspect(intent)
                        Protocol.SWIPE -> swipe(intent)
                        else -> reply(intent, "UNSUPPORTED", "Acción desconocida")
                    }
                } catch (t: Throwable) {
                    Log.e(TAG, "Command failed", t)
                    reply(intent, "ERROR", t.message ?: t.javaClass.simpleName)
                }
            }
        }
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED)
        else @Suppress("DEPRECATION") registerReceiver(receiver, filter)
        status("READY", "Servicio conectado")
    }

    private fun pinch(i: Intent) {
        val display = i.getIntExtra(Protocol.DISPLAY_ID, 0)
        val cx = i.getIntExtra(Protocol.CENTER_X, -1)
        val cy = i.getIntExtra(Protocol.CENTER_Y, -1)
        val start = i.getIntExtra(Protocol.START_SPAN, 220)
        val end = i.getIntExtra(Protocol.END_SPAN, 120)
        val duration = i.getIntExtra(Protocol.DURATION, 350)
        val dims = dimensions(display) ?: return reply(i, "DISPLAY_NOT_FOUND", "Display $display no disponible")
        if (start !in 20..3000 || end !in 20..3000 || start == end || duration !in 80..5000)
            return reply(i, "INVALID_ARGUMENT", "Rangos de pinch o duración inválidos")
        if (cx < 0 || cy < 0 || cx >= dims.first || cy >= dims.second)
            return reply(i, "INVALID_COORDINATES", "Centro fuera del display")
        val inward = i.action == Protocol.PINCH_IN
        if ((inward && end >= start) || (!inward && end <= start))
            return reply(i, "INVALID_ARGUMENT", "La separación final no coincide con el tipo de pinch")
        val a = start / 2f; val b = end / 2f
        if (cx-a < 0 || cx+a >= dims.first || cx-b < 0 || cx+b >= dims.first || cy < 0 || cy >= dims.second)
            return reply(i, "INVALID_COORDINATES", "El gesto excede los límites del display")
        val p1 = Path().apply { moveTo(cx-a, cy.toFloat()); lineTo(cx-b, cy.toFloat()) }
        val p2 = Path().apply { moveTo(cx+a, cy.toFloat()); lineTo(cx+b, cy.toFloat()) }
        val gesture = GestureDescription.Builder().setDisplayId(display)
            .addStroke(GestureDescription.StrokeDescription(p1, 0, duration.toLong()))
            .addStroke(GestureDescription.StrokeDescription(p2, 0, duration.toLong())).build()
        enqueue(gesture, display, if (inward) "PINCH_IN" else "PINCH_OUT", requestId(i))
    }

    private fun swipe(i: Intent) {
        val display = i.getIntExtra(Protocol.DISPLAY_ID, 0)
        val dims = dimensions(display) ?: return reply(i, "DISPLAY_NOT_FOUND", "Display $display no disponible")
        val x1=i.getIntExtra("x1", -1); val y1=i.getIntExtra("y1", -1)
        val x2=i.getIntExtra("x2", -1); val y2=i.getIntExtra("y2", -1)
        val duration=i.getIntExtra(Protocol.DURATION, 350)
        if (duration !in 80..5000 || listOf(x1,x2).any { it !in 0 until dims.first } || listOf(y1,y2).any { it !in 0 until dims.second })
            return reply(i, "INVALID_ARGUMENT", "Coordenadas o duración fuera de rango")
        val p=Path().apply { moveTo(x1.toFloat(),y1.toFloat()); lineTo(x2.toFloat(),y2.toFloat()) }
        enqueue(GestureDescription.Builder().setDisplayId(display).addStroke(GestureDescription.StrokeDescription(p,0,duration.toLong())).build(), display, "SWIPE", requestId(i))
    }

    private fun enqueue(gesture: GestureDescription, display: Int, name: String, request: String) {
        gestureQueue.addLast(QueuedGesture(gesture, display, name, request))
        runNextGesture()
    }

    private fun runNextGesture() {
        if (gestureRunning || gestureQueue.isEmpty()) return
        val next = gestureQueue.removeFirst()
        if (Build.VERSION.SDK_INT < 30) {
            reply(next.requestId, "UNSUPPORTED_API", "Se requiere Android 11 o superior"); runNextGesture(); return
        }
        gestureRunning = true
        val accepted = try {
            dispatchGesture(next.gesture, object : GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription?) {
                    gestureRunning = false; reply(next.requestId, "SUCCESS", "${next.name} completado en display ${next.displayId}"); runNextGesture()
                }
                override fun onCancelled(gestureDescription: GestureDescription?) {
                    gestureRunning = false; reply(next.requestId, "CANCELLED", "${next.name} cancelado"); runNextGesture()
                }
            }, handler)
        } catch (t: Throwable) {
            gestureRunning = false; reply(next.requestId, "ERROR", t.message ?: "dispatchGesture falló"); runNextGesture(); false
        }
        if (!accepted && gestureRunning) {
            gestureRunning = false; reply(next.requestId, "REJECTED", "Android rechazó el gesto"); runNextGesture()
        }
    }

    private fun semanticAction(i: Intent, match: Match, long: Boolean) {
        val display=i.getIntExtra(Protocol.DISPLAY_ID,0)
        val query=if(match==Match.ID) i.getStringExtra(Protocol.VIEW_ID).orEmpty().trim() else i.getStringExtra(Protocol.TEXT).orEmpty().trim()
        if(query.isBlank()) return reply(i,"INVALID_ARGUMENT","Texto o ID vacío")
        val nodes=findNodes(display,query,match)
        if(nodes.isEmpty()) return reply(i,"NOT_FOUND","No se encontró '$query' en display $display")
        if(nodes.size>1) return reply(i,"AMBIGUOUS","Se encontraron ${nodes.size} coincidencias exactas para '$query'; usa ID o descripción más específica")
        val node=nodes.first()
        if(!node.isEnabled || !node.isVisibleToUser) return reply(i,"NOT_ACTIONABLE","El elemento no está habilitado o visible")
        val action=if(long) AccessibilityNodeInfo.ACTION_LONG_CLICK else AccessibilityNodeInfo.ACTION_CLICK
        if(node.performAction(action)) reply(i,"SUCCESS","${if(long) "LONG_TAP" else "TAP"} aceptado: '$query'")
        else {
            val r=Rect(); node.getBoundsInScreen(r)
            val dims=dimensions(display) ?: return reply(i,"DISPLAY_NOT_FOUND","Display no disponible")
            if(r.isEmpty || r.left<0 || r.top<0 || r.right>dims.first || r.bottom>dims.second)
                return reply(i,"NOT_ACTIONABLE","El nodo no tiene límites válidos para fallback táctil")
            val x=r.exactCenterX(); val y=r.exactCenterY()
            val duration=if(long) i.getIntExtra(Protocol.DURATION,900).coerceIn(500,3000) else 100
            val path=Path().apply { moveTo(x,y); lineTo(x+0.1f,y+0.1f) }
            enqueue(GestureDescription.Builder().setDisplayId(display).addStroke(GestureDescription.StrokeDescription(path,0,duration.toLong())).build(),display,if(long) "LONG_TAP_FALLBACK" else "TAP_FALLBACK",requestId(i))
        }
    }

    private fun tapCoordinates(i:Intent) {
        val display=i.getIntExtra(Protocol.DISPLAY_ID,0); val dims=dimensions(display)
            ?: return reply(i,"DISPLAY_NOT_FOUND","Display $display no disponible")
        val x=i.getIntExtra(Protocol.CENTER_X,-1); val y=i.getIntExtra(Protocol.CENTER_Y,-1)
        val duration=i.getIntExtra(Protocol.DURATION,100)
        if(x !in 0 until dims.first || y !in 0 until dims.second || duration !in 80..5000)
            return reply(i,"INVALID_ARGUMENT","Coordenadas o duración inválidas")
        val path=Path().apply { moveTo(x.toFloat(),y.toFloat()); lineTo(x+0.1f,y+0.1f) }
        enqueue(GestureDescription.Builder().setDisplayId(display)
            .addStroke(GestureDescription.StrokeDescription(path,0,duration.toLong())).build(),display,"TAP_COORDS",requestId(i))
    }

    private fun findNodes(display:Int, query:String, match:Match):List<AccessibilityNodeInfo> {
        val found=mutableListOf<AccessibilityNodeInfo>(); val q=norm(query)
        rootsFor(display).forEach { root ->
            val queue=ArrayDeque<AccessibilityNodeInfo>(); queue.add(root); var count=0
            while(queue.isNotEmpty() && count++<2500 && found.size<3) {
                val n=queue.removeFirst()
                val value=when(match) { Match.TEXT->n.text?.toString(); Match.DESCRIPTION->n.contentDescription?.toString(); Match.ID->n.viewIdResourceName }.orEmpty().trim()
                val exact=if(match==Match.ID) value==query || value.endsWith("/$query") else norm(value)==q
                if(exact && n.isVisibleToUser) found.add(n)
                for(index in 0 until n.childCount) try { n.getChild(index)?.let(queue::addLast) } catch(_:Exception){}
            }
        }
        return found
    }

    private fun inspect(i:Intent) {
        val display=i.getIntExtra(Protocol.DISPLAY_ID,0); val roots=rootsFor(display); val out=JSONArray()
        roots.forEach { root ->
            val queue=ArrayDeque<AccessibilityNodeInfo>(); queue.add(root); var count=0
            while(queue.isNotEmpty() && count++<2500 && out.length()<300) {
                val n=queue.removeFirst(); val text=n.text?.toString().orEmpty(); val desc=n.contentDescription?.toString().orEmpty(); val id=n.viewIdResourceName.orEmpty()
                if(text.isNotBlank()||desc.isNotBlank()||id.isNotBlank()) {
                    val r=Rect(); n.getBoundsInScreen(r)
                    out.put(JSONObject().apply {
                        put("text",text); put("description",desc); put("viewId",id); put("class",n.className?.toString().orEmpty())
                        put("bounds", "[${r.left},${r.top}][${r.right},${r.bottom}]")
                        put("clickable",n.isClickable); put("longClickable",n.isLongClickable); put("enabled",n.isEnabled)
                        put("visible",n.isVisibleToUser); put("matchType",when { text.isNotBlank()->"TEXT"; desc.isNotBlank()->"DESCRIPTION"; else->"ID" })
                    })
                }
                for(index in 0 until n.childCount) try { n.getChild(index)?.let(queue::addLast) } catch(_:Exception){}
            }
        }
        val payload=JSONObject().put("request_id",requestId(i)).put("display_id",display).put("count",out.length()).put("items",out).toString()
        sendBroadcast(Intent(Protocol.INSPECT_RESULT).setPackage(packageName).putExtra(Protocol.PAYLOAD,payload).putExtra(Protocol.REQUEST_ID,requestId(i)))
        sendExternal(Protocol.INSPECT_RESULT,payload,requestId(i))
        reply(i,if(out.length()==0) "EMPTY" else "SUCCESS","Inspección completada: ${out.length()} elementos")
    }

    private fun scrollToText(i:Intent) {
        val display=i.getIntExtra(Protocol.DISPLAY_ID,0); val query=i.getStringExtra(Protocol.TEXT).orEmpty()
        val match=findNodes(display,query,Match.TEXT)
        if(match.isNotEmpty()) return reply(i,"SUCCESS","Elemento visible: '$query'")
        val scroll=findScrollable(display) ?: return reply(i,"NO_SCROLL_CONTAINER","No se encontró un contenedor desplazable")
        val ok=scroll.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
        reply(i,if(ok) "SCROLLED_ONCE" else "SCROLL_REJECTED", "Se realizó un único desplazamiento; vuelve a inspeccionar para verificar")
    }

    private fun findScrollable(display:Int):AccessibilityNodeInfo? {
        rootsFor(display).forEach { root -> val q=ArrayDeque<AccessibilityNodeInfo>();q.add(root);var c=0
            while(q.isNotEmpty()&&c++<2500){val n=q.removeFirst();if(n.isScrollable)return n;for(k in 0 until n.childCount)try{n.getChild(k)?.let(q::addLast)}catch(_:Exception){}}
        }; return null
    }

    private fun rootsFor(display:Int):List<AccessibilityNodeInfo> {
        val result=mutableListOf<AccessibilityNodeInfo>()
        if(Build.VERSION.SDK_INT>=30) try { windowsOnAllDisplays.get(display)?.forEach { w -> try { w.root?.let(result::add) } catch(_:Exception){} } } catch(t:Throwable){Log.w(TAG,"Cannot read windows for display $display",t)}
        else if(display==0) windows.forEach { try { it.root?.let(result::add) } catch(_:Exception){} }
        return result
    }

    private fun dimensions(id:Int):Pair<Int,Int>? {
        val d=(getSystemService(DISPLAY_SERVICE) as DisplayManager).getDisplay(id) ?: return null
        val m=android.util.DisplayMetrics(); @Suppress("DEPRECATION") d.getRealMetrics(m)
        return if(m.widthPixels>0&&m.heightPixels>0) m.widthPixels to m.heightPixels else null
    }
    private fun norm(s:String)=s.trim().lowercase().replace(Regex("\\s+")," ")
    private fun requestId(i:Intent)=i.getStringExtra(Protocol.REQUEST_ID)?.takeIf{it.isNotBlank()} ?: UUID.randomUUID().toString()

    private fun reply(i:Intent,status:String,message:String)=reply(requestId(i),status,message)
    private fun reply(request:String,status:String,message:String) {
        val obj=JSONObject().put("request_id",request).put("status",status).put("message",message).toString()
        sendExternal(Protocol.STATUS,obj,request)
        sendBroadcast(Intent(Protocol.STATUS).setPackage(packageName).putExtra(Protocol.PAYLOAD,message).putExtra(Protocol.REQUEST_ID,request))
    }
    private fun status(status:String,message:String)=reply(UUID.randomUUID().toString(),status,message)
    private fun sendExternal(action:String,payload:String,request:String) {
        // Tasker Intent Received can listen for this action; package is explicitly routed to Tasker.
        sendBroadcast(Intent(action).setPackage(TASKER_PACKAGE).putExtra(Protocol.PAYLOAD,payload).putExtra(Protocol.REQUEST_ID,request))
    }

    override fun onAccessibilityEvent(event:AccessibilityEvent?)=Unit
    override fun onInterrupt(){status("INTERRUPTED","Servicio interrumpido")}
    override fun onDestroy(){receiver?.let{try{unregisterReceiver(it)}catch(_:Exception){}};handler.removeCallbacksAndMessages(null);gestureQueue.clear();super.onDestroy()}

    companion object {
        private const val TAG="AEMultiTouch2"
        private const val PREFS="bridge_settings"
        private const val TOKEN_KEY="command_token"
        private const val TASKER_PACKAGE="net.dinglisch.android.taskerm"
    }
}
