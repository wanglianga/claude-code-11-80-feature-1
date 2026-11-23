package com.flood.statemigrate.domain;

/**
 * 降雨事件状态：
 * CONFIRMED —— 边界不受缺测影响，可定场；
 * CANDIDATE —— 缺站导致边界不可判，保留候选区间，不强行定场；
 * SUPERSEDED —— 被新版本取代（站点增补/设备更换/权重变动后重算）。
 */
public enum RainEventStatus {
    CONFIRMED,
    CANDIDATE,
    SUPERSEDED
}
