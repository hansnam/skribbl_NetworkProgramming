# MODULE SERVER — SKRIBBL MULTIPLAYER

> 📘 **Phụ trách chính:** Thành viên 1 (Lõi Mạng TCP, Đa luồng Java 21 Virtual Threads & Hệ thống Đa phòng)

---

## 🎯 MỤC TIÊU & KIẾN TRÚC LÕI

Module **Server** đóng vai trò là trung tâm điều phối có thẩm quyền (*Server-Authoritative*) cho toàn bộ trò chơi Skribbl:
- Tiếp nhận và duy trì đồng thời hàng chục kết nối TCP Socket Client với độ trễ thấp (`TCP_NODELAY = true`).
- Ứng dụng **Java 21 Virtual Threads** (`Thread.ofVirtual()`): Mỗi kết nối Client được phục vụ đọc dữ liệu bất đồng bộ trên một virtual thread riêng biệt mà không lo cạn kiệt tài nguyên hệ thống.
- **Thread-safe Socket Writer:** Đồng bộ hóa quá trình gửi bản tin bằng `synchronized(writeLock)` kèm `writer.newLine(); writer.flush();` để ngăn chặn trộn lẫn byte giữa luồng game và luồng vẽ.
- **Hệ thống Đa phòng (`RoomManager` + `GameRoom`):** Quản lý đồng thời nhiều phòng chơi độc lập trên RAM bằng `ConcurrentHashMap`. Hỗ trợ sinh mã phòng 5 ký tự ngẫu nhiên, cơ chế kiểm tra trùng Nickname trong cùng phòng, và tự động chuyển giao quyền Chủ phòng (Host) khi Host ngắt kết nối.
- **Bộ định tuyến gói tin (`ServerPacketRouter`):** Giải mã bản tin NDJSON và phân phối xử lý theo `MessageType`.

---

## ⚙️ CÔNG NGHỆ SỬ DỤNG

| Thành phần | Công nghệ / Thư viện | Vai trò |
| :--- | :--- | :--- |
| **Ngôn ngữ** | **Java 21 LTS** | Virtual Threads, Records, Pattern Matching |
| **Giao tiếp mạng** | **TCP Socket (`java.net.ServerSocket`, `Socket`)** | Giao tiếp 2 chiều song công, framing NDJSON UTF-8 |
| **Định dạng dữ liệu** | **NDJSON + Jackson Databind 2.17.2** | Mã hóa và giải mã bản tin 1 dòng duy nhất |
| **Kiểm thử** | **JUnit 5.10.3** | Unit test và Integration test mạng TCP thời gian thực |
| **Đóng gói** | **Maven Shade Plugin 3.6.0** | Xuất file thực thi độc lập `skribbl-server.jar` |

---

## 🚀 HƯỚNG DẪN BUILD & CHẠY

### 1. Build & Test qua Maven
Từ thư mục gốc dự án:
```bash
cd source
mvn clean test
```

### 2. Đóng gói Fat-JAR
```bash
cd source
mvn clean package -DskipTests
```
File thực thi sẽ được tạo tại: `source/server/target/skribbl-server.jar`.

### 3. Khởi động Server
Chạy với cổng mặc định (`8888`):
```bash
java -jar source/server/target/skribbl-server.jar
```
Hoặc chỉ định cổng tùy chọn (ví dụ: `9090`):
```bash
java -jar source/server/target/skribbl-server.jar 9090
```

---

## 📦 CẤU TRÚC GÓI MÃ NGUỒN PHẦN SERVER

```text
source/server/src/main/java/com/skribbl/server/
├── ServerMain.java                  # Điểm khởi chạy Server & Shutdown Hook
├── net/
│   ├── TcpServer.java               # ServerSocket Acceptor & Virtual Threads
│   └── ClientSession.java           # Quản lý Socket I/O, UTF-8, Thread-safe write
├── room/
│   ├── RoomManager.java             # Quản lý đa phòng song song, sinh mã 5 ký tự
│   └── GameRoom.java                # Quản lý thành viên phòng, Host election, broadcast
└── handler/
    └── ServerPacketRouter.java      # Định tuyến các gói tin theo MessageType
```

---

## 🧪 KIỂM THỬ TÍCH HỢP & CHỊU LỖI (FAULT TOLERANCE)

- `RoomManagerTest`: Kiểm tra tạo phòng, vào phòng, chặn trùng nickname, tự chuyển quyền Host và dọn dẹp phòng trống khi người cuối cùng rời đi.
- `TcpServerIntegrationTest`: Mở server trên cổng động, kết nối socket thật gửi `CONNECT_REQ`, `CREATE_ROOM`, `JOIN_ROOM`, `PING`/`PONG` qua NDJSON và kiểm tra đa luồng đồng thời.
