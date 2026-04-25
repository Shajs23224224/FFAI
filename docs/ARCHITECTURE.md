# FFAI Architecture Documentation

## System Overview

FFAI is a modular AI system for mobile battle royale games, designed specifically for the Samsung Galaxy A21S and similar mid-range devices.

## Core Architecture

### Layer Structure

```
┌─────────────────────────────────────────────────────────────┐
│                    PRESENTATION LAYER                       │
│  ┌──────────────┐ ┌──────────────┐ ┌──────────────┐         │
│  │ MainActivity │ │ ControlPanel │ │ Performance  │         │
│  │   (UI)       │ │  Fragment    │ │  Monitor     │         │
│  └──────────────┘ └──────────────┘ └──────────────┘         │
└─────────────────────────────────────────────────────────────┘
                            ↕
┌─────────────────────────────────────────────────────────────┐
│                    SERVICE LAYER                              │
│  ┌─────────────────────────────────────────────────────────┐  │
│  │          FFAIAccessibilityService                        │  │
│  │  - Screen capture via MediaProjection                  │  │
│  │  - Gesture injection                                    │  │
│  │  - Window analysis                                     │  │
│  └─────────────────────────────────────────────────────────┘  │
└─────────────────────────────────────────────────────────────┘
                            ↕
┌─────────────────────────────────────────────────────────────┐
│                    CORE LAYER                                 │
│  ┌─────────────────────────────────────────────────────────┐  │
│  │            CentralOrchestrator                            │  │
│  │     (Decision coordination & conflict resolution)       │  │
│  └─────────────────────────────────────────────────────────┘  │
│  ┌─────────────┐ ┌─────────────┐ ┌─────────────────────────┐  │
│  │  TaskScheduler│ │ EventBus     │ │  PerformanceMonitor     │  │
│  │   (Priority)  │ │ (RingBuffer) │ │  (Thermal/CPU/Mem)      │  │
│  └─────────────┘ └─────────────┘ └─────────────────────────┘  │
└─────────────────────────────────────────────────────────────┘
                            ↕
┌─────────────────────────────────────────────────────────────┐
│                    MODULE LAYER                               │
│  ┌───────────┐ ┌───────────┐ ┌───────────┐ ┌───────────┐     │
│  │Perception │ │   Memory  │ │ Learning  │ │ Prediction│     │
│  │ (Vision)  │ │(STM/MTM/  │ │   (RL)    │ │ (Tracking)│     │
│  │  (OCR)    │ │   LTM)    │ │           │ │           │     │
│  └───────────┘ └───────────┘ └───────────┘ └───────────┘     │
│  ┌───────────┐ ┌───────────┐ ┌───────────┐ ┌───────────┐     │
│  │  Tactics  │ │  Combat   │ │Humanization│ │ Profiling │     │
│  │(Decision) │ │(Aim/Fire) │ │ (Variation)│ │ (Patterns)│     │
│  └───────────┘ └───────────┘ └───────────┘ └───────────┘     │
│  ┌───────────┐ ┌───────────┐ ┌───────────┐ ┌───────────┐     │
│  │  Economy  │ │Information│ │ Resilience│ │    Meta   │     │
│  │(Inventory)│ │ (Exposure)│ │(Recovery) │ │(Eval/Opt) │     │
│  └───────────┘ └───────────┘ └───────────┘ └───────────┘     │
└─────────────────────────────────────────────────────────────┘
                            ↕
┌─────────────────────────────────────────────────────────────┐
│                    EXECUTION LAYER                          │
│  ┌──────────────┐ ┌──────────────┐ ┌──────────────┐         │
│  │GestureEngine │ │CameraEngine  │ │MovementCtrl  │         │
│  │   (C++)      │ │   (C++)      │ │   (Nav)      │         │
│  │ - Classifier │ │ - PID/Kalman │ │ - Pathfind   │         │
│  │ - Executor   │ │ - Smoothing  │ │ - Cover      │         │
│  │ - Interpolator│ │ - Prediction │ │ - Loot       │         │
│  └──────────────┘ └──────────────┘ └──────────────┘         │
└─────────────────────────────────────────────────────────────┘
                            ↕
┌─────────────────────────────────────────────────────────────┐
│                    INFRASTRUCTURE                           │
│  ┌─────────────────────────────────────────────────────────┐│
│  │               ML Runtime (TFLite/ONNX)                    ││
│  │  ┌───────────┐ ┌───────────┐ ┌───────────┐             ││
│  │  │ NNAPI     │ │ GPU Del.  │ │ CPU/XNN   │             ││
│  │  │ (Exynos)  │ │ (Mali-G52)│ │ (4 thr.)  │             ││
│  │  └───────────┘ └───────────┘ └───────────┘             ││
│  └─────────────────────────────────────────────────────────┘│
│  ┌─────────────────────────────────────────────────────────┐│
│  │               Native Libraries (NDK)                    ││
│  │  - gesture_engine.so  - camera_engine.so                ││
│  │  - vision_processor.so  - ml_inference.so               ││
│  └─────────────────────────────────────────────────────────┘│
└─────────────────────────────────────────────────────────────┘
```

