# 📄 CrptApi.java

## Описание
CrptApi — это потокобезопасный Java-класс для работы с API Честного знака, включающий встроенный rate limiter (ограничитель запросов).  
При превышении лимита запросы блокируются, пока не освободится «окно» в пределах заданного интервала времени.

Реализация выполнена в одном файле (`CrptApi.java`), все вспомогательные классы находятся внутри него (inner classes).  
Поддерживается Java 11 и выше.

---

## 🔧 Возможности
- Потокобезопасная отправка запросов к API Честного знака
- Блокирующий лимитатор запросов (`requestLimit` за заданный `timeUnit`)
- Простое подключение к тестовой или боевой среде
- Возможность расширения под любые методы API
- Поддержка автоматической сериализации Java-объектов в JSON (если установлен Jackson)
- Возможность передавать JSON-строку напрямую
- Метод для создания документа «Ввод в оборот. Производство РФ» (`LP_INTRODUCE_GOODS`)

---

## 📦 Конструктор
public CrptApi(String baseUrl, String bearerToken, TimeUnit timeUnit, int requestLimit)
- baseUrl — базовый URL API (например, `"https://markirovka.demo.crpt.tech"`)
- bearerToken — Bearer-токен авторизации (или null, если авторизация не нужна)
- timeUnit — единица времени (`TimeUnit.SECONDS`, TimeUnit.MINUTES, …)
- requestLimit — максимальное количество запросов в этом интервале

---

## 🛠 Основные методы

### 1. Создание любого документа
HttpResponse<String> createDocument(String path, Object document, String signature)
- path — относительный путь API (например, `"/api/v3/lk/documents/commissioning/create"`)
- document — объект Java (сериализуется в JSON через Jackson) или готовая JSON-строка
- signature — строка подписи (отправляется в заголовке `Signature`)

Возвращает: HttpResponse<String> с кодом ответа и телом ответа как String.

---

### 2. Создание документа «Ввод в оборот. Производство РФ»
HttpResponse<String> createIntroduceGoods(Object document, String signature)
Обёртка над createDocument(...) с уже заданным путём:
/api/v3/lk/documents/commissioning/create

---

## 🔍 Пример использования
```
import java.net.http.HttpResponse;
import java.util.*;
import java.util.concurrent.TimeUnit;

public class Example {
    public static void main(String[] args) throws Exception {
        CrptApi api = new CrptApi(
            "https://markirovka.demo.crpt.tech", // базовый URL
            "YOUR_BEARER_TOKEN",                // токен авторизации
            TimeUnit.SECONDS,                   // единица времени для лимита
            5                                   // макс. запросов в интервал
        );

        Map<String, Object> document = new HashMap<>();
        document.put("doc_type", "LP_INTRODUCE_GOODS");
        document.put("participant_inn", "1234567890");
        // Заполните остальные поля согласно спецификации API

        String signature = "base64-signature-string";

        HttpResponse<String> response = api.createIntroduceGoods(document, signature);
        System.out.println("Status: " + response.statusCode());
        System.out.println("Body: " + response.body());
    }
}
```
---

## 📌 Замечания
1. Сериализация в JSON  
   - Если библиотека com.fasterxml.jackson.databind.ObjectMapper доступна в classpath, document можно передавать как Java-объект.
   - Если Jackson отсутствует, document должен быть String с готовым JSON.

2. Rate limiting  
   - Запросы, превышающие requestLimit за timeUnit, блокируются до освобождения слота.
   - Поведение одинаковое для многопоточной работы.

3. Путь и заголовки  
   - По умолчанию метод createIntroduceGoods использует путь из документации для «Ввод в оборот. Производство РФ».
   - Заголовок подписи по умолчанию называется Signature — при необходимости измените в коде.

---
