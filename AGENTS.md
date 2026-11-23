# AE MultiTouch Bridge V2

Reglas específicas del proyecto. Las globales de AE Remote Dev Environment se heredan desde `~/.config/opencode/AGENTS.md`: no las repitas aquí.

- Kotlin con scripts Groovy (`build.gradle`); **no hay Gradle Wrapper**. CI instala Gradle 8.4 y compila con `gradle --no-daemon --max-workers=1 assembleDebug` (JDK 17, SDK platform 34, minSdk 30, targetSdk 33).
- Builds Debug solo en GitHub Actions (`.github/workflows/android-debug.yml`); artefacto real: `app-debug` (no `apk-debug`). Prohibido compilar, instalar SDK/Gradle o ejecutar `sdkmanager` en Termux.
- Flujo: `/status` e `/review` para inspeccionar, `/sync` para preparar (nunca ejecuta `add`/`commit`/`push`), y `/compile` y `/download` solo con autorización explícita. Sin `git fetch` automático.
- El token de la API Tasker se genera en la app y viaja en `auth_token`: nunca lo imprimas ni lo guardes en el repo.
