# BÀI TẬP LỚN: LẬP TRÌNH MẠNG  

## Skribbl Clone – Ứng dụng Trò chơi Vẽ và Đoán chữ Trực tuyến Nhiều người chơi

> 📘 *Mẫu README này là khung hướng dẫn. Sinh viên chỉ cần điền thông tin của nhóm và nội dung dự án theo từng mục.*

---

## 🧑‍💻 THÔNG TIN NHÓM

| STT | Họ và Tên | MSSV | Email | Đóng góp |
|-----|-----------|------|-------|----------|
| 1 | Trần Hán Nam | B23DCCN590 | namth.b23dccn590@stu.ptit.edu.vn | Hạ tầng TCP Server, Java 21 Virtual Threads, Giao thức NDJSON & Hệ thống Đa phòng |
| 2 | Đàm Tiến Thành  | B23DCCN764 | b@example.com | ... |
| 3 | Đinh Minh Tuân | B23DCCN880 | c@example.com | ... |

**Tên nhóm:** Nhóm 20 – Lập trình mạng  
**Chủ đề đã đăng ký:** Skribbl clone

---

## 🧠 MÔ TẢ HỆ THỐNG

Hệ thống là ứng dụng trò chơi **vẽ và đoán từ khóa trực tuyến nhiều người chơi (Skribbl Clone)** được xây dựng theo mô hình **Client–Server** dựa trên kết nối **TCP Socket** hai chiều thời gian thực:

- **Server:** Đóng vai trò trung tâm điều phối có thẩm quyền (*Server-Authoritative*), lắng nghe và phục vụ đồng thời nhiều kết nối Client. Server quản lý nhiều phòng chơi độc lập trên bộ nhớ (`RoomManager`), điều khiển máy trạng thái vòng đời ván đấu (`GameEngine`), đồng bộ đồng hồ đếm ngược, mở dần chữ cái gợi ý, kiểm tra đáp án tiếng Anh, tính điểm thưởng theo tốc độ đoán, chuyển tiếp nét vẽ thời gian thực và xử lý an toàn các tình huống ngắt kết nối đột ngột.
- **Client:** Ứng dụng Desktop cung cấp giao diện tương tác trực quan gồm màn hình cấu hình kết nối (`IP/Port/Nickname`), sảnh chờ đa phòng (`Lobby`), bảng vẽ đồng bộ chống răng cưa (`DrawingCanvas`), thanh công cụ chọn màu/độ dày nét vẽ/tẩy/xóa bảng, khung trò chuyện kết hợp đoán từ và bảng xếp hạng điểm số trực tiếp.
- **Cơ chế giao tiếp:** Hai phía trao đổi thông điệp theo giao thức tầng ứng dụng tự thiết kế sử dụng định dạng **NDJSON (Newline-Delimited JSON)** mã hóa `UTF-8` trên luồng TCP (`TCP_NODELAY`), kết hợp tách biệt luồng đọc/ghi mạng với luồng giao diện (*Swing EDT*) để đảm bảo độ mượt và an toàn đa luồng.

**Cấu trúc logic tổng quát:**
```text
+--------------------+                               +----------------------------------+
|  Client 1 (Drawer) | <=== TCP Socket (NDJSON) ===> |                                  |
+--------------------+                               |     Multi-threaded TCP Server    |
+--------------------+                               |  - ClientSession (VirtualThread) |
| Client 2 (Guesser) | <=== TCP Socket (NDJSON) ===> |  - RoomManager (Multi-room)      | <--> [statics/words_en.csv]
+--------------------+                               |  - GameEngine & TurnScheduler    |
+--------------------+                               |  - CanvasSync & ScoreService     |
| Client N (Guesser) | <=== TCP Socket (NDJSON) ===> |                                  |
+--------------------+                               +----------------------------------+
```

**Sơ đồ hệ thống:**

![System Diagram](./statics/diagram.png)

---

## ⚙️ CÔNG NGHỆ SỬ DỤNG

| Thành phần | Công nghệ / Thư viện | Ghi chú & Vai trò trong hệ thống |
|------------|----------------------|----------------------------------|
| **Ngôn ngữ chính** | **Java 21** | Phát triển toàn bộ `common`, `server` và `client`; áp dụng OOP, `record`, đa luồng (`Virtual Threads`, `ScheduledExecutorService`). |
| **Giao tiếp mạng** | **TCP Socket (`java.net`)** | Sử dụng `ServerSocket`, `Socket`, `BufferedReader`, `BufferedWriter` (`UTF-8`, `TCP_NODELAY`) trao đổi dữ liệu song công. |
| **Định dạng bản tin** | **JSON + Jackson Databind** | Chuẩn hóa giao thức **NDJSON** giữa các module, thuận tiện kiểm tra và gỡ lỗi gói tin mạng. |
| **Giao diện (Client)** | **Java Swing + Java2D + FlatLaf** | Xây dựng màn hình Lobby, bảng vẽ Canvas chống răng cưa, khung Chat, đồng hồ đếm ngược và bảng điểm hiện đại. |
| **Lưu trữ dữ liệu** | **In-memory + CSV Dataset** | Quản lý trạng thái các phòng trên RAM (`ConcurrentHashMap`); nạp kho từ vựng tiếng Anh từ `statics/words_en.csv`. |
| **Build & Kiểm thử** | **Maven + JUnit 5 + Git/GitHub** | Tổ chức Maven Multi-module, đóng gói thực thi `.jar`, viết Unit Test và quản lý phiên bản làm việc nhóm. |

