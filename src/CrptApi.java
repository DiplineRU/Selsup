// CrptApi.java
// Реализация в одном файле
// Использование: компиляция с помощью javac CrptApi.java

import java.io.IOException;
import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.locks.*;

/**
 * Потокобезопасный клиент для CRPT API с ограничителем скорости блокировки.
 *
 * Главный конструктор:
 * общедоступный CrptApi(String baseUrl, String bearerToken, TimeUnit TimeUnit, int requestLimit)
 *
 * Методы:
 * HttpResponse<String> createDocument(String path, Object document, String signature)
 * - path: относительный путь, например "/api/v3/lk/documents/commissioning/create"
 * - документ: объект Java (будет сериализован с помощью Jackson, если присутствует) ИЛИ строка (уже в формате JSON)
 * - подпись: строка подписи (будет отправлена в качестве заголовка "Подпись")
 *
 * Удобство:
 * HttpResponse<String> createIntroduceGoods(Object document, String signature)
 * - отправляет сообщения по пути по умолчанию для "LP_INTRODUCE_GOODS" (выпущено в обращение, производство в Российской Федерации)
 *
 * Ограничение скорости:
 * Блокирует вызывающие потоки до тех пор, пока запрос не будет выполнен без превышения
 установленного лимита (requestLimit на единицу времени).
 *
 * Соответствующий раздел документа: "Ввод в обращение" (LP_INTRODUCE_GOODS). Смотрите прилагаемую документацию.  [oai_citation:1!=API documentation.pdf](file-service://file-J25MS4hGFXLaMvfs8x6F87)
 */
public class CrptApi {

    private final HttpClient http;
    private final String baseUrl;
    private final String bearerToken; // может быть нулевым
    private final RateLimiter rateLimiter;
    private final Serializer serializer;

    // Путь по умолчанию для "Ввод в оборот. Производство РФ" (может быть переопределен)
    private static final String DEFAULT_INTRODUCE_GOODS_PATH = "/api/v3/lk/documents/commissioning/create";

