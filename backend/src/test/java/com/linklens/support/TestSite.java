package com.linklens.support;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;

/** A tiny "third-party website" on 127.0.0.1 whose pages tests can change on the fly. */
public final class TestSite {

    private record Page(int status, String body, String location) {
    }

    private static TestSite instance;

    private final HttpServer server;
    private final Map<String, Page> pages = new ConcurrentHashMap<>();

    private TestSite() {
        try {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        server.setExecutor(Executors.newCachedThreadPool());
        server.createContext("/", ex -> {
            Page page = pages.getOrDefault(ex.getRequestURI().getPath(), new Page(404, "<title>Not Found</title>", null));
            if (page.location() != null) {
                ex.getResponseHeaders().set("Location", page.location());
            }
            byte[] body = page.body() == null ? new byte[0] : page.body().getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().set("Content-Type", "text/html; charset=utf-8");
            ex.sendResponseHeaders(page.status(), body.length == 0 ? -1 : body.length);
            if (body.length > 0) {
                try (OutputStream out = ex.getResponseBody()) {
                    out.write(body);
                }
            }
            ex.close();
        });
        server.start();
    }

    public static synchronized TestSite get() {
        if (instance == null) {
            instance = new TestSite();
        }
        return instance;
    }

    public String url(String path) {
        return "http://127.0.0.1:" + server.getAddress().getPort() + path;
    }

    public void page(String path, int status, String title) {
        pages.put(path, new Page(status, title == null ? "" : "<html><head><title>" + title + "</title></head></html>", null));
    }

    public void redirect(String path, int status, String location) {
        pages.put(path, new Page(status, null, location));
    }
}
