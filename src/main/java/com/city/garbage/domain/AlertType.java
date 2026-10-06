package com.city.garbage.domain;

/** 告警类型：满溢未到场、异味、错投、夜间高峰、断网、投诉、清运后未清洁、积分未到账、摄像头误判 */
public enum AlertType {
    KITCHEN_FULL_NO_TRUCK("厨余桶满溢且清运车未到"),
    OTHER_BIN_FULL("其他垃圾桶满溢"),
    ODOR("异味超标"),
    MISSORT("居民错投"),
    NIGHT_PEAK("夜间投放高峰"),
    DEVICE_OFFLINE("设备断网"),
    ODOR_COMPLAINT("异味投诉"),
    NOT_CLEANED_AFTER_COLLECTION("清运后未清洁"),
    POINTS_UNSETTLED("积分未到账"),
    CAMERA_MISJUDGE("摄像头误判申诉"),
    CAMERA_CALIBRATION("摄像头误判率升高待校准");

    private final String label;
    AlertType(String label) { this.label = label; }
    public String getLabel() { return label; }
}
