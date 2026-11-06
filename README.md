# Glass — launcher para la TCL

Pantalla de inicio propia para la tele (Google TV, Android 11): vidrio estilo Apple TV sobre estampas ukiyo-e que cambian según el momento del día.

- **Inicio:** dock con las apps favoritas, clima, entradas HDMI, ficha de la obra.
- **Arte:** 12 estampas de Hokusai e Hiroshige (dominio público, colecciones abiertas del Met), 3 por momento: amanecer, día, atardecer y noche. Rotan una vez al día.
- **Modo galería:** tras 3 minutos sin usar el control, solo queda la obra. También como protector de pantalla del sistema.
- **Modo noche:** de 20:00 a 5:00 baja el brillo de la interfaz y el volumen de los sonidos.
- **Sonidos propios** en lugar de los del sistema.

Todo lo ajustable está en `app/src/main/java/mx/oli/glass/Config.kt`.

## Compilar

Cada push a `main` compila en GitHub Actions y publica `glass.apk` en la rama `build`.

## Instalar

```
adb connect 192.168.1.85:5555
adb install -r glass.apk
adb shell am start -n mx.oli.glass/.MainActivity
```