---

## 🚀 HƯỚNG DẪN CHẠY DỰ ÁN

### 1. Build & Đóng gói toàn bộ hệ thống
Yêu cầu hệ thống: **JDK 21 LTS** và **Apache Maven 3.9+**.
```bash
cd source
mvn clean package -DskipTests
```
Lệnh trên sẽ tự động đóng gói 2 file thực thi độc lập:
- Server: `source/server/target/skribbl-server.jar`
- Client: `source/client/target/skribbl-client.jar`

### 2. Chạy Server
Khởi động máy chủ TCP (mặc định lắng nghe trên cổng `8888`):
```bash
java -jar source/server/target/skribbl-server.jar
```
*(Có thể truyền tham số cổng tùy chỉnh: `java -jar source/server/target/skribbl-server.jar 9090`)*

### 3. Chạy Client
Mở giao diện Client:
```bash
java -jar source/client/target/skribbl-client.jar
```

### 4. Kiểm thử tự động (JUnit 5)
Chạy toàn bộ Unit Test và Integration Test mạng:
```bash
cd source
mvn test
```

---

## 🔗 GIAO TIẾP & ĐẶC TẢ GIAO THỨC MẠNG (NDJSON)

Hệ thống giao tiếp thông qua giao thức **TCP Socket thuần**, mã hóa `UTF-8` với cơ chế **Newline-Delimited JSON (NDJSON)**: Mỗi bản tin là một chuỗi JSON nằm trọn vẹn trên đúng **1 dòng kết thúc bằng ký tự `\n`**, bọc trong cấu trúc vỏ `NetworkMessage`:

```json
{"type":"DRAW_STROKE","timestamp":1761465000000,"payload":{"x1":100,"y1":150,"x2":108,"y2":156,"color":"#1E88E5","thickness":6,"eraser":false}}
```

| STT | `MessageType` | Chiều | Trường dữ liệu `payload` | Mô tả nghiệp vụ |
| :-: | :--- | :---: | :--- | :--- |
| 1 | `CONNECT_REQ` | C → S | `nickname` | Client đăng ký tên hiển thị ngay khi mở kết nối |
| 2 | `CONNECT_ACK` | S → C | `playerId`, `nickname` | Server cấp ID duy nhất và xác nhận kết nối |
| 3 | `CREATE_ROOM` | C → S | `maxRounds`, `drawTimeSec` | Tạo phòng mới (sinh mã phòng ngẫu nhiên 5 ký tự) |
| 4 | `JOIN_ROOM` | C → S | `roomCode` | Tham gia phòng chơi bằng mã 5 ký tự |
| 5 | `LEAVE_ROOM` | C → S | *(rỗng)* | Rời khỏi phòng hiện tại |
| 6 | `ROOM_UPDATE` | S → Room | `roomCode`, `hostId`, `status`, `players[]`, `maxRounds`, `drawTimeSec`, `currentRound` | Đồng bộ danh sách người chơi, điểm số và chủ phòng |
| 7 | `START_GAME` | C → S | *(rỗng)* | Chủ phòng (Host) phát lệnh bắt đầu ván chơi |
| 8 | `WORD_CHOICES` | S → Drawer | `choices[3]`, `chooseSeconds` | Gửi riêng 3 từ tiếng Anh cho người vẽ chọn |
| 9 | `CHOOSE_WORD` | Drawer → S | `choiceIndex` (`0..2`) | Người vẽ gửi lựa chọn từ khóa |
| 10 | `TURN_START` | S → Room | `round`, `maxRounds`, `drawerId`, `wordDisplay`, `drawTimeSec` | Bắt đầu lượt vẽ mới |
| 11 | `TIMER_UPDATE` | S → Room | `remainingSec`, `wordMask` | Cập nhật đồng hồ đếm ngược và gợi ý mở chữ cái |
| 12 | `DRAW_STROKE` | Drawer → S → Others | `x1`, `y1`, `x2`, `y2`, `color`, `thickness`, `eraser` | Chuyển tiếp tọa độ nét vẽ thời gian thực |
| 13 | `CLEAR_CANVAS` | Drawer → S → Others | *(rỗng)* | Lệnh xóa sạch bảng vẽ |
| 14 | `CANVAS_SYNC` | S → C | `strokes[]` | Đồng bộ toàn bộ nét vẽ cho người vào giữa ván |
| 15 | `CHAT_SEND` | C → S | `text` | Gửi câu trò chuyện hoặc từ đoán lên Server |
| 16 | `CHAT_MESSAGE` | S → Room/C | `sender`, `text`, `kind` (`NORMAL`, `SYSTEM`, `CLOSE_GUESS`) | Hiển thị tin nhắn hoặc báo gần đúng |
| 17 | `PLAYER_GUESSED`| S → Room | `playerId`, `nickname`, `awardedPoints`, `drawerBonus`, `players[]` | Thông báo đoán đúng & cập nhật điểm số |
| 18 | `TURN_END` | S → Room | `word`, `reason`, `turnScores`, `players[]` | Hết lượt vẽ và công bố đáp án |
| 19 | `GAME_OVER` | S → Room | `winners[]`, `players[]` | Kết thúc toàn bộ ván đấu và trao giải |
| 20 | `ERROR_MSG` | S → C | `code`, `message` | Thông báo lỗi hệ thống/nghiệp vụ |