    /**
     *  Создание клиента
     *
     * @param baseUrl Базовый URL, e.g. "https://ismp.crpt.ru"
     * @param bearerToken Токен авторизации (может быть нулевым)
     * @param timeUnit Единица измерения времени для ограничения скорости (например, TimeUnit.SECONDS)
     * @param requestLimit максимальное количество запросов за единицу времени (позитив)
     */
    public CrptApi(String baseUrl, String bearerToken, TimeUnit timeUnit, int requestLimit) {
        Objects.requireNonNull(baseUrl, "Значение baseUrl не должно быть null");
        if (requestLimit <= 0) throw new IllegalArgumentException("предел запроса должен быть > 0");
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length()-1) : baseUrl;
        this.bearerToken = bearerToken;
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(20))
                .version(HttpClient.Version.HTTP_2)
                .build();
        this.rateLimiter = new RateLimiter(timeUnit, requestLimit);
        this.serializer = Serializer.createDefault();
    }

    /**
     * Удобный конструктор без токена (можно указать null).
     */
    public CrptApi(String baseUrl, TimeUnit timeUnit, int requestLimit) {
        this(baseUrl, null, timeUnit, requestLimit);
    }

    /**
     * Создание документа по заданному пути Блокирует, если превышен лимит скорости, до тех пор, пока не освободится место.
     *
     * @param path Путь (должен начинаться с '/'), например "/api/v3/..."
     * @param document документирует Java-объект, который должен быть преобразован в JSON или строку с помощью JSON
     * @param signature строка подписи (будет отправлена как заголовок "Подпись")
     * @return HttpResponse<String> текст ответа в виде строки
     * @throws IOException выдает исключение при ошибках сети/сериализации
     * @throws InterruptedException выдает исключение если поток прерывается во время ожидания ограничителя скорости или HTTP
     */
    public HttpResponse<String> createDocument(String path, Object document, String signature)
            throws IOException, InterruptedException {
        if (path == null || !path.startsWith("/")) throw new IllegalArgumentException("путь должен быть ненулевым и начинаться с '/'");
        Objects.requireNonNull(document, "документ не должен быть пустым");

        // Block until allowed by rate limiter
        rateLimiter.acquire();

        // Build JSON body
        final String jsonBody = serializer.serialize(document);

        HttpRequest.Builder reqb = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + path))
                .timeout(Duration.ofSeconds(60))
                .header("Принять", "*/*")
                .header("Тип содержимого", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(jsonBody));

        if (bearerToken != null && !bearerToken.isEmpty()) {
            reqb.header("Авторизация", "Носитель " + bearerToken);
        }
        if (signature != null && !signature.isEmpty()) {
            // имя заголовка здесь, в задаче, строго не указано — используйте "Подпись"
            reqb.header("Подпись", signature);
        }

        HttpRequest req = reqb.build();
        return http.send(req, HttpResponse.BodyHandlers.ofString());
    }

    /**
     * Удобный способ создания документа "Ввод в оборот"
     * Использует DEFAULT_INTRODUCE_GOODS_PATH — при необходимости изменяет его в соответствии с вашими условиями
     *
     */
    public HttpResponse<String> createIntroduceGoods(Object document, String signature)
            throws IOException, InterruptedException {
        return createDocument(DEFAULT_INTRODUCE_GOODS_PATH, document, signature);
    }

    // -----------------------
    // Внутренний ограничитель скорости
    // -----------------------
    /**
     * Простой блокирующий ограничитель скорости, основанный на скользящем окне временных меток.
     * Потокобезопасный.
     */

    private static class RateLimiter {
        private final long windowMillis;
        private final int limit;

        // временные метки последних запросов (в миллисекундах), отсортированные по возрастанию
        private final Deque<Long> timestamps = new ArrayDeque<>();
        private final Lock lock = new ReentrantLock();
        private final Condition notFull = lock.newCondition();

        RateLimiter(TimeUnit timeUnit, int limit) {
            this.limit = limit;
            this.windowMillis = timeUnit.toMillis(1);
            if (this.windowMillis <= 0) throw new IllegalArgumentException("Временная единица слишком мала");
        }

        /**
         * Получает разрешение на выполнение одного запроса. Блокирует до получения разрешения.
         */
        void acquire() throws InterruptedException {
            lock.lock();
            try {
                while (true) {
                    long now = System.currentTimeMillis();
                    // удаление устаревших временных меток
                    prune(now);

                    if (timestamps.size() < limit) {
                        // разрешить, записать временную метку и вернуть
                        timestamps.addLast(now);
                        return;
                    } else {
                        // Нужно подождать, пока не появятся старые + windowMillis - сейчас
                        long oldest = timestamps.peekFirst();
                        long waitMillis = (oldest + windowMillis) - now;
                        if (waitMillis <= 0) {
                            // он стал доступен в нулевое время (обычно этого не должно происходить из-за обрезки), повторяет цикл
                            prune(System.currentTimeMillis());
                            continue;
                        }
                        // ожидание по таймауту; если происходит ложное пробуждение, мы проводим повторную оценку
                        notFull.await(waitMillis, TimeUnit.MILLISECONDS);
                    }
                }
            } finally {
                lock.unlock();
            }
        }

        /**
         * Удаляет временные метки, более старые, чем window.
         */
        private void prune(long now) {
            long boundary = now - windowMillis;
            while (!timestamps.isEmpty()) {
                Long t = timestamps.peekFirst();
                if (t < boundary) {
                    timestamps.removeFirst();
                } else {
                    break;
                }
            }
            // if slots freed, notify waiting threads
            if (timestamps.size() < limit) {
                notFull.signalAll();
            }
        }
    }

    // -----------------------
    // Внутренний: сериализатор
    // -----------------------
    /**
     * Сериализатор пытается использовать Jackson, если он присутствует. В противном случае требуется, чтобы входные данные были строкой.
     */
    private static class Serializer {
        private final Object mapper; // com.fasterxml.jackson.databind.ObjectMapper, если присутствует
        private final java.lang.reflect.Method writeValueAsString; // ссылка на метод при наличии картографа

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
                // Джексон недоступен — резервный вариант для нулевого сопоставления
                return new Serializer(null, null);
            }
        }

        String serialize(Object obj) {
            if (obj instanceof String) {
                // assume already JSON
                return (String) obj;
            }
            if (mapper != null && writeValueAsString != null) {
                try {
                    return (String) writeValueAsString.invoke(mapper, obj);
                } catch (Exception e) {
                    throw new RuntimeException("Не удалось сериализовать объект с помощью Jackson", e);
                }
            }
            // Нет Джексона и строки -> не удается надежно сериализовать
            throw new IllegalStateException("Нет доступного JSON-сериализатора. Добавить Jackson (com.fasterxml.jackson.databind.ObjectMapper) указать путь к классу или передать документ в виде JSON String.");
        }
    }

    // -----------------------
    // Пример использования
    // -----------------------
//
//    public static void main(String[] args) throws Exception {
//        // Example (assuming Jackson is on classpath):
//        CrptApi api = new CrptApi("https://markirovka.demo.crpt.tech", "YOUR_BEARER_TOKEN", TimeUnit.SECONDS, 5);
//        Map<String,Object> document = new HashMap<>();
//        document.put("doc_type", "LP_INTRODUCE_GOODS");
//        document.put("participant_inn", "1234567890");
//        // ... fill other fields according to API spec (see attached documentation) ...
//        String signature = "base64-signature-string";
//        HttpResponse<String> resp = api.createIntroduceGoods(document, signature);
//        System.out.println(resp.statusCode());
//        System.out.println(resp.body());
//    }
//

}

