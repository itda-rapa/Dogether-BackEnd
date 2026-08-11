package itda.media.controller;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

@Controller
public class MediaTestPageController {

    @GetMapping("/media-test")
    public String showMediaTestPage() {
        return "media-test";
    }
}
