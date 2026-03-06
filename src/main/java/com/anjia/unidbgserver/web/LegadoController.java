package com.anjia.unidbgserver.web;

import org.springframework.core.io.ClassPathResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.util.StreamUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import javax.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * Legado 书源导出接口
 */
@RestController
public class LegadoController {

    @GetMapping(value = "/api/legado/fqnovel-batch-booksource", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<byte[]> getBatchBookSource(HttpServletRequest request) throws IOException {
        String template = StreamUtils.copyToString(
            new ClassPathResource("static/legado/fqnovel-batch-booksource.json").getInputStream(),
            StandardCharsets.UTF_8
        );
        String baseUrl = request.getScheme() + "://" + request.getServerName() + ":" + request.getServerPort();
        byte[] body = template.replace("http://127.0.0.1:9999", baseUrl).getBytes(StandardCharsets.UTF_8);
        return ResponseEntity.ok()
            .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE + ";charset=UTF-8")
            .body(body);
    }
}
