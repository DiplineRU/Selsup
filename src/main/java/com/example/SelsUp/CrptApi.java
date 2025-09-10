package com.example.SelsUp;

import java.io.IOException;
import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.locks.*;

// Реализация в одном файле
/**
 * Потокобезопасный клиент для CRPT API с ограничителем скорости.
 *
 * Конструктор:
 * public CrptApi(String baseUrl, String bearerToken, TimeUnit timeUnit, int requestLimit)
 *
 * Методы:
 * HttpResponse<String> createDocument(String path, Object document, String signature)
 * HttpResponse<String> createIntroduceGoods(Object document, String signature)
 */

public class CrptApi {

    private final HttpClient http;
    private final String baseUrl;
    private final String bearerToken; // может быть null
    private final RateLimiter rateLimiter;
    private final Serializer serializer;

    // Путь для "Ввод в оборот. Производство РФ"
    private static final String DEFAULT_INTRODUCE_GOODS_PATH = "/api/v3/lk/documents/create";

    /**
     * Создание клиента
     *
     * @param baseUrl      базовый URL, например "https://ismp.crpt.ru"
     * @param bearerToken  токен авторизации (может быть null)
     * @param timeUnit     единица времени для лимита
     * @param requestLimit максимум запросов в интервал времени (>0)
     */
    public CrptApi(String baseUrl, String bearerToken, TimeUnit timeUnit, int requestLimit) {
        Objects.requireNonNull(baseUrl, "baseUrl не должен быть null");
        if (requestLimit <= 0) throw new IllegalArgumentException("requestLimit должен быть > 0");

        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        this.bearerToken = bearerToken;
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(20))
                .version(HttpClient.Version.HTTP_2)
                .build();
        this.rateLimiter = new RateLimiter(timeUnit, requestLimit);
        this.serializer = Serializer.createDefault();
    }

    public CrptApi(String baseUrl, TimeUnit timeUnit, int requestLimit) {
        this(baseUrl, null, timeUnit, requestLimit);
    }

    /**
     * Создание документа (универсальный метод).
     */
    public HttpResponse<String> createDocument(String path, Object document, String signature)
            throws IOException, InterruptedException {
        if (path == null || !path.startsWith("/")) throw new IllegalArgumentException("path должен начинаться с '/'");
        Objects.requireNonNull(document, "document не должен быть null");
        Objects.requireNonNull(signature, "signature не должен быть null");

        // Лимитер
        rateLimiter.acquire();

        // Формируем JSON тело вида { "document": {...}, "signature": "..." }
        Map<String, Object> body = new HashMap<>();
        body.put("document", document);
        body.put("signature", signature);
        final String jsonBody = serializer.serialize(body);

        HttpRequest.Builder reqb = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + path))
                .timeout(Duration.ofSeconds(60))
                .header("Accept", "*/*")
                .header("Content-Type", "application/json;charset=UTF-8")
                .POST(HttpRequest.BodyPublishers.ofString(jsonBody));

        if (bearerToken != null && !bearerToken.isEmpty()) {
            reqb.header("Authorization", "Bearer " + bearerToken);
        }

        HttpRequest req = reqb.build();
        return http.send(req, HttpResponse.BodyHandlers.ofString());
    }

    /**
     * Упрощённый метод для "Ввод в оборот"
     */
    public HttpResponse<String> createIntroduceGoods(Object document, String signature)
            throws IOException, InterruptedException {
        return createDocument(DEFAULT_INTRODUCE_GOODS_PATH, document, signature);
    }

    // -----------------------
    // Ограничитель скорости
    // -----------------------
    private static class RateLimiter {
        private final long windowMillis;
        private final int limit;

        private final Deque<Long> timestamps = new ArrayDeque<>();
        private final Lock lock = new ReentrantLock();
        private final Condition notFull = lock.newCondition();

        RateLimiter(TimeUnit timeUnit, int limit) {
            this.limit = limit;
            this.windowMillis = timeUnit.toMillis(1);
            if (this.windowMillis <= 0) throw new IllegalArgumentException("timeUnit слишком мал");
        }

        void acquire() throws InterruptedException {
            lock.lock();
            try {
                while (true) {
                    long now = System.currentTimeMillis();
                    prune(now);

                    if (timestamps.size() < limit) {
                        timestamps.addLast(now);
                        return;
                    } else {
                        long oldest = timestamps.peekFirst();
                        long waitMillis = (oldest + windowMillis) - now;
                        if (waitMillis <= 0) {
                            prune(System.currentTimeMillis());
                            continue;
                        }
                        notFull.await(waitMillis, TimeUnit.MILLISECONDS);
                    }
                }
            } finally {
                lock.unlock();
            }
        }

        private void prune(long now) {
            long boundary = now - windowMillis;
            while (!timestamps.isEmpty() && timestamps.peekFirst() < boundary) {
                timestamps.removeFirst();
            }
            if (timestamps.size() < limit) {
                notFull.signalAll();
            }
        }
    }

    // -----------------------
    // Сериализация
    // -----------------------
    private static class Serializer {
        private final Object mapper;
        private final java.lang.reflect.Method writeValueAsString;

        private Serializer(Object mapper, java.lang.reflect.Method writeValueAsString) {
            this.mapper = mapper;
            this.writeValueAsString = writeValueAsString;
        }

        static Serializer createDefault() {
            try {
                Class<?> objectMapperClass = Class.forName("com.fasterxml.jackson.databind.ObjectMapper");
                Object mapper = objectMapperClass.getDeclaredConstructor().newInstance();
                java.lang.reflect.Method m = objectMapperClass.getMethod("writeValueAsString", Object.class);
                return new Serializer(mapper, m);
            } catch (Exception ex) {
                return new Serializer(null, null);
            }
        }

        String serialize(Object obj) {
            if (obj instanceof String) {
                return (String) obj;
            }
            if (mapper != null && writeValueAsString != null) {
                try {
                    return (String) writeValueAsString.invoke(mapper, obj);
                } catch (Exception e) {
                    throw new RuntimeException("Ошибка сериализации через Jackson", e);
                }
            }
            throw new IllegalStateException("Нет JSON-сериализатора. Добавьте Jackson или передайте JSON строкой.");
        }
    }

    // -----------------------
    // Пример использования
    // -----------------------
//
    public static void main(String[] args) throws Exception {
        CrptApi api = new CrptApi("https://httpbin.org", null, TimeUnit.SECONDS, 5);

        Map<String, Object> document = new HashMap<>();
        document.put("doc_type", "LP_INTRODUCE_GOODS");
        document.put("participant_inn", "1234567890");

        String signature = "base64-signature-string";

        HttpResponse<String> resp = api.createDocument("/post", document, signature);
        System.out.println(resp.statusCode());
        System.out.println(resp.body());
    }

//
}
