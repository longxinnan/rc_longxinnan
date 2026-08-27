package com.rc.longxinnan.template;

/**
 * 模板渲染失败（JSON 序列化异常等），非受检异常。
 */
public class TemplateRenderingException extends RuntimeException {

    public TemplateRenderingException(String message, Throwable cause) {
        super(message, cause);
    }
}
