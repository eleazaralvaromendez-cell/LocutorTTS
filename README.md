# Locutor TTS para Android

Proyecto Android nativo que convierte texto a audio usando el motor TTS instalado en el teléfono.

## Qué hace

- Pegar o escribir textos largos.
- Abrir `.txt`.
- Abrir `.docx` de Word.
- Intentar extraer texto de `.pdf`.
- Elegir entre las voces en español instaladas en Android.
- Ajustar velocidad y tono.
- Divide automáticamente textos largos en fragmentos.
- Une los fragmentos en un solo archivo WAV.
- Guarda el audio en `Descargas/LocutorTTS`.
- Permite escuchar y compartir el audio.

## Importante sobre "Loquendo"

Esta app NO incluye voces propietarias de Loquendo. Usa las voces TTS instaladas en Android. Puedes instalar/seleccionar una voz española o mexicana desde los ajustes del motor TTS del teléfono.

## PDF

El extractor PDF de esta primera versión es deliberadamente liviano para no depender de librerías externas. Funciona con muchos PDFs simples de texto, pero algunos PDFs con fuentes/codificaciones complejas pueden no extraerse bien. Un PDF que sea sólo fotografías/escaneo necesita OCR.

## Por qué genera WAV y no MP3

Android TTS produce audio de forma fiable como WAV/PCM. WAV funciona en editores de video como CapCut y evita depender de un codificador MP3 externo. Una siguiente versión puede añadir conversión a M4A/MP3.

## Compilar automáticamente con GitHub Actions

El proyecto incluye `.github/workflows/build-apk.yml`.

Cuando termine el workflow, descarga el artefacto `LocutorTTS-debug-apk`; dentro estará `app-debug.apk`.

## Requisitos

- Android 10 (API 29) o superior.
- Un motor de texto a voz instalado.
- Para algunas voces de red, conexión a internet.
