# FFAI - Free Fire AI Assistant

Sistema de IA avanzado para asistencia en Free Fire (Battle Royale), optimizado para dispositivos de gama media como el Samsung Galaxy A21S.

## Características

- **Sistema de IA Modular**: 15+ módulos cooperativos con orquestador central
- **Percepción Visual**: Detección de objetos en tiempo real usando YOLO/TensorFlow Lite
- **Control por Gestos**: Motor de gestos en C++ para ejecución precisa vía AccessibilityService
- **Aprendizaje Reforzado**: PPO on-device que se adapta a tu estilo de juego
- **Personalidades Adaptativas**: 8 modos de personalidad (Defensivo, Agresivo, Sigiloso, etc.)
- **Optimización A21S**: Configuración específica para Exynos 850 (8x Cortex-A55)
- **Gestión Térmica**: Monitoreo y throttling automático para prevenir sobrecalentamiento

## Arquitectura

```
FFAI/
├── Core
│   ├── Orquestador Central - Coordina todos los módulos
│   ├── Planificador de Tareas - Scheduling RT
│   └── Bus de Eventos - RingBuffer lock-free
├── Percepción
│   ├── Visión (YOLO/TFLite) - Detección de enemigos, loot, cobertura
│   ├── OCR (ML Kit) - Lectura de UI (HP, munición, minimapa)
│   └── Fusión Sensorial - Combina fuentes de información
├── Memoria
│   ├── STM (30s) - Buffer circular de estado reciente
│   ├── MTM (SQLite) - Datos de partida, patrones de enemigos
│   └── LTM (mmap) - Perfiles persistentes, conocimiento de mapas
├── Aprendizaje
│   ├── RL Engine (PPO) - Optimización de políticas
│   ├── Adaptación - Ajuste dinámico de parámetros
│   └── Generalización - Transfer learning entre mapas
├── Táctica
│   ├── Posicionamiento - Control de cobertura y distancia
│   ├── Tempo - Ritmo de combate
│   └── Decisión - Árbol de decisión ponderado
├── Combate
│   ├── Targeting - Selección de objetivo
│   ├── Tracking - Seguimiento fino con predicción
│   ├── Recoil - Compensación de retroceso por arma
│   └── Fire Control - Tap, burst, spray
├── Humanización
│   ├── Variación - Aleatoriedad controlada
│   ├── Ritmo - Tempo natural
│   └── Error - Errores "humanos" calculados
└── Ejecución
    ├── Gesture Engine (C++) - Interpolación Catmull-Rom
    ├── Camera Engine (C++) - PID + Kalman filter
    └── AccessibilityService - Inyección de toques
```

## Requisitos

- **Android**: 8.0+ (API 26), optimizado para Android 12
- **Dispositivo Recomendado**: Samsung Galaxy A21S o equivalente
  - Exynos 850 (8x Cortex-A55 @ 2.0GHz)
  - 3-6GB RAM
  - Android 12
- **Almacenamiento**: 200MB+ (sin modelos), 500MB+ (con modelos ML)
- **Permisos**: Accesibilidad, Overlay, Captura de Pantalla

## Instalación

1. Descargar APK desde releases
2. Habilitar "Fuentes desconocidas" en ajustes
3. Instalar APK
4. Abrir FFAI y conceder permisos:
   - **Accesibilidad**: Configuración > Accesibilidad > FFAI Core Service
   - **Overlay**: Permitir dibujar sobre otras apps
   - **Captura de Pantalla**: Se solicitará al iniciar

## Uso

1. Iniciar FFAI
2. Seleccionar modo de personalidad
3. Ajustar nivel de agresión
4. Iniciar servicio
5. Abrir Free Fire

La IA comenzará a analizar la pantalla y asistir según el nivel configurado:
- **Ninguno**: Solo análisis, sin acción
- **Pasivo**: Sugerencias visuales
- **Semi**: Asiste con aim y movimiento
- **Completo**: Automatización total

## Modos de Personalidad

