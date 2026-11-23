package com.flood.statemigrate.domain;

/**
 * 降雨事件边界方案：
 * FIXED_GAP —— 固定无雨间隔合并（规则透明；土壤已饱和时可能把对产流重要的间歇误判为新场）；
 * ADAPTIVE_WETNESS —— 按前期湿润状态与流域响应时间估计事件边界（更贴近产流，需校准状态输入）。
 */
public enum RainScheme {
    FIXED_GAP,
    ADAPTIVE_WETNESS
}
