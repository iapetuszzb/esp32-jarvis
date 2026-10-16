#pragma once
// From the user's vendor CameraWebServer example: CAMERA_MODEL_ESP32S3_EYE.
// This is NOT the AI Thinker pin layout. OV5640 uses the same board connector.
constexpr int CAM_XCLK = 15, CAM_SDA = 4, CAM_SCL = 5;
constexpr int CAM_D0 = 11, CAM_D1 = 9, CAM_D2 = 8, CAM_D3 = 10;
constexpr int CAM_D4 = 12, CAM_D5 = 18, CAM_D6 = 17, CAM_D7 = 16;
constexpr int CAM_VSYNC = 6, CAM_HREF = 7, CAM_PCLK = 13;
