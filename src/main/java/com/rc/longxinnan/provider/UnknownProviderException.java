package com.rc.longxinnan.provider;

/**
 * 供应商未在 app.providers 中注册时抛出，入站层映射为 400。
 */
public class UnknownProviderException extends RuntimeException {

    public UnknownProviderException(String provider) {
        super("unknown provider: " + provider);
    }
}