---

## 📊 KẾT QUẢ THỰC NGHIỆM

> Đưa ảnh chụp kết quả hoặc mô tả log chạy thử.

![Demo Result](./statics/result.png)

---

## 🧩 CẤU TRÚC DỰ ÁN
```text
skribbl-app/
├── README.md                          # Tài liệu mô tả tổng quan hệ thống, giao thức và hướng dẫn chạy
├── INSTRUCTION.md                     # Tài liệu hướng dẫn bài tập lớn của giảng viên
├── .gitignore                         # Cấu hình loại trừ file biên dịch và IDE cho Git
├── statics/                           # Chứa hình ảnh minh họa, sơ đồ kiến trúc và bộ từ vựng
│   ├── logo.png
│   ├── diagram.png
│   └── words_en.csv
└── source/                            # Toàn bộ mã nguồn dự án (Maven Multi-module)
    ├── .gitignore
    ├── pom.xml                        # Maven Parent POM quản lý chung các module
    ├── common/                        # Module dùng chung (Giao thức NDJSON, DTOs, Models, Utils)
    │   ├── pom.xml
    │   └── src/main/java/com/skribbl/common/
    │       ├── protocol/              # Định nghĩa MessageType, NetworkMessage, JsonCodec
    │       ├── dto/                   # Các gói dữ liệu trao đổi giữa Client và Server
    │       ├── model/                 # Mô hình dữ liệu người chơi, phòng, nét vẽ
    │       └── util/                  # Xử lý che ký tự gợi ý và khoảng cách chuỗi Levenshtein
    ├── server/                        # Module phía Server
    │   ├── README.md
    │   ├── pom.xml
    │   └── src/main/java/com/skribbl/server/
    │       ├── net/                   # Lắng nghe ServerSocket và quản lý phiên ClientSession
    │       ├── handler/               # Định tuyến và phân phối bản tin từ Client
    │       ├── room/                  # Quản lý đa phòng chơi (RoomManager, GameRoom)
    │       └── game/                  # Máy trạng thái luật chơi, bộ đếm giờ, tính điểm, đồng bộ vẽ
    └── client/                        # Module phía Client
        ├── README.md
        ├── pom.xml
        └── src/main/java/com/skribbl/client/
            ├── net/                   # Kết nối SocketClient và luồng đọc dữ liệu bất đồng bộ
            ├── controller/            # Điều phối sự kiện từ luồng mạng sang giao diện Swing EDT
            └── ui/                    # Giao diện người dùng (lobby/, canvas/, chat/, game/)
```

---

## 🧩 HƯỚNG PHÁT TRIỂN THÊM

> Nêu ý tưởng mở rộng hoặc cải tiến hệ thống.

- [ ] Cải thiện giao diện người dùng
- [ ] Thêm tính năng xác thực và phân quyền
- [ ] Tối ưu hóa hiệu suất
- [ ] Triển khai trên cloud

---

## 📝 GHI CHÚ

- Repo tuân thủ đúng cấu trúc đã hướng dẫn trong `INSTRUCTION.md`.
- Đảm bảo test kỹ trước khi submit.

---

## 📚 TÀI LIỆU THAM KHẢO

> (Nếu có) Liệt kê các tài liệu, API docs, hoặc nguồn tham khảo đã sử dụng.

> (Nếu có) Liệt kê các tài liệu, API docs, hoặc nguồn tham khảo đã sử dụng.