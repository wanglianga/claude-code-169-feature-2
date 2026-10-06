package com.city.garbage.domain;

/** 摄像头校准任务状态 */
public enum CameraCalibrationStatus {
    NORMAL,        // 识别正常，错投处罚自动生效
    CALIBRATING,   // 误判率升高，已进入校准任务，期间错投处罚暂停自动生效
    CALIBRATED     // 校准完成，恢复正常执法
}
