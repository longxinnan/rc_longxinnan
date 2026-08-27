package com.rc.longxinnan.controller;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 示例 REST 接口，用于验证工程可正常启动。
 */
@RestController
public class HelloController {

    @GetMapping("/")
    public String hello() {
        return "Hello from rc_longxinnan Spring Boot!";
    }

}
