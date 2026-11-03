package com.hayden.sbterminal;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.zip.GZIPInputStream;

/** Small HTTP GET with gzip (the full Bazaar response is several MB uncompressed). */
final class Http {
    private static final HttpClient CLIENT = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();

    private Http() {}

    static String get(String url) throws Exception {
        HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(20))
                .header("User-Agent", "SkyBlockTerminal/0.1")
                .header("Accept-Encoding", "gzip")
                .GET().build();
        HttpResponse<InputStream> res = CLIENT.send(req, HttpResponse.BodyHandlers.ofInputStream());
        if (res.statusCode() != 200) throw new IllegalStateException("HTTP " + res.statusCode());
        InputStream in = res.body();
        if (res.headers().firstValue("Content-Encoding").orElse("").contains("gzip")) in = new GZIPInputStream(in);
        try (InputStream s = in; ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            s.transferTo(out);
            return out.toString(StandardCharsets.UTF_8);
        }
    }
}
