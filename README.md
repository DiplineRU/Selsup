# CrptApi

## 📌 Задание
Необходимо реализовать на языке Java (версии 11+) класс для работы с API Честного знака.  
Класс должен быть:
- потокобезопасным,  
- поддерживать ограничение количества запросов к API,  
- расширяемым.  

Требования:
- В конструкторе задаётся количество запросов и интервал времени:
  ```java
  public CrptApi(TimeUnit timeUnit, int requestLimit)
  ```
- При превышении лимита запрос **блокируется**, пока не освободится «окно».
- Нужно реализовать метод для **создания документа ввода в оборот** товара, произведённого в РФ.  
- Документ передаётся в виде Java-объекта, подпись — строкой.  
- Все вспомогательные классы должны быть внутренними.  
- Реализация в одном файле `CrptApi.java`.

---

## 🛠 Реализация
Файл `CrptApi.java` содержит:
- `CrptApi` — основной класс клиента.
- `RateLimiter` — внутренний потокобезопасный ограничитель скорости (Sliding Window).
- `Serializer` — внутренний сериализатор JSON:
  - использует Jackson, если доступен в classpath;
  - если нет — принимает готовую JSON-строку.

### Конструкторы
```java
CrptApi(String baseUrl, String bearerToken, TimeUnit timeUnit, int requestLimit)
CrptApi(String baseUrl, TimeUnit timeUnit, int requestLimit) // без токена
```

### Методы
```java
// Универсальный метод
HttpResponse<String> createDocument(String path, Object document, String signature)

// Упрощённый метод для "Ввод в оборот"
HttpResponse<String> createIntroduceGoods(Object document, String signature)
```

- `path` — путь API (например, `/api/v3/lk/documents/create`).
- `document` — Java Map/POJO или готовая JSON-строка.
- `signature` — строка подписи.

---

## 🚀 Пример использования

```java
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
```

👉 В примере используется `https://httpbin.org/post` как тестовый сервис.  
Он возвращает 200 и печатает JSON с отправленными данными.

---

## 📦 Сборка и запуск

### Maven
В `pom.xml` должна быть зависимость:
```xml
<dependency>
    <groupId>com.fasterxml.jackson.core</groupId>
    <artifactId>jackson-databind</artifactId>
    <version>2.17.2</version>
</dependency>
```

Запуск:
```bash
mvn clean package
java -cp target/SelsUp-0.0.1-SNAPSHOT.jar com.example.SelsUp.CrptApi
```

### IntelliJ IDEA
- Открой проект как Maven.  
- Запусти `CrptApi.main`.  

---

## ⚠️ Примечания по API Честного знака
- Для реальной работы нужно заменить:
  ```java
  new CrptApi("https://ismp.crpt.ru", "REAL_BEARER_TOKEN", TimeUnit.SECONDS, 5);
  ```
- `document` должен содержать все обязательные поля по спецификации (`doc_id`, `description`, `products`, `owner_inn`, `producer_inn` и т. д.).
- Метод по умолчанию: `/api/v3/lk/documents/create`.
- Без валидного токена `Bearer` сервер вернёт `401 Unauthorized`.

---

## ✅ Итог
- Реализация соответствует заданию: потокобезопасность, rate-limit, единый файл.  
- Код протестирован с `httpbin.org` и возвращает правильное тело запроса.  
- Готов для расширения и подключения к боевому API Честного знака.