| Modo | Agresión | Cautela | Estilo | Uso Recomendado |
|------|----------|---------|--------|-----------------|
| Ultra Defensivo | 10% | 95% | Tortuga | Supervivencia extrema |
| Defensivo | 30% | 80% | Cauteloso | Juego seguro |
| Equilibrado | 50% | 50% | Estándar | Mixto |
| Agresivo | 80% | 20% | Dinámico | Push activo |
| Ultra Agresivo | 95% | 5% | Asaltante | Riesgo alto |
| Sigiloso | 30% | 90% | Lento | Early game |
| Francotirador | 40% | 70% | Preciso | Larga distancia |
| Asaltante | 90% | 15% | Rápido | CQC |

## Configuración A21S

El sistema detecta automáticamente el A21S y aplica optimizaciones:

```kotlin
// A21S Optimizations
const val THERMAL_THROTTLE_TEMP = 40°C
const val MAX_CONCURRENT_MODELS = 1
const val INFERENCE_INTERVAL_MS = 100
const val SCREEN_ANALYSIS_RES = 240px
const val MODEL_QUANTIZATION = INT8
const val USE_NNAPI = true
const val MEMORY_LIMIT_MB = 250
```

## Desarrollo

### Build Local

```bash
# Clonar repositorio
git clone https://github.com/username/ffai.git
cd ffai

# Build debug
./gradlew assembleDebug

# Build release (requiere keystore)
./gradlew assembleRelease
```

### CI/CD

El proyecto incluye GitHub Actions:

- **Push a main/develop**: Build debug + tests
- **Tag v***: Build release firmado + GitHub Release
- **Pull Request**: Lint + tests + security scan

## Modelos ML

| Modelo | Tamaño | Propósito |
|--------|--------|-----------|
| object_detector.tflite | 45MB | Detección de enemigos, loot |
| ocr_recognizer.tflite | 25MB | Lectura de UI |
| tactical_policy.onnx | 35MB | Decisiones tácticas (PPO) |
| gesture_classifier.tflite | 5MB | Clasificación de gestos |
| recoil_predictor.tflite | 8MB | Compensación de recoil |
| enemy_predictor.onnx | 20MB | Predicción de movimiento |
| style_profiler.tflite | 12MB | Clasificación de estilos |
| **Total** | **~150MB** | |

## Arquitectura ML

### Red Neuronal de Decisión (Tactical Policy)

```
Input: Vector de estado (512 dims)
  ↓
Dense 256 + ReLU + Dropout 0.3
  ↓
Dense 128 + ReLU
  ↓
Output: Vector de acción (16 dims) + Valor de estado
```

- **Entrenamiento**: PPO (Proximal Policy Optimization)
- **Actualización**: On-device cada 100 ciclos
- **Batch size**: 16-32 (según dispositivo)

## Seguridad

- Todos los modelos se verifican con checksum SHA-256
- Comunicación con servidor de updates usa TLS 1.3
- No se recolectan datos personales
- Todo el procesamiento es local

## Limitaciones

- Requiere permisos de accesibilidad (Android limita ciertas funciones)
- Rendimiento varía según dispositivo
- Algunos juegos pueden detectar inyección de gestos
- Throttling térmico en sesiones largas (>30min)

## Contribuir

1. Fork del repositorio
2. Crear rama feature: `git checkout -b feature/nueva-funcionalidad`
3. Commit: `git commit -am 'Añadir nueva funcionalidad'`
4. Push: `git push origin feature/nueva-funcionalidad`
5. Pull Request

## Licencia

MIT License - Ver LICENSE

## Descargo de Responsabilidad

Este software es para fines educativos y de investigación. El uso en competiciones oficiales puede violar términos de servicio. Úsalo bajo tu propia responsabilidad.

## Contacto

- Issues: GitHub Issues
- Discusiones: GitHub Discussions
- Email: support@ffai.dev

---

**FFAI** - Inteligencia Artificial para Free Fire
Optimizado para Samsung Galaxy A21S
