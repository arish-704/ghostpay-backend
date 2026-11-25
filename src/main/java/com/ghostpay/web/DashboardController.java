package com.ghostpay.web;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * Serves the interactive simulation dashboard at the root URL.
 */
@Controller
public class DashboardController {

    @GetMapping("/")
    public String index() {
        return "forward:/index.html";
    }
}
