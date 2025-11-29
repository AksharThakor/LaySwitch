# 📱LaySwitch: Handedness Detection Using IMU Data

### Real-time left / right / both hand detection for smartphones using IMU sensors and Deep Learning

---

## 🧭 Overview

This project demonstrates a complete end-to-end system for **detecting which hand a user is using to interact with their smartphone** — left, right, or both — using only **IMU (Inertial Measurement Unit)** data collected from the device.

It includes:

- **Two Android applications**
  - 📲 **IMUData_Handedness** – for recording labeled IMU data  
  - ⚙️ **HandednessDetector** – for performing real-time inference using a trained model
- **A training pipeline** based on a **1D Convolutional Neural Network (1D-CNN)**
- **Supporting scripts and data-handling tools**
- **Pre-trained model architecture and results**

The system serves as a **proof of concept** for future **OS-level or kernel-level handedness APIs**, enabling developers to adapt app interfaces dynamically based on which hand the user is using.

---

## 🧩 Project Structure

```
handedness-detection/
├── AndroidStudioProjects/
│   ├── IMUData_Handedness/           
│   └── HandednessDetector/           
│
├── Data/                             
│
├── Resources/
│   ├── AppScreenshots/               
│   └── ModelArch/                    
│
├── Training.ipynb                    
├── requirements.txt                  
└── project_structure.txt             
```

---

## 🧱 Components

### 1. **IMUData_Handedness App**

This Android app is used to collect IMU data (Accelerometer + Gyroscope) for three conditions:

- **LEFT-hand usage**
- **RIGHT-hand usage**
- **BOTH-hands usage**

#### **How to Use**

1. Launch the app.  
2. Select one mode — **Left**, **Right**, or **Both**.
3. Tap **Start Recording**.  
   - You can minimize the app — it will continue recording in the background.
   - Use the phone naturally *only with the selected hand* while typing or using the device.
4. To stop recording:
   - Reopen the app and tap **Stop Recording**, **or**
   - Tap **Stop** from the **notification panel**.
5. If you make an error:
   - Tap **See Recordings**, open the **Recordings Screen**, and delete the incorrect file.

Automatic stop triggers:
- Screen off  
- Phone lock  
- No motion detected  

#### **Permissions**
- `ACTIVITY_RECOGNITION`
- `POST_NOTIFICATIONS`

#### **Export Data**
After 8–10 minutes per mode:
1. Tap **Export CSVs**
2. Transfer the ZIP file to PC
3. Extract into:

```
Data/
```

---

### 2. **Model Training**

Set up environment:

```bash
python -m venv venv
source venv/bin/activate
pip install -r requirements.txt
```

Run:

```
Training.ipynb
```

Outputs:
- Validation metrics  
- `.tflite` model for on-device inference  

---

### 3. **Model Architecture**

![Model Architecture](Resources/ModelArch/model%20arch%20mono.jpg)

- Input: `(120, 6)` at 60 Hz  
- Fixed normalization  
- 1×1 calibration conv  
- Temporal 1D-CNN layers  
- Global Average Pooling  
- Dense classifier  
- Output classes: Left / Right / Both  

---

### 4. **HandednessDetector App**

Copy the trained model to:

```
HandednessDetector/app/src/main/assets/
```

Build APK in Android Studio.

#### **Usage**

- Tap text field  
- Keyboard opens  
- Real-time handedness predictions begin  
- Close keyboard → predictions stop  

---

## ⚙️ Technical Summary

- Sensors: Accelerometer, Gyroscope  
- Sample rate: 60 Hz  
- Model: 1D CNN  
- Framework: TensorFlow Lite  
- Data: IMU windows (~8–10 min per mode)  

---

## 🧠 Vision

This project is a step toward **OS-level handedness APIs**, enabling adaptive UI layouts such as:

- Moving UI elements toward active thumb  
- Dynamic keyboard curvature  
- Reachability-aware layouts  
- Reducing false touches  

---

## 🧑‍💻 Workflow Summary

| Step | Description |
|------|-------------|
| **Collect Data** | Use IMUData_Handedness app |
| **Export CSVs** | Extract into `Data/` |
| **Train Model** | Run `Training.ipynb` |
| **Deploy Model** | Move `.tflite` into assets folder |
| **Run Inference** | Build and use HandednessDetector |

---

## 📦 Requirements

- Python ≥ 3.9  
- TensorFlow ≥ 2.13  
- NumPy, Pandas, Scikit‑learn  
- Android Studio (SDK 33+)  

---

## 🙏 Acknowledgements

Thanks to the guiding professor and colleagues who participated in IMU data collection and testing.

---

**Author:** Akshar Thakor  
**Version:** v1.0.0
