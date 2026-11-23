package com.flood.statemigrate.domain;

/**
 * 采样质量：MEASURED 为实测（含实测 0mm）；MISSING 为缺测。
 * 缺测界限器只对 MISSING 给上下界，绝不用插值点伪装实测。
 */
public enum SampleQuality {
    MEASURED,
    MISSING
}
