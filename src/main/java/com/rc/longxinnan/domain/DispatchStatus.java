package com.rc.longxinnan.domain;

/**
 * 通知记录的投递状态。
 */
public enum DispatchStatus {

    /** 待投递（等待同步尝试或轮询器认领重试）。 */
    PENDING,

    /** 已成功送达供应商（收到 2xx）。 */
    SUCCESS,

    /** 死信：超过最大投递次数仍未成功，等待人工排查。 */
    FAILED
}