## Data Flow

### Perception Cycle (60Hz target)

```
Screen Capture (MediaProjection)
        ↓
Frame Buffer (2-3 frames max)
        ↓
Parallel Analysis:
  ├─> Object Detection (YOLO) ──> Enemies, Loot, Cover
  ├─> OCR (ML Kit) ────────────> HP, Ammo, Timer
  └─> Audio Analysis ───────────> Footsteps, Gunshots
        ↓
Fusion & State Extraction
        ↓
STM (Short-Term Memory)
        ↓
Prediction Engine ────────────> Enemy trajectories
        ↓
Tactical Context Builder
        ↓
CentralOrchestrator
        ↓
Module Scoring (weighted decision)
        ↓
Action Selection
        ↓
Humanization Layer ───────────> Variation, rhythm, errors
        ↓
Gesture/Camera Execution
        ↓
Feedback to RL (reward calculation)
```

## Memory Architecture

### STM (Short-Term Memory)
- **Capacity**: 300 frames (~30s @ 10fps analysis)
- **Retention**: 30 seconds
- **Structure**: Circular ArrayDeque
- **Use**: Immediate context, motion tracking
- **Size**: ~50KB

### MTM (Medium-Term Memory)
- **Storage**: SQLite
- **Retention**: Current match
- **Data**: Encounters, deaths, decisions, enemy patterns
- **Tables**: 
  - encounters: enemy interactions
  - deaths: death locations and causes
  - decisions: tactical choices and outcomes
  - enemy_patterns: learned player behaviors
- **Size**: ~5-10MB per match

### LTM (Long-Term Memory)
- **Storage**: mmap + protobuf
- **Retention**: Persistent
- **Data**: Player profiles, map knowledge, RL models
- **Format**: Binary (efficient)
- **Size**: ~50-100MB total

## ML Model Architecture

### Model Specifications

| Model | Size | Input | Output | Backend |
|-------|------|-------|--------|---------|
| Object Detector | 45MB | 320x320 RGB | 10 detections | TFLite + NNAPI |
| OCR Recognizer | 25MB | Variable x 32 | Text + bbox | ML Kit |
| Tactical Policy | 35MB | 128-dim state | 16 actions | ONNX Runtime |
| Gesture Classifier | 5MB | 20 points x 5 | Gesture type | TFLite |
| Recoil Predictor | 8MB | Weapon + pose | Compensation | TFLite |
| Enemy Predictor | 20MB | 10-frame hist | 5-frame future | ONNX |
| Style Profiler | 12MB | 64-dim behavior | Style scores | TFLite |
| **Total** | **~150MB** | | | |

### Tactical Policy Network

```
Input Layer: 128 neurons (normalized game state)
    ↓
Dense Layer 1: 256 neurons, ReLU, Dropout 0.3
    ↓
Dense Layer 2: 128 neurons, ReLU
    ↓
Output Layer 1: 16 neurons (action logits) - Softmax
Output Layer 2: 1 neuron (state value) - Linear
```

**Training**: PPO (Proximal Policy Optimization)
- Learning rate: 0.001
- Discount (γ): 0.99
- GAE λ: 0.95
- Clip ε: 0.2
- Batch size: 16-32 (A21S optimized)

## Decision System

### Scoring Algorithm

