
# 📬 Notification Service – Backend Homework

This is a technical assignment for backend engineer candidates. You are expected to build a RESTful notification service using **Spring Boot**, integrating **MySQL**, **Redis**, and **RocketMQ**.

---

## 🎯 Objective

Design and implement a notification API system that allows users to send either **email** or **SMS** notifications. The system must:

- Persist notifications in a **MySQL database**
- Push messages to **RocketMQ**
- Cache and retrieve recent notifications using **Redis**

---

## 🧰 Tech Requirements

You **must use** the following technologies:

- **Java 21+**
- **Spring Boot**
- **MySQL** (for persistence)
- **Redis** (for caching)
- **RocketMQ** (for event messaging)

You may use starter dependencies such as:
- Spring Web
- Spring Data JPA
- Spring Cache
- RocketMQ Spring Boot Starter

---

## 🔧 Features to Implement

### 1️⃣ Create Notification

**Endpoint**  
`POST /notifications`

**Request JSON**
```json
{
  "type": "email",              // or "sms"
  "recipient": "user@example.com",
  "subject": "Welcome!",
  "content": "Thanks for signing up!"
}
```
Behavior:  
- Save the notification to MySQL  
- Send the message to RocketMQ topic notification-topic  
- Add it to Redis cache of the 10 most recent notifications  

⸻

### 2️⃣ Get Notification by ID

**Endpoint**  
`GET /notifications/{id}`

Behavior:
- Retrieve the notification from Redis if available
- Otherwise fetch it from MySQL and update the Redis cache

⸻

### 3️⃣ Get Recent Notifications

**Endpoint**  
`GET /notifications/recent`

Response Example
```json
[
  {
    "id": 123,
    "type": "email",
    "recipient": "user@example.com",
    "subject": "Welcome!",
    "createdAt": "2025-07-15T12:01:02Z"
  }
]
```

⸻
### 4️⃣ Update Notification

**Endpoint**  
`PUT /notifications/{id}`

**Request Payload**
```json
{
  "subject": "Updated subject line",
  "content": "Updated content of the notification"
}
```

Behavior:
- Update the subject and content fields of an existing notification in MySQL
- If the notification exists in Redis, either:
  - Update the cached value, or
  - Invalidate the cache entry
- If the notification does not exist (in DB), return 404 Not Found  


⸻

### 5️⃣ Delete Notification

**Endpoint**
`DELETE /notifications/{id}`

Behavior:
- Delete the notification with the specified ID from MySQL
- Remove it from Redis cache if present
- Return 204 No Content on success
- If the notification does not exist, return 404 Not Found


⸻

🧪 Bonus (Optional)  
- Use Spring Cache abstraction or RedisTemplate encapsulation
- Take race conditions into account
- Apply proper error handling with meaningful status codes
- Define your own DTO and message format for RocketMQ
- Use consistent and modular code structure (controller, service, repository, config, etc.)
- Test case coverage: as much as possible

⸻

🐳 Environment Setup

Use the provided docker-compose.yaml file to start required services:

Service	Port  
MySQL	3306  
Redis	6379  
RocketMQ Namesrv	9876  
RocketMQ Broker	10911  
RocketMQ Console	8088  

To start the services:

```commandline
docker-compose up -d
```

MySQL credentials:
- User: taskuser
- Password: taskpass
- Database: taskdb

You may edit init.sql to create required tables automatically.

⸻

🚀 Getting Started

To run the application:

./mvn spring-boot:run

Make sure to update your application.yml with the proper connections for:
- spring.datasource.url
- spring.redis.host
- rocketmq.name-server

⸻

📤 Submission

Please submit a `public Github repository` that includes:
- ✅ Complete and executable source code
- ✅ README.md (this file)
- ✅ Any necessary setup or data scripts please add them in HELP.md
- ✅ Optional: Postman collection or curl samples  

⸻

📌 Notes
- Focus on API correctness, basic error handling, and proper use of each technology
- You may use tools like Vibe Coding / ChatGPT to assist, but please write and understand your own code
- The expected time to complete is around 3 hours

Good luck!

---
