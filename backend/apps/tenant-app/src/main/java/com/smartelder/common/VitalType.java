package com.smartelder.common;

import java.math.BigDecimal;

/**
 * 体征类型与异常阈值判定（需求 HC-09 阈值可配置，一期内置默认阈值）。
 */
public enum VitalType {

    BLOOD_PRESSURE("血压", "mmHg") {
        @Override
        public boolean isAbnormal(BigDecimal systolic, BigDecimal diastolic) {
            if (systolic == null) {
                return false;
            }
            boolean high = systolic.compareTo(BigDecimal.valueOf(140)) >= 0
                    || (diastolic != null && diastolic.compareTo(BigDecimal.valueOf(90)) >= 0);
            boolean low = systolic.compareTo(BigDecimal.valueOf(90)) < 0;
            return high || low;
        }
    },
    HEART_RATE("心率", "次/分") {
        @Override
        public boolean isAbnormal(BigDecimal hr, BigDecimal ignored) {
            return hr != null && (hr.compareTo(BigDecimal.valueOf(100)) > 0 || hr.compareTo(BigDecimal.valueOf(50)) < 0);
        }
    },
    BLOOD_SUGAR("血糖", "mmol/L") {
        @Override
        public boolean isAbnormal(BigDecimal glucose, BigDecimal ignored) {
            return glucose != null && (glucose.compareTo(BigDecimal.valueOf(7.0)) > 0
                    || glucose.compareTo(BigDecimal.valueOf(3.9)) < 0);
        }
    },
    SPO2("血氧", "%") {
        @Override
        public boolean isAbnormal(BigDecimal spo2, BigDecimal ignored) {
            return spo2 != null && spo2.compareTo(BigDecimal.valueOf(93)) < 0;
        }
    },
    TEMPERATURE("体温", "℃") {
        @Override
        public boolean isAbnormal(BigDecimal t, BigDecimal ignored) {
            return t != null && (t.compareTo(BigDecimal.valueOf(37.5)) > 0 || t.compareTo(BigDecimal.valueOf(35.0)) < 0);
        }
    };

    private final String label;
    private final String unit;

    VitalType(String label, String unit) {
        this.label = label;
        this.unit = unit;
    }

    public String label() {
        return label;
    }

    public String unit() {
        return unit;
    }

    public abstract boolean isAbnormal(BigDecimal value1, BigDecimal value2);

    public static VitalType parse(String type) {
        try {
            return valueOf(type);
        } catch (IllegalArgumentException e) {
            throw new BizException("不支持的体征类型：" + type);
        }
    }
}
