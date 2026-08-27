package com.rc.longxinnan.domain;

/**
 * 供应商的投递模式（按供应商配置）。
 */
public enum DispatchMode {

    /** 入站时先在请求线程内立即尝试投递，失败/超时则回退为轮询兜底。适用于低量、高实时业务。 */
    SYNC,

    /** 仅落库即返回，由轮询器批量投递。适用于高量、低实时业务。 */
    ASYNC
}