```kotlin
finalScore = (
    perceptionScore * 0.15 +
    memoryScore * 0.10 +
    predictionScore * 0.15 +
    tacticsScore * 0.20 +
    combatScore * 0.20 +
    economyScore * 0.05 +
    humanizationScore * 0.10 +
    profilingScore * 0.05
) * urgency * personalityMultiplier
```

### Action Types

1. **Move**: Direction + speed (walk/run/sprint)
2. **Aim**: Target position + prediction
3. **Fire**: Mode (tap/burst/spray) + duration
4. **UseAbility**: Grenade, smoke, heal, etc.
5. **TakeCover**: Cover position
6. **Loot**: Item position
7. **Heal**: Item slot
8. **Reload**: Weapon slot
9. **Scan**: Area to search
10. **Compound**: Sequences with delays

## Gesture System

### Pipeline

```
Raw Input → Normalize → Filter Jitter → 
    ↓
Classify (ML) → Interpret → 
    ↓
Interpolate (Catmull-Rom) → 
    ↓
Execute (dispatchGesture)
    ↓
Verify
```

### Supported Gestures

- Tap (single/double/triple)
- Hold (short/long)
- Swipe (short/long/directional)
- Drag
- Pinch (2-finger)
- Rotate (2-finger)
- Compound sequences
- Camera pan
- Camera micro-adjust

### Interpolation

**Catmull-Rom Spline** for smooth curves:
```cpp
P(t) = 0.5 * [
    (2 * P1) +
    (-P0 + P2) * t +
    (2*P0 - 5*P1 + 4*P2 - P3) * t² +
    (-P0 + 3*P1 - 3*P2 + P3) * t³
]
```

## Camera Control

### PID Controller

```cpp
output = Kp * error + Ki * integral + Kd * derivative

Kp = 0.8  // Proportional gain
Ki = 0.1  // Integral gain  
Kd = 0.3  // Derivative gain
```

### Kalman Filter

**State**: [x, y, vx, vy]
**Prediction**:
```
x' = x + vx * dt
y' = y + vy * dt
```

**Update** (simplified):
```
K = P / (P + R)
x = x + K * (measurement - x)
P = (1 - K) * P
```

## Optimization for A21S

### Hardware Constraints
- **CPU**: 8x Cortex-A55 @ 2.0GHz (no big cores)
- **GPU**: Mali-G52 (limited compute)
- **RAM**: 3-6GB shared
- **No NPU**: No dedicated AI acceleration

### Applied Optimizations

| Area | Strategy | Value |
|------|----------|-------|
| Models | Quantization | INT8 |
| Inference | Threading | 2 threads max |
| Delegates | NNAPI first, GPU fallback | - |
| Screen Analysis | Resolution | 240px width |
| Frame Rate | Analysis interval | 100ms (10fps) |
| Memory | Model pooling | 1 model active |
| Thermal | Throttling | Start @ 40°C |
| Battery | Aggressive doze | - |

### Thermal Management

```
Temp < 38°C: Full performance
38-40°C:     Reduce inference to 5fps
40-42°C:     Single model, 2fps
> 42°C:      Pause analysis, gestures only
```

## Security & Privacy

### Data Protection
- All ML inference local (no cloud)
- No screen data transmitted
- Models signed (SHA-256 verification)
- Updates via TLS 1.3

### Anti-Detection
- Humanization layer adds realistic jitter
- Variable reaction times (150-400ms)
- Occasional intentional misses
- Random micro-pauses

## Build System

### GitHub Actions Pipeline

```yaml
Trigger: Push/PR/Tag
├── Setup (Java 17, Android SDK)
├── Cache (Gradle, NDK)
├── Lint & Tests
├── Build (Debug/Release)
├── Sign (Release only)
└── Upload Artifacts
```

### NDK Build

**CMake flags**:
```
-O3 -DNDEBUG -ffast-math -fomit-frame-pointer
-march=armv8-a+crc -mtune=cortex-a55
```

## Future Enhancements

1. **Model Streaming**: Download models on-demand
2. **Federated Learning**: Share patterns (opt-in)
3. **Advanced Vision**: Instance segmentation
4. **Voice Commands**: Speech recognition
5. **3D Reconstruction**: Depth estimation
6. **Auto-Difficulty**: Adapt to player skill
