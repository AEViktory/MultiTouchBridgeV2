# AE MultiTouch Bridge 2.0.1

Proyecto Android nativo Kotlin para inspeccionar el árbol de accesibilidad disponible, seleccionar displays, ejecutar gestos y recibir órdenes desde Tasker mediante broadcasts autenticados.

## Estado de entrega

Proyecto fuente actualizado con correcciones de auditoría. **No se declara compilado ni probado en dispositivo**: el entorno de generación no tiene Android SDK/Gradle. El APK debe generarse y probarse en el entorno del usuario.

## Requisitos

- Android 11 / API 30 o superior.
- JDK 17.
- Android SDK Platform 34.
- Gradle 8.2 o superior (recomendado 8.4).

## Compilar

```bash
gradle --no-daemon --max-workers=1 assembleDebug
```

APK esperado: `app/build/outputs/apk/debug/app-debug.apk`.

## Primer inicio

1. Instala el APK.
2. Abre AE MultiTouch Bridge.
3. Pulsa **Copiar token para Tasker** y guarda el token en una variable segura de Tasker.
4. Habilita el servicio en Ajustes > Accesibilidad.
5. Crea el virtual display de scrcpy y pulsa **Actualizar pantallas**.
6. Selecciona el display y prueba primero **Inspeccionar pantalla**.

El token se genera localmente y se guarda en preferencias privadas de la app. Los comandos sin token válido se rechazan. No compartas el token: cualquier aplicación o perfil que lo conozca podría enviar acciones.

## Broadcast API para Tasker

Paquete: `com.ae.multitouchbridge`

Extras comunes:
- `auth_token`: token copiado desde la app.
- `request_id`: identificador que Tasker genera para correlacionar la respuesta.
- `display_id`: ID del display actual.

### Tap por texto exacto

```bash
am broadcast -a com.ae.multitouchbridge.TAP_TEXT -p com.ae.multitouchbridge --es auth_token TU_TOKEN --es request_id task_001 --es text "Aceptar" --ei display_id 2
```

### Long Tap por texto exacto

```bash
am broadcast -a com.ae.multitouchbridge.LONG_TAP_TEXT -p com.ae.multitouchbridge --es auth_token TU_TOKEN --es request_id task_002 --es text "Aceptar" --ei display_id 2 --ei duration 900
```

### Tap por descripción accesible

```bash
am broadcast -a com.ae.multitouchbridge.TAP_DESCRIPTION -p com.ae.multitouchbridge --es auth_token TU_TOKEN --es request_id task_003 --es text "Cerrar" --ei display_id 2
```

### Tap por resource ID

```bash
am broadcast -a com.ae.multitouchbridge.TAP_ID -p com.ae.multitouchbridge --es auth_token TU_TOKEN --es request_id task_004 --es view_id "com.example:id/confirm" --ei display_id 2
```

### Inspeccionar

```bash
am broadcast -a com.ae.multitouchbridge.INSPECT -p com.ae.multitouchbridge --es auth_token TU_TOKEN --es request_id task_005 --ei display_id 2
```

Respuesta `INSPECT_RESULT`: JSON con `request_id`, `display_id`, `count` e `items`. Cada elemento contiene `text`, `description`, `viewId`, `bounds`, `clickable`, `longClickable`, `enabled`, `visible` y `matchType`.

### Pinch In (840 × 1500)

```bash
am broadcast -a com.ae.multitouchbridge.PINCH_IN -p com.ae.multitouchbridge --es auth_token TU_TOKEN --es request_id task_006 --ei display_id 2 --ei center_x 420 --ei center_y 750 --ei start_span 220 --ei end_span 120 --ei duration 350
```

### Pinch Out (840 × 1500)

```bash
am broadcast -a com.ae.multitouchbridge.PINCH_OUT -p com.ae.multitouchbridge --es auth_token TU_TOKEN --es request_id task_007 --ei display_id 2 --ei center_x 420 --ei center_y 750 --ei start_span 120 --ei end_span 220 --ei duration 350
```

### Tap por coordenadas

```bash
am broadcast -a com.ae.multitouchbridge.TAP_COORDS -p com.ae.multitouchbridge --es auth_token TU_TOKEN --es request_id task_008 --ei display_id 2 --ei center_x 420 --ei center_y 750 --ei duration 100
```

### Swipe

```bash
am broadcast -a com.ae.multitouchbridge.SWIPE -p com.ae.multitouchbridge --es auth_token TU_TOKEN --es request_id task_009 --ei display_id 2 --ei x1 420 --ei y1 1125 --ei x2 420 --ei y2 375 --ei duration 350
```

### Respuestas a Tasker

Tasker debe configurar un perfil **Event > System > Intent Received** para estas acciones:
- `com.ae.multitouchbridge.STATUS`
- `com.ae.multitouchbridge.INSPECT_RESULT`

La respuesta se dirige explícitamente al paquete `net.dinglisch.android.taskerm`. El extra `payload` contiene JSON y `request_id` permite asociarlo con la solicitud. El estado puede ser `SUCCESS`, `NOT_FOUND`, `AMBIGUOUS`, `NOT_ACTIONABLE`, `INVALID_ARGUMENT`, `DISPLAY_NOT_FOUND`, `UNAUTHORIZED`, `CANCELLED`, `REJECTED` o `ERROR`.

## Correcciones de esta revisión

- Token de autenticación obligatorio para todos los comandos.
- Respuestas dirigidas a Tasker, además del canal interno de la app.
- `request_id` en respuestas.
- Coincidencia semántica exacta; no pulsa automáticamente la primera coincidencia parcial.
- Resultado `AMBIGUOUS` cuando hay varias coincidencias exactas.
- Selección por texto, descripción o resource ID desde el inspector.
- Validación de límites, duración y parámetros de pinch/swipe/tap.
- Cola de gestos para no superponer llamadas a `dispatchGesture()`.
- Tap por coordenadas separado de Tap por texto.
- Inspector limitado a 300 nodos etiquetados para evitar crear listas enormes.

## Limitaciones que siguen requiriendo pruebas reales

- La lista de displays no garantiza que el servicio pueda leer las ventanas de cada display. Un virtual display privado de scrcpy puede no exponer su árbol accesible al servicio.
- El inspector lee el árbol de accesibilidad, no una captura de pantalla. Canvas, juegos, controles personalizados y algunas WebViews pueden no exponer nodos.
- `SCROLL_TO_TEXT` todavía hace un solo desplazamiento y devuelve `SCROLLED_ONCE`; no promete encontrar el elemento automáticamente.
- La acción `SUCCESS` confirma que Android aceptó/terminó el gesto o que el nodo aceptó la acción. No confirma que la app de destino haya cambiado visualmente como se esperaba.
- El canal Tasker requiere que el perfil Intent Received escuche el paquete/acción indicados. Debe probarse con la versión de Tasker instalada.
- La interfaz es una primera consola visual nativa. Aún no incluye captura de pantalla anotada, overlay de selección ni editor de secuencias avanzado.

## Diagnóstico

```bash
adb logcat -s AEMultiTouch2
```
